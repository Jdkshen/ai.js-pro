"""Run the Node24 UI performance probe on an idle MI8 and save a baseline/regression JSON.

The runner is intentionally close to tools/node24-ui-regression.py:
- never touches K40 (cccc62c7)
- refuses to start when MCP already has active runs or overlays
- only stops the execution it started itself
- keeps old evidence by writing a timestamped file when <label>.json already exists

Usage:
  python tools/node24-ui-perf.py --label baseline
  python tools/node24-ui-perf.py --label after-cache --expect-version dev-1229
"""
import argparse
import datetime
import hashlib
import json
import pathlib
import re
import subprocess
import time
import urllib.request

REPO = pathlib.Path(__file__).resolve().parents[1]
DEFAULT_SOURCE = REPO / 'modules/engine/src/test/device/node24-ui-perf.js'
ADB = 'C:/Android/platform-tools/adb.exe'
RESULT_MARKER = 'NODE24_UI_PERF_RESULT '
FAIL_MARKER = 'NODE24_UI_PERF_FAIL '
LAST_DEVICE_FILE = '/sdcard/脚本/node24-ui-perf-last.json'

ap = argparse.ArgumentParser()
ap.add_argument('--serial', default='ce4d2bdb')
ap.add_argument('--port', type=int, default=18767)
ap.add_argument('--label', default='baseline')
ap.add_argument('--source', type=pathlib.Path, default=DEFAULT_SOURCE)
ap.add_argument('--output', type=pathlib.Path, default=REPO / '.artifacts/m3-perf/round2')
ap.add_argument('--timeout', type=int, default=120)
ap.add_argument('--expect-version', default='')
args = ap.parse_args()

if args.serial == 'cccc62c7':
    raise SystemExit('K40 (cccc62c7) is reserved; select the idle MI8')
args.output.mkdir(parents=True, exist_ok=True)
started_at = datetime.datetime.now().astimezone().isoformat(timespec='seconds')

def adb(*cmd):
    return subprocess.check_output([ADB, '-s', args.serial, *cmd], text=True,
                                   encoding='utf-8', errors='replace').strip()

def call(name, values=None):
    body = json.dumps({'jsonrpc': '2.0', 'id': 1, 'method': 'tools/call',
                       'params': {'name': name, 'arguments': values or {}}}).encode()
    request = urllib.request.Request(
        f'http://127.0.0.1:{args.port}/mcp', data=body,
        headers={'Content-Type': 'application/json', 'Accept': 'application/json, text/event-stream'})
    with urllib.request.urlopen(request, timeout=35) as response:
        text = response.read().decode()
    obj = json.loads(text if text.lstrip().startswith('{') else
                     [line[5:] for line in text.splitlines() if line.startswith('data:')][-1])
    if obj.get('error'):
        raise RuntimeError(obj['error'])
    result = obj.get('result', {})
    if result.get('isError'):
        raise RuntimeError(result)
    if 'structuredContent' in result:
        return result['structuredContent']
    return json.loads('\n'.join(c.get('text', '') for c in result.get('content', []) if c.get('type') == 'text'))

def version():
    info = adb('shell', 'dumpsys', 'package', 'com.jdkshen.aijspro')
    match = re.search(r'versionCode=(\d+)', info)
    if not match:
        raise RuntimeError('com.jdkshen.aijspro is not installed')
    return int(match.group(1))

def choose_output(label):
    exact = args.output / f'{label}.json'
    if not exact.exists():
        return exact, False
    stamp = datetime.datetime.now().strftime('%Y%m%d-%H%M%S')
    return args.output / f'{label}-{stamp}.json', True

def marker_from_logs(logs):
    payload = None
    fail = None
    for entry in logs or []:
        content = str(entry.get('content', ''))
        if RESULT_MARKER in content:
            try:
                payload = json.loads(content.split(RESULT_MARKER, 1)[1].strip())
            except Exception:
                payload = {'ok': False, 'parseError': 'bad NODE24_UI_PERF_RESULT JSON'}
        if FAIL_MARKER in content:
            fail = content.split(FAIL_MARKER, 1)[1].strip()
    return payload, fail

def read_device_fallback():
    try:
        local = args.output / '.node24-ui-perf-last-pulled.json'
        adb('pull', LAST_DEVICE_FILE, str(local))
        return json.loads(local.read_text(encoding='utf-8'))
    except Exception:
        return None

