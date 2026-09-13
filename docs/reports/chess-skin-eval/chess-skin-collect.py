# -*- coding: utf-8 -*-
"""P0：给「腾讯天天象棋皮肤 + K40」建一套离线评测帧。

做法：游戏在前台时，反复「截一帧 → 随机点两步（走子）→ 再截一帧」，
只有棋盘真的变了才留下这一帧（说明那两步被游戏接受了）。
位置的多样性靠游戏自己的 AI 应着 + 我们的随机走子自然产生。

产物：.artifacts/chess-skin/skin-NN.png + collect.log（每帧一行）
"""
import io
import os
import random
import subprocess
import sys
import time

from PIL import Image

ADB = r'C:\Android\platform-tools\adb.exe'
SER = 'cccc62c7'
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'chess-skin')
# 棋盘几何（board-locator 在真机给出的值：起点 + 格距）
X0, Y0, SX, SY = 81, 666, 115, 114
WANT = int(sys.argv[1]) if len(sys.argv) > 1 else 12
os.makedirs(OUT, exist_ok=True)


def adb(*args, binary=False):
    result = subprocess.run([ADB, '-s', SER] + list(args), capture_output=True)
    if result.returncode != 0:
        raise RuntimeError('adb %s -> %s' % (' '.join(args), result.stderr.decode('utf-8', 'replace')))
    return result.stdout


def capture(path):
    # exec-out 直接落二进制，不经 shell 的换行改写
    with io.open(path, 'wb') as file:
        subprocess.run([ADB, '-s', SER, 'exec-out', 'screencap', '-p'], stdout=file, check=True)


def tap(col, row):
    adb('shell', 'input', 'tap', str(X0 + SX * col), str(Y0 + SY * row))


def board_pixels(path):
    """棋盘区域内一批采样点的像素（用于判断局面有没有变）。"""
    img = Image.open(path).convert('RGB')
    pixels = img.load()
    points = []
    for row in range(10):
        for col in range(9):
            points.append(pixels[X0 + SX * col, Y0 + SY * row])
    return points


def changed(a, b, threshold=12):
    diff = sum(abs(p[0] - q[0]) + abs(p[1] - q[1]) + abs(p[2] - q[2]) for p, q in zip(a, b))
    return diff / float(len(a)) > threshold, diff / float(len(a))


def main():
    random.seed(20260913)
    top = adb('shell', 'dumpsys', 'activity', 'activities').decode('utf-8', 'replace')
    line = [item for item in top.splitlines() if 'topResumedActivity' in item]
    print('前台：%s' % (line[0].strip()[:120] if line else '?'))
    if line and 'com.tencent.qqgame.xq' not in line[0]:
        print('⚠️ 游戏不在前台，先切过去再跑')
        return 1

    log = io.open(os.path.join(OUT, 'collect.log'), 'w', encoding='utf-8')
    before_path = os.path.join(OUT, '_before.png')
    after_path = os.path.join(OUT, '_after.png')
    capture(before_path)
    saved = 0
    attempts = 0
    while saved < WANT and attempts < WANT * 6:
        attempts += 1
        base = board_pixels(before_path)
        # 随机选一对交点当作「起点/终点」；不合法游戏会忽略，我们再换一对
        cells = [(c, r) for r in range(10) for c in range(9)]
        src, dst = random.sample(cells, 2)
        tap(src[0], src[1])
        time.sleep(0.35)
        tap(dst[0], dst[1])
        time.sleep(1.6)
        capture(after_path)
        ok, score = changed(base, board_pixels(after_path))
        if not ok:
            log.write('跳过：%s -> %s 局面没变（diff=%.1f）\n' % (src, dst, score))
            log.flush()
            continue
        saved += 1
        name = 'skin-%02d.png' % saved
        os.replace(after_path, os.path.join(OUT, name))
        log.write('%s  %s -> %s  diff=%.1f\n' % (name, src, dst, score))
        log.flush()
        print('%s  (%d/%d)  %s->%s diff=%.1f' % (name, saved, WANT, src, dst, score))
        os.replace(before_path, os.path.join(OUT, '_prev.png'))
        # 下一轮的「前」= 本轮的「后」
        capture(before_path)
    log.close()
    for temp in ('_before.png', '_after.png'):
        path = os.path.join(OUT, temp)
        if os.path.isfile(path):
            os.remove(path)
    print('抓帧完成：%d 张（尝试 %d 次）' % (saved, attempts))
    return 0


if __name__ == '__main__':
    sys.exit(main())
