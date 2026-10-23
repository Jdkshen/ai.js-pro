'use strict';
const assert = require('assert'), path = require('path');
const requests = [], pending = [], events = [];
globalThis.__ajsEnableJavaCallbacks = () => 93;
globalThis.__autojsHost = text => {
    const r = JSON.parse(text); requests.push(r);
    let value = true;
    if (r.kind === 'poll') value = {$events:events.splice(0), $more:false};
    if (r.kind === 'java' && r.op === 'defineClass') throw Error('synchronous compilation is forbidden');
    if (r.kind === 'java' && r.op === 'asyncCall') {pending.push(r);value=null;}
    return JSON.stringify({ok:true,value});
};
require(path.resolve(__dirname, '../../main/assets/node24/prelude.js'));
const Base = $java.findClass('java.lang.Object');
function complete(request, error) {
    events.push({type:'__ajs_java_result',payload:{id:request.id,
        ...(error ? {error} : {value:{name:request.call.name,superMethods:['toString']}})}});
    __autojsPoll();
}
async function main() {
    class Alpha extends Base {toString(){return 'alpha';}}
    class Beta extends Base {}
    let resolved=false;
    const a=$java.defineClass(Alpha).then(value=>{resolved=true;return value;});
    const b=$java.defineClass(Beta);
    assert.equal(pending.length,2,'parallel definitions submit without blocking Java compilation');
    assert.equal(resolved,false);
    assert.equal(pending[0].mode,'io');
    assert.equal(pending[0].call.op,'defineClass');
    assert(pending[0].call.methods.includes('toString'));
    await assert.rejects($java.defineClass(Alpha),/已定义 Java 类/,'in-flight names are reserved');
    complete(pending[1]);
    assert.strictEqual(await b,Beta,'out-of-order replies resolve the matching class');
    assert.equal(resolved,false);
    complete(pending[0]);
    assert.strictEqual(await a,Alpha);
    assert.equal(Object.getPrototypeOf(Alpha).__className,'Alpha');
    assert.equal(typeof Object.getPrototypeOf(Alpha.prototype).toString,'function');
    await assert.rejects($java.defineClass(Alpha),/已定义 Java 类/);
    class Retry extends Base {}
    const failed=$java.defineClass(Retry);
    complete(pending[2],'compile failed');
    await assert.rejects(failed,/compile failed/);
    const retry=$java.defineClass(Retry);
    complete(pending[3]);
    assert.strictEqual(await retry,Retry,'a failed definition releases its name');
    assert(!requests.some(r=>r.kind==='java' && r.op==='defineClass'));
    console.log('PASS Node24 async defineClass: yielding, parallel replies, duplicate reservation and failure retry');
}
main().catch(error=>{console.error(error);process.exit(1);});
