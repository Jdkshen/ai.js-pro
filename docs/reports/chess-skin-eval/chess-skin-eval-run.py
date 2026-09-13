# -*- coding: utf-8 -*-
"""真机皮肤盘面级评测的一键运行器（避免 PowerShell 内联引号把 JSON 写坏）。

用法：
  python -X utf8 .artifacts/chess-skin-eval-run.py                # 内置模型 + 不带校准
  python -X utf8 .artifacts/chess-skin-eval-run.py --calibrate    # 内置模型 + 会话级校准
  python -X utf8 .artifacts/chess-skin-eval-run.py --model /sdcard/yolo-bench/models/chessai_v4.onnx
  python -X utf8 .artifacts/chess-skin-eval-run.py --all          # 四种组合都跑一遍
"""
import io
import json
import os
import re
import subprocess
import sys
import time

REPO = r'C:\Users\18101\Documents\ai-js-pro'
ADB = r'C:\Android\platform-tools\adb.exe'
SER = 'cccc62c7'
OPTIONS = '/sdcard/xiangqi-p4/skin-eval.json'
READY_MARK = 'SKINE done'


def adb(*args):
    return subprocess.run([ADB, '-s', SER] + list(args), capture_output=True)


def run_once(model, calibrate, confidence=0.25):
    options = {'calibrate': calibrate, 'confidence': confidence,
               'search': {'maxShift': 18, 'step': 3}, 'maxCandidates': 25}
    if model:
        options['model'] = model
        options['labels'] = '/sdcard/yolo-bench/models/chessai_160.txt'
    local = os.path.join(REPO, '.artifacts', 'chess-skin', 'skin-eval-run.json')
    with io.open(local, 'w', encoding='utf-8') as file:
        file.write(json.dumps(options, ensure_ascii=False))
    adb('push', local, OPTIONS)
    adb('logcat', '-c')
    adb('shell', 'am', 'start', '-a', 'android.intent.action.VIEW',
        '-d', 'file:///sdcard/xiangqi-p4/tools/p4-skin-eval.js', '-t', 'text/plain',
        '-n', 'com.jdkshen.aijspro/.external.open.RunIntentActivity')

    deadline = time.time() + 180
    text = ''
    while time.time() < deadline:
        time.sleep(6)
        text = adb('logcat', '-d', '-s', 'GlobalConsole:V').stdout.decode('utf-8', 'replace')
        if READY_MARK in text:
            break
    print('--- 模型=%s 校准=%s ---' % (model or '内置', calibrate))
    for line in text.splitlines():
        index = line.find('SKINE')
        if index < 0:
            continue
        payload = line[index:]
        if ('严格' in payload) or ('===' in payload) or ('开始' in payload):
            print(payload[:300])


def main():
    args = sys.argv[1:]
    model = None
    if '--model' in args:
        model = args[args.index('--model') + 1]
    if '--all' in args:
        for name, path in (('内置', None), ('v4', '/sdcard/yolo-bench/models/chessai_v4.onnx')):
            for calibrate in (False, True):
                run_once(path, calibrate)
        return 0
    run_once(model, '--calibrate' in args)
    return 0


if __name__ == '__main__':
    sys.exit(main())
