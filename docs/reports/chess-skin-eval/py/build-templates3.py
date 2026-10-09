# -*- coding: utf-8 -*-
"""从 9 帧真机样本重建 11 类字形模板（半像素对齐 + 多帧中值合成）。

背景：v2 模板（2026-09-22）是从一张开局帧的单次裁剪 —— 可能有 1~2px 偏心 + 单帧木纹噪声。
本次「细节修整」：
  · 样本 = 9 帧（skin-a/b/c + skin2-01..06）按真值 FEN 逐格裁剪，
    口径与评测完全一致：窗心 = 格点 + (0, ANCHOR_DY)、TW=34 → 68×68 灰度；
  · 对齐：以现有模板为参照做 2× 上采样相关搜索（±4px、半像素精度）；
    NCC < 0.85 的样本按动画/遮挡帧剔除（不足 3 个时放宽到 0.80）；
  · 合成：逐像素中值（抗木纹噪声与个别坏样本），尺寸保持 68×68 —— 识别端零改动。

产出：cv-templates3/（tpl_*.png + manifest.json + compare.png 新旧对照图）。
验证：把新集换进 cv-templates2 后跑 run-py-eval-9.py；换进 assets/glyph-tpl 后跑
     node tools/glyph-pc-eval.js —— 两者都应 ≥ 基线（py 805/809、js 806/809）。
"""
import importlib.util
import json
import os
import shutil
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
OUT = os.path.join(HERE, 'cv-templates3')

TW = 34          # 窗半宽（68x68），与 collect-templates2.py / 评测同口径
ANCHOR_DY = -5   # 字形视觉中心相对格点的垂直偏移
ALIGN_MAX = 4.0  # 对齐搜索半径（±px）
KEEP_NCC = 0.85  # 样本准入分（对齐后的 NCC）
KEEP_NCC_LOOSE = 0.80

# ⚠️ 与 run-py-eval-9.py 的 TRUTH 同步（2026-09-25 真值修正版）
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

GLYPH_ORDER = ['車', '馬', '炮', '相', '仕', '帥', '兵', '象', '士', '將', '卒']
ASCII_NAME = {'車': 'R-che', '馬': 'N-ma', '炮': 'C-pao', '相': 'B-xiang', '仕': 'A-shi',
              '帥': 'K-shuai', '兵': 'P-bing', '象': 'b-xiang', '士': 'a-shi',
              '將': 'k-jiang', '卒': 'p-zu'}
# 真值 FEN 字母 → 模板名（車馬炮红黑同形共用；其余按阵营分字）
FEN2TPL = {'R': '車', 'N': '馬', 'C': '炮', 'r': '車', 'n': '馬', 'c': '炮',
           'B': '相', 'b': '象', 'A': '仕', 'a': '士', 'K': '帥', 'k': '將',
           'P': '兵', 'p': '卒'}


def truth_chars(fen):
    out = []
    for row in fen.split(" ")[0].split("/"):
        for ch in row:
            if ch.isdigit():
                out.extend(["."] * int(ch))
            else:
                out.append(ch)
    return out


def align(crop, ref):
    """把 crop 对齐到 ref（2× 上采样相关，±ALIGN_MAX px，半像素精度）。返回 (ncc, aligned)。"""
    up_c = cv2.resize(crop, (0, 0), fx=2, fy=2, interpolation=cv2.INTER_CUBIC)
    up_r = cv2.resize(ref, (0, 0), fx=2, fy=2, interpolation=cv2.INTER_CUBIC)
    pad = int(ALIGN_MAX * 2)
    canvas = cv2.copyMakeBorder(up_c, pad, pad, pad, pad, cv2.BORDER_REPLICATE)
    res = cv2.matchTemplate(canvas, up_r, cv2.TM_CCOEFF_NORMED)
    _, mx, _, ml = cv2.minMaxLoc(res)
    dx = (ml[0] - pad) / 2.0
    dy = (ml[1] - pad) / 2.0
    M = np.float32([[1, 0, -dx], [0, 1, -dy]])
    aligned = cv2.warpAffine(crop, M, (crop.shape[1], crop.shape[0]),
                             flags=cv2.INTER_CUBIC, borderMode=cv2.BORDER_REPLICATE)
    return float(mx), aligned


