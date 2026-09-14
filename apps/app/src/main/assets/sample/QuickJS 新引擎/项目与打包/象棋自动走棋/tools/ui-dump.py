# -*- coding: utf-8 -*-
"""把 uiautomator dump 出来的 ui.xml 打印成「文本 + 中心坐标」清单。

为什么要有它（2026-09-14）：真机上「看截图凭感觉点」反复点不中（渲染缩放导致目测偏 ~100px），
后来定的规矩是 uiautomator dump → 取节点 bounds 中心再 tap。之前每次都在 PowerShell 里写
多行 python -c，引号一旦没配好就把 shell 卡进续行提示（刚踩过），所以落成脚本。

用法：
    python .artifacts/tools/ui-dump.py .artifacts/k40-diag/ui.xml [关键字]
"""
import io
import re
import sys

def main():
    if len(sys.argv) < 2:
        print("用法: ui-dump.py <ui.xml> [关键字]")
        return 1
    path = sys.argv[1]
    keyword = sys.argv[2] if len(sys.argv) > 2 else None
    try:
        text = io.open(path, encoding="utf-8", errors="replace").read()
    except Exception as error:            # noqa: BLE001 - 诊断脚本，直接报原因
        print("读不到 %s: %s" % (path, error))
        return 1

    # 每个 node 一行；同一节点上 text / content-desc 都可能是可读文案
    rows = []
    for node in re.findall(r"<node\b[^>]*>", text):
        bounds = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
        if not bounds:
            continue
        x1, y1, x2, y2 = (int(v) for v in bounds.groups())
        cx, cy = (x1 + x2) // 2, (y1 + y2) // 2
        for attr in ("text", "content-desc"):
            m = re.search(attr + r'="([^"]*)"', node)
            if not m or not m.group(1).strip():
                continue
            label = m.group(1).strip()
            if keyword and keyword not in label:
                continue
            rows.append((label, cx, cy, attr))

    print("命中节点 %d 个" % len(rows))
    for label, cx, cy, attr in rows:
        print("  (%-6d,%-6d) %-30s [%s]" % (cx, cy, label[:30], attr))
    return 0

if __name__ == "__main__":
    sys.exit(main())
