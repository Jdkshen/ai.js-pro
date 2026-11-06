/*
 * Node24 datastore 契约测试（真实 asset，不是镜像实现）。
 *
 * 回归背景：真机 Xiaomi 8（dev-1095 ce4d2bdb）运行
 * apps/app/src/main/assets/sample/Node 引擎/Node.js/本地存储.js 时报
 * `TypeError: datastore.set is not a function`（baseline/047-*.log）。
 *
 * 本测试按生产顺序执行完整 prelude.js，再用 require('datastore') 只断言公开契约：
 *   - 异步 set/get/remove/contains/clear/edit + 同步 getSync 等；
 *   - JSON 值/缺省值/remove 返回值/clear；
 *   - edit 链式 editor、clear 与 set 的顺序；
 *   - encryptionKey：随机 IV、GCM 认证、错误密钥拒绝、跨实例读取、持久化值无明文；
 *   - 旧 put 方法兼容。
 *
 * host 只按 Node24HostBridge `kind:storages` 的真实协议读写字符串
 * （get 缺失返回 null，put/remove/clear 返回 true），不复制 prelude 实现。
 *
 * 运行：node tools/test-node24-datastore.cjs
 * 不构建、不装 APK、不连设备。
 */
'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '..');
const PRELUDE = path.join(ROOT, 'modules', 'engine', 'src', 'main', 'assets', 'node24', 'prelude.js');
const SAMPLE = path.join(ROOT, 'apps', 'app', 'src', 'main', 'assets', 'sample',
  'Node \u5f15\u64ce', 'Node.js', '\u672c\u5730\u5b58\u50a8.js');
const SCRIPT_FILE = path.join(ROOT, 'tmp-node24-datastore-contract', 'src', 'main.js');

const prefs = new Map();
const calls = [];
const state = {
  getError: null
};

function store(name) {
  let values = prefs.get(name);
  if (!values) {
    values = new Map();
    prefs.set(name, values);
  }
  return values;
}

function reply(value) {
  return JSON.stringify({ok: true, value: value === undefined ? null : value});
}

function failure(message) {
  return JSON.stringify({ok: false, error: message});
}

function mockHost(raw) {
  const request = JSON.parse(raw);
  calls.push(request);

  if (request.kind === 'poll') { return reply({events: []}); }
  if (request.kind === 'keepalive') { return reply(true); }
  if (request.kind === 'host') {
    return reply({bridge: 'mock-node24-datastore', version: 2, projectDir: path.dirname(SCRIPT_FILE)});
  }
  if (request.kind === 'storages') {
    if (state.getError && request.action === 'get') { return failure(state.getError); }
    const args = request.args || {};
    const values = store(args.name || 'default');
    switch (request.action) {
      case 'get':
        return reply(values.has(args.key) ? values.get(args.key) : null);
      case 'put':
        values.set(args.key, String(args.value));
        return reply(true);
      case 'remove':
        values.delete(args.key);
        return reply(true);
      case 'clear':
        values.clear();
        return reply(true);
      default:
        return failure('unsupported storages action: ' + request.action);
    }
  }
  return failure('unsupported kind: ' + request.kind);
}

globalThis.__ajsProjectDir = path.dirname(path.dirname(SCRIPT_FILE));
globalThis.__ajsScriptFile = SCRIPT_FILE;
// Production prelude changes the real cwd; provide an actual test workspace.
fs.mkdirSync(globalThis.__ajsProjectDir, {recursive: true});
globalThis.__autojsHost = mockHost;

// 与 Node24Prelude.wrap 一致：加载完整 prelude 资产。datastore 在 prelude 内定义。
new Function('require', fs.readFileSync(PRELUDE, 'utf8'))(require);

const datastoreModule = require('datastore');
const createDatastore = datastoreModule.createDatastore;
const storages = require('storages');

const tests = [];
function test(name, fn) { tests.push({name: name, fn: fn}); }

function valuesFor(name) {
  return prefs.get(name);
}

// ---------------------------------------------------------------------------
// 普通 datastore
// ---------------------------------------------------------------------------
test('API surface exposes async/sync Pro datastore contract', async function () {
  assert.equal(typeof createDatastore, 'function');
  const ds = createDatastore('contract.api');
  for (const name of ['get', 'set', 'remove', 'contains', 'clear', 'edit',
    'getSync', 'setSync', 'removeSync', 'containsSync', 'clearSync', 'editSync', 'put']) {
    assert.equal(typeof ds[name], 'function', 'missing datastore.' + name);
  }
  assert.ok(ds.set('probe', true) instanceof Promise);
  await ds.clear();
});

