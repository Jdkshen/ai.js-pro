# -*- coding: utf-8 -*-
"""象棋 7 类模型——百度 AI Studio 免费 GPU 训练脚本（V100 32G）。

用法（AI Studio 项目里的 Notebook，把本文件拖进 JupyterLab 文件区）：
    1) !pip install ultralytics -q
    2) !python train_aistudio.py
先小跑验证流程：!EPOCHS=10 python train_aistudio.py

断线续训（/home/aistudio/work 会保留）：
    from ultralytics import YOLO
    YOLO('/home/aistudio/work/runs/train/weights/last.pt').train(resume=True)
"""
import glob
import os
import shutil

from ultralytics import YOLO

IMGZ = int(os.environ.get('IMGZ', '320'))    # 160 或 320
EPOCHS = int(os.environ.get('EPOCHS', '100'))
BATCH = int(os.environ.get('BATCH', '64'))   # V100 32G 够用；显存不够改 32
WORK = '/home/aistudio/work'
NAMES = '[车, 马, 相, 仕, 帅, 炮, 兵]'


def find_dataset():
    """在 AI Studio 的挂载目录里找含 images/ 的数据集（一般是 data/data<ID>/）。"""
    for pat in ('/home/aistudio/data/data*/images',
                '/home/aistudio/data/*/images',
                '/home/aistudio/data/*/*/images',
                '/home/aistudio/work/*/images',
                '/home/aistudio/*/images'):
        hits = glob.glob(pat)
        if hits:
            return os.path.dirname(hits[0])
    return None


def extract_from_archives():
    """数据集目录里只有分卷(part01/02/...)或 zip 时：自动拼回并解压到 work/data。

    AI Studio 实际挂载路径较深（如 /home/aistudio/data/datasets/397783/chess-merged/），
    所以往下扫 4 层。
    """
    import zipfile
    target = os.path.join(WORK, 'data')
    dirs = sorted(set(glob.glob('/home/aistudio/data/*')
                      + glob.glob('/home/aistudio/data/*/*')
                      + glob.glob('/home/aistudio/data/*/*/*')
                      + glob.glob('/home/aistudio/data/*/*/*/*')))
    for d in dirs:
        if not os.path.isdir(d):
            continue
        parts = sorted(f for f in glob.glob(os.path.join(d, '*part*')) if os.path.isfile(f))
        zips = [f for f in glob.glob(os.path.join(d, '*.zip'))
                if os.path.isfile(f) and os.path.getsize(f) > 10 * 1024 * 1024]
        if not parts and not zips:
            continue
        os.makedirs(WORK, exist_ok=True)
        if parts:
            tmp = os.path.join(WORK, 'full.zip')
            print('reassemble %d parts -> %s' % (len(parts), tmp), flush=True)
            with open(tmp, 'wb') as o:
                for p in parts:
                    with open(p, 'rb') as f:
                        shutil.copyfileobj(f, o)
        else:
            tmp = zips[0]
            print('use zip:', tmp, flush=True)
        print('extract ->', target, flush=True)
        shutil.rmtree(target, ignore_errors=True)
        with zipfile.ZipFile(tmp) as z:
            z.extractall(target)
        if os.path.isdir(os.path.join(target, 'images')):
            return target
    return None


def main():
    src = find_dataset() or extract_from_archives()
    if src is None:
        raise SystemExit('未找到数据集——先在项目里挂载数据集（images/ 或 part01/part02... 分卷均可）')
    print('dataset:', src, flush=True)

    os.makedirs(WORK, exist_ok=True)
    data_dir = os.path.join(WORK, 'data')
    if not os.path.exists(data_dir):
        print('copy dataset ->', data_dir, flush=True)
        shutil.copytree(src, data_dir)

    yaml = os.path.join(data_dir, 'data.yaml')
    with open(yaml, 'w', encoding='utf-8') as f:
        f.write('path: %s\ntrain: images/train\nval: images/val\nnc: 7\nnames: %s\n' % (data_dir, NAMES))

    model = YOLO(os.path.join(src, 'yolo26n.pt'))
    model.train(data=yaml, imgsz=IMGZ, epochs=EPOCHS, batch=BATCH, workers=4, device=0,
                optimizer='AdamW', lr0=0.001, seed=0,
                project=os.path.join(WORK, 'runs'), name='train', exist_ok=True)

    best = os.path.join(WORK, 'runs', 'train', 'weights', 'best.pt')
    print('best:', best, flush=True)
    onnx = YOLO(best).export(format='onnx', imgsz=IMGZ, opset=12, simplify=True,
                             half=False, dynamic=False, nms=False)
    for p in (best, onnx):
        dst = os.path.join('/home/aistudio', os.path.basename(p))
        shutil.copy(p, dst)
        print('output:', dst, flush=True)
    print('DONE')


if __name__ == '__main__':
    main()
