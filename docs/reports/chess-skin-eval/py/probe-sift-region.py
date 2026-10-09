# -*- coding: utf-8 -*-
"""按宿主 sift_matcher.cpp 的完整逻辑复现 SIFT 定位（fast 先跑 2/3 档、FLANN、坐标还原）。

设备日志只给区域数字，这里能重放每一步：哪遍（fast 1/3 还是主遍 0.5）匹到的、
四角落在哪、推导出的等效缩放 sx/sy 是多少、区域公式结果是什么。

用法：
  python probe-sift-region.py <帧.png> [ts ...] [输出.png]
默认 ts 只测 0.59；输出 = py/sift-region-debug.png。
"""
import os
import sys

import cv2
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
SAMPLE = os.path.normpath(os.path.join(HERE, '..', '..', '..', '..',
    'apps', 'app', 'src', 'main', 'assets', 'sample', 'QuickJS 新引擎', '项目与打包', '象棋自动走棋'))
DEFAULT_TPL = os.path.join(SAMPLE, 'assets', 'board-sift', 'board.png')

ARGS = [a for a in sys.argv[1:] if not a.lower().endswith('.png')]
PNGS = [a for a in sys.argv[1:] if a.lower().endswith('.png')]
FRAME_PATH = PNGS[0] if len(PNGS) > 0 else r"C:\Users\18101\AppData\Local\Temp\xin-now.png"
OUT_PATH = PNGS[1] if len(PNGS) > 1 else os.path.join(HERE, 'sift-region-debug.png')
TPL_PATH = DEFAULT_TPL

# 与设备（基础示例.js / 宿主）同口径
LADDER = [float(x) for x in ARGS] or [0.59]
SCENE_SCALE = 0.5                     # host: scale=0.5
FAST_FACTOR = 2.0 / 3.0               # host: fast 首遍 = scale*(2/3)
RATIO = 0.7
MIN_MATCH = 6
RANSAC = 3.0


def imread_u(path):
    data = np.fromfile(path, dtype=np.uint8)
    img = cv2.imdecode(data, cv2.IMREAD_COLOR)
    if img is None:
        raise SystemExit('读不到：' + path)
    return img


def region_of_corners(corners, fw, fh):
    xs = [p[0] for p in corners]
    ys = [p[1] for p in corners]
    minx, maxx = min(xs), max(xs)
    miny, maxy = min(ys), max(ys)
    pad = int(round(min(maxx - minx, maxy - miny) * 0.04))
    left = max(0, int(np.floor(minx)) - pad)
    top = max(0, int(np.floor(miny)) - pad)
    right = min(fw, int(np.ceil(maxx)) + pad)
    bottom = min(fh, int(np.ceil(maxy)) + pad)
    return [left, top, right - left, bottom - top]


def make_matcher(flann):
    if flann:
        return cv2.FlannBasedMatcher(dict(algorithm=1, trees=4), dict(checks=32))
    return cv2.BFMatcher()


def run_cell(frame, tpl_color, ts, scale, support, flann):
    """按宿主一次 search() 的逻辑：缩放、SIFT、knn、ratio、RANSAC、坐标还原。"""
    fh, fw = frame.shape[:2]
    th, tw = tpl_color.shape[:2]
    w = int(round(fw * scale))
    h = int(round(fh * scale))
    scene = cv2.resize(frame, (w, h), interpolation=cv2.INTER_AREA)
    scene_gray = cv2.cvtColor(scene, cv2.COLOR_BGR2GRAY)
    sx, sy = w / fw, h / fh

    tpl = cv2.resize(tpl_color, (int(round(tw * ts)), int(round(th * ts))), interpolation=cv2.INTER_AREA)
    tpl_gray = cv2.cvtColor(tpl, cv2.COLOR_BGR2GRAY)

    sift = cv2.SIFT_create()
    kp_t, des_t = sift.detectAndCompute(tpl_gray, None)
    kp_s, des_s = sift.detectAndCompute(scene_gray, None)
    head = {'tplF': len(kp_t), 'sceneF': len(kp_s)}
    if des_t is None or des_s is None or len(kp_s) < 2:
        return None, head, '场景特征不足'
    matcher = make_matcher(flann)
    try:
        neighbors = matcher.knnMatch(des_t, des_s, k=2)
    except cv2.error as e:
        return None, head, 'knn 异常 %s' % e
    from_pts, to_pts = [], []
    for pair in neighbors:
        if len(pair) >= 2 and pair[0].distance < RATIO * pair[1].distance:
            from_pts.append(kp_t[pair[0].queryIdx].pt)
            to_pts.append(kp_s[pair[0].trainIdx].pt)
    head['good'] = len(from_pts)
    minimum = max(MIN_MATCH, support)
    if len(from_pts) < minimum:
        return None, head, '好匹配 %d < %d' % (len(from_pts), minimum)
    H, mask = cv2.findHomography(np.float32(from_pts), np.float32(to_pts), cv2.RANSAC, RANSAC)
    if H is None or mask is None:
        return None, head, '没解出 H'
    inliers = int(mask.sum())
    head['inliers'] = inliers
    if inliers < minimum:
        return None, head, '内点 %d < %d' % (inliers, minimum)
    src = np.float32([[0, 0], [tpl_gray.shape[1], 0],
                      [tpl_gray.shape[1], tpl_gray.shape[0]], [0, tpl_gray.shape[0]]]).reshape(-1, 1, 2)
    corners = cv2.perspectiveTransform(src, H).reshape(-1, 2)
    corners = np.float32([(p[0] / sx, p[1] / sy) for p in corners])
    return corners, head, '匹到'


