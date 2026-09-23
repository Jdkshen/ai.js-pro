# -*- coding: utf-8 -*-
"""cv-chess-recognize v2 —— 图色(OpenCV)象棋识别（K40 浅木皮肤）

v1 -> v2 修正：
  1) 棋盘横线 = 10 条（v1 用 11 条导致整盘下移一行、红方底线漏扫）
  2) 字窗 68x68（v1 56x56 会切掉大字体的边缘笔画）
  3) 字形视觉中心比格点高 5px（3D 圆盘透视），模板与匹配统一用该锚点
  4) 匹配改用 cv2.matchTemplate（C 实现，比手写 NCC 循环快 ~20 倍）
  5) 車/馬/炮 三字红黑同形 -> 用笔画颜色 (R-B) 判定阵营

用法：
  python cv-chess-recognize-2.py --live            # 从手机拉当前屏识别
  python cv-chess-recognize-2.py --file xxx.png    # 识别指定截图
  python cv-chess-recognize-2.py --file xxx.png --json out.json
  python cv-chess-recognize-2.py --file xxx.png --learn   # 高置信棋子补进模板库
"""
import json
import os
import subprocess
import sys
import time

import cv2
import numpy as np

try:
    from PIL import Image, ImageDraw, ImageFont
except ImportError:
    Image = None

BASE = os.path.dirname(os.path.abspath(__file__))
TPL_DIR = os.path.join(BASE, 'cv-templates2')
ADB = r'C:\Android\platform-tools\adb.exe'
SER = 'cccc62c7'

TW = 34           # 字窗半宽（68x68）
ANCHOR_DY = -5    # 字形中心相对格点偏移
SEARCH = 12       # 位移搜索半径
CONF_NCC = 0.70   # 分类判定边界（同类min0.96 / 类间max0.55）
DARK_THR = 0.18   # 有子判定（暗像素占比）
FACE_P90 = 200    # 盘面亮度门限（真棋子窗内必有大片亮盘面；按皮肤标定）
SIDED = ('車', '馬', '炮')   # 红黑同形、需颜色判阵营的字


# ---------------- 工具 ----------------
def imread_u(path):
    data = np.fromfile(path, dtype=np.uint8)
    return cv2.imdecode(data, cv2.IMREAD_COLOR)


def imwrite_u(path, img):
    ext = os.path.splitext(path)[1] or '.png'
    ok, buf = cv2.imencode(ext, img)
    buf.tofile(path)


# ---------------- 1) 棋盘定位 ----------------
def valley_mask_1d(vals, win=7, drop=14):
    n = len(vals)
    m = np.zeros(n, dtype=np.int32)
    core = vals[win:n - win]
    neigh = (vals[0:n - 2 * win] + vals[2 * win:n]) / 2.0
    m[win:n - win] = (core < neigh - drop).astype(np.int32)
    return m


def pick_chain_best(cands, step, tol, n, low_ok=40):
    """cands: [(pos, score)]；返回 n 个等距线位置。
    若最长链超过 n，取分数和最大的 n 连长子窗（防止把棋盘外框线算进来）。"""
    cs = sorted(cands)
    pos = [c[0] for c in cs]
    sc = [c[1] for c in cs]
    best = []
    for i in range(len(cs)):
        chain = [i]
        for j in range(i + 1, len(cs)):
            if abs((pos[j] - pos[chain[-1]]) - step) <= tol:
                chain.append(j)
        if len(chain) > len(best):
            best = chain
    if len(best) >= n:
        wbest, wsc = None, None
        for s in range(len(best) - n + 1):
            sub = best[s:s + n]
            tot = sum(sc[k] for k in sub)
            if wsc is None or tot > wsc:
                wsc, wbest = tot, sub
        seg = np.array([pos[k] for k in wbest], dtype=float)
    else:
        seg = np.array([pos[k] for k in best], dtype=float)
        while len(seg) < n:
            if seg[0] - step > low_ok:
                seg = np.insert(seg, 0, seg[0] - step)
            else:
                seg = np.append(seg, seg[-1] + step)
    return seg[:n]


def locate_board(gray):
    """9 条竖线 + 10 条横线（中国象棋：9 列 x 10 行交叉点）"""
    H, W = gray.shape
    gi = gray.astype(int)
    vx = np.zeros(W, dtype=np.int32)
    for y in range(900, 1400, 4):
        vx += valley_mask_1d(gi[y, :])
    cand = [x for x in range(40, W - 40) if vx[x] > 0 and vx[x] >= vx[max(0, x - 20):x + 20].max()]
    merged = []
    for p in cand:
        if merged and p - merged[-1] <= 30:
            if vx[p] > vx[merged[-1]]:
                merged[-1] = p
        else:
            merged.append(p)
    vy = np.zeros(H, dtype=np.int32)
    for x in range(90, 990, 6):
        vy += valley_mask_1d(gi[:, x])
    candy = [y for y in range(430, 1800) if vy[y] > 3 and vy[y] >= vy[max(0, y - 20):y + 20].max()]
    mergedy = []
    for p in candy:
        if mergedy and p - mergedy[-1] <= 30:
            if vy[p] > vy[mergedy[-1]]:
                mergedy[-1] = p
        else:
            mergedy.append(p)
    v9 = pick_chain_best([(x, int(vx[x])) for x in merged], 114.6, 9, 9)
    h10 = pick_chain_best([(y, int(vy[y])) for y in mergedy], 115.1, 9, 10)
    return v9, h10, {'x_cands': merged, 'y_cands': mergedy}