def ncc(a, b):
    a = a.astype(np.float64) - a.mean()
    b = b.astype(np.float64) - b.mean()
    d = np.sqrt((a * a).sum() * (b * b).sum())
    return float((a * b).sum() / d) if d > 0 else 0.0


def imwrite_u(path, im):
    ok, buf = cv2.imencode(os.path.splitext(path)[1] or '.png', im)
    buf.tofile(path)
    return ok


# ---------------- 收集样本 ----------------
old = cvrec.load_templates()  # {name: [imgs]}
refs = {}
for name in old:
    refs[name] = old[name][0].astype(np.uint8)

samples = {name: [] for name in refs}
frame_counts = {name: {} for name in refs}

for fname, fen in TRUTH.items():
    img = cvrec.imread_u(os.path.join(SKIN, fname))
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    v9, h10, _dbg = cvrec.locate_board(gray)
    chars = truth_chars(fen)
    for r in range(10):
        for c in range(9):
            ch = chars[r * 9 + c]
            if ch == '.' or ch == '?':
                continue
            tpl_name = FEN2TPL.get(ch)
            if tpl_name is None or tpl_name not in refs:
                continue
            x = int(round(v9[c]))
            y = int(round(h10[r]))
            cy = y + ANCHOR_DY
            crop = gray[cy - TW:cy + TW, x - TW:x + TW]
            if crop.shape != (TW * 2, TW * 2):
                continue
            score, aligned = align(crop, refs[tpl_name])
            samples[tpl_name].append({'score': score, 'img': aligned,
                                'src': '%s:r%dc%d' % (fname, r, c)})
            frame_counts[tpl_name][fname] = frame_counts[tpl_name].get(fname, 0) + 1

# ---------------- 筛选 + 合成 ----------------
if os.path.isdir(OUT):
    shutil.rmtree(OUT)
os.makedirs(OUT)

new_tpls = {}
manifest = []
print('%-4s %5s %5s %7s %7s   %s' % ('字', '样本', '保留', '均分', '最低分', '剔除的（分数）'))
for name in GLYPH_ORDER:
    arr = samples[name]
    if not arr:
        print('%-4s  %s  没有样本，保留旧模板' % (name, ''))
        new_tpls[name] = refs[name]
        continue
    arr_sorted = sorted(arr, key=lambda s: -s['score'])
    keep = [s for s in arr_sorted if s['score'] >= KEEP_NCC]
    if len(keep) < 3:
        keep = [s for s in arr_sorted if s['score'] >= KEEP_NCC_LOOSE]
    if not keep:
        keep = arr_sorted[:1]
    keep_ids = set(id(s) for s in keep)
    dropped = [s for s in arr if id(s) not in keep_ids]
    stack = np.stack([s['img'].astype(np.float32) for s in keep])
    merged = np.median(stack, axis=0)
    merged = np.clip(np.round(merged), 0, 255).astype(np.uint8)
    new_tpls[name] = merged
    scores = [s['score'] for s in keep]
    print('%-4s %5d %5d %7.3f %7.3f   %s' % (
        name, len(arr), len(keep), float(np.mean(scores)), float(np.min(scores)),
        ' '.join('%s:%.2f' % (s['src'].split(':')[0].replace('.png', ''),
                              s['score']) for s in dropped[:6])))
    fn = 'tpl_%s.png' % name
    imwrite_u(os.path.join(OUT, fn), merged)
    manifest.append({'name': name, 'file': fn, 'samples': len(arr), 'kept': len(keep),
                     'minScore': round(float(np.min(scores)), 4),
                     'meanScore': round(float(np.mean(scores)), 4),
                     'srcs': [s['src'] for s in keep]})

json.dump(manifest, open(os.path.join(OUT, 'manifest.json'), 'w', encoding='utf-8'),
          ensure_ascii=False, indent=1)

