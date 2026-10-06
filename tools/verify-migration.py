#!/usr/bin/env python3
"""机械验证 Room 迁移：跑一遍迁移，再和 Room 从实体生成的 schema 逐项比对。

为什么需要它：迁移写歪了**不会编译报错**——Room 只在 App 真去开那个旧库时才比对，
对不上就抛 IllegalStateException，用户看到的是"打开就闪退"。而开发机上通常没有旧库，
所以这个错常常要到用户升级时才暴露。这个脚本把那一比对提前到构建期。

比对口径与 Room 运行时一致（`TableInfo.read`）：pragma table_info / index_list /
foreign_key_list，不是比字符串——所以列顺序、缩进怎么写都不影响结论。

**SQL 的唯一真源是 `LedgerDatabase.kt`**，脚本从源码里把 `db.execSQL(...)` 抠出来
（含字符串拼接与三引号 + trimIndent），不另抄一份。抄一份就会漂，而漂了的校验器
比没有校验器更糟——它会给你一个假的"通过"。

用法（仓库根目录）：python3 tools/verify-migration.py
"""

import json
import re
import sqlite3
import sys
from pathlib import Path

SOURCE = Path("app/src/main/kotlin/dev/dzsun/bookkeeping/core/database/LedgerDatabase.kt")
SCHEMA_DIR = Path("app/schemas/dev.dzsun.bookkeeping.core.database.LedgerDatabase")


def strip_comments(text: str) -> str:
    """去掉行注释与块注释（Kotlin 的块注释可嵌套），免得把注释里的 SQL 也当成语句。"""
    out, i, depth = [], 0, 0
    while i < len(text):
        if depth == 0 and text.startswith("//", i):
            i = text.find("\n", i)
            if i < 0:
                break
            continue
        if text.startswith("/*", i):
            depth += 1
            i += 2
            continue
        if depth and text.startswith("*/", i):
            depth -= 1
            i += 2
            continue
        if depth == 0:
            out.append(text[i])
        i += 1
    return "".join(out)


def read_literal(text: str, i: int):
    """从 text[i] 开始读一个 Kotlin 字符串字面量，返回 (内容, 结束下标)。"""
    if text.startswith('"""', i):
        end = text.index('"""', i + 3)
        return text[i + 3:end], end + 3
    assert text[i] == '"', text[i:i + 20]
    i += 1
    buf = []
    while text[i] != '"':
        if text[i] == "\\":
            nxt = text[i + 1]
            buf.append({"n": "\n", "t": "\t", '"': '"', "\\": "\\", "$": "$"}.get(nxt, nxt))
            i += 2
        else:
            buf.append(text[i])
            i += 1
    return "".join(buf), i + 1


def dedent(text: str) -> str:
    """等价于 Kotlin 的 trimIndent()：去掉首尾空行，再按最小缩进整体左移。"""
    lines = text.split("\n")
    while lines and not lines[0].strip():
        lines.pop(0)
    while lines and not lines[-1].strip():
        lines.pop()
    indents = [len(l) - len(l.lstrip()) for l in lines if l.strip()]
    cut = min(indents) if indents else 0
    return "\n".join(l[cut:] if l.strip() else "" for l in lines)


def extract_migrations() -> dict:
    """{起始版本: [SQL, ...]}，按 LedgerDatabase.kt 里的顺序。"""
    text = strip_comments(SOURCE.read_text(encoding="utf-8"))
    migrations = {}
    for m in re.finditer(r"val\s+MIGRATION_(\d+)_(\d+)\b", text):
        start, end = int(m.group(1)), int(m.group(2))
        body_start = text.index("{", m.end())
        # 用括号配平找到这个 Migration 匿名对象的终点
        depth, i = 0, body_start
        while True:
            if text[i] == "{":
                depth += 1
            elif text[i] == "}":
                depth -= 1
                if depth == 0:
                    break
            i += 1
        body = text[body_start:i]

        statements = []
        for call in re.finditer(r"db\.execSQL\s*\(", body):
            j = call.end()
            depth = 1
            literals, chunk = [], []
            while depth:
                ch = body[j]
                if ch == '"':
                    lit, j = read_literal(body, j)
                    chunk.append(lit)
                    continue
                if ch == "(":
                    depth += 1
                elif ch == ")":
                    depth -= 1
                    if depth == 0:
                        break
                if ch == "+":
                    pass
                j += 1
            literals = chunk
            statements.append(dedent("".join(literals)))
        migrations[start] = statements
    return migrations


def create(db, spec):
    db.execute(spec["createSql"].replace("${TABLE_NAME}", spec["tableName"]))
    for idx in spec.get("indices", []):
        db.execute(idx["createSql"].replace("${TABLE_NAME}", spec["tableName"]))


def table_info(db, table):
    cols = {r[1]: (r[2].upper(), r[3], r[4]) for r in db.execute(f"PRAGMA table_info({table})")}
    idx = sorted(r[1] for r in db.execute(f"PRAGMA index_list({table})"))
    fks = sorted((r[2], r[3], r[4], r[5], r[6]) for r in db.execute(f"PRAGMA foreign_key_list({table})"))
    return cols, idx, fks


def main() -> int:
    versions = sorted(int(p.stem) for p in SCHEMA_DIR.glob("*.json"))
    if len(versions) < 2:
        print("只有一个版本的 schema，没有可验证的迁移")
        return 0

    migrations = extract_migrations()
    schemas = {v: json.loads((SCHEMA_DIR / f"{v}.json").read_text())["database"] for v in versions}

    failures = 0
    for lower, upper in zip(versions, versions[1:]):
        if lower not in migrations:
            print(f"✗ 缺少 MIGRATION_{lower}_{upper}（LedgerDatabase.kt 里没有）")
            failures += 1
            continue
        if migrations[lower] and not migrations[lower][0].strip():
            print(f"✗ MIGRATION_{lower}_{upper} 抠出来是空的——脚本没读懂源码，别信它的通过")
            failures += 1
            continue

        db = sqlite3.connect(":memory:")
        for entity in schemas[lower]["entities"]:
            create(db, entity)
        for stmt in migrations[lower]:
            db.execute(stmt)

        expected = schemas[upper]["entities"]
        for entity in expected:
            table = entity["tableName"]
            got, want = table_info(db, table), None
            fresh = sqlite3.connect(":memory:")
            for e in expected:
                create(fresh, e)
            want = table_info(fresh, table)
            if got != want:
                failures += 1
                print(f"✗ MIGRATION_{lower}_{upper} 后 {table} 与 v{upper} schema 不一致")
                if got[0] != want[0]:
                    print(f"    列  迁移后 {got[0]}")
                    print(f"        期望   {want[0]}")
                if got[1] != want[1]:
                    print(f"    索引 迁移后 {got[1]}  期望 {want[1]}")
                if got[2] != want[2]:
                    print(f"    外键 迁移后 {got[2]}  期望 {want[2]}")
            else:
                if got == want:
                    print(f"✓ MIGRATION_{lower}_{upper}：{table} 一致"
                          f"（{len(got[0])} 列 / {len(got[1])} 索引 / {len(got[2])} 外键）")

        extra = {r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        allowed = {e["tableName"] for e in expected} | {"sqlite_sequence", "android_metadata", "room_master_table"}
        if extra - allowed:
            failures += 1
            print(f"✗ MIGRATION_{lower}_{upper} 多出了 schema 里没有的表：{extra - allowed}")

    print("结论：" + ("全部迁移通过" if failures == 0 else f"{failures} 处不一致"))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
