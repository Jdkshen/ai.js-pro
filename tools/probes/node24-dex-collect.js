"nodejs";
// Run after node24-dex-create.js in the SAME warm Node pool process, without force-stop.
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const Lifetime = $java.findClass('org.autojs.source.engine.android.Node24DexLifetime');
const created = JSON.parse(fs.readFileSync(path.join(__dirname, 'dex-created.json'), 'utf8'));
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
(async () => {
  assert.strictEqual(android.os.Process.myPid(), created.pid, 'the loader collection must be verified in the same live process');
  const refs = java.lang.System.getProperties().get('org.aijsprobe.dexlife.refs');
  for (let index = 0; index < 60; ++index) {
    java.lang.System.gc();
    java.lang.System.runFinalization();
    await delay(250);
    if (Lifetime.pendingLoaderCount() === 0 && Lifetime.closedDexCount() - created.baseline === created.count) break;
  }
  assert.strictEqual(Lifetime.pendingLoaderCount(), 0, 'no permanent loader retention');
  // Do not inspect referents while collection is still running: returning a live
  // Java referent through the bridge itself retains it in this job's object table.
  for (let index = 0; index < 3; ++index) {
    java.lang.System.gc(); java.lang.System.runFinalization(); await delay(250);
  }
  let alive = 0;
  for (let i = 0; i < refs.size(); ++i) if (refs.get(i).get() !== null) alive++;
  assert.strictEqual(alive, 0, 'both loaders and DexFile objects must become collectable');
  assert.strictEqual(Lifetime.failureCount(), 0);
  assert.strictEqual(Lifetime.closedDexCount() - created.baseline, created.count);
  // Another collection after the last weak refs clear exercises DexFile.finalize too.
  java.lang.System.gc(); java.lang.System.runFinalization(); await delay(300);
  java.lang.System.getProperties().remove('org.aijsprobe.dexlife.refs');
  console.log('NODE_DEX_COLLECT_RESULT ' + JSON.stringify({ok: true, pid: created.pid,
    released: created.count, pending: Lifetime.pendingLoaderCount(), alive}));
})().catch(error => {console.error(error.stack || error); process.exitCode = 1;});