test('plain store: set/get, missing value, remove return value, clear', async function () {
  const ds = createDatastore('plain.basic');

  assert.equal(await ds.get('missing'), undefined);
  assert.equal(ds.getSync('missing'), undefined);
  assert.equal(await ds.contains('missing'), false);

  assert.ok(ds.set('hello', 'world') instanceof Promise);
  await ds.set('hello', 'world');
  assert.equal(await ds.get('hello'), 'world');
  assert.equal(ds.getSync('hello'), 'world');
  assert.equal(await ds.contains('hello'), true);
  assert.equal(ds.containsSync('hello'), true);

  await ds.set('versions', {autojspro: 'mock', nodejs: process.version});
  assert.deepEqual(await ds.get('versions'), {autojspro: 'mock', nodejs: process.version});
  assert.deepEqual(ds.getSync('versions'), {autojspro: 'mock', nodejs: process.version});

  assert.equal(await ds.remove('missing'), undefined);
  assert.equal(await ds.remove('hello'), 'world');
  assert.equal(await ds.get('hello'), undefined);
  assert.equal(await ds.contains('hello'), false);

  await ds.set('nullValue', null);
  assert.equal(await ds.get('nullValue'), null);
  await ds.set('undefinedValue', undefined);
  assert.equal(await ds.get('undefinedValue'), undefined);
  assert.equal(await ds.contains('undefinedValue'), true);
  await ds.set('falseValue', false);
  assert.equal(await ds.get('falseValue'), false);
  await ds.set('zeroValue', 0);
  assert.equal(await ds.get('zeroValue'), 0);
  await ds.set('emptyString', '');
  assert.equal(await ds.get('emptyString'), '');

  assert.equal(await ds.clear(), undefined);
  assert.equal(await ds.get('versions'), undefined);
  assert.equal(await ds.contains('nullValue'), false);
});

test('plain store: sync variants and old put compatibility', async function () {
  const ds = createDatastore('plain.sync');
  ds.setSync('a', 1);
  assert.equal(ds.getSync('a'), 1);
  assert.equal(ds.containsSync('a'), true);
  assert.equal(ds.removeSync('a'), 1);
  assert.equal(ds.containsSync('a'), false);

  // 旧 prelude 的 put 是同步写入并返回 true；兼容层保留这个时序。
  assert.equal(ds.put('legacy', {value: 2}), true);
  assert.equal(ds.getSync('legacy'), '[object Object]');
  assert.equal(await ds.get('legacy'), '[object Object]');
  ds.putSync('legacySync', 'ok');
  assert.equal(ds.getSync('legacySync'), 'ok');
  ds.clearSync();
});

test('plain store preserves dev-1095 namespace and old raw string values', async function () {
  const ds = createDatastore('shared.name');
  storages.put('sameKey', 'storage-value', 'shared.name');
  storages.put('numberText', '123', 'shared.name');
  storages.put('empty', '', 'shared.name');
  assert.equal(await ds.get('sameKey'), 'storage-value');
  assert.equal(ds.getSync('numberText'), '123');
  assert.equal(await ds.remove('empty'), '');
  await ds.set('sameKey', 'datastore-value');
  assert.equal(await ds.get('sameKey'), 'datastore-value');
  assert.equal(valuesFor('shared.name').get('sameKey'), 'AJSDSJSON1.\"datastore-value\"');
  assert.equal(createDatastore('shared.name').getSync('sameKey'), 'datastore-value');
  await ds.clear();
  assert.equal(await ds.contains('sameKey'), false);
});

test('plain store: edit records chainable ops and commits in order', async function () {
  const ds = createDatastore('plain.edit');
  await ds.set('versions', {a: 1});
  await ds.edit(function (editor) {
    const returned = editor.set('boolKey', true)
      .set('arrayKey', [1, '2', false])
      .remove('versions');
    assert.equal(returned, editor);
    assert.equal(typeof editor.clear, 'function');
  });
  assert.equal(await ds.get('versions'), undefined);
  assert.equal(await ds.get('boolKey'), true);
  assert.deepEqual(await ds.get('arrayKey'), [1, '2', false]);

  // clear then set must leave the later write in place.
  await ds.edit(function (editor) {
    assert.equal(editor.clear().set('afterClear', 'yes'), editor);
  });
  assert.equal(await ds.get('boolKey'), undefined);
  assert.equal(await ds.get('afterClear'), 'yes');

  // set then clear must preserve order too.
  await ds.edit(function (editor) {
    editor.set('beforeClear', 'gone').clear();
  });
  assert.equal(await ds.get('afterClear'), undefined);
  assert.equal(await ds.get('beforeClear'), undefined);
  assert.equal(await ds.contains('afterClear'), false);

  ds.editSync(function (editor) {
    editor.set('syncEdit', 7).set('syncArray', [8]).remove('doesNotExist');
  });
  assert.equal(ds.getSync('syncEdit'), 7);
  assert.deepEqual(ds.getSync('syncArray'), [8]);

  // A throwing block must not commit earlier queued ops.
  await ds.set('keep', 'value');
  await assert.rejects(
    ds.edit(function (editor) {
      editor.set('shouldNotCommit', true);
      throw new Error('editor block failed');
    }),
    /editor block failed/
  );
  assert.equal(await ds.contains('shouldNotCommit'), false);
  assert.equal(await ds.get('keep'), 'value');
});

