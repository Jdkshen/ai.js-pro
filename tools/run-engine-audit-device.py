"""Run one explicitly selected sample and collect new Android log evidence."""
import argparse
import json
import re
import shlex
import subprocess
import time
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    target = parser.add_mutually_exclusive_group(required=True)
    target.add_argument('--script', type=Path)
    target.add_argument('--asset')
    parser.add_argument('--until', required=True, help='completion regex, not an API success assertion')
    parser.add_argument('--seconds', type=int, default=45)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    adb = ['C:/Android/platform-tools/adb.exe', '-s', args.serial]

    def call(*parts):
        return subprocess.check_output(adb + list(parts), stderr=subprocess.STDOUT).decode('utf8', errors='replace')

    remote = '/sdcard/Download/codex-three-engine-audit/' + (args.script.name if args.script else '')
    if args.script:
        call('shell', 'mkdir -p /sdcard/Download/codex-three-engine-audit')
        call('push', str(args.script.resolve()), remote)
    command = ['am', 'broadcast', '-n', 'com.jdkshen.aijspro/com.jdkshen.aijspro.flutterpoc.DebugCommandReceiver',
               '-a', 'com.jdkshen.aijspro.flutterpoc.DEBUG_RUN', '--es',
               'path' if args.script else 'assetPath', remote if args.script else args.asset]
    started = time.monotonic()
    matched = False
    with args.output.open('wb') as log:
        process = subprocess.Popen(adb + ['logcat', '-T', '1', '-v', 'threadtime', '-s', 'GlobalConsole:V',
                                   'PocRuntime:V', 'PocDebugCmd:V', 'Node24Service:V', 'AndroidRuntime:E', 'libc:F'],
                                   stdout=log, stderr=subprocess.STDOUT)
        try:
            time.sleep(.25)
            print(call('shell', shlex.join(command)).strip(), flush=True)
            while time.monotonic() - started < args.seconds:
                time.sleep(.25)
                text = args.output.read_text(encoding='utf8', errors='replace')
                if re.search(args.until, text):
                    matched = True
                    time.sleep(.5)
                    break
        finally:
            process.terminate()
            process.wait(timeout=5)
    result = dict(target=str(args.script or args.asset), completionMatched=matched,
                  elapsedSeconds=round(time.monotonic() - started, 2), log=str(args.output.resolve()))
    args.output.with_suffix('.run.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf8')
    print(json.dumps(result, ensure_ascii=False))
    if not matched:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
