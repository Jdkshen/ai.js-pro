'use strict';
const assert = require('assert'), fs = require('fs'), path = require('path'), cp = require('child_process');
const source = fs.readFileSync(path.resolve(__dirname, '../../main/assets/node24/prelude.js'), 'utf8');
const match = source.match(/\(function installUnhandledErrorReporting\(\) \{[\s\S]*?\n  \}\)\(\);/);
assert(match, 'actual prelude monitor must be present');
function probe(body) {
    const prefix = 'function hostValue(value){process.stdout.write(JSON.stringify(value)+"\\n");}\n';
    return cp.spawnSync(process.execPath, ['-e', prefix + match[0] + '\n' + body], { encoding: 'utf8', timeout: 5000 });
}
for (const body of [
    'setTimeout(function(){throw Error("NODE_EXPECTED_TIMER_ERROR");},1);',
    'Promise.reject(Error("NODE_EXPECTED_REJECTION_ERROR"));'
]) {
    const result = probe(body);
    assert.equal(result.status, 1, 'monitor must preserve the default fatal exit');
    const report = JSON.parse(result.stdout.trim());
    assert.equal(report.action, 'scriptError');
    assert.match(report.args.detail, /NODE_EXPECTED_(TIMER|REJECTION)_ERROR/);
    assert.match(report.args.detail, /at /, 'preserve the original stack');
    assert.match(result.stderr, /NODE_EXPECTED_/, 'do not suppress native diagnostics');
}
const handled = probe('process.on("uncaughtException",function(){});setTimeout(function(){throw Error("handled");},1);');
assert.equal(handled.status, 0, 'user exception handling policy must stay intact');
const normal = probe('process.exit(7);');
assert.equal(normal.status, 7);
assert.equal(normal.stdout, '', 'explicit exits must not be reported as script errors');
console.log('PASS Node error monitor: timer/rejection stacks, fatal exit, user handlers and explicit exit codes');