test('plain store: custom serializer option is honored', async function () {
  const ds = createDatastore('plain.serializer', {
    serializer: {
      valueToString: function (value) { return 'v:' + value; },
      valueFromString: function (value) { return value.slice(2); }
    }
  });
  await ds.set('key', 'hello');
  assert.equal(await ds.get('key'), 'hello');
  assert.equal(valuesFor('plain.serializer').get('key'), 'AJSDSJSON1.v:hello');
});

// ---------------------------------------------------------------------------
// 加密 datastore
// ---------------------------------------------------------------------------
const ENCRYPTION_KEY = '0123456789abcdef'; // 16 bytes, same shape as sample.
const OTHER_KEY = 'fedcba9876543210';
const SECRET = {password: 's3cr3t-value', nested: [1, {deep: true}]};

function encryptedEntries(storeName) {
  const values = valuesFor('datastore.encrypted.' + storeName);
  assert.ok(values, 'encrypted host store not found: ' + storeName);
  return [...values.entries()];
}

test('encrypted store: set/get/getSync, remove returns plaintext, clear', async function () {
  const ds = createDatastore('enc.basic', {encryptionKey: ENCRYPTION_KEY});
  await ds.set('secret', SECRET);
  await ds.set('empty', undefined);
  await ds.set('nullValue', null);

  assert.deepEqual(await ds.get('secret'), SECRET);
  assert.deepEqual(ds.getSync('secret'), SECRET);
  assert.equal(await ds.get('empty'), undefined);
  assert.equal(await ds.get('nullValue'), null);
  assert.equal(await ds.contains('secret'), true);
  assert.equal(await ds.contains('missing'), false);

  assert.deepEqual(await ds.remove('secret'), SECRET);
  assert.equal(await ds.contains('secret'), false);
  assert.equal(await ds.remove('secret'), undefined);

  await ds.set('again', {ok: true});
  assert.equal(await ds.clear(), undefined);
  assert.equal(await ds.contains('again'), false);
  assert.equal(await ds.get('again'), undefined);

  // clear removes the key-check record; the next write must recreate it.
  await ds.set('afterClear', 'yes');
  assert.equal(await ds.get('afterClear'), 'yes');
  assert.equal(encryptedEntries('enc.basic').some(function (entry) {
    return entry[0] === '__ajs_datastore_key_check_v1__';
  }), true);
});

test('encrypted store: cross-instance read with the same key', async function () {
  const writer = createDatastore('enc.cross', {encryptionKey: ENCRYPTION_KEY});
  await writer.set('shared', {value: 42});

  const reader = createDatastore('enc.cross', {encryptionKey: Buffer.from(ENCRYPTION_KEY, 'utf8')});
  assert.deepEqual(await reader.get('shared'), {value: 42});
  assert.deepEqual(reader.getSync('shared'), {value: 42});
  assert.equal(await reader.contains('shared'), true);
});

test('encrypted store: persisted keys/values contain no plaintext and use random IV', async function () {
  const ds = createDatastore('enc.persisted', {encryptionKey: ENCRYPTION_KEY});
  await ds.set('secretKey', SECRET);
  const first = encryptedEntries('enc.persisted');
  const firstSecret = first.find(function (entry) { return entry[0] !== '__ajs_datastore_key_check_v1__'; });
  assert.ok(firstSecret, 'missing encrypted value');

  // Same key/value twice must produce different ciphertext because IV is random.
  await ds.set('secretKey', SECRET);
  const second = encryptedEntries('enc.persisted');
  const secondSecret = second.find(function (entry) { return entry[0] !== '__ajs_datastore_key_check_v1__'; });
  assert.notEqual(firstSecret[1], secondSecret[1]);

  for (const [storedKey, storedValue] of second) {
    assert.equal(storedKey.indexOf('secretKey'), -1, 'plaintext key leaked: ' + storedKey);
    assert.equal(storedKey.indexOf(ENCRYPTION_KEY), -1, 'encryption key leaked in key name');
    assert.equal(typeof storedValue, 'string');
    assert.equal(storedValue.slice(0, 7), 'AJSDS1.', 'value is not an encrypted envelope');
    assert.equal(storedValue.indexOf('s3cr3t-value'), -1, 'plaintext value leaked');
    assert.equal(storedValue.indexOf('password'), -1, 'plaintext JSON field leaked');
    assert.equal(storedValue.indexOf(ENCRYPTION_KEY), -1, 'encryption key leaked in value');
  }
  // Canary and one user value.
  assert.equal(second.length, 2);
});

