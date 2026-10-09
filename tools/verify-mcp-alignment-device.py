"""Integration gate for a dedicated test device; context contains its private connection settings."""
import argparse
import base64
import io
import json
import subprocess
import time
import urllib.request
import uuid
import xml.etree.ElementTree as ET
from pathlib import Path
from PIL import Image

parser = argparse.ArgumentParser()
parser.add_argument('--context', required=True, type=Path)
parser.add_argument('--output', required=True, type=Path)
options = parser.parse_args()
ctx = json.loads(options.context.read_text('utf-8'))
results = []
request_id = 0
fixture = '.mcp-api-check-' + uuid.uuid4().hex[:12]
script_root = None
ADB = r'C:\Android\platform-tools\adb.exe'
PKG = 'com.jdkshen.aijspro'

def rpc(method, params=None):
    global request_id
    request_id += 1
    body = json.dumps(dict(jsonrpc='2.0', id=request_id, method=method, params=params or {}), ensure_ascii=False).encode()
    headers = {'Content-Type': 'application/json', 'Accept': 'application/json, text/event-stream',
        'Authorization': 'Bearer ' + ctx['token'], 'MCP-Protocol-Version': '2025-06-18'}
    request = urllib.request.Request(ctx['url'], body, headers)
    with urllib.request.urlopen(request, timeout=75) as response:
        data = json.load(response)
    assert 'error' not in data, data.get('error')
    return data['result']

def call(name, arguments=None, error=False, raw=False):
    result = rpc('tools/call', dict(name=name, arguments=arguments or {}))
    if error:
        assert result.get('isError') is True, result
        return result
    assert result.get('isError') is not True, result
    if raw: return result
    if 'structuredContent' in result: return result['structuredContent']
    text = next((c['text'] for c in result.get('content', []) if c['type'] == 'text'), '')
    try: return json.loads(text)
    except json.JSONDecodeError: return text

def check(name, function):
    started = time.monotonic()
    try:
        detail = function()
        results.append(dict(name=name, passed=True, elapsedMs=round((time.monotonic()-started)*1000), detail=detail))
        print('PASS', name, flush=True)
    except Exception as error:
        results.append(dict(name=name, passed=False, elapsedMs=round((time.monotonic()-started)*1000), error=str(error)[:1500]))
        print('FAIL', name, str(error)[:400], flush=True)
    options.output.write_text(json.dumps(dict(checks=results), ensure_ascii=False, indent=2), 'utf-8')

def write_script(path, source):
    ws = call('workspace_open', dict(path=path, create=True))
    call('workspace_write', dict(workspaceId=ws['workspaceId'], path=path, content=source))
    call('workspace_request_apply', dict(workspaceId=ws['workspaceId']))

def content(run): return '\n'.join(entry['content'] for entry in run.get('logs', []))
def run_source(source, engine='quickjs', name='runScript', **kwargs):
    run = call(name, dict(source=source, engine=engine, wait=True, timeoutSeconds=15, **kwargs))
    assert run['status'] == 'SUCCEEDED', run
    return run

