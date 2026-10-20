"nodejs";
const assert = require('assert');
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const Session = $java.findClass('com.stardust.autojs.engine.node24.NodeUiLocalSession');
const gate = new android.os.ConditionVariable(false);
const pending = Session.getDeclaredField('pending');
pending.setAccessible(true);
async function until(fn) {
  for (let i = 0; i < 100; i++) { if (fn()) return; await delay(20); }
  throw new Error('UI session state did not become ready');
}
const request = () => new org.json.JSONObject(JSON.stringify({
  layoutXml: '<column><text id="launch_cancel_text" text="Node UI launch cancellation"/></column>',
}));
(async () => {
  // Block Android main with pure Java, so the Activity cannot attach before close.
  $java.setThreadMode(gate, 'ui');
  const blocked = gate.block(5000);
  await delay(150);
  $java.setDefaultThreadMode(Session, 'io');
  const launched = Session.show($autojs.androidContext, __dirname, request());
  $java.setDefaultThreadMode(Session, 'current');
  // Attach rejection handling before triggering close to avoid unhandledRejection.
  const rejected = launched.then(() => {throw new Error('Cancelled launch unexpectedly succeeded');}, error => error);
  await until(() => pending.get(null) !== null);
  assert.strictEqual(Session.getActivity(), null, 'test must cancel before attach');
  assert.strictEqual(Session.isShowing(), true, 'pending UI must be visible to stop cleanup');
  const start = Date.now();
  Session.close();
  const error = await rejected;
  assert.match(String(error), /取消|关闭/);
  assert(Date.now() - start < 1500, 'close must wake show instead of waiting for its 10s timeout');
  assert.strictEqual(Session.isShowing(), false);
  $java.setThreadMode(gate, 'current');
  gate.open();
  await blocked;
  await delay(300);
  assert.strictEqual(Session.getActivity(), null, 'late Activity must not attach after close');
  const reopened = Session.show($autojs.androidContext, __dirname, request());
  assert.strictEqual(reopened.getBoolean('ok'), true);
  assert(Session.getActivity(), 'the same Node process must allow the next UI launch');
  Session.close();
  console.log('JAVA_UI_LAUNCH_CANCEL_RESULT ' + JSON.stringify({ok: true,
    checks: ['pending launch participates in stop cleanup', 'close wakes show promptly',
      'late Activity cannot attach', 'same process reopens UI successfully']}));
})().catch(error => {
  console.error(error.stack || error);
  process.exitCode = 1;
}).finally(() => {
  $java.setThreadMode(gate, 'current');
  gate.open();
  $java.setDefaultThreadMode(Session, 'current');
  Session.close();
});
