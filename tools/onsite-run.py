#!/usr/bin/env python3
"""端侧真跑驱动：启动 App → 设置 → 加载模型 → 生成 → 抓 logcat 证据。"""
import re
import subprocess
import sys
import time

PKG = "dev.dzsun.bookkeeping"
TAG = "SparkLlm"


def shell(*args, timeout=120):
    r = subprocess.run(["adb", "shell", *args], capture_output=True, timeout=timeout)
    return (r.stdout + r.stderr).decode("utf-8", "replace").replace("\r", "")


def run_out(*args, timeout=120):
    r = subprocess.run(["adb", *args], capture_output=True, timeout=timeout)
    return (r.stdout + r.stderr).decode("utf-8", "replace",)


def dump():
    shell("uiautomator", "dump", "/sdcard/ui.xml")
    r = subprocess.run(["adb", "exec-out", "cat", "/sdcard/ui.xml"], capture_output=True, timeout=60)
    return r.stdout.decode("utf-8", "replace")


def node_bounds(xml, text, exact=True):
    for n in re.findall(r"<node[^>]*>", xml):
        m = re.search(r'text="([^"]*)"', n)
        if not m:
            continue
        t = m.group(1)
        if (t == text) if exact else (text in t):
            b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
            if b:
                x1, y1, x2, y2 = map(int, b.groups())
                return (x1 + x2) // 2, (y1 + y2) // 2
    return None


def node_text(xml, needle):
    for n in re.findall(r"<node[^>]*>", xml):
        m = re.search(r'text="([^"]*)"', n)
        if m and needle in m.group(1):
            return m.group(1)
    return None


def tap(xy, label=""):
    if not xy:
        print(f"  !! 找不到可点元素: {label}", flush=True)
        return False
    shell("input", "tap", str(xy[0]), str(xy[1]))
    return True


def logcat_grep(pattern, seconds, clear_first=True):
    if clear_first:
        shell("logcat", "-c")
    deadline = time.time() + seconds
    buf = ""
    while time.time() < deadline:
        buf += run_out("logcat", "-d", "-s", TAG, timeout=60)
        if re.search(pattern, buf):
            return buf
        time.sleep(3)
    return buf


def main():
    print("== 1) 冷启动 ==", flush=True)
    shell("am", "force-stop", PKG)
    time.sleep(1)
    shell("logcat", "-c")
    shell("am", "start", "-n", f"{PKG}/.MainActivity")
    time.sleep(8)

    print("== 2) 进设置 ==", flush=True)
    xml = dump()
    tap(node_bounds(xml, "设置"), "设置")
    time.sleep(3)

    print("== 3) 滚到「端侧模型」 ==", flush=True)
    for _ in range(6):
        xml = dump()
        if node_bounds(xml, "加载模型"):
            break
        shell("input", "swipe", "540", "1700", "540", "500", "250")
        time.sleep(1.5)
    xml = dump()
    print("  模型行:", node_text(xml, "模型：") or node_text(xml, "模型"), flush=True)
    print("  状态行:", node_text(xml, "未加载") or node_text(xml, "已加载") or "(未识别)", flush=True)

    print("== 4) 加载模型 ==", flush=True)
    tap(node_bounds(xml, "加载模型"), "加载模型")
    out = logcat_grep(r"model loaded|model load failed", 180)
    xml = dump()
    status = node_text(xml, "已加载") or node_text(xml, "失败") or node_text(xml, "加载") or "(未识别)"
    print("  状态:", status, flush=True)
    if "model loaded" not in out:
        print("  !! 加载未确认，logcat 片段:", out[-1500:], flush=True)
        return 1

    print("== 5) 生成 ==", flush=True)
    shell("logcat", "-c")
    tap(node_bounds(xml, "生成"), "生成")
    out = logcat_grep(r"generate done|generate failed", 240)
    xml = dump()
    print("  计时行:", node_text(xml, "首 token") or "(未出现)", flush=True)
    print("  输出预览:", (node_text(xml, "打车") or node_text(xml, "分类") or "(未识别)")[:300], flush=True)
    print("== logcat 证据 ==", flush=True)
    for line in out.splitlines():
        if TAG in line:
            print("  " + line.strip()[:400], flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
