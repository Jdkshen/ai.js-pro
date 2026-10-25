"""Exercise phone-owned MCP pipe terminals; writes only a uniquely named probe directory.

Usage: python tools/verify-mcp-terminal.py --url http://PHONE:8765/mcp --jar probe.jar
No device discovery, app installation, global stop, or package downloads are performed.
"""
import argparse
import base64
import json
from pathlib import Path
import re
import shlex
import time
import urllib.request
import uuid


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--url', required=True)
    parser.add_argument('--jar', type=Path, help='Small JVM-compiled fixture JAR for the D8 test')
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    evidence = []
    active = set()

    def call(name, **arguments):
        request = urllib.request.Request(args.url, json.dumps({
            'jsonrpc': '2.0', 'id': len(evidence) + 1, 'method': 'tools/call',
            'params': {'name': name, 'arguments': arguments}
        }).encode(), {'Content-Type': 'application/json', 'Accept': 'application/json'})
        with urllib.request.urlopen(request, timeout=25) as response:
            result = json.load(response)
        evidence.append({'tool': name, 'arguments': arguments, 'response': result})
        (args.out / 'calls.json').write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding='utf-8')
        if 'error' in result:
            raise RuntimeError(result['error'])
        result = result['result']
        value = result.get('structuredContent')
        if value is None:
            value = result['content'][0]['text']
        if result.get('isError'):
            raise RuntimeError(value)
        return value

    def start(command, **kwargs):
        result = call('terminal_exec', command=command, **kwargs)
        active.add(result['jobId'])
        return result['jobId']

    def finish(job, expected='SUCCEEDED', offset=0):
        output = []
        deadline = time.monotonic() + 45
        while True:
            result = call('terminal_read', jobId=job, offset=offset, waitMillis=1000)
            output.append(result['output'])
            offset = result['nextOffset']
            if not result['processActive'] and not result['hasMore']:
                active.discard(job)
                assert result['status'] == expected, result
                return result, ''.join(output)
            assert time.monotonic() < deadline, result

    def run(command, expected='SUCCEEDED', **kwargs):
        return finish(start(command, **kwargs), expected)

    try:
        call('learn_autojs_skill')
        status = call('get_status')
        tools = call('get_build_tools')
        project = '_mcp_terminal_smoke_' + uuid.uuid4().hex[:10]
        call('workspace_mkdir', path=project)
        result, output = run('printf "first"; sleep 1; printf "第二段\\n"; printf err >&2')
        assert output == 'first第二段\nerr', output
        assert result['exitCode'] == 0

        job = start('printf ready; read value; printf "got:%s" "$value"')
        first = call('terminal_read', jobId=job, waitMillis=1000)
        assert first['output'] == 'ready' and first['running'], first
        call('terminal_write', jobId=job, input='输入\n', eof=True)
        result, output = finish(job, offset=first['nextOffset'])
        assert output == 'got:输入', output

        _, output = run('printf "%s|%s" "$PROBE_VALUE" "$PWD"', workingDirectory=project,
                        env={'PROBE_VALUE': 'value with spaces'})
        assert output == 'value with spaces|' + status['scriptRoot'] + '/' + project, output

        timeout = start('sleep 30', timeoutSeconds=1)
        finish(timeout, 'TIMED_OUT')

        stopped = start('sleep 120 & child=$!; printf "child=%s\\n" "$child"; wait')
        child_state = call('terminal_read', jobId=stopped, waitMillis=1000)
        child = int(re.search(r'child=(\d+)', child_state['output']).group(1))
        call('terminal_stop', jobId=stopped)
        finish(stopped, 'STOPPED', offset=child_state['nextOffset'])
        _, output = run(f'if [ -r /proc/{child}/stat ]; then cat /proc/{child}/stat; else printf gone; fi')
        assert output == 'gone' or re.match(r'\d+ \(.*\) Z ', output), output

        verbose = start("head -c 400000 /dev/zero | tr '\\000' x")
        time.sleep(0.5)
        first = call('terminal_read', jobId=verbose)
        assert first['truncated'] and first['outputStart'] > 0, first
        result, _ = finish(verbose, offset=first['nextOffset'])
        assert result['outputEnd'] == 400000, result

        smali = '.class public Lmcp/TerminalProbe;\n.super Ljava/lang/Object;\n.method public static value()I\n.registers 1\nconst/16 v0, 42\nreturn v0\n.end method\n'
        encoded = base64.b64encode(smali.encode()).decode()
        run('mkdir -p src; printf %s ' + shlex.quote(encoded) + ' | base64 -d > src/TerminalProbe.smali', workingDirectory=project)
        result, output = run('CLASSPATH="$AIJS_APK" app_process /system/bin "$AIJS_BUILD_MAIN" smali assemble src -o classes.dex && test -s classes.dex', workingDirectory=project)
        if args.jar:
            encoded = base64.b64encode(args.jar.read_bytes()).decode()
            run('printf %s ' + shlex.quote(encoded) + ' | base64 -d > input.jar', workingDirectory=project)
            result, output = run('mkdir -p dex; CLASSPATH="$AIJS_APK" app_process /system/bin "$AIJS_BUILD_MAIN" d8 --min-api 26 --output dex input.jar && test -s dex/classes.dex', workingDirectory=project)

        # A broken compiler CLI must fail its child process and leave the host available.
        run('CLASSPATH="$AIJS_APK" app_process /system/bin "$AIJS_BUILD_MAIN" d8 missing.jar', expected='FAILED', workingDirectory=project)
        call('get_status')
        result, output = run('od -An -t x1 -N 8 classes.dex; ls -l classes.dex', workingDirectory=project)
        assert '64 65 78 0a' in output, output
        summary = {'passed': True, 'probeDirectory': project, 'buildTools': tools,
                   'd8JarTest': bool(args.jar), 'callCount': len(evidence)}
        (args.out / 'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding='utf-8')
        print(json.dumps(summary, ensure_ascii=False))
    finally:
        for job in active:
            try:
                call('terminal_stop', jobId=job)
            except Exception:
                pass


if __name__ == '__main__':
    main()
