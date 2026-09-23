# -*- coding: utf-8 -*-
"""测量：以「设备 lattice 网格 + ANCHOR_DY」为窗心，每格字形需要滑动多少像素才能匹配上
（给移植版定 SEARCH_X / SEARCH_Y；同时对 py 的 -5 锚点做核对）"""
import importlib.util
import os
import sys

import cv2
import numpy as np


def _load(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    m = importlib.util.module_from_spec(spec)
    sys.modules[name] = m
    spec.loader.exec_module(m)
    return m


HERE = os.path.dirname(os.path.abspath(__file__))
cvrec = _load(os.path.join(HERE, 'cv-chess-recognize-2.py'), 'cvrec')
SKIN = os.path.normpath(os.path.join(HERE, '..'))

GRIDS = {
    'skin-a.png': (80.875, 666.000, 114.850, 113.600),
    'skin-b.png': (80.875, 665.750, 114.850, 114.350),
    'skin-c.png': (80.875, 665.625, 114.850, 114.350),
    'skin2-01.png': (80.875, 665.625, 114.850, 114.350),
    'skin2-02.png': (80.875, 665.500, 114.850, 114.350),
    'skin2-03.png': (80.875, 665.500, 114.850, 114.350),
    'skin2-04.png': (80.875, 665.500, 114.850, 114.350),
    'skin2-05.png': (80.875, 665.500, 114.850, 114.350),
    'skin2-06.png': (80.875, 665.500, 114.850, 114.350),
}

TW = 34
ANCHOR_DY = -5
BIG = 40  # 测量窗额外半径（超过移植版 SEARCH，用来看真实需求）

templates = cvrec.load_templates()
all_dx, all_dy = [], []
worst = []
for name, (ox, oy, sx, sy) in GRIDS.items():
    img = cvrec.imread_u(os.path.join(SKIN, name))
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    grayf = gray.astype(np.float32)
    for r in range(10):
        for c in range(9):
            x = int(round(ox + c * sx))
            y = int(round(oy + r * sy))
            cy = y + ANCHOR_DY
            r0, r1 = cy - TW - BIG, cy + TW + BIG
            c0, c1 = x - TW - BIG, x + TW + BIG
            if r0 < 0 or c0 < 0 or r1 > gray.shape[0] or c1 > gray.shape[1]:
                continue
            region = grayf[r0:r1, c0:c1]
            best = (-2.0, 0, 0)
            for tname, tl in templates.items():
                for t in tl:
                    res = cv2.matchTemplate(region, t, cv2.TM_CCOEFF_NORMED)
                    _, mx, _, ml = cv2.minMaxLoc(res)
                    if mx > best[0]:
                        best = (float(mx), c0 + ml[0] + TW, r0 + ml[1] + TW)
            sc, gx, gy = best
            dx, dy = gx - x, gy - cy
            if sc >= 0.90 and abs(dx) <= 45 and abs(dy) <= 45:
                all_dx.append(abs(dx))
                all_dy.append(abs(dy))
                if abs(dy) >= 18 or abs(dx) >= 10:
                    worst.append((abs(dy), name, r, c, dx, dy, round(sc, 3)))

all_dx.sort()
all_dy.sort()
n = len(all_dy)
print('样本格数 %d（score>=0.90）' % n)
print('|dx|: max=%.0f  p99=%.0f  p95=%.0f' % (all_dx[-1], all_dx[int(n * 0.99)], all_dx[int(n * 0.95)]))
print('|dy|: max=%.0f  p99=%.0f  p95=%.0f' % (all_dy[-1], all_dy[int(n * 0.99)], all_dy[int(n * 0.95)]))
print()
print('需要滑动 >=18px 的格（前 20）：')
for w in sorted(worst, reverse=True)[:20]:
    print('  dy=%2d %-14s r%dc%d  dx=%+.0f dy=%+.0f score=%.3f' % (w[0], w[1], w[2], w[3], w[4], w[5], w[6]))
