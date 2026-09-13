# -*- coding: utf-8 -*-
"""用「识别草稿 + 像素机械判读」交叉验证，为新增帧生成真值。

规则（只有两种**独立方法一致**的格子才算真值）：
  · 机械判读（像素）：该格是空 / 有子且偏红 / 有子且偏暗
  · 识别草稿（已校准的模型输出）
  → 两者一致 → 采用（兵种取自草稿）
  → 不一致，或草稿本身是 '?' → 写成 '?'（**不参与计分**，所有模型用同一套跳过集合，比较仍然公平）

这一步不靠「看图猜」，冲突格宁可不算 —— 上一次域内微调就是被自标标签坑掉的。
"""
import io
import json
import os
import re
import sys

from PIL import Image

X0, Y0, SX, SY = 81, 666, 115, 114
COLS, ROWS = 9, 10
FRAMES = r'.artifacts/chess-skin2/skin2'
DRAFT = r'.artifacts/chess-skin2/draft.json'
OUT = r'.artifacts/chess-skin2/skin2-truth.json'


def expand(layout):
    cells = []
    for row in str(layout).split('/'):
        col = 0
        for ch in row:
            if ch.isdigit():
                cells.extend(['.'] * int(ch))
            else:
                cells.append(ch)
    return cells


def mechanical(img, col, row):
    """空 / 'r'（红）/ 'b'（黑）：格心区统计暗像素与偏红像素。"""
    x, y = X0 + SX * col, Y0 + SY * row
    dark = red = 0
    for dy in range(-30, 31, 3):
        for dx in range(-30, 31, 3):
            r, g, b = img.getpixel((x + dx, y + dy))
            if max(r, g, b) < 170:
                dark += 1
                if r > b + 40:
                    red += 1
    if dark < 60:
        return '.'
    return 'r' if red * 2 > dark else 'b'


def main():
    draft = json.load(io.open(DRAFT, encoding='utf-8'))
    truth = {}
    report = []
    for name in sorted(draft):
        path = os.path.join(FRAMES, name)
        if not os.path.isfile(path):
            print('缺文件：%s' % path)
            continue
        img = Image.open(path).convert('RGB')
        cells = expand(draft[name])
        out = []
        skipped = []
        for index in range(90):
            col, row = index % COLS, index // COLS
            mech = mechanical(img, col, row)
            got = cells[index] if index < len(cells) else '?'
            if got == '?':
                out.append('?')
                skipped.append('%d,%d' % (col, row))
                continue
            got_is_red = got.isupper()
            if got == '.' and mech == '.':
                out.append('.')
            elif mech == 'r' and got_is_red:
                out.append(got)
            elif mech == 'b' and not got_is_red:
                out.append(got)
            else:
                out.append('?')
                skipped.append('%d,%d(机械=%s,草稿=%s)' % (col, row, mech, got))
        # 还原成布局串
        rows = []
        for row in range(ROWS):
            text = ''
            empty = 0
            for col in range(COLS):
                ch = out[row * COLS + col]
                if ch == '.':
                    empty += 1
                    continue
                if empty:
                    text += str(empty)
                    empty = 0
                text += ch
            if empty:
                text += str(empty)
            rows.append(text)
        layout = '/'.join(rows)
        truth[name] = layout
        report.append((name, layout, skipped))

    io.open(OUT, 'w', encoding='utf-8').write(json.dumps(truth, ensure_ascii=False, indent=2))
    for name, layout, skipped in report:
        print('%-14s %s' % (name, layout))
        print('    跳过 %d 格：%s' % (len(skipped), ' '.join(skipped) if skipped else '（无）'))
    print('已写 %s' % OUT)
    # 顺便数一下双方棋子数，明显不合理就说明这帧不能用
    types = 'RNBAKCP'
    for name, layout, _ in report:
        cells = expand(layout)
        red = {t: cells.count(t) for t in types}
        black = {t: cells.count(t.lower()) for t in types}
        unknown = cells.count('?')
        print('%s 红 %s 黑 %s 未知 %d' % (name, red, black, unknown))
    return 0


if __name__ == '__main__':
    sys.exit(main())