def locate_board_multi(gray):
    """返回候选网格列表（按优先顺序）：[(v9, h10), ...]
    首项为主检测；后续为按格距平移并吸附到谷线候选的备选 ——
    防「底排/首排被棋子压住 → 最优窗选错 → 整盘错位」的隐患。"""
    v9, h10, dbg = locate_board(gray)
    cands = [(v9, h10)]
    yc = dbg['y_cands']
    for shift in (1, -1, 2, -2):
        alt = []
        for y in h10:
            t = y + shift * 115.1
            near = [c for c in yc if abs(c - t) <= 10]
            alt.append(min(near, key=lambda c: abs(c - t)) if near else t)
        cands.append((v9, np.array(alt, dtype=float)))
    return cands


# ---------------- 2/3/4) 识别 ----------------
def load_templates():
    man_path = os.path.join(TPL_DIR, 'manifest.json')
    man = json.load(open(man_path, encoding='utf-8'))
    out = {}
    for item in man:
        t = imread_u(os.path.join(TPL_DIR, item['file']))
        g = cv2.cvtColor(t, cv2.COLOR_BGR2GRAY) if t.ndim == 3 else t
        out.setdefault(item['name'], []).append(g.astype(np.float32))
    return out


def match_at(grayf, x, y, templates):
    """在 (x, y+ANCHOR_DY) 附近 ±SEARCH 搜索最佳模板；返回 (name, ncc, cx, cy)"""
    cy = y + ANCHOR_DY
    H, W = grayf.shape
    r0, r1 = cy - TW - SEARCH, cy + TW + SEARCH
    c0, c1 = x - TW - SEARCH, x + TW + SEARCH
    if r0 < 0 or c0 < 0 or r1 > H or c1 > W:
        return None
    region = grayf[r0:r1, c0:c1]
    best = ('?', -1.0, x, cy)
    for name, tlist in templates.items():
        for t in tlist:
            res = cv2.matchTemplate(region, t, cv2.TM_CCOEFF_NORMED)
            _, mx, _, ml = cv2.minMaxLoc(res)
            if mx > best[1]:
                best = (name, float(mx), c0 + ml[0] + TW, r0 + ml[1] + TW)
    return best


def side_of_center(img, cx, cy):
    """車/馬/炮：匹配窗内笔画颜色 R-B>45 -> 红，否则黑"""
    r0, c0 = cy - TW, cx - TW
    win = img[r0:r0 + TW * 2, c0:c0 + TW * 2]
    if win.shape[0] != TW * 2 or win.shape[1] != TW * 2:
        return '?'
    win_g = cv2.cvtColor(win, cv2.COLOR_BGR2GRAY)
    m = win_g < 150
    if m.sum() < 30:
        return '?'
    b, g, r = win[m].mean(axis=0)
    return 'r' if (r - b) > 45 else 'b'


def recognize(img, v9, h10, templates, verbose=False):
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    grayf = gray.astype(np.float32)
    t0 = time.time()
    results = []
    for r in range(10):
        for c in range(9):
            x, y = int(round(v9[c])), int(round(h10[r]))
            patch = gray[max(0, y - 34):y + 34, max(0, x - 34):x + 34]
            if patch.size == 0 or float((patch < 140).mean()) <= DARK_THR:
                continue
            # 内层门限：字形必在格心附近（滤掉棋盘下边缘暗带之类的边缘误报）
            cy = y + ANCHOR_DY
            inner = gray[cy - 20:cy + 20, x - 20:x + 20]
            if inner.size == 0 or float((inner < 140).mean()) <= 0.12:
                continue
            # 亮盘面门限：真棋子的字窗内必有大片亮盘面（p90）；板边/暗带没有
            face = gray[cy - TW:cy + TW, x - TW:x + TW]
            if face.shape[0] != TW * 2 or float(np.percentile(face, 90)) < FACE_P90:
                continue
            m = match_at(grayf, x, y, templates)
            if m is None:
                continue
            name, sc, mx, my = m
            if sc < CONF_NCC:
                results.append({'r': r, 'c': c, 'x': mx, 'y': my, 'name': '?',
                                'ncc': round(sc, 3), 'side': None})
                continue
            if name in SIDED:
                side = side_of_center(img, mx, my)
            else:
                side = 'r' if name in ('帥', '仕', '相', '兵') else 'b'
            results.append({'r': r, 'c': c, 'x': mx, 'y': my, 'name': name,
                            'ncc': round(sc, 3), 'side': side})
    if verbose:
        print('识别耗时 %.2fs' % (time.time() - t0))
    return results


