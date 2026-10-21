'use strict';
const assert = require('assert');
const path = require('path');
require(path.resolve(__dirname, '../../main/assets/node24/prelude.js'));
const {Deferred} = require('lang');
(async () => {
  const d = new Deferred();
  assert.strictEqual(d.promise(), d.promise());
  assert.strictEqual(d.finished, false);
  setTimeout(() => d.resolve('server-ready'), 5);
  assert.strictEqual(await d.promise(), 'server-ready');
  assert.strictEqual(d.value, 'server-ready');
  assert.strictEqual(d.reject(new Error('late')), false);
  const failed = new Deferred(false);
  const rejected = assert.rejects(failed.promise(), /real rejection/);
  failed.reject(new Error('real rejection'));
  await rejected;
  console.log('PASS Deferred asynchronous resolve/reject, idempotent settlement and timer release');
})().catch(error => {console.error(error); process.exitCode = 1;});
