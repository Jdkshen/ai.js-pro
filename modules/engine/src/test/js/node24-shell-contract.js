'use strict';
const assert=require('assert'),path=require('path');
require(path.resolve(__dirname,'../../main/assets/node24/prelude.js'));
const shell=require('shell');
(async()=>{
 const events=[];
 const stream=shell.createShell({shell:'D:/Git/bin/bash.exe'});
 const started=Date.now(); let completed=false;
 stream.on('line',line=>events.push({line,early:!completed,elapsed:Date.now()-started}));
 const result=await stream.exec('echo FIRST; sleep 0.4; echo SECOND; echo STDERR >&2; exit 7');
 completed=true;
 assert.strictEqual(result.code,7);
 assert.strictEqual(result.result,'FIRST\nSECOND\n'); assert.strictEqual(result.error,'STDERR\n');
 assert(events.some(e=>e.line==='FIRST'&&e.early&&e.elapsed<350));
 await stream.exit();
 await assert.rejects(stream.exec('echo should-not-run'),/Shell 已关闭/);
 console.log('PASS streaming shell: lines arrive before completion, stdout/stderr, exit code and closed queue');
})().catch(e=>{console.error(e);process.exitCode=1;});
