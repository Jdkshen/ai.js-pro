# -*- coding: utf-8 -*-
"""导出一份「电脑侧评测数据」给 JS 用：每帧的棋盘线位置（v9/h10）+ 真值 FEN。

JS 侧不需要会定位（不需要色彩 API）—— 直接吃这份几何，就能在电脑上跑
真正的 `src/vision/glyph-recognizer.js`，见 tools/glyph-pc-eval.js。

用法：python -X utf8 docs/reports/chess-skin-eval/py/export-eval-9.py
产出：docs/reports/chess-skin-eval/eval-9.json
"""
import importlib.util
import json
import os
import sys

import cv2

HERE = os.path.dirname(os.path.abspath(__file__))
EVAL = os.path.normpath(os.path.join(HERE, ".."))


def _load(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    m = importlib.util.module_from_spec(spec)
    sys.modules[name] = m
    spec.loader.exec_module(m)
    return m


cvrec = _load(os.path.join(HERE, "cv-chess-recognize-2.py"), "cvrec")
ev = _load(os.path.join(HERE, "run-py-eval-9.py"), "ev")

out = {"source": "K40 天天象棋 浅木皮肤；几何来自 py locate_board（与 JS lattice 同源口径）",
       "tw": int(cvrec.TW), "anchorDy": int(cvrec.ANCHOR_DY), "frames": {}}
for name, fen in ev.TRUTH.items():
    img = cvrec.imread_u(os.path.join(EVAL, name))
    if img is None:
        print("读图失败：" + name)
        continue
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    v9, h10, _ = cvrec.locate_board(gray)
    out["frames"][name] = {
        "width": int(img.shape[1]),
        "height": int(img.shape[0]),
        "v9": [int(round(v)) for v in v9],
        "h10": [int(round(v)) for v in h10],
        "truth": fen,
    }
    print("%-14s 图 %d×%d  竖线 %s  横线 %s" %
          (name, img.shape[1], img.shape[0], [int(v) for v in v9], [int(v) for v in h10]))

path = os.path.join(EVAL, "eval-9.json")
with open(path, "w", encoding="utf-8") as fh:
    json.dump(out, fh, ensure_ascii=False, indent=1)
print("已写：%s" % path)