# ---------------- 一致性对比：旧模板 vs 新模板对上保留样本的 NCC ----------------
print()
print('--- 一致性（对保留样本的 NCC：旧 → 新，越大越稳） ---')
old_min, new_min = 1.0, 1.0
for name in GLYPH_ORDER:
    arr = samples[name]
    if not arr or name not in new_tpls or len(arr) < 2:
        continue
    o = refs[name].astype(np.float32)
    n = new_tpls[name].astype(np.float32)
    o_scores, n_scores = [], []
    for s in arr:
        if s['score'] >= (KEEP_NCC_LOOSE if len(arr) >= 3 else 1.0):
            o_scores.append(ncc(o, s['img'].astype(np.float32)))
            n_scores.append(ncc(n, s['img'].astype(np.float32)))
    if not o_scores:
        continue
    old_min = min(old_min, min(o_scores))
    new_min = min(new_min, min(n_scores))
    print('%-4s 旧: min=%.3f mean=%.3f   新: min=%.3f mean=%.3f' % (
        name, min(o_scores), float(np.mean(o_scores)), min(n_scores), float(np.mean(n_scores))))
print('全局最低 NCC：旧 %.3f → 新 %.3f' % (old_min, new_min))

# ---------------- 类间混淆对比（±6 平移搜索的最大 NCC，越低越好）----------------
print()
print('--- 类间最大 NCC（前 6 个可疑对，旧 → 新）---')
def top_pairs(tpls):
    pairs = []
    for a in GLYPH_ORDER:
        if a not in tpls:
            continue
        ta = tpls[a].astype(np.float32)
        canvas = cv2.copyMakeBorder(ta, 6, 6, 6, 6, cv2.BORDER_REPLICATE)
        for b in GLYPH_ORDER:
            if b == a or b not in tpls:
                continue
            res = cv2.matchTemplate(canvas, tpls[b].astype(np.float32), cv2.TM_CCOEFF_NORMED)
            _, mx, _, _ = cv2.minMaxLoc(res)
            pairs.append((mx, a, b))
    pairs.sort(reverse=True)
    return pairs[:6]

old_pairs = top_pairs(refs)
new_pairs = top_pairs({k: v for k, v in new_tpls.items() if k in refs})
for i in range(6):
    print('  %s/%s=%.3f   →   %s/%s=%.3f' % (old_pairs[i][1], old_pairs[i][2], old_pairs[i][0],
                                             new_pairs[i][1], new_pairs[i][2], new_pairs[i][0]))

# ---------------- 新旧对照图（放大 3×）----------------
cell = TW * 2
scale = 3
rows = len(GLYPH_ORDER)
pad_label = 90
sheet = np.zeros((rows * cell * scale, pad_label + 3 * cell * scale), np.uint8)
sheet[:] = 30
for i, name in enumerate(GLYPH_ORDER):
    o = cv2.resize(refs[name], (cell * scale, cell * scale), interpolation=cv2.INTER_NEAREST)
    n = cv2.resize(new_tpls[name], (cell * scale, cell * scale), interpolation=cv2.INTER_NEAREST)
    d = cv2.absdiff(refs[name], new_tpls[name]).astype(np.float32) * 3.0
    d = np.clip(d, 0, 255).astype(np.uint8)
    d = cv2.resize(d, (cell * scale, cell * scale), interpolation=cv2.INTER_NEAREST)
    y0 = i * cell * scale
    sheet[y0:y0 + cell * scale, pad_label:pad_label + cell * scale] = o
    sheet[y0:y0 + cell * scale, pad_label + cell * scale:pad_label + 2 * cell * scale] = n
    sheet[y0:y0 + cell * scale, pad_label + 2 * cell * scale:pad_label + 3 * cell * scale] = d
    cv2.putText(sheet, ASCII_NAME[name], (6, y0 + cell * scale // 2),
                cv2.FONT_HERSHEY_SIMPLEX, 0.55, 220, 1, cv2.LINE_AA)
cv2.putText(sheet, 'old | new | diffX3', (6, 18), cv2.FONT_HERSHEY_SIMPLEX, 0.5, 180, 1, cv2.LINE_AA)
imwrite_u(os.path.join(OUT, 'compare.png'), sheet)

print()
print('done ->', OUT)
