"""Run reviewed, bounded engine contracts via the real device MCP service.

Each assertion is recorded separately. Compilation, capability enumeration and
behavior are deliberately separate evidence. Only audit-owned files are used.
"""
import argparse
import hashlib
import json
import subprocess
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ADB = 'C:/Android/platform-tools/adb.exe'


class DeviceAudit:
    def __init__(self, serial, output):
        self.serial, self.output = serial, output
        self.responses = []
        self.port = subprocess.check_output([ADB, '-s', serial, 'forward', 'tcp:0', 'tcp:8765'], text=True).strip()

    def call(self, tool_name, **args):
        body = dict(jsonrpc='2.0', id=len(self.responses) + 1, method='tools/call',
                    params=dict(name=tool_name, arguments=args))
        request = urllib.request.Request('http://127.0.0.1:' + self.port + '/mcp',
                                         json.dumps(body).encode(), {'Content-Type': 'application/json', 'Accept': 'application/json'})
        with urllib.request.urlopen(request, timeout=90) as response:
            result = json.load(response)
        self.responses.append(dict(request=body, response=result))
        self.save()
        if 'error' in result:
            raise RuntimeError(result['error'])
        envelope = result['result']
        if envelope.get('isError'):
            raise RuntimeError(envelope.get('structuredContent') or envelope.get('content'))
        data = envelope.get('structuredContent')
        if data is None:
            data = json.loads(envelope['content'][0]['text'])
        return data

    def save(self):
        (self.output / 'mcp-responses.json').write_text(json.dumps(self.responses, ensure_ascii=False, indent=2), encoding='utf8')

    def run(self, label, engine, source, marker, seconds=40, path=None):
        target={'path':path} if path else {'source':source}
        data = self.call('run_script', engine=engine, **target, wait=True, timeoutSeconds=seconds)
        if data['status'] in ('STARTING', 'RUNNING', 'QUEUED'):
            data = self.call('wait_execution', executionId=data['executionId'], timeoutSeconds=seconds)
        text = '\n'.join(str(row.get('content', '')) for row in data.get('logs', []))
        (self.output / (label + '.log')).write_text(text, encoding='utf8')
        row = dict(label=label, engine=engine, status=data['status'], sha256=hashlib.sha256(source.encode()).hexdigest())
        matches = [line.split(marker, 1)[1] for line in text.replace(chr(30), '').splitlines() if marker in line]
        if matches:
            row['result'] = json.loads(matches[-1])
        else:
            row['error'] = data.get('error') or 'completion result not observed'
        (self.output / (label + '.json')).write_text(json.dumps(row, ensure_ascii=False, indent=2), encoding='utf8')
        print(json.dumps(row, ensure_ascii=False), flush=True)
        if data['status'] in ('STARTING', 'RUNNING', 'QUEUED'):
            self.call('stop_script', executionId=data['executionId'])
        return row

    def close(self):
        self.save()
        subprocess.run([ADB, '-s', self.serial, 'forward', '--remove', 'tcp:' + self.port], check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--mode', choices=['details', 'baseline', 'compile', 'concurrency', 'regressions', 'legacy', 'quickjs-full', 'node-lifecycle', 'ui', 'catalog', 'pikafish'], default='details')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    audit = DeviceAudit(args.serial, args.output)
    rows = []
    try:
        if args.mode == 'pikafish':
            source=(ROOT/'tools/probes/pikafish-invalid-position.js').read_text(encoding='utf8')
            rows.append(audit.run('pikafish-invalid-position','rhino',source,'PIKAFISH_INVALID_POSITION_RESULT=',15))
            source=(ROOT/'tools/probes/pikafish-safe-search.js').read_text(encoding='utf8')
            rows.append(audit.run('pikafish-safe-search','quickjs',source,'PIKAFISH_SAFE_SEARCH_RESULT=',30))
            source=(ROOT/'tools/probes/pikafish-plugin-safe-search.js').read_text(encoding='utf8')
            rows.append(audit.run('pikafish-plugin-safe-search','nodejs',source,'PIKAFISH_PLUGIN_SAFE_RESULT=',30))
        elif args.mode == 'catalog':
            for engine in ['rhino','quickjs','nodejs']:
                data=audit.call('list_engine_api',engine=engine)
                row={'engine':engine,'globals':data,'modules':{}}
                names=(data.get('modules') or []) if engine=='nodejs' else ['files','images','colors','events','engines','threads','storages','device','app','dialogs','ui','floaty','http','sensors','media','sqlite','shell','plugins','ocr','yolo','$settings','$colors','$crypto','$zip']
                for name in names:
                    try:
                        row['modules'][name]=audit.call('list_engine_api',engine=engine,module=name) if engine=='nodejs' else audit.call('probe_engine_api',engine=engine,name=name)
                    except RuntimeError as error:
                        row['modules'][name]={'queryError':str(error)}
                rows.append(row)
                print(engine,'module queries',len(names),flush=True)
        elif args.mode == 'ui':
            sample=ROOT/'apps/app/src/main/assets/sample/Node 引擎/Node.js/Node.js中使用UI'
            for name,folder,probe,marker in [
                ('traditional',sample/'传统界面','node24-traditional-ui.js','NODE_TRADITIONAL_UI_RESULT '),
                ('custom',sample/'自定义组件','node24-custom-widget.js','NODE_CUSTOM_WIDGET_RESULT '),
                ('web',None,'node24-webui-contract.js','NODE24_WEBUI_RESULT ')]:
                remote='/sdcard/脚本/__engine_detail_audit__/'+name
                subprocess.run([ADB,'-s',args.serial,'shell','mkdir -p '+remote],check=True,stdout=subprocess.DEVNULL)
                if folder:
                    for file in folder.iterdir():
                        if file.is_file():
                            subprocess.run([ADB,'-s',args.serial,'push',str(file),remote+'/'+file.name],check=True,stdout=subprocess.DEVNULL)
                local=ROOT/'tools/probes'/probe
                subprocess.run([ADB,'-s',args.serial,'push',str(local),remote+'/probe.node.js'],check=True,stdout=subprocess.DEVNULL)
                row=audit.run(name+'-ui','nodejs',local.read_text(encoding='utf8'),marker,35,path='__engine_detail_audit__/'+name+'/probe.node.js')
                value=row.get('result',{})
                checks=value.get('checks',[])
                value['total']=len(checks)
                value['passed']=sum(v is True for v in checks.values()) if isinstance(checks,dict) else (len(checks) if value.get('ok') else 0)
                rows.append(row)
        elif args.mode == 'details':
            for engine in ['rhino', 'quickjs', 'nodejs']:
                source = (ROOT / 'tools/probes/three-engine-details.js').read_text(encoding='utf8')
                rows.append(audit.run(engine + '-details', engine, 'var auditEngine=' + json.dumps(engine) + ';\n' + source, 'ENGINE_DETAILS_RESULT='))
        elif args.mode == 'baseline':
            for engine, file, marker in [
                ('rhino', 'tools/verify-rhino-api-device.js', 'RHINO_API_REGRESSION_RESULT='),
                ('nodejs', 'output/three-engine-audit-20261019/node-behavior.js', 'NODE_BEHAVIOR_RESULT='),
            ]:
                rows.append(audit.run(engine + '-baseline', engine, (ROOT / file).read_text(encoding='utf8'), marker))
        elif args.mode == 'concurrency':
            active=[]
            namespace='__engine_detail_pair_'+str(time.time_ns())
            for number in range(2):
                source = '''const fs=require('fs'), engines=require('engines');
const name='PAIR_NUMBER';
const ds=require('datastore').createDatastore('PAIR_NAMESPACE');
ds.getSync('init');
let success=0,started=0,childId=null,childSource=null;
setTimeout(()=>{
 const me=engines.myEngine();
 console.log('OWNER_RESULT='+JSON.stringify({name,id:me.id,source:String(me.getSource()),argv:me.execArgv}));
 const path='/sdcard/Download/codex-three-engine-details/child-'+name+'.js';
 fs.writeFileSync(path,'"rhino";sleep(700);console.log("CHILD_'+name+'");');
 const child=engines.execScriptFile(path,{arguments:{owner:name}});
 child.once('start',()=>started++).once('success',()=>success++);
 child.engine().then(e=>{childId=e.id;childSource=String(e.getSource());});
 for(let i=0;i<100;i++)ds.setSync(name+'-'+i,i);
},2500);
setTimeout(()=>{
 let keys=0;for(let n=0;n<2;n++)for(let i=0;i<100;i++)if(ds.getSync(n+'-'+i)===i)keys++;
 console.log('PAIR_RESULT='+JSON.stringify({name,keys,started,success,childId,childSource}));
},6000);'''.replace('PAIR_NUMBER',str(number)).replace('PAIR_NAMESPACE',namespace)
                active.append(audit.call('run_script',engine='nodejs',source=source,wait=False,config={'arguments':{'owner':number}}))
            for task in active:
                result=audit.call('wait_execution',executionId=task['executionId'],timeoutSeconds=12)
                text='\n'.join(row.get('content','') for row in result.get('logs',[]))
                print(text,flush=True)
                rows.append(result)
            owners=[]
            for record in rows:
                logs='\n'.join(x.get('content','') for x in record.get('logs',[])).replace(chr(30),'').splitlines()
                owner=next(json.loads(x.split('OWNER_RESULT=',1)[1]) for x in logs if 'OWNER_RESULT=' in x)
                pair=next(json.loads(x.split('PAIR_RESULT=',1)[1]) for x in logs if 'PAIR_RESULT=' in x)
                assert owner['argv']['owner']==int(owner['name']),owner
                assert pair['keys']==200 and pair['started']==1 and pair['success']==1,pair
                assert pair['childSource'].endswith('child-'+pair['name']+'.js'),pair
                owners.append(owner['id'])
                record['result']={'passed':5,'total':5,'owner':owner,'pair':pair}
            assert len(set(owners))==2,owners
        elif args.mode == 'node-lifecycle':
            source='''const seen=[];
$autojs.on('__detail_payload',value=>seen.push(value));
for(const value of [0,false,'',null,[1,2],{x:3}])require('events').emit('__detail_payload',value);
const t=require('threads').start(function(){process.exit(0);});
const joined=t.join(2000),alive=t.isAlive();
setTimeout(()=>console.log('NODE_LIFECYCLE_RESULT='+JSON.stringify({total:3,passed:Number(joined)+Number(!alive)+Number(JSON.stringify(seen)==='[0,false,"",null,[1,2],{"x":3}]'),joined,alive,seen})),300);'''
            rows.append(audit.run('node-events-worker','nodejs',source,'NODE_LIFECYCLE_RESULT=',8))
            for label,action in [('exit','exit()'),('interrupt','require("threads").currentThread().interrupt()')]:
                record=audit.call('run_script',engine='nodejs',source='setInterval(()=>console.log("UNEXPECTED_TIMER"),50);console.log("BEFORE_TERMINATION");'+action+';console.log("UNEXPECTED_AFTER");',wait=True,timeoutSeconds=4)
                text='\n'.join(x.get('content','') for x in record.get('logs',[]))
                assert 'BEFORE_TERMINATION' in text and 'UNEXPECTED_' not in text,record
                assert record['status'] in ('SUCCEEDED','STOPPED'),record
                rows.append(dict(label=label,result={'passed':1,'total':1},execution=record))
        elif args.mode == 'regressions':
            for engine, filename, marker in [
                ('rhino','rhino-exact-color.js','RHINO_EXACT_COLOR_RESULT='),
                ('quickjs','quickjs-detail-regression.js','QUICKJS_DETAIL_RESULT=')]:
                rows.append(audit.run(filename[:-3],engine,(ROOT/'tools/probes'/filename).read_text(encoding='utf8'),marker,20))
        elif args.mode == 'quickjs-full':
            source=(ROOT/'apps/app/src/main/assets/sample/QuickJS 引擎/回归测试/QuickJS 全模块回归测试.js').read_text(encoding='utf8')
            source=source.replace('var pass = 0, fail = 0, skipped = 0;', 'var pass = 0, fail = 0, skipped = 0, failedNames = [];')
            source=source.replace("else { fail++; console.error('❌ ' + name); }", "else { fail++; failedNames.push(name); console.error('❌ ' + name); }")
            # console.clear() in the sample removes earlier failure messages.
            # Preserve the names before its final throw without changing pass/fail.
            source=source.replace('if (fail > 0) {', '\nconsole.log("QUICKJS_FULL_RESULT="+JSON.stringify({total:pass+fail,passed:pass,failed:fail,names:failedNames,skipped:skipped}));\nif (fail > 0) {')
            rows.append(audit.run('quickjs-full','quickjs',source,'QUICKJS_FULL_RESULT=',50))
        elif args.mode == 'legacy':
            for engine in ['rhino']:
                for name in ['crypto','zip','colormapping','settings','features']:
                    source=(ROOT/'tools/pro-api'/(name+'-contract.js')).read_text(encoding='utf8').replace('// @engine rhino','')
                    source+='\nconsole.log("LEGACY_DETAIL_RESULT="+JSON.stringify({total:pass+fail,passed:pass,failed:fail}));\nexit();'
                    rows.append(audit.run(engine+'-'+name,engine,source,'LEGACY_DETAIL_RESULT=',25))
        else:
            inventory = ROOT / 'output/engine-detail-audit-20261021/sources.json'
            sources = json.loads(inventory.read_text(encoding='utf8'))
            for item in sources:
                if item['source_kind'] == 'frontend-build-source':
                    rows.append(dict(path=item['path'], engine=item['engine'], valid=None, reason='frontend-build-source'))
                    continue
                engine = 'nodejs' if item['engine'] == 'node' else item['engine']
                checked = audit.call('check_syntax', engine=engine, code=item['source'], name=item['path'])
                rows.append(dict(path=item['path'], sha256=item['sha256'], **checked))
                if len(rows) % 25 == 0:
                    print('Compiled', len(rows), '/', len(sources), flush=True)
        (args.output / 'summary.json').write_text(json.dumps(rows, ensure_ascii=False, indent=2), encoding='utf8')
        failed=[row for row in rows if row.get('error') or row.get('valid') is False or
                (isinstance(row.get('result'),dict) and row['result'].get('passed') != row['result'].get('total'))]
        if failed:
            raise SystemExit('Audit failures: '+str(len(failed)))
    finally:
        audit.close()


if __name__ == '__main__':
    main()
