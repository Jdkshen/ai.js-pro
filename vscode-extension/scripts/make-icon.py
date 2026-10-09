# -*- coding: utf-8 -*-
"""从应用图标生成 VS Code 扩展图标（images/icon.png, 128x128）。"""
import os

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(
    os.path.dirname(ROOT),
    'modules', 'engine', 'src', 'main', 'res', 'drawable-nodpi', 'ai_js_pro_logo.png',
)
OUT_DIR = os.path.join(ROOT, 'images')
OUT = os.path.join(OUT_DIR, 'icon.png')


def main():
    image = Image.open(SRC).convert('RGBA')
    icon = image.resize((128, 128), Image.LANCZOS)
    os.makedirs(OUT_DIR, exist_ok=True)
    icon.save(OUT, optimize=True)
    print('saved', OUT, icon.size, os.path.getsize(OUT), 'bytes')


if __name__ == '__main__':
    main()
