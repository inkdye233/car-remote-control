#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""用 headless Chrome/Edge 把手册 HTML 打印成 PDF（中文字体由系统的中文字体提供）。

浏览器查找顺序：
  1. 环境变量 CHROME_PATH（显式指定，优先级最高）
  2. 各平台常见安装位置
  3. PATH 中的 chrome / chromium / msedge 等可执行文件
"""
import os
import shutil
import subprocess
import sys

BASE = os.path.dirname(os.path.abspath(__file__))
HTML = os.path.join(BASE, "软件使用手册.html")
PDF = os.path.join(BASE, "软件使用手册.pdf")

# 各平台常见安装位置（均为公开的默认安装路径，与具体用户无关）
CANDIDATES = [
    # Windows
    r"C:\Program Files\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
    r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
    os.path.expandvars(r"%LOCALAPPDATA%\Google\Chrome\Application\chrome.exe"),
    # macOS
    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
    "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
    "/Applications/Chromium.app/Contents/MacOS/Chromium",
]

# PATH 中可能出现的命令名
COMMANDS = [
    "chrome", "google-chrome", "google-chrome-stable", "chromium",
    "chromium-browser", "msedge", "microsoft-edge",
]

# 用只含 ASCII 的临时副本，规避命令行上的中文路径编码问题
TMP_HTML = os.path.join(BASE, "_manual_tmp_print.html")
TMP_PDF = os.path.join(BASE, "_manual_tmp_print.pdf")


def find_browser():
    override = os.environ.get("CHROME_PATH")
    if override:
        if os.path.isfile(override):
            return override
        sys.exit("CHROME_PATH 指向的文件不存在: %s" % override)

    for path in CANDIDATES:
        if path and os.path.isfile(path):
            return path

    for name in COMMANDS:
        found = shutil.which(name)
        if found:
            return found

    sys.exit(
        "未找到 Chrome / Edge / Chromium，无法导出 PDF。\n"
        "请安装上述任一浏览器，或设置环境变量 CHROME_PATH 指向浏览器可执行文件。"
    )


def main():
    if not os.path.isfile(HTML):
        sys.exit("未找到 %s，请先运行 build_manual.py 生成 HTML。" % HTML)

    browser = find_browser()
    shutil.copyfile(HTML, TMP_HTML)
    if os.path.exists(TMP_PDF):
        os.remove(TMP_PDF)

    cmd = [
        browser,
        "--headless=new",
        "--disable-gpu",
        "--no-sandbox",
        "--no-first-run",
        "--no-pdf-header-footer",
        "--print-to-pdf-no-header",
        "--print-to-pdf=" + TMP_PDF,
        "file:///" + TMP_HTML.replace("\\", "/"),
    ]
    result = subprocess.run(cmd, capture_output=True, text=True, timeout=180)
    if not os.path.exists(TMP_PDF) or os.path.getsize(TMP_PDF) == 0:
        sys.stdout.write(result.stdout[-3000:])
        sys.stderr.write(result.stderr[-3000:])
        sys.exit("PDF 生成失败")

    shutil.move(TMP_PDF, PDF)
    os.remove(TMP_HTML)
    print("浏览器:", browser)
    print("PDF:", PDF, os.path.getsize(PDF), "bytes")


if __name__ == "__main__":
    main()
