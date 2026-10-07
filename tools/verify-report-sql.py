#!/usr/bin/env python3
"""拿真实 SQLite 跑一遍报表/预算的聚合 SQL，验证月份边界与排除规则。

为什么需要它：端上不开库（没有 Robolectric，网络也不允许拉），
`PostingDao` 那几条聚合 SQL **在单测里跑不起来**——纯函数测试只能盖到喂给 SQL 的
日期窗口，盖不到 `BETWEEN` 到底含不含端点、`VOID` 排没排掉。那几条恰恰是
「跨月边界」最会出错的地方。这个脚本把 SQL 提到构建期跑一遍。

**SQL 的唯一真源是 `LedgerDao.kt`**，脚本从 `@Query(...)` 里抠出来
（`tools/verify-migration.py` 已有同一套抠字符串的机器，直接复用），不另抄一份——
抄一份就会漂，而漂了的校验器比没有校验器更糟，它会给你一个假的"通过"。

用法（仓库根目录）：python3 tools/verify-report-sql.py
"""

import importlib.util
import json
import re
import sqlite3
import sys
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DAO = ROOT / "app/src/main/kotlin/dev/dzsun/bookkeeping/core/database/LedgerDao.kt"
SCHEMA = ROOT / "app/schemas/dev.dzsun.bookkeeping.core.database.LedgerDatabase/4.json"

# verify-migration.py 的文件名带连字符，不能直接 import，用 spec 加载
_spec = importlib.util.spec_from_file_location("verify_migration", Path(__file__).resolve().parent / "verify-migration.py")
vm = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(vm)


def extract_queries() -> dict:
    """{函数名: SQL}，只取 `@Query(...)` 后面紧跟的那个 fun。"""
    text = vm.strip_comments(DAO.read_text(encoding="utf-8"))
    queries = {}
    for m in re.finditer(r"@Query\s*\(", text):
        i = m.end()
        while i < len(text) and text[i].isspace():
            i += 1
        sql, i = vm.read_literal(text, i)
        fm = re.search(r"(?:suspend\s+)?fun\s+(\w+)\s*\(", text[i:])
        if fm:
            queries[fm.group(1)] = vm.dedent(sql)
    return queries


def epoch_day(y: int, m: int, d: int) -> int:
    """与 Kotlin `LocalDate.toEpochDay()` 同一个口径：1970-01-01 起的天数。"""
    return (date(y, m, d) - date(1970, 1, 1)).days


def build_db() -> sqlite3.Connection:
    db = sqlite3.connect(":memory:")
    for entity in json.loads(SCHEMA.read_text())["database"]["entities"]:
        vm.create(db, entity)
    return db


def seed(db: sqlite3.Connection) -> None:
    """一笔支出落在每个边界日上，外加一笔 VOID、一笔收入。"""
    db.execute("INSERT INTO account VALUES ('expense.food','餐饮','EXPENSE','CNY',NULL,0,0)")
    db.execute("INSERT INTO account VALUES ('income.salary','工资','INCOME','CNY',NULL,0,1)")
    db.execute("INSERT INTO account VALUES ('asset.cash','现金','ASSET','CNY',NULL,0,2)")

    # (id, 日期, 状态, 分类, 金额)  支出记正、收入记负，与账本口径一致
    rows = [
        ("j_oct05", (2026, 10, 5), "CLEARED", "expense.food", 3800),
        ("j_oct31", (2026, 10, 31), "CLEARED", "expense.food", 1200),  # 当月最后一天，要算进来
        ("j_nov01", (2026, 11, 1), "CLEARED", "expense.food", 9999),   # 下月第一天，不能算进来
        ("j_sep30", (2026, 9, 30), "CLEARED", "expense.food", 7777),   # 上月最后一天，不能算进来
        ("j_void", (2026, 10, 15), "VOID", "expense.food", 5555),      # 作废，不能算进来
        ("j_income", (2026, 10, 20), "CLEARED", "income.salary", -500000),
    ]
    for jid, (y, m, d), status, account, amount in rows:
        db.execute(
            "INSERT INTO journal (id,dateEpochDay,payee,note,status,source,"
            "externalSource,externalRef,externalStatus,place,reversesJournalId,createdAt,updatedAt) "
            "VALUES (?,?,NULL,NULL,?,'MANUAL',NULL,NULL,NULL,NULL,NULL,0,0)",
            (jid, epoch_day(y, m, d), status),
        )
        db.execute("INSERT INTO posting VALUES (?,?,?,?,?,NULL)", (jid + "_p", jid, account, amount, "CNY"))
        # 对侧分录：支出现金减、收入现金加。报表只认分类侧，这条只是让账是平的
        cash = -amount if account != "income.salary" else -amount
        db.execute("INSERT INTO posting VALUES (?,?,?,?,?,NULL)", (jid + "_c", jid, "asset.cash", cash, "CNY"))