out = {'label': args.label, 'serial': args.serial, 'source': str(args.source),
       'sourceSha256': '', 'startedAt': started_at, 'ok': False}
execution = None
original_pid = None
try:
    if not args.source.is_file():
        raise RuntimeError(f'source probe missing: {args.source}')
    source_text = args.source.read_text(encoding='utf-8')
    source_sha = hashlib.sha256(source_text.encode('utf-8')).hexdigest()
    out['sourceSha256'] = source_sha
    out['version'] = version()
    if args.expect_version and str(out['version']) != args.expect_version:
        raise RuntimeError(f"version mismatch: expected {args.expect_version}, got {out['version']}")
    adb('forward', f"tcp:{args.port}", 'tcp:8765')
    status = call('get_status')
    overlays = call('list_overlays')
    out['beforeStatus'] = status
    out['beforeOverlays'] = overlays
    if status.get('activeMcpRuns') or overlays.get('count'):
        raise RuntimeError('phone busy: activeMcpRuns/overlays not zero; no script was stopped')
    original_pid = adb('shell', 'pidof', 'com.jdkshen.aijspro')
    out['originalPid'] = original_pid
    run = call('run_script', {'engine': 'node', 'source': source_text,
                              'name': 'node24-ui-perf-' + args.label, 'wait': False})
    execution = run['executionId']
    out['executionId'] = execution
    execution_item = None
    deadline = time.monotonic() + args.timeout
    while time.monotonic() < deadline:
        item = call('get_execution', {'executionId': execution})
        if item.get('status') not in ['RUNNING', 'QUEUED']:
            execution_item = item
            execution = None
            break
        time.sleep(0.4)
    if execution_item is None:
        out['threads'] = adb('shell', 'ps', '-A', '-T')
        raise RuntimeError('Node UI perf probe timed out; stopped only this execution')
    out['execution'] = execution_item
    out['versionAfter'] = version()
    out['mainPidUnchanged'] = adb('shell', 'pidof', 'com.jdkshen.aijspro') == original_pid
    probe, fail = marker_from_logs(execution_item.get('logs'))
    if probe is None:
        probe = read_device_fallback()
        if probe is not None:
            out['probeSource'] = 'device-fallback'
    if probe is None:
        raise RuntimeError('no NODE24_UI_PERF_RESULT marker in logs: ' + str(fail or execution_item.get('status')))
    out['probe'] = probe
    out['afterStatus'] = call('get_status')
    after_overlays = call('list_overlays')
    out['afterOverlays'] = after_overlays
    out['ok'] = bool(execution_item.get('status') == 'SUCCEEDED' and probe.get('ok') and
                     out['mainPidUnchanged'] and out['versionAfter'] == out['version'] and
                     after_overlays.get('count') == 0)
    if not out['ok']:
        raise RuntimeError('probe failed / version changed / main pid restarted / overlay left')
    target, renamed = choose_output(args.label)
    out['output'] = str(target)
    out['outputRenamed'] = renamed
    out['finishedAt'] = datetime.datetime.now().astimezone().isoformat(timespec='seconds')
    target.write_text(json.dumps(out, ensure_ascii=False, indent=2), encoding='utf-8')
    print('NODE24_UI_PERF_JSON ' + json.dumps({
        'label': args.label, 'version': out['version'], 'output': str(target),
        'phases': probe.get('phases'), 'inflate': probe.get('inflate'),
        'ok': True}, ensure_ascii=False), flush=True)
except Exception as error:
    out['error'] = str(error)
    target, renamed = choose_output(args.label + '-failed')
    out['output'] = str(target)
    out['outputRenamed'] = renamed
    out['finishedAt'] = datetime.datetime.now().astimezone().isoformat(timespec='seconds')
    target.write_text(json.dumps(out, ensure_ascii=False, indent=2), encoding='utf-8')
    print('NODE24_UI_PERF_JSON ' + json.dumps({'label': args.label, 'ok': False,
         'error': str(error), 'output': str(target)}, ensure_ascii=False), flush=True)
    raise
finally:
    if execution:
        try:
            call('stop_script', {'executionId': execution})
        except Exception:
            pass
    try:
        adb('forward', '--remove', f'tcp:{args.port}')
    except Exception:
        pass
