"ui nodejs";
// Device regression. During BUSY_BEGIN, set input "probe" to "UI_ALIVE"
// through accessibility; this must finish while JS is busy for 12 seconds.
require('rhino').install();
const ui = require('ui');
const assert = require('node:assert/strict');
let started = false;

class Probe extends ui.Activity {
    get layoutXml() {
        return '<vertical padding="16"><text text="Node UI dispatch regression"/>'
            + '<input id="probe" text="probe"/></vertical>';
    }
    onContentViewSet(view) {
        if (started) return;
        started = true;
        (async () => {
            const Thread = java.lang.Thread;
            const Looper = android.os.Looper;
            // Activity lifecycle dispatch itself is a scoped UI callback.
            assert.equal(ui.isUiThread(), true);
            let background;
            assert.equal(await ui.runOnUiThread(() => {
                assert.equal(ui.isUiThread(), true);
                assert.equal(String(Thread.currentThread().getName()), 'main');
                const handler = new android.os.Handler();
                assert(handler.getLooper().equals(Looper.getMainLooper()));
                background = Thread.currentThread.invoke(null, [], 'io');
                return 42;
            }), 42);
            assert.notEqual(String((await background).getName()), 'main');
            assert.equal(ui.isUiThread(), false);

            const fast = globalThis.__ajsJavaFastCall;
            try {
                globalThis.__ajsJavaFastCall = undefined;
                await ui.runOnUiThread(() => {
                    // Repeat to cover the parameterless JSON-call cache.
                    for (let i = 0; i < 4; i++) {
                        assert.equal(ui.isUiThread(), true);
                        assert.equal(String(Thread.currentThread().getName()), 'main');
                    }
                });
                assert.equal(ui.isUiThread(), false);
            } finally { globalThis.__ajsJavaFastCall = fast; }

            await assert.rejects(ui.runOnUiThread(() => {
                assert.equal(ui.isUiThread(), true);
                throw new Error('expected UI callback failure');
            }), /expected UI callback failure/);
            assert.equal(ui.isUiThread(), false);
            assert.equal(await ui.runOnUiThread(() => ui.runOnUiThread(() => 73)), 73);
            console.log('COOPERATIVE_SCOPES_PASS');

            await ui.runOnUiThread(() => {
                console.log('COOPERATIVE_BUSY_BEGIN');
                const until = Date.now() + 12000;
                while (Date.now() < until) { /* Deliberate regression stress. */ }
                console.log('COOPERATIVE_BUSY_END');
            });
            assert.equal(String(view.binding.probe.getText()), 'UI_ALIVE');
            console.log('COOPERATIVE_UI_PASS');
        })().catch(error => {
            console.error('COOPERATIVE_UI_FAIL: ' + error.stack);
            process.exitCode = 1;
        }).finally(() => ui.close());
    }
}
ui.setMainActivity(Probe);
