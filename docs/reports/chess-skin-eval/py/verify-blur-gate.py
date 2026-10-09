# -*- coding: utf-8 -*-
"""验证「模糊门」收益：识别链输出 '?' 的格，若窗清晰度 < 帧内基准×0.5 → 判「空中子，忽略」= '.'。

预期：skin2-04 的 3 格全部纠正（清晰度 248~315 << 门限），284 个静止子零误伤（最低 1289）。
"""
import importlib.util
import os
import sys

import numpy as np
import cv2

HERE = os.path.dirname(os.path.abspath(__file__))


def _load(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    m = importlib.util.module_from_spec(spec)
    sys.modules[name] = m
    spec.loader.exec_module(m)
    return m


cvrec = _load(os.path.join(HERE, 'cv-chess-recognize-2.py'), 'cvrec')
SKIN = os.path.normpath(os.path.join(HERE, '..'))

TRUTH = {
    "skin-a.png": "rnbakab1r/9/3c2nc1/p1p1p1p1p/9/9/P1P1P1P1P/2N1C2C1/9/R1BAKABNR",
    "skin-b.png": "rnbakab1r/9/1c4nc1/p1p1p1p1p/9/9/P1P1P1P1P/4C2C1/9/RNBAKABNR",
    "skin-c.png": "rnbakab1r/9/3c2nc1/p1p1p3p/6p2/7C1/P1P1P1P1P/2N1C4/9/R1BAKABNR",
    "skin2-01.png": "rnbakab1r/9/3c2nc1/p1p1p3p/6p2/7C1/P1P1P1P1P/R1N1C4/9/2BAKABNR",
    "skin2-02.png": "r1bakab1r/9/2nc2nc1/p1p1p3p/6p2/7C1/P1P1P1P1P/R1N1C4/9/2BAKABNR",
    "skin2-03.png": "r1bakab1r/9/2nc2nc1/p1p1p3p/6p2/7C1/P1P1P1P1P/R1N1C3R/9/2BAKABN1",
    "skin2-04.png": "r1bakab1r/9/2nc2n2/p1p1p3p/4?1p2/7C1/P1P1P1P1P/R1N1C3R/9/2BAKABc1",
    "skin2-05.png": "r1bakab1r/9/2nc2n2/p1p1p3p/6p2/7C1/P1P1P1P1P/R1N1C3R/9/2BAKABc1",
    "skin2-06.png": "r1bakab1r/9/2nc2n2/p1p1p3p/6p2/7C1/P1P1P1P1P/R1N1C3R/9/2BAKABc1",
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


def lapvar(gray, cx, cy, tw=34):
    r0, r1 = cy - tw, cy + tw
    c0, c1 = cx - tw, cx + tw
    if r0 < 0 or c0 < 0 or r1 > gray.shape[0] or c1 > gray.shape[1]:
        return None
    win = gray[r0:r1, c0:c1].astype(np.float32)
    return float(cv2.Laplacian(win, cv2.CV_32F).var())


templates = cvrec.load_templates()
GATE_RATIO = 0.5   # 清晰度 < 帧内认准子中位数 × 0.5 → 「空中子，忽略」

total_ok = total_bad = gate_fired = false_drop = 0
for name, fen in TRUTH.items():
    img = cvrec.imread_u(os.path.join(SKIN, name))
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    v9, h10, _ = cvrec.locate_board(gray)
    results = cvrec.recognize(img, v9, h10, templates)

    # 帧内基准：认准子（名字 != '?' 且分 ≥0.70）的清晰度中位数
    confident = []
    for it in results:
        if it.get('x') is None or it['name'] == '?':
            continue
        if float(it.get('ncc', 0)) >= 0.70:
            lv = lapvar(gray, int(round(it['x'])), int(round(it['y'])))
            if lv is not None:
                confident.append(lv)
    base = float(np.median(confident)) if confident else 0.0
    threshold = base * GATE_RATIO

    chars = ["."] * 90
    gated = []
    for it in results:
        r, c = it['r'], it['c']
        if it['name'] == '?':
            # 原有行为：'?'；模糊门：够模糊就当空（空中子）
            lv = None
            if it.get('x') is not None:
                lv = lapvar(gray, int(round(it['x'])), int(round(it['y'])))
            if lv is not None and base > 0 and lv < threshold:
                chars[r * 9 + c] = "."
                gated.append((r, c, lv))
                gate_fired += 1
            else:
                chars[r * 9 + c] = "?"
            continue
        if it['name'] in cvrec.SIDED:
            ch = (it['name'].replace("車", "R").replace("馬", "N").replace("炮", "C")
                  if it['side'] == 'r' else
                  it['name'].replace("車", "r").replace("馬", "n").replace("炮", "c"))
        else:
            ch = CHAR[it['name']]
        chars[r * 9 + c] = ch

    truth = truth_chars(fen)
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
    note = ''
    if gated:
        note = '  模糊门触发: ' + ' '.join('r%dc%d(lap=%.0f)' % g for g in gated)
    print("%-14s %2d/%2d = %5.1f%%  基准=%.0f 门限=%.0f %s%s" % (
        name, ok, ok + bad, 100.0 * ok / max(1, ok + bad), base, threshold, " ".join(bad_cells), note))

print()
print("合计 %d/%d = %.1f%%   模糊门触发 %d 次" % (total_ok, total_ok + total_bad,
      100.0 * total_ok / max(1, total_ok + total_bad), gate_fired))
