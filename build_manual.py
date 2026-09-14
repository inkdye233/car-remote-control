#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""把《软件使用手册.md》转成带打印样式的 HTML，供 headless Chrome 导出 PDF。"""
import io
import os
import re

import markdown

BASE = os.path.dirname(os.path.abspath(__file__))
MD = os.path.join(BASE, "软件使用手册.md")
HTML = os.path.join(BASE, "软件使用手册.html")

CSS = """
@page { size: A4; margin: 17mm 15mm 16mm; }
* { box-sizing: border-box; }
body {
  font-family: "Microsoft YaHei", "PingFang SC", "Noto Sans CJK SC", sans-serif;
  color: #1f2937; font-size: 10.5pt; line-height: 1.72; margin: 0;
}
h1 { font-size: 20pt; color: #0f3b66; border-bottom: 3px solid #1a6fb5;
     padding-bottom: 8px; margin: 0 0 10px 0; }
h2 { font-size: 14pt; color: #0f3b66; border-left: 5px solid #1a6fb5;
     padding-left: 10px; margin: 24px 0 10px; page-break-after: avoid; }
h3 { font-size: 11.5pt; color: #1a6fb5; margin: 16px 0 7px; page-break-after: avoid; }
p { margin: 7px 0; }
ul, ol { margin: 6px 0; padding-left: 24px; }
li { margin: 3px 0; }
strong { color: #0d1117; }
table { border-collapse: collapse; width: 100%; margin: 10px 0 14px;
        font-size: 9.5pt; page-break-inside: avoid; }
th { background: #1a6fb5; color: #fff; font-weight: 600; padding: 6px 10px;
     text-align: left; border: 1px solid #155d99; }
td { padding: 5px 10px; border: 1px solid #d5dee8; vertical-align: top; }
tr:nth-child(even) td { background: #f2f7fc; }
code { font-family: Consolas, "Courier New", monospace; background: #eef2f7;
       color: #b3261e; padding: 1px 5px; border-radius: 3px; font-size: 9pt; }
hr { border: none; border-top: 1px solid #d5dee8; margin: 20px 0; }
blockquote { border-left: 4px solid #f0a13a; background: #fdf6e9;
             margin: 10px 0; padding: 8px 14px; color: #6b5410;
             page-break-inside: avoid; }
blockquote p { margin: 4px 0; }
em { color: #6b7280; }
/* 键位文字（界面按钮名）在正文中加粗提亮 */
body > p:first-of-type { line-height: 1.9; }
"""


def main():
    with io.open(MD, "r", encoding="utf-8") as handle:
        text = handle.read()

    # 抬头信息块：每行单独成行，避免被合并成一大段
    text = re.sub(r"(\*\*[^\n*]+?\*\*：[^\n]+)\n(?=\*\*)", r"\1  \n", text)

    body = markdown.markdown(
        text,
        extensions=["tables", "sane_lists", "nl2br"],
        output_format="html5",
    )
    # nl2br 会给所有段落行尾加 <br>，去掉段末多余的 <br>
    body = re.sub(r"<br />\s*(</p>)", r"\1", body)

    html = (
        '<!DOCTYPE html>\n<html lang="zh-CN"><head><meta charset="utf-8">\n'
        "<title>小车遥控 App 软件使用手册</title>\n"
        "<style>%s</style></head><body>\n%s\n</body></html>\n" % (CSS, body)
    )
    with io.open(HTML, "w", encoding="utf-8") as handle:
        handle.write(html)
    print("HTML written:", HTML, len(html), "chars")


if __name__ == "__main__":
    main()
