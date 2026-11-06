"""Run explicitly reviewed bundled entries on Xiaomi 8 and retain per-entry evidence.

Completion is not a functional pass. Long-running/UI entries remain observations.
"""
import argparse
import hashlib
import json
import re
import shlex
import subprocess
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ADB = ['C:/Android/platform-tools/adb.exe', '-s', 'ce4d2bdb']
RECEIVER = 'com.jdkshen.aijspro/com.jdkshen.aijspro.flutterpoc.DebugCommandReceiver'


def adb(*args):
    return subprocess.check_output(ADB + list(args), stderr=subprocess.STDOUT, timeout=30)


def broadcast(*args):
    command = ['am', 'broadcast', '-n', RECEIVER, '-a',
               'com.jdkshen.aijspro.flutterpoc.DEBUG_RUN', *args]
    return adb('shell', shlex.join(command)).decode('utf8', errors='replace')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ids', default='', help='Reviewed indices in sources.json, comma separated')
    parser.add_argument('--seconds', type=float, default=7)
    parser.add_argument('--label', default='baseline')
    parser.add_argument('--asset', action='store_true', help='Use isolated bundled asset extraction rather than the shared test tree')
    parser.add_argument('--dialog-driver', action='store_true', help='Use the app-scoped driver for reviewed dialog samples only')
    parser.add_argument('--driver', default='dialog-driver.js', help='Reviewed app-scoped driver basename')
    parser.add_argument('--target-fixture', action='store_true', help='Show the local disposable target before gesture tests')
    parser.add_argument('--probe', type=Path, help='Local controlled probe copied into the owned test tree')
    parser.add_argument('--keep', action='store_true', help='Keep last script for reviewed UI interaction')
    args = parser.parse_args()
    sources = json.loads((ROOT / 'output/three-engine-audit-20261019/sources.json').read_text(encoding='utf8'))
    folder = ROOT / 'output/mi8-three-engines-20261019' / args.label
    folder.mkdir(parents=True, exist_ok=True)
    if args.probe:
        probe = args.probe.resolve()
        sources = [{'path': probe.name, 'sha256': hashlib.sha256(probe.read_bytes()).hexdigest(), 'engine': 'probe'}]
        adb('push', str(probe), '/sdcard/Android/data/com.jdkshen.aijspro/files/codex-mi8-engine-samples/' + probe.name)
        ids = [0]
    else:
        ids = [int(i) for i in args.ids.split(',') if i]
    installed = adb('shell', 'dumpsys', 'package', 'com.jdkshen.aijspro').decode('utf8', errors='replace')
    version_match = re.search(r'versionName=([^\r\n]+)', installed)
    installed_version = version_match.group(1).strip() if version_match else 'unknown'
    for index in ids:
        source = sources[index]
        slug = str(index).zfill(3) + '-' + hashlib.sha256(source['path'].encode()).hexdigest()[:10]
        logpath = folder / (slug + '.log')
        broadcast('--ez', 'stopAll', 'true')
        time.sleep(.3)
        if args.target_fixture:
            adb('shell', 'cmd', 'statusbar', 'collapse')
            adb('shell', 'input', 'keyevent', 'BACK')
            adb('shell', 'cmd', 'statusbar', 'collapse')
            adb('shell', 'am', 'start', '-W', '-n', 'com.jdkshen.aijspro.apitest/.MainActivity')
            time.sleep(.5)
        row = dict(installedVersionName=installed_version, index=(-1 if args.probe else index), path=source['path'], engine=source['engine'],
                   sourceSha256=source['sha256'], functionalPass=False,
                   evidence=str(logpath.relative_to(ROOT)))
        print('RUN', index, source['path'], flush=True)
        with logpath.open('wb') as log:
            process = subprocess.Popen(ADB + ['logcat', '-T', '1', '-v', 'threadtime', '-s',
                                       'GlobalConsole:V', 'PocRuntime:V', 'PocDebugCmd:V',
                                       'Node24Service:V', 'AndroidRuntime:E', 'libc:F'],
                                       stdout=log, stderr=subprocess.STDOUT)
            try:
                time.sleep(.2)
                offset = logpath.stat().st_size
                started = time.monotonic()
                target = ('assetPath', 'sample/' + source['path']) if args.asset else (
                    'path', '/sdcard/Android/data/com.jdkshen.aijspro/files/codex-mi8-engine-samples/sample/' + source['path'])
                if args.probe:
                    target = ('path', '/sdcard/Android/data/com.jdkshen.aijspro/files/codex-mi8-engine-samples/' + source['path'])
                row['baselineSourceSha256'] = source['sha256']
                if not args.asset:
                    row['sourceSha256'] = adb('shell', 'sha256sum ' + shlex.quote(target[1])).decode('utf8').split()[0]
                row['broadcast'] = broadcast('--es', *target)
                row['launchMode'] = target[0]
                execution_started = None
                driver_started = False
                while time.monotonic() - started < args.seconds + 20:
                    time.sleep(.25)
                    data = logpath.read_bytes()[offset:].decode('utf8', errors='replace')
                    if execution_started is None and '开始运行[' in data:
                        execution_started = time.monotonic()
                    if args.dialog_driver and execution_started is not None and not driver_started:
                        broadcast('--es', 'path', '/sdcard/Android/data/com.jdkshen.aijspro/files/codex-mi8-engine-samples/' + args.driver)
                        driver_started = True
                    failure = re.search(r'(?:ScriptException|QuickJsException|FATAL EXCEPTION|Fatal signal|脚本执行失败|debug command failed)', data)
                    if failure:
                        row['status'] = 'runtime_error'
                        time.sleep(.25)
                        break
                    # Child engines also log completion; wait for this entry's filename.
                    entry_end = re.escape(source['path'].rsplit('/', 1)[-1]) + r'\]运行结束，用时'
                    if re.search(entry_end, data):
                        row['status'] = 'completed_review_required'
                        time.sleep(.25)
                        break
                    if execution_started is not None and time.monotonic() - execution_started >= args.seconds:
                        row['status'] = 'observation_timeout'
                        break
                else:
                    row['status'] = 'observation_timeout'
                row['elapsedSeconds'] = round(time.monotonic() - started, 2)
                data = logpath.read_bytes()[offset:].decode('utf8', errors='replace')
                row['runtimeStarted'] = '开始运行[' in data
                if not row['runtimeStarted'] and row['status'] == 'observation_timeout':
                    row['status'] = 'launch_not_observed'
                row['errors'] = [line for line in data.splitlines() if re.search(r'(?:/E:|GlobalConsole:.* E |Exception|Error:|Fatal signal|失败|不支持|未实现)', line)]
                if row['status'] == 'completed_review_required' and row['errors']:
                    row['status'] = 'completed_with_errors'
                if row['status'] in ('observation_timeout', 'launch_not_observed'):
                    (folder / (slug + '.png')).write_bytes(adb('exec-out', 'screencap', '-p'))
                row['crashBuffer'] = adb('logcat', '-b', 'crash', '-d', '-t', '20').decode('utf8', errors='replace')
            finally:
                process.terminate()
                process.wait(timeout=5)
                if not args.keep:
                    broadcast('--ez', 'stopAll', 'true')
        (folder / (slug + '.json')).write_text(json.dumps(row, ensure_ascii=False, indent=2), encoding='utf8')
        print(row['status'], '\n'.join(row['errors'][:3]), flush=True)


if __name__ == '__main__':
    main()
