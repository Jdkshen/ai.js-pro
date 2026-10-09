# -*- coding: utf-8 -*-
"""测量：「落子动画中间帧」里的飞行/点起子 与 静止子 的清晰度可分性。

背景：9 帧评测里 JS 链 806/809 —— 剩余的 skin2-04（+py 侧 skin-c r9c7）都是
「子在空中/被点起」的中间状态。本探针量化：如果要在【单帧内】识别"这子没落稳、
别当棋子"，用清晰度类特征（拉普拉斯方差等）能不能把这几格和 280 个静止子分开。

用法：python measure-animation-state.py
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
FEN2TPL = {'R': '車', 'N': '馬', 'C': '炮', 'r': '車', 'n': '馬', 'c': '炮',
           'B': '相', 'b': '象', 'A': '仕', 'a': '士', 'K': '帥', 'k': '將',
           'P': '兵', 'p': '卒'}

# 已知问题格：skin2-04 的 3 格（动画帧）+ skin-c r9c7（js 抢救/点起状态）
PROBLEM = [('skin2-04.png', 4, 3), ('skin2-04.png', 5, 3), ('skin2-04.png', 5, 5)]
LIFTED = [('skin-c.png', 9, 7)]


def truth_chars(fen):
    out = []
    for row in fen.split(" ")[0].split("/"):
        for ch in row:
            if ch.isdigit():
                out.extend(["."] * int(ch))
            else:
                out.append(ch)
    return out


def sharp_metrics(gray, cx, cy, tw=34):
    r0, r1 = cy - tw, cy + tw
    c0, c1 = cx - tw, cx + tw
    if r0 < 0 or c0 < 0 or r1 > gray.shape[0] or c1 > gray.shape[1]:
        return None
    win = gray[r0:r1, c0:c1].astype(np.float32)
    lap = cv2.Laplacian(win, cv2.CV_32F)
    lap_var = float(lap.var())
    gx = cv2.Sobel(win, cv2.CV_32F, 1, 0, ksize=3)
    gy = cv2.Sobel(win, cv2.CV_32F, 0, 1, ksize=3)
    grad_mean = float(np.sqrt(gx * gx + gy * gy).mean())
    return lap_var, grad_mean


templates = cvrec.load_templates()
resting = []
problem_rows = []
lifted_rows = []
unknown_metrics = []

for fname, fen in TRUTH.items():
    img = cvrec.imread_u(os.path.join(SKIN, fname))
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    grayf = gray.astype(np.float32)
    v9, h10, _ = cvrec.locate_board(gray)
    chars = truth_chars(fen)
    results = cvrec.recognize(img, v9, h10, templates)
    items = {(it['r'], it['c']): it for it in results if it.get('x') is not None}

    for r in range(10):
        for c in range(9):
            ch = chars[r * 9 + c]
            if ch in ('.', '?'):
                continue
            it = items.get((r, c))
            if it is None:
                continue
            x, y = int(round(it['x'])), int(round(it['y']))
            m = sharp_metrics(gray, x, y)
            if m is None:
                continue
            key = (fname, r, c)
            row = (key, ch, it.get('name'), round(float(it.get('score', 0)), 3), m[0], m[1])
            if key in [(p[0], p[1], p[2]) for p in PROBLEM]:
                problem_rows.append(row)
            elif key in [(p[0], p[1], p[2]) for p in LIFTED]:
                lifted_rows.append(row)
            else:
                resting.append(row)

    # 未被认出的格子（name == '?' 且 truth 为空）——就是 r4c3/r5c3 这类
    for (r, c), it in items.items():
        ch = chars[r * 9 + c]
        if ch == '.' and it.get('name') == '?':
            x, y = int(round(it['x'])), int(round(it['y']))
            m = sharp_metrics(gray, x, y)
            if m:
                unknown_metrics.append(((fname, r, c), round(float(it.get('score', 0)), 3), m[0], m[1]))

lap = np.array([row[4] for row in resting])
grad = np.array([row[5] for row in resting])
print('静止子 n=%d' % len(resting))
print('  Laplacian方差: min=%.1f p1=%.1f p5=%.1f 中位=%.1f max=%.1f' % (
    lap.min(), np.percentile(lap, 1), np.percentile(lap, 5), np.median(lap), lap.max()))
print('  梯度均值: min=%.1f p5=%.1f 中位=%.1f' % (grad.min(), np.percentile(grad, 5), np.median(grad)))
print()
print('问题格（动画帧/点起）:')
for row in problem_rows + lifted_rows:
    (fname, r, c), ch, name, score, lv, gm = row
    print('  %-13s r%dc%d truth=%-3s 认成=%-3s 分=%.3f  Laplacian=%7.1f 梯度=%.1f' % (
        fname.replace('.png', ''), r, c, ch, name, score, lv, gm))
print()
print('其它「认成?」的空格（可能有同族）：')
for key, score, lv, gm in unknown_metrics:
    print('  %-13s r%dc%d 分=%.3f Laplacian=%7.1f 梯度=%.1f' % (key[0].replace('.png', ''), key[1], key[2], score, lv, gm))
