"nodejs";
const assert = require('assert');
const count = 1000;
const started = Date.now();
$java.setDefaultThreadMode(java.lang.Math, 'io');
const timeout = setTimeout(() => {
  console.error('JAVA_ASYNC_STRESS_TIMEOUT: a Promise completion was lost');
  process.exit(1);
}, 15000);
(async () => {
  const requests = Array.from({length: count}, (_, index) => java.lang.Math.max(index, -1));
  const results = await Promise.all(requests);
  assert.strictEqual(results.length, count);
  results.forEach((value, index) => assert.strictEqual(value, index));
  console.log('JAVA_ASYNC_STRESS_RESULT ' + JSON.stringify({ok: true, count, elapsedMs: Date.now() - started}));
})().catch(error => { console.error(error); process.exitCode = 1; }).finally(() => {
  clearTimeout(timeout);
  $java.setDefaultThreadMode(java.lang.Math, 'current');
});