def render_text(results):
    grid = [['.' for _ in range(9)] for _ in range(10)]
    for it in results:
        grid[it['r']][it['c']] = it['name']
    return '\n'.join(' '.join(row) for row in grid), grid


def _cn_font(size=30):
    if Image is None:
        return None
    for p in (r'C:\Windows\Fonts\msyh.ttc', r'C:\Windows\Fonts\simhei.ttf',
              r'C:\Windows\Fonts\simsun.ttc'):
        if os.path.exists(p):
            return ImageFont.truetype(p, size)
    return None


def annotate(img, v9, h10, results):
    canvas = img.copy()
    for x in v9:
        cv2.line(canvas, (int(x), int(h10[0])), (int(x), int(h10[-1])), (0, 200, 0), 2)
    for y in h10:
        cv2.line(canvas, (int(v9[0]), int(y)), (int(v9[-1]), int(y)), (0, 200, 0), 2)
    items = []
    for it in results:
        red = it.get('side') == 'r'
        if it['name'] == '?':
            color = (0, 165, 255)
        else:
            color = (0, 0, 255) if red else (255, 80, 0)
        cv2.circle(canvas, (it['x'], it['y']), 40, color, 4)
        label = '%s %s %.2f' % (it['name'],
                                '' if it.get('side') is None else ('红' if red else '黑'),
                                it['ncc'])
        items.append((label, it['x'] - 60, it['y'] - 62, color))
    if Image is not None:
        font = _cn_font(28)
        pil = Image.fromarray(cv2.cvtColor(canvas, cv2.COLOR_BGR2RGB))
        dr = ImageDraw.Draw(pil)
        for label, tx, ty, (b, g, r) in items:
            dr.text((tx, max(0, ty)), label, font=font, fill=(r, g, b))
        canvas = cv2.cvtColor(np.array(pil), cv2.COLOR_RGB2BGR)
    else:
        for label, tx, ty, color in items:
            cv2.putText(canvas, label, (tx, max(20, ty + 20)), cv2.FONT_HERSHEY_SIMPLEX,
                        0.8, color, 2)
    return canvas


def learn(img, results):
    man_path = os.path.join(TPL_DIR, 'manifest.json')
    man = json.load(open(man_path, encoding='utf-8'))
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    added = 0
    for it in results:
        if it['name'] == '?' or it['ncc'] < 0.9:
            continue
        win = gray[it['y'] - TW:it['y'] + TW, it['x'] - TW:it['x'] + TW]
        if win.shape[0] != TW * 2:
            continue
        fn = 'tpl_%s_%d_%d.png' % (it['name'], it['x'], it['y'])
        imwrite_u(os.path.join(TPL_DIR, fn), win)
        man.append({'name': it['name'], 'file': fn, 'src': [it['x'], it['y'] - ANCHOR_DY]})
        added += 1
    json.dump(man, open(man_path, 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
    return added


def main():
    args = sys.argv[1:]
    if '--live' in args:
        print('从 K40 拉当前屏...')
        r = subprocess.run([ADB, '-s', SER, 'exec-out', 'screencap', '-p'], capture_output=True)
        img = cv2.imdecode(np.frombuffer(r.stdout, dtype=np.uint8), cv2.IMREAD_COLOR)
    elif '--file' in args:
        img = imread_u(args[args.index('--file') + 1])
    else:
        img = imread_u(os.path.join(BASE, 'newgame-frame1.png'))
    if img is None:
        print('读图失败')
        return 1

    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    v9, h10, dbg = locate_board(gray)
    print('棋盘定位: 竖线 %s' % [int(v) for v in v9])
    print('          横线 %s' % [int(v) for v in h10])
    templates = load_templates()
    print('模板库: %s' % {k: len(v) for k, v in sorted(templates.items())})

    results = recognize(img, v9, h10, templates, verbose=True)
    text, grid = render_text(results)
    print()
    print('==== 盘面（%d 子）====' % len(results))
    print(text)
    print()
    for it in results:
        tag = {'r': '红', 'b': '黑', None: '  '}.get(it['side'], '?')
        print('  r%dc%d → %s%s (NCC %.3f)' % (it['r'], it['c'], tag, it['name'], it['ncc']))
    n_r = sum(1 for it in results if it['side'] == 'r')
    n_b = sum(1 for it in results if it['side'] == 'b')
    print('阵营: 红 %d / 黑 %d' % (n_r, n_b))

    canvas = annotate(img, v9, h10, results)
    out = os.path.join(BASE, 'recognized2.png')
    imwrite_u(out, canvas)
    print('saved: %s' % out)

    if '--json' in args:
        p = args[args.index('--json') + 1]
        json.dump({'lines': [int(v) for v in v9], 'ranks': [int(v) for v in h10],
                   'pieces': results},
                  open(p, 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
        print('json: %s' % p)
    if '--learn' in args:
        print('learned %d' % learn(img, results))
    return 0


if __name__ == '__main__':
    sys.exit(main())
