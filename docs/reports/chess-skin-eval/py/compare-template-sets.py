# -*- coding: utf-8 -*-
"""对比两套模板目录在 9 帧真值格上的「最高分」分布（模板细节修整的量化验证）。

用法（py 目录下）：
  python compare-template-sets.py cv-templates2-old-backup cv-templates3
或直接不带参数（默认旧=cv-templates2-old-backup、新=cv-templates3）。
输出：逐类 / 全局的 sim 均值、最低分、低于 0.70 的格数等。
"""
import importlib.util
import os
import sys

import cv2
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))


def _load(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    m = importlib.util.module_from_spec(spec)
    sys.modules[name] = m
    spec.loader.exec_module(m)
    return m


cvrec = _load(os.path.join(HERE, 'cv-chess-recognize-2.py'), 'cvrec')
SKIN = os.path.normpath(os.path.join(HERE, '..'))

FEN2TPL = {'R': '車', 'N': '馬', 'C': '炮', 'r': '車', 'n': '馬', 'c': '炮',
           'B': '相', 'b': '象', 'A': '仕', 'a': '士', 'K': '帥', 'k': '將',
           'P': '兵', 'p': '卒'}

# 与 run-py-eval-9.py 同步的真值（2026-09-25 修正版）
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


def truth_chars(fen):
    out = []
    for row in fen.split(" ")[0].split("/"):
        for ch in row:
            if ch.isdigit():
                out.extend(["."] * int(ch))
            else:
                out.append(ch)
    return out


def load_from(dirpath):
    t = {}
    for fn in sorted(os.listdir(dirpath)):
        if fn.startswith('tpl_') and fn.endswith('.png'):
            name = fn[4:-4]
            img = cvrec.imread_u(os.path.join(dirpath, fn))
            if img.ndim == 3:
                img = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
            t.setdefault(name, []).append(img.astype(np.float32))
    return t


def correct_sim(grayf, x, y, tpl_list):
    """正确字模板在该格搜索窗里能拿到的最高 NCC（衡量「认对有多少余量」）。"""
    cy = y + cvrec.ANCHOR_DY
    tw = cvrec.TW
    search = cvrec.SEARCH
    r0, r1 = cy - tw - search, cy + tw + search
    c0, c1 = x - tw - search, x + tw + search
    if r0 < 0 or c0 < 0 or r1 > grayf.shape[0] or c1 > grayf.shape[1]:
        return None
    window = grayf[r0:r1, c0:c1]
    best = -2.0
    for t in tpl_list:
        res = cv2.matchTemplate(window, t.astype(np.float32), cv2.TM_CCOEFF_NORMED)
        _, mx, _, _ = cv2.minMaxLoc(res)
        if float(mx) > best:
            best = float(mx)
    return best


def score_set(templates, label):
    per_glyph = {}
    all_scores = []
    near = []   # 低于 0.75 的（边界样本）
    for fname, fen in TRUTH.items():
        img = cvrec.imread_u(os.path.join(SKIN, fname))
        gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
        grayf = gray.astype(np.float32)
        v9, h10, _ = cvrec.locate_board(gray)
        chars = truth_chars(fen)
        for r in range(10):
            for c in range(9):
                ch = chars[r * 9 + c]
                tpl = FEN2TPL.get(ch)
                if ch in ('.', '?') or tpl is None:
                    continue
                x = int(round(v9[c]))
                y = int(round(h10[r]))
                sim = correct_sim(grayf, x, y, templates[tpl])
                if sim is None:
                    continue
                per_glyph.setdefault(tpl, []).append(sim)
                all_scores.append(sim)
                if sim < 0.75:
                    near.append((sim, fname, r, c, ch, tpl))
    a = np.array(all_scores)
    print('== %s ==' % label)
    print('  总格数 %d  均值 %.3f  最低 %.3f  p5 %.3f  <0.70 的格数 %d  <0.75 的格数 %d' % (
        len(a), a.mean(), a.min(), np.percentile(a, 5), int((a < 0.70).sum()), int((a < 0.75).sum())))
    for g in sorted(per_glyph):
        s = np.array(per_glyph[g])
        print('  %-3s n=%2d 均值 %.3f 最低 %.3f' % (g, len(s), s.mean(), s.min()))
    if near:
        print('  低分格（前 12）：' + ' '.join('%s r%dc%d %s=%.2f' % (n[1].replace('.png',''), n[2], n[3], n[4], n[0]) for n in sorted(near)[:12]))
    return a, per_glyph


old_dir = os.path.join(HERE, sys.argv[1] if len(sys.argv) > 1 else 'cv-templates2-old-backup')
new_dir = os.path.join(HERE, sys.argv[2] if len(sys.argv) > 2 else 'cv-templates3')
old_t = load_from(old_dir)
new_t = load_from(new_dir)
print('旧集: %s（%d 类）' % (os.path.basename(old_dir), len(old_t)))
print('新集: %s（%d 类）' % (os.path.basename(new_dir), len(new_t)))
print()
a_old, g_old = score_set(old_t, '旧模板')
print()
a_new, g_new = score_set(new_t, '新模板')
print()
print('=== 差值（新 - 旧；正 = 新更好）===')
print('  全局 均值 %+0.4f  最低 %+0.4f  p5 %+0.4f' % (a_new.mean() - a_old.mean(), a_new.min() - a_old.min(),
      np.percentile(a_new, 5) - np.percentile(a_old, 5)))
for g in sorted(set(list(g_old) + list(g_new))):
    so = np.array(g_old.get(g, [np.nan]))
    sn = np.array(g_new.get(g, [np.nan]))
    print('  %-3s 均值 %+0.4f 最低 %+0.4f' % (g, sn.mean() - so.mean(), sn.min() - so.min()))