def main() -> int:
    queries = extract_queries()
    needed = ["observeTotal", "observeCategoryTotals", "observeCategorySpent"]
    missing = [q for q in needed if q not in queries]
    if missing:
        print(f"✗ 源码里没抠到这些查询：{missing}——脚本没读懂，别信它的通过")
        return 1

    db = build_db()
    seed(db)
    failures = []

    def check(label, got, want):
        ok = got == want
        print(f"{'✓' if ok else '✗'} {label}：{got}" + ("" if ok else f"（期望 {want}）"))
        if not ok:
            failures.append(label)

    oct_from, oct_to = epoch_day(2026, 10, 1), epoch_day(2026, 10, 31)
    nov_from, nov_to = epoch_day(2026, 11, 1), epoch_day(2026, 11, 30)
    aug_from, aug_to = epoch_day(2026, 8, 1), epoch_day(2026, 8, 31)

    # ---- 正常月份：当月两笔支出（10-05 的 3800 + 10-31 的 1200），VOID 那笔不算
    rows = db.execute(
        queries["observeCategoryTotals"],
        {"type": "EXPENSE", "fromEpochDay": oct_from, "toEpochDay": oct_to},
    ).fetchall()
    check("正常月份 · 分类合计只有餐饮且金额正确", rows, [("expense.food", "餐饮", 5000)])

    # ---- 跨月边界：上月最后一天(9-30)、下月第一天(11-1)都不能落进十月
    check(
        "跨月边界 · 十月不含 9-30 与 11-01",
        db.execute(queries["observeTotal"], {"type": "EXPENSE", "currency": "CNY",
                                             "fromEpochDay": oct_from, "toEpochDay": oct_to}).fetchone()[0],
        5000,
    )
    check(
        "跨月边界 · 九月只含 9-30 那笔",
        db.execute(queries["observeTotal"], {"type": "EXPENSE", "currency": "CNY",
                                             "fromEpochDay": epoch_day(2026, 9, 1),
                                             "toEpochDay": epoch_day(2026, 9, 30)}).fetchone()[0],
        7777,
    )
    check(
        "跨月边界 · 十一月只含 11-01 那笔",
        db.execute(queries["observeTotal"], {"type": "EXPENSE", "currency": "CNY",
                                             "fromEpochDay": nov_from, "toEpochDay": nov_to}).fetchone()[0],
        9999,
    )

    # ---- 空月份返回零（不是 NULL，也不是报错）
    check(
        "空月份 · 没有账目的月份合计为 0",
        db.execute(queries["observeTotal"], {"type": "EXPENSE", "currency": "CNY",
                                             "fromEpochDay": aug_from, "toEpochDay": aug_to}).fetchone()[0],
        0,
    )
    check(
        "空月份 · 没有账目的月份分类合计为空",
        db.execute(queries["observeCategoryTotals"],
                   {"type": "EXPENSE", "fromEpochDay": aug_from, "toEpochDay": aug_to}).fetchall(),
        [],
    )

    # ---- 收入侧在账上是负数（贷方）。数据层不翻正，翻正是 MonthlyReportQuery 的事
    check(
        "收入侧 · 账上记的是负数",
        db.execute(queries["observeTotal"], {"type": "INCOME", "currency": "CNY",
                                             "fromEpochDay": oct_from, "toEpochDay": oct_to}).fetchone()[0],
        -500000,
    )

    # ---- 单分类当月已花（预算用），且同样排除 VOID
    check(
        "单分类当月已花 · 餐饮十月",
        db.execute(queries["observeCategorySpent"],
                   {"categoryId": "expense.food", "fromEpochDay": oct_from, "toEpochDay": oct_to}).fetchone()[0],
        5000,
    )
    check(
        "单分类当月已花 · 没花过的月份是 0 不是 NULL",
        db.execute(queries["observeCategorySpent"],
                   {"categoryId": "expense.food", "fromEpochDay": aug_from, "toEpochDay": aug_to}).fetchone()[0],
        0,
    )

    print("结论：" + ("报表 SQL 全部符合预期" if not failures else f"{len(failures)} 处不符：{failures}"))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