test('encrypted store: wrong key is rejected for get/contains/remove/clear/set', async function () {
  const correct = createDatastore('enc.wrong', {encryptionKey: ENCRYPTION_KEY});
  await correct.set('secret', SECRET);

  const wrong = createDatastore('enc.wrong', {encryptionKey: OTHER_KEY});
  const pending = wrong.get('secret');
  assert.ok(pending instanceof Promise);
  await assert.rejects(pending, /encryptionKey|密钥/);
  assert.throws(function () { wrong.getSync('secret'); }, /encryptionKey|密钥/);
  await assert.rejects(wrong.contains('secret'), /encryptionKey|密钥/);
  await assert.rejects(wrong.remove('secret'), /encryptionKey|密钥/);
  await assert.rejects(wrong.clear(), /encryptionKey|密钥/);
  await assert.rejects(wrong.set('other', 1), /encryptionKey|密钥/);

  // None of the failed operations may destroy or overwrite the correct data.
  assert.deepEqual(await correct.get('secret'), SECRET);
  assert.equal(await correct.contains('other'), false);
});

test('encrypted store: invalid key length and type fail loudly', async function () {
  assert.throws(function () { createDatastore('enc.invalid', {encryptionKey: 'short'}); },
    /16、32 或 64/);
  assert.throws(function () { createDatastore('enc.invalid', {encryptionKey: 123}); },
    /字符串或 Buffer/);
  assert.throws(function () { createDatastore('enc.invalid', {encryptionKey: ''}); },
    /16、32 或 64/);
});

test('encrypted store: 32/64-byte keys and edit are supported', async function () {
  const key32 = 'k'.repeat(32);
  const ds32 = createDatastore('enc.key32', {encryptionKey: key32});
  await ds32.set('k', 32);
  assert.equal(await ds32.get('k'), 32);

  const key64 = 'k'.repeat(64);
  const ds64 = createDatastore('enc.key64', {encryptionKey: key64});
  await ds64.set('k', 64);
  assert.equal(await ds64.get('k'), 64);

  await ds64.edit(function (editor) {
    editor.set('bulk', {n: 1}).remove('k');
  });
  assert.deepEqual(await ds64.get('bulk'), {n: 1});
  assert.equal(await ds64.contains('k'), false);
  for (const [, storedValue] of encryptedEntries('enc.key64')) {
    assert.equal(storedValue.slice(0, 7), 'AJSDS1.');
    assert.equal(storedValue.indexOf('"bulk"'), -1);
  }
});

test('real sample 本地存储.js runs end-to-end without the baseline TypeError', async function () {
  const source = fs.readFileSync(SAMPLE, 'utf8');
  const logs = [];
  const fakeConsole = {
    log: function () {
      logs.push(Array.prototype.map.call(arguments, String).join(' '));
    },
    error: function () {
      logs.push('ERROR ' + Array.prototype.map.call(arguments, String).join(' '));
    }
  };
  // 不修改样例：按原样执行，只替换 require/console/process 注入。
  new Function('require', 'console', 'process', source)(require, fakeConsole, process);
  await new Promise(function (resolve) { setTimeout(resolve, 25); });

  assert.deepEqual(logs.slice(0, 3), [
    'get hello: world',
    'remove hello: world',
    'contains hello: false'
  ]);
  assert.ok(logs.indexOf('1,2,false') >= 0, 'edit array not read back: ' + logs.join(' | '));
  assert.ok(logs.some(function (line) { return line.indexOf('timestamp: ') === 0; }),
    'encrypted sample value not read back: ' + logs.join(' | '));
  assert.equal(logs.filter(function (line) {
    return line.indexOf('TypeError') >= 0 || line.indexOf('ERROR ') === 0;
  }).length, 0, 'sample logged an error: ' + logs.join(' | '));
});

// ---------------------------------------------------------------------------
(async function main() {
  let passed = 0;
  let failed = 0;
  for (const item of tests) {
    try {
      await item.fn();
      passed++;
      console.log('ok ' + item.name);
    } catch (error) {
      failed++;
      console.error('FAIL ' + item.name);
      console.error(error && error.stack ? error.stack : error);
    }
  }
  console.log('\n' + passed + ' passed, ' + failed + ' failed');
  if (failed > 0) { process.exitCode = 1; }
})().catch(function (error) {
  console.error(error);
  process.exitCode = 1;
});
