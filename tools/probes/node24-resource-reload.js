"nodejs";
// Run only in an owned copy of the native UI sample with compiled resources.
const assert = require('assert'), fs = require('fs'), path = require('path');
const ui = require('ui');
(async () => {
  const apk = path.join(__dirname, 'build/project-res.apk');
  const sidecar = path.join(__dirname, 'build/project-res.json');
  const original = fs.readFileSync(sidecar);
  const metadata = JSON.parse(original);
  assert.match(metadata.appResourcesSha256, /^[0-9a-f]{64}$/);
  const saved = apk + '.reload-probe';
  assert(!fs.existsSync(saved), 'Do not overwrite a previous resource backup');
  let parked = false;
  try {
    fs.renameSync(apk, saved); parked = true;
    assert.strictEqual((await ui.loadAndroidResources()).compiled, false);
    fs.renameSync(saved, apk); parked = false;
    fs.writeFileSync(sidecar, JSON.stringify({...metadata, appApkSize: 1}));
    const restored = await ui.loadAndroidResources();
    assert.strictEqual(restored.compiled, true, 'A valid table must replace the cached fallback');
    assert.strictEqual(ui.R.layout.activity_main, restored.r.layout.activity_main);
    fs.writeFileSync(sidecar, JSON.stringify({...metadata, appResourcesSha256: '0'.repeat(64)}));
    assert.strictEqual((await ui.loadAndroidResources()).compiled, false, 'A changed host table must be rejected');
  } finally {
    if (parked) fs.renameSync(saved, apk);
    fs.writeFileSync(sidecar, original);
    assert.strictEqual((await ui.loadAndroidResources()).compiled, true);
  }
  console.log('NODE_RESOURCE_RELOAD_RESULT '+JSON.stringify({ok:true, missingThenRebuilt:true,
    digestOverridesApkSize:true, changedHostRejected:true, restored:true}));
})().catch(error => { console.error(error.stack || error); process.exitCode = 1; });