try:
    for _ in range(20):
        try: rpc('initialize', dict(protocolVersion='2025-06-18', capabilities={}, clientInfo=dict(name='mcp-alignment-gate',version='1'))); break
        except OSError: time.sleep(.5)
    else: raise RuntimeError('MCP service not reachable')
    status = call('get_status')
    script_root = status['scriptRoot']
    assert status['executionAllowed'] and status['writeAllowed'], status

    def catalog():
        tools = rpc('tools/list')['tools']
        by_name = {t['name']:t for t in tools}
        assert len(by_name) == len(tools) == 39
        aliases = {'getUiLayout':'get_ui_layout','captureScreen':'capture_screen','checkSyntax':'check_syntax',
            'runScript':'run_script','learnAutoJsSkill':'learn_autojs_skill','stopAllScripts':'stop_all_scripts','getMcpLog':'get_mcp_log'}
        for alias, target in aliases.items(): assert by_name[alias]['inputSchema'] == by_name[target]['inputSchema']
        assert by_name['list_scripts']['inputSchema']['properties']['recursive']['type'] == 'boolean'
        assert by_name['workspace_cleanup']['inputSchema']['properties']['keepAppliedHours']['type'] == 'integer'
        return dict(tools=len(tools), aliases=len(aliases))
    check('39 tools and 7 compatible schemas', catalog)
    check('learnAutoJsSkill guide', lambda: (lambda text: dict(characters=len(text)) if 'QuickJS' in text and 'getUiLayout' in text and 'Node.js' in text else (_ for _ in ()).throw(AssertionError(text[:200])))(call('learnAutoJsSkill')))
    check('invalid source contract rejected', lambda: call('runScript',dict(path='main.js',source='1'),error=True)['isError'])
    check('outside-root path rejected', lambda: call('runScript',dict(file='/system/outside.js'),error=True)['isError'])

    for engine in ('quickjs','rhino'):
        def syntax(e=engine):
            valid = call('checkSyntax',dict(engine=e,code="throw new Error('MUST_NOT_RUN'); while(true) {}"))
            invalid = call('check_syntax',dict(engine=e,code='var a = 1;\nvar b = ;'))
            xml = call('checkSyntax',dict(engine=e,code='ui.layout(<vertical><text text="hi"/></vertical>);'))
            template = call('checkSyntax',dict(engine=e,code='var a = `hello`;'))
            assert valid['valid'] and not valid['executed'], valid
            assert not invalid['valid'] and invalid['errors'], invalid
            assert xml['valid'] and template['valid'], (xml,template)
            return dict(valid=True, invalid=invalid['errors'], xml=True, template=True)
        check(engine+' syntax-only, invalid location, XML and templates',syntax)
        def source(e=engine):
            opposite = 'rhino' if e=='quickjs' else 'quickjs'
            run = run_source('// @engine '+opposite+'\nconsole.log("TEST_RESULT=" + (6 * 7)); 6 * 7;',e)
            assert run['engine']==e and 'TEST_RESULT=42' in content(run),run
            if e=='quickjs': assert float(run['result'])==42,run
            return dict(engine=run['engine'],result=run.get('result'),logs=content(run))
        check(engine+' source execution and explicit engine override',source)

    write_script(fixture+'/lib/value.js','module.exports = {message:"MODULE_OK"};')
    write_script(fixture+'/main.js','console.log("MODULE=" + require("./lib/value.js").message); console.log("ARGV=" + engines.myEngine().execArgv.message);')
    write_script(fixture+'/slow.js','console.log("SLOW_STARTED"); sleep(2800); console.log("TEMP_COUNT=" + files.listDir(files.cwd()).filter(function(n){return n.indexOf(".mcp-engine-") >= 0}).length);')
    for engine in ('quickjs','rhino'):
        def files_and_args(e=engine):
            run = call('runScript',dict(file=script_root+'/'+fixture+'/main.js',engine=e,wait=True,
                config=dict(arguments=dict(message='ARGV_OK'),loopTimes=2,delay=30,interval=20)))
            assert run['status']=='SUCCEEDED',run
            assert content(run).count('MODULE=MODULE_OK')==2 and content(run).count('ARGV=ARGV_OK')==2,run
            source = run_source('console.log("MODULE=" + require("./lib/value.js").message);',e, workingDirectory=fixture)
            assert 'MODULE=MODULE_OK' in content(source),source
            return dict(file=True, arguments=True,loops=2,sourceModule=True)
        check(engine+' absolute file, config arguments/loops and source modules',files_and_args)

    for engine in ('quickjs','rhino'):
        def nested_arguments(e=engine):
            run = run_source('var a=engines.myEngine().execArgv; if(!Array.isArray(a.nested.items) || a.nested.items[1]!==null || a.nested.items[2]!=="🙂" || a.nested.flag!==true || typeof a.nested.items[0]!=="number") throw new Error("JSON_ARGS"); a.changed=7; if(engines.myEngine().execArgv.changed!==7) throw new Error("ARG_IDENTITY"); console.log("NESTED_ARGS_OK");',e,
                config=dict(arguments=dict(nested=dict(items=[1,None,'🙂'],flag=True))))
            assert 'NESTED_ARGS_OK' in content(run),run
            return dict(nested=True,null=True,unicode=True,mutable=True)
        check(engine+' nested JSON arguments and mutable execArgv identity',nested_arguments)

    def isolated():
        q = call('run_script',dict(source='for(var i=0;i<5;i++){ console.log("Q_ONLY="+i); sleep(150); }',engine='quickjs'))
        r = call('runScript',dict(source='for(var i=0;i<5;i++){ console.log("R_ONLY="+i); sleep(150); }',engine='rhino'))
        q = call('wait_execution',dict(executionId=q['executionId'],timeoutSeconds=10))
        r = call('wait_execution',dict(executionId=r['executionId'],timeoutSeconds=10))
        assert q['status']==r['status']=='SUCCEEDED',(q,r)
        assert 'Q_ONLY=' in content(q) and 'R_ONLY=' not in content(q),q
        assert 'R_ONLY=' in content(r) and 'Q_ONLY=' not in content(r),r
        own = call('getMcpLog',dict(executionId=q['executionId'],maxChars=30))
        assert len(own['log'])<=30 and own['truncated'],own
        service = call('get_mcp_log',dict(channel='service',maxChars=1000))
        assert service['log'] and len(service['log'])<=1000,service
        return dict(quickjsLines=len(q['logs']),rhinoLines=len(r['logs']),tail=True,service=True)
    check('parallel task logs isolated and bounded service logs',isolated)

    def probe():
        before = call('list_scripts',dict(path=fixture))
        q = call('probe_engine_api',dict(engine='quickjs',name='ui'))
        r = call('probe_engine_api',dict(engine='rhino',name='ui'))
        diff = call('engine_api_diff')
        after = call('list_scripts',dict(path=fixture))
        assert q['type']==r['type']=='object',(q,r)
        for engine in ('quickjs','rhino'):
            assert call('probe_engine_api',dict(engine=engine,name='ui.performClick'))['type']=='function'
        assert diff['rhinoAvailable'] and diff['quickjsCount']>30 and diff['rhinoCount']>30,diff
        assert before==after,(before,after)
        return dict(quickjs=diff['quickjsCount'],rhino=diff['rhinoCount'])
    check('both-engine probes and difference',probe)

    def timeout_cleanup():
        run = call('runScript',dict(file=fixture+'/slow.js',engine='quickjs',wait=True,timeoutSeconds=1))
        assert run['timedOut'] and run['status'] in ('RUNNING','QUEUED'),run
        files = call('list_scripts',dict(path=fixture))['items']
        assert any('.mcp-engine-' in f['name'] for f in files),files
        final = call('wait_execution',dict(executionId=run['executionId'],timeoutSeconds=10))
        assert final['status']=='SUCCEEDED' and 'TEMP_COUNT=1' in content(final),final
        time.sleep(.2)
        files = call('list_scripts',dict(path=fixture))['items']
        assert not any('.mcp-engine-' in f['name'] for f in files),files
        return dict(timeout=True,fileRetained=True,fileCleaned=True)
    check('wait timeout retains temporary engine file until actual completion',timeout_cleanup)

    def layout():
        result = call('getUiLayout')
        assert result['success'] and result['nodeCount']>1,result
        ET.fromstring(result['xml'])
        limited = call('get_ui_layout',dict(windowId=result['windowId'],maxNodes=1))
        assert limited['nodeCount']==1 and limited['truncated'],limited
        ET.fromstring(limited['xml'])
        call('getUiLayout',dict(windowId=-1234),error=True)
        return dict(package=result['packageName'],nodes=result['nodeCount'],windows=len(result['windows']))
    check('real settings window, bounded XML and invalid window diagnostic',layout)
    for format in ('png','jpg'):
        def screenshot(f=format):
            raw = call('captureScreen',dict(format=f,quality=75,dataUri=f=='jpg',maxWidth=640 if f=='jpg' else 0),raw=True)
            image = next(c for c in raw['content'] if c['type']=='image')
            data = base64.b64decode(image['data'],validate=True)
            decoded = Image.open(io.BytesIO(data))
            meta = raw['structuredContent']
            assert decoded.size==(meta['width'],meta['height']),meta
            assert image['mimeType']==('image/png' if f=='png' else 'image/jpeg'),image['mimeType']
            if f=='jpg': assert meta['width']==640 and meta['dataUri'].endswith(image['data']),meta
            options.output.with_suffix('.'+f).write_bytes(data)
            return dict(width=meta['width'],height=meta['height'],size=len(data),mimeType=image['mimeType'])
        check(format+' real screenshot, MIME, dimensions and dataUri',screenshot)
    check('invalid screenshot format rejected',lambda: call('capture_screen',dict(format='gif'),error=True)['isError'])

    def stop_one():
        run = call('runScript',dict(source='while(true){ console.log("WAITING"); sleep(200); }',engine='quickjs'))
        stopped = call('stop_script',dict(executionId=run['executionId']))
        time.sleep(.5)
        final = call('get_execution',dict(executionId=run['executionId']))
        assert stopped['status']==final['status']=='STOPPED' and 'error' not in final,final
        return dict(stopped=True,errorAbsent=True)
    check('async source stopped without error state',stop_one)

    def stop_all():
        write_script(fixture+'/external.js','// @engine quickjs\nvar p=files.join(files.cwd(),"external-counter.txt"); var i=0; while(true){files.write(p,String(++i));sleep(100);}')
        path = script_root+'/'+fixture+'/external.js'
        subprocess.run([ADB,'-s',ctx['serial'],'shell','run-as',PKG,'am','startservice','--user','0','-n',
            PKG+'/com.jdkshen.aijspro.external.ScriptExecutionIntentService','--es','path',path],check=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=15)
        run = call('runScript',dict(source='while(true){sleep(200);}',engine='rhino'))
        time.sleep(.7)
        before = call('read_script',dict(path=fixture+'/external-counter.txt'))['content']
        stopped = call('stopAllScripts')
        assert stopped['success'] and stopped['stopped']>=2,stopped
        time.sleep(.6)
        after = call('read_script',dict(path=fixture+'/external-counter.txt'))['content']
        time.sleep(.5)
        assert after==call('read_script',dict(path=fixture+'/external-counter.txt'))['content']
        final = call('get_execution',dict(executionId=run['executionId']))
        assert final['status']=='STOPPED' and 'error' not in final,final
        return dict(stopped=stopped['stopped'],externalCounterStopped=True,mcpStopped=True)
    check('stopAllScripts stops MCP and externally started scripts',stop_all)
finally:
    if script_root:
        try: call('stop_all_scripts')
        except Exception: pass
        target = script_root.rstrip('/')+'/'+fixture
        assert fixture.startswith('.mcp-api-check-') and target.startswith(script_root.rstrip('/')+'/')
        subprocess.run([ADB,'-s',ctx['serial'],'shell','rm','-rf',target],stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=15)
    summary = dict(device=ctx['serial'],passed=sum(r['passed'] for r in results),failed=sum(not r['passed'] for r in results),checks=results)
    options.output.write_text(json.dumps(summary,ensure_ascii=False,indent=2),'utf-8')
    print(json.dumps({k:v for k,v in summary.items() if k!='checks'}),flush=True)
    if summary['failed']: raise SystemExit(1)
