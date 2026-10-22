"""Run the same common API sources and cross-process storage hand-off on real devices."""
import argparse
import importlib.util
import json
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('engine_audit', ROOT / 'tools/audit-engine-details.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    audit = module.DeviceAudit(args.serial, args.output)
    rows = []
    try:
        # A fresh install starts the service asynchronously. Only retry a read-only
        # tools/list request; never replay a script whose execution is uncertain.
        for attempt in range(30):
            request = urllib.request.Request('http://127.0.0.1:' + audit.port + '/mcp',
                json.dumps({'jsonrpc': '2.0', 'id': 'ready', 'method': 'tools/list'}).encode(),
                {'Content-Type': 'application/json', 'Accept': 'application/json'})
            try:
                with urllib.request.urlopen(request, timeout=2) as response:
                    ready = json.load(response)
                if 'result' in ready:
                    break
            except (OSError, ValueError):
                if attempt == 29:
                    raise
            time.sleep(0.25)
        else:
            raise RuntimeError('MCP did not become ready')
        for engine in ['rhino', 'quickjs', 'nodejs']:
            for name, marker in [('基础接口一致性', 'COMMON_API_RESULT='), ('文件变化监听', 'COMMON_OBSERVER_RESULT='), ('高级版设备接口', 'COMMON_DEVICE_V2_RESULT='), ('颜色与高级版颜色对象', 'COMMON_COLOR_RESULT='), ('子脚本参数与定时执行', 'COMMON_CHILD_RESULT='), ('异步与Java转换工具', 'COMMON_LANG_RESULT='), ('定时器停止与自动退出', 'COMMON_TIMERS_RESULT=')]:
                source = (ROOT / 'samples/engine-common' / (name + '.js')).read_text(encoding='utf8')
                row = audit.run(engine + '-' + name, engine, source, marker, 20)
                rows.append(row)
                print(engine, name, json.dumps(row, ensure_ascii=False), flush=True)
        # The same named store stays live in each process; changing engines must see fresh JSON.
        for index, engine in enumerate(['rhino', 'nodejs', 'quickjs', 'rhino', 'nodejs']):
            source = """var s=storages.create('__aijs_cross_engine_contract__');
var before=s.get('counter',0); s.put('counter',%d);
console.log('STORAGE_HANDOFF='+JSON.stringify({before:before,after:s.get('counter')}));
%s""" % (index + 1, "s.clear();" if index == 4 else "")
            if index == 0:
                source = "storages.create('__aijs_cross_engine_contract__').clear();\n" + source
            row = audit.run('storage-handoff-' + str(index), engine, source, 'STORAGE_HANDOFF=', 15)
            row['expected'] = {'before': index, 'after': index + 1}
            rows.append(row)
            print('handoff', engine, row, flush=True)
        for engine in ['rhino', 'quickjs', 'nodejs']:
            for name, module_name, method, parameters, expected in [
                ('files', 'files', 'getExtension', ['测试.js'], 'js'),
                ('color', 'color', 'toColorInt', [4294967295], -1),
                ('java-utils', 'java_utils', 'toStringOr', [None, 'fallback'], 'fallback')]:
                response = audit.call('call_engine_api', engine=engine, module=module_name, name=method, args=parameters)
                rows.append({'label': engine + '-mcp-' + name, 'engine': engine, 'status': 'SUCCEEDED',
                    'result': {'ok': response.get('ok'), 'value': response.get('value')},
                    'expected': {'ok': True, 'value': expected}})
    finally:
        audit.close()
        (args.output / 'summary.json').write_text(json.dumps(rows, ensure_ascii=False, indent=2), encoding='utf8')
    def failed(row):
        value = row.get('result', {})
        if row['status'] != 'SUCCEEDED' or not value:
            return True
        if 'expected' in row:
            return value != row['expected']
        if 'passed' in value:
            return bool(value['failed'])
        return not (value.get('modified') and value.get('stopped') and value.get('restarted'))
    if any(failed(row) for row in rows):
        raise SystemExit('Common API audit failed; see summary.json')
    print('All common API and storage hand-off cases passed')


if __name__ == '__main__':
    main()