frame = imread_u(FRAME_PATH)
tpl_color = imread_u(TPL_PATH)
fh, fw = frame.shape[:2]
print('帧 %dx%d  模板 %dx%d' % (fw, fh, tpl_color.shape[1], tpl_color.shape[0]))

# 参考：真实棋盘（浅木区域）大致范围
gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
hsv = cv2.cvtColor(frame, cv2.COLOR_BGR2HSV)
bright = ((gray > 120) & (hsv[:, :, 1] < 120)).astype(np.uint8)
bright[:, : int(fw * 0.05)] = 0
bright[:, int(fw * 0.95):] = 0
rows = np.where(bright.mean(axis=1) > 0.55)[0]
cols = np.where(bright.mean(axis=0) > 0.30)[0]
wood = None
if len(rows) and len(cols):
    wood = (int(cols.min()), int(rows.min()), int(cols.max()), int(rows.max()))
    print('真实棋盘(浅木)≈ x %d..%d  y %d..%d（高 %d）' % (wood[0], wood[2], wood[1], wood[3], wood[3] - wood[1]))

draw_quad = None
order = (('fast1/3', SCENE_SCALE * FAST_FACTOR, 8, True),
         ('fast1/3', SCENE_SCALE * FAST_FACTOR, 8, False),
         ('main0.5', SCENE_SCALE, 4, True),
         ('main0.5', SCENE_SCALE, 4, False))
for ts in LADDER:
    print('—— 模板×%.2f ——' % ts)
    device_hit = None
    for name, sc, support, flann in order:
        tag = '%s %s' % (name, 'FLANN' if flann else 'BF   ')
        corners, head, note = run_cell(frame, tpl_color, ts, sc, support, flann)
        if corners is None:
            print('  %s: %s（好匹配 %s）' % (tag, note, head.get('good')))
            continue
        xs = [p[0] for p in corners]
        ys = [p[1] for p in corners]
        sx_full = (max(xs) - min(xs)) / (tpl_color.shape[1] * ts)
        sy_full = (max(ys) - min(ys)) / (tpl_color.shape[0] * ts)
        region = region_of_corners(corners, fw, fh)
        print('  %s: 内点 %d 好匹配 %d | 四角 (%.0f,%.0f)(%.0f,%.0f)(%.0f,%.0f)(%.0f,%.0f)'
              % (tag, head['inliers'], head.get('good'),
                 corners[0][0], corners[0][1], corners[1][0], corners[1][1],
                 corners[2][0], corners[2][1], corners[3][0], corners[3][1]))
        print('      → 等效 sx=%.3f sy=%.3f | region (%d,%d)%dx%d'
              % (sx_full, sy_full, region[0], region[1], region[2], region[3]))
        if name.startswith('fast') and flann and device_hit is None:
            device_hit = corners   # 设备顺序里 fast 遍 FLANN 命中就会直接返回

if wood is not None:
    dbg = frame.copy()
    cv2.rectangle(dbg, (wood[0], wood[1]), (wood[2], wood[3]), (255, 0, 0), 6)
    cv2.putText(dbg, 'wood', (wood[0] + 10, wood[1] + 60), cv2.FONT_HERSHEY_SIMPLEX, 1.8, (255, 0, 0), 4, cv2.LINE_AA)
    cv2.rectangle(dbg, (0, 803), (0 + 1279, 803 + 1214), (0, 0, 255), 6)     # 设备此次区域的数字
    cv2.putText(dbg, 'device region', (20, 803 + 80), cv2.FONT_HERSHEY_SIMPLEX, 1.8, (0, 0, 255), 4, cv2.LINE_AA)
    ok, buf = cv2.imencode('.png', dbg)
    buf.tofile(OUT_PATH)
    print('调试图已写：' + OUT_PATH)
