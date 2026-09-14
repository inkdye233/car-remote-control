#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""用 headless Chrome/Edge 把手册 HTML 打印成 PDF（中文字体由系统 Microsoft YaHei 提供）。"""
import os
import shutil
import subprocess
import sys

BASE = os.path.dirname(os.path.abspath(__file__))
HTML = os.path.join(BASE, "软件使用手册.html")
PDF = os.path.join(BASE, "软件使用手册.pdf")

CANDIDATES = [
    r"C:\Program Files\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
    r"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
]

# 用只含 ASCII 的临时副本，规避命令行上的中文路径编码问题
TMP_HTML = os.path.join(BASE, "_manual_tmp_print.html")
TMP_PDF = os.path.join(BASE, "_manual_tmp_print.pdf")


def find_browser():
    for path in CANDIDATES:
        if os.path.isfile(path):
            return path
    found = shutil.which("chrome") or shutil.which("msedge")
    if found:
        return found
    sys.exit("未找到 Chrome / Edge，无法导出 PDF")


def main():
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
