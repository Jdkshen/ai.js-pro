"nodejs";
// Place the unchanged advanced 设置.js beside this probe as original-settings.js.
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const settings = require('settings');
const names = Object.keys(settings);
const before = {};
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const context = $autojs.androidContext;
const strings = $java.findClass('com.jdkshen.aijspro.R$string');
const keys = {
  stableMode: context.getString(strings.key_stable_mode),
  enableAccessibilityServiceByRoot: context.getString(strings.key_enable_accessibility_service_by_root),
  stopAllOnVolumeUp: context.getString(strings.key_use_volume_control_running),
  noConsoleOnLaunch: 'not_show_console',
  foregroundService: context.getString(strings.key_foreground_servie),
};
const prefFile = path.join(context.getApplicationInfo().dataDir, 'shared_prefs',
  context.getPackageName() + '_preferences.xml');
async function assertPersisted(name, expected) {
  const text = '<boolean name="' + keys[name] + '" value="' + expected + '"';
  for (let i = 0; i < 100; ++i) {
    if (fs.existsSync(prefFile) && fs.readFileSync(prefFile, 'utf8').includes(text)) return;
    await delay(20);
  }
  throw new Error('The real app preference was not persisted: ' + name);
}
(async () => {
  for (const name of names) before[name] = settings[name].value;
  try {
    for (const name of names) {
      settings[name].value = !before[name];
      assert.strictEqual(settings[name].value, !before[name]);
      await assertPersisted(name, !before[name]);
    }
    require('./original-settings.js');
    assert.strictEqual(settings.stableMode.value, true);
    assert.strictEqual(settings.foregroundService.value, false);
    await assertPersisted('stableMode', true);
    await assertPersisted('foregroundService', false);
  } finally {
    for (const name of names) if (Object.hasOwn(before, name)) settings[name].value = before[name];
    for (const name of names) if (Object.hasOwn(before, name)) {
      assert.strictEqual(settings[name].value, before[name]);
      await assertPersisted(name, before[name]);
    }
    console.log('JAVA_PRO_SETTINGS_RESTORED ' + JSON.stringify({ok: true, names}));
  }
  console.log('JAVA_PRO_SETTINGS_RESULT ' + JSON.stringify({ok: true, names,
    evidence: 'all five settings persisted to the real app SharedPreferences; original example unchanged'}));
})().catch(error => {console.error(error.stack || error); process.exitCode = 1;});
