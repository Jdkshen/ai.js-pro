# -*- coding: utf-8 -*-
"""从标准开局帧自动收集全部 11 类字形模板 + 交叉验证"""
import json
import os
import shutil
import numpy as np
import cv2

BASE = os.path.dirname(os.path.abspath(__file__))   # 存档版：本目录自包含（原为 .artifacts/k40-now）
TPL_DIR = os.path.join(BASE, 'cv-templates2')
FRAME = os.path.join(BASE, 'newgame-frame1.png')

TW = 34          # 窗口半宽（68x68）
ANCHOR_DY = -5   # 字形视觉中心相对格点的垂直偏移（3D 圆盘透视）

# 标准开局布局（已由截图目检确认）
LAYOUT = {
    '車': [(81, 699), (998, 699), (81, 1734), (998, 1734)],
    '馬': [(196, 699), (884, 699), (196, 1734), (884, 1734)],
    '象': [(309, 699), (768, 699)],
    '士': [(426, 699), (655, 699)],
    '將': [(538, 699)],
    '炮': [(196, 925), (884, 925), (196, 1497), (884, 1497)],
    '卒': [(81, 1038), (309, 1038), (538, 1038), (768, 1038), (998, 1038)],
    '兵': [(81, 1382), (309, 1382), (538, 1382), (768, 1382), (998, 1382)],
    '相': [(309, 1734), (768, 1734)],
    '仕': [(426, 1734), (655, 1734)],
    '帥': [(538, 1734)],
}

img = cv2.imdecode(np.fromfile(FRAME, dtype=np.uint8), cv2.IMREAD_COLOR)
gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)


def imwrite_u(path, im):
    ok, buf = cv2.imencode(os.path.splitext(path)[1] or '.png', im)
    buf.tofile(path)
    return ok


def win_at(x, y):
    cy = y + ANCHOR_DY
    return gray[cy - TW:cy + TW, x - TW:x + TW]


def ncc(a, b):
    a = a.astype(np.float64) - a.mean()
    b = b.astype(np.float64) - b.mean()
    d = np.sqrt((a * a).sum() * (b * b).sum())
    return float((a * b).sum() / d) if d > 0 else 0.0


# ---- 抽取主模板 ----
if os.path.isdir(TPL_DIR):
    shutil.rmtree(TPL_DIR)
os.makedirs(TPL_DIR)
man = []
tpls = {}
for shape, positions in LAYOUT.items():
    x, y = positions[0]
    w = win_at(x, y)
    assert w.shape == (TW * 2, TW * 2), (shape, w.shape)
    fn = 'tpl_%s.png' % shape
    ok = imwrite_u(os.path.join(TPL_DIR, fn), w)
    assert ok and os.path.exists(os.path.join(TPL_DIR, fn)), fn
    tpls[shape] = w
    man.append({'name': shape, 'file': fn, 'src': [x, y]})
json.dump(man, open(os.path.join(TPL_DIR, 'manifest.json'), 'w', encoding='utf-8'),
          ensure_ascii=False, indent=1)
print('templates:', {k: v for k, v in sorted(tpls.items())} and
      {k: LAYOUT[k][0] for k in LAYOUT})

# ---- 交叉实例验证：同类不同位置的 NCC ----
print()
print('--- cross-instance NCC (same class, all positions) ---')
worst = 1.0
for shape, positions in LAYOUT.items():
    tpl = tpls[shape].astype(np.float32)
    scores = []
    for (x, y) in positions:
        cy = y + ANCHOR_DY
        region = gray[cy - TW - 10:cy + TW + 10, x - TW - 10:x + TW + 10].astype(np.float32)
        res = cv2.matchTemplate(region, tpl, cv2.TM_CCOEFF_NORMED)
        _, mx, _, _ = cv2.minMaxLoc(res)
        scores.append(mx)
        worst = min(worst, mx)
    print('%-3s n=%d %s min=%.3f' % (shape, len(positions),
          ' '.join('%.3f' % s for s in scores), min(scores)))
print('overall worst cross-instance NCC = %.3f' % worst)

# ---- 类间混淆矩阵（±6 位移搜索取最大）----
print()
print('--- inter-class max NCC matrix (rows=%s) ---' % ' '.join(sorted(tpls)))
shapes = sorted(tpls)
hdr = '      ' + ' '.join('%5s' % s for s in shapes)
print(hdr)
conf_pairs = []
for a in shapes:
    row = []
    ta = tpls[a].astype(np.float32)
    for b in shapes:
        tb = tpls[b].astype(np.float32)
        # b 在 a 周围 ±6 内平移匹配（a 作搜索图，tb 作模板）
        pad = 6
        canvas = cv2.copyMakeBorder(ta, pad, pad, pad, pad, cv2.BORDER_REPLICATE)
        res = cv2.matchTemplate(canvas, tb, cv2.TM_CCOEFF_NORMED)
        _, mx, _, _ = cv2.minMaxLoc(res)
        row.append(mx)
        if a != b and mx > 0.5:
            conf_pairs.append((a, b, mx))
    print('%-5s ' % a + ' '.join('%5.2f' % v for v in row))
print()
conf_pairs.sort(key=lambda t: -t[2])
print('suspicious pairs (off-diag > 0.50):', ['%s/%s=%.2f' % p for p in conf_pairs[:12]])
print('done ->', TPL_DIR)
