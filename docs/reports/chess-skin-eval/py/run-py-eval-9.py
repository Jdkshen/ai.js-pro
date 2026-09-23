# -*- coding: utf-8 -*-
"""电脑 py 识别在这 9 帧上的基准成绩（严格口径，? 跳过）—— 移植版要对齐的目标"""
import importlib.util
import os
import sys

import cv2


def _load(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    m = importlib.util.module_from_spec(spec)
    sys.modules[name] = m
    spec.loader.exec_module(m)
    return m


HERE = os.path.dirname(os.path.abspath(__file__))
cvrec = _load(os.path.join(HERE, 'cv-chess-recognize-2.py'), 'cvrec')
SKIN = os.path.normpath(os.path.join(HERE, '..'))

TRUTH = {
    "skin-a.png": "rnbakab1r/9/3c2nc1/p1p1p1p1p/9/9/P1P1P1P1P/2N1C2C1/9/R1BAKABNR",
    "skin-b.png": "rnbakab1r/9/1c4nc1/p1p1p1p1p/9/9/P1P1P1P1P/4C2C1/9/RNBAKABNR",
    "skin-c.png": "rnbakab1r/9/3c2nc1/p1p1p3p/6p2/7C1/P1P1P1P1P/2N1C4/9/R1BAKABNR",
    "skin2-01.png": "rnbakab1r/9/3c2nc1/p1p1p3p/6p2/7C1/P1P1P1P1P/C1N1?4/9/?1BAKA?NR",
    "skin2-02.png": "r1bakab1r/9/2nc2nc1/p1p1p3p/6p2/7?1/P1P1P1P1P/C1N1?4/9/2BAKA?NR",
    "skin2-03.png": "r1bakab1r/9/2nc2nc1/p1p1p3p/6p2/7?1/P1P1P1P1P/C1N1?3C/9/2BAKA?N?",
    "skin2-04.png": "r1bakab1r/9/2c2?n2/p1p5p/4?1p2/7?1/P1P1P1P1P/C1N1?3C/9/2BAKA??1",
    "skin2-05.png": "r1bakab1r/9/2c2?n2/p1p1p3p/6p2/7?1/P1P1P1P1P/C1N1?3C/9/2BAKA??1",
    "skin2-06.png": "r1bakab1r/9/2c2?n2/p1p1p3p/6p2/7?1/P1P1P1P1P/C1N1?3C/9/2BAKA??1",
}

CHAR = {"帥": "K", "仕": "A", "相": "B", "兵": "P", "將": "k", "士": "a", "象": "b", "卒": "p"}


def truth_chars(fen):
    out = []
    for row in fen.split(" ")[0].split("/"):
        for ch in row:
            if ch.isdigit():
                out.extend(["."] * int(ch))
            else:
                out.append(ch)
    return out


templates = cvrec.load_templates()
total_ok = total_bad = 0
for name in TRUTH:
    img = cvrec.imread_u(os.path.join(SKIN, name))
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    v9, h10, dbg = cvrec.locate_board(gray)
    results = cvrec.recognize(img, v9, h10, templates)
    chars = ["."] * 90
    for it in results:
        ch = "?"
        if it["name"] != "?":
            if it["name"] in cvrec.SIDED:
                ch = (it["name"].replace("車", "R").replace("馬", "N").replace("炮", "C")
                      if it["side"] == "r" else
                      it["name"].replace("車", "r").replace("馬", "n").replace("炮", "c"))
            else:
                ch = CHAR[it["name"]]
        chars[it["r"] * 9 + it["c"]] = ch
    truth = truth_chars(TRUTH[name])
    ok = bad = 0
    bad_cells = []
    for k in range(90):
        if truth[k] == "?":
            continue
        if chars[k] == truth[k]:
            ok += 1
        else:
            bad += 1
            bad_cells.append("r%dc%d:%s→%s" % (k // 9, k % 9, truth[k], chars[k]))
    total_ok += ok
    total_bad += bad
    print("%-14s %2d/%2d = %5.1f%%  %s" % (name, ok, ok + bad, 100.0 * ok / max(1, ok + bad), " ".join(bad_cells)))
print("合计 %d/%d = %.1f%%" % (total_ok, total_ok + total_bad, 100.0 * total_ok / max(1, total_ok + total_bad)))
