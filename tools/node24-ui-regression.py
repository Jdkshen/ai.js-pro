"""Run Node UI issue probes on an idle MI8; never stop unrelated executions."""
import argparse
import json
import pathlib
import re
import subprocess
import time
import urllib.request

ap = argparse.ArgumentParser()
ap.add_argument('--serial', default='ce4d2bdb')
ap.add_argument('--port', type=int, default=18767)
ap.add_argument('--source', type=pathlib.Path, required=True)
ap.add_argument('--output', type=pathlib.Path, required=True)
ap.add_argument('--timeout', type=int, default=90)
args = ap.parse_args()
if args.serial == 'cccc62c7':
    raise SystemExit('K40 has the user chess task; select an idle phone')
args.output.mkdir(parents=True, exist_ok=True)
ADB = 'C:/Android/platform-tools/adb.exe'
def adb(*cmd):
    return subprocess.check_output([ADB, '-s', args.serial, *cmd], text=True, encoding='utf-8', errors='replace').strip()
def call(name, values=None):
    body = json.dumps({'jsonrpc': '2.0', 'id': 1, 'method': 'tools/call',
                      'params': {'name': name, 'arguments': values or {}}}).encode()
    request = urllib.request.Request(f'http://127.0.0.1:{args.port}/mcp', data=body,
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
    return int(re.search(r'versionCode=(\d+)', adb('shell', 'dumpsys', 'package', 'com.jdkshen.aijspro')).group(1))
adb('forward', f'tcp:{args.port}', 'tcp:8765')
out = {'version': version(), 'source': str(args.source), 'ok': False}
execution = None
try:
    if call('get_status').get('activeMcpRuns') or call('list_overlays').get('count'):
        raise RuntimeError('Phone busy; no script was stopped')
    pid = adb('shell', 'pidof', 'com.jdkshen.aijspro')
    source = args.source.read_text(encoding='utf-8')
    run = call('run_script', {'engine': 'node', 'source': source, 'name': 'node24-ui-issues-regression', 'wait': False})
    execution = run['executionId']
    out['executionId'] = execution
    deadline = time.monotonic() + args.timeout
    while time.monotonic() < deadline:
        item = call('get_execution', {'executionId': execution})
        if item.get('status') not in ['RUNNING', 'QUEUED']:
            out['execution'] = item
            execution = None
            break
        time.sleep(.4)
    else:
        # Only our execution is stopped in finally; retain process/thread evidence first.
        out['threads'] = adb('shell', 'ps', '-A', '-T')
        raise RuntimeError('Node UI probe timed out')
    out['versionAfter'] = version()
    out['mainPidUnchanged'] = adb('shell', 'pidof', 'com.jdkshen.aijspro') == pid
    out['ok'] = (out['execution'].get('status') == 'SUCCEEDED'
                 and out['versionAfter'] == out['version'] and out['mainPidUnchanged'])
    if not out['ok']:
        raise RuntimeError('Probe failed, version changed or main process restarted')
    print(json.dumps(out, ensure_ascii=False), flush=True)
except Exception as error:
    out['error'] = str(error)
    raise
finally:
    if execution:
        try:
            call('stop_script', {'executionId': execution})
        except Exception:
            pass
    (args.output / 'result.json').write_text(json.dumps(out, ensure_ascii=False, indent=2), encoding='utf-8')
    adb('forward', '--remove', f'tcp:{args.port}')
