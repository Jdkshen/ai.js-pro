// Force the JSON/queued-event compatibility route on an idle test device.
// This exercises real Android Views/IPC, not a mock or an old-host version claim.
require('rhino').install();
const enableCallbacks = globalThis.__ajsEnableJavaCallbacks;
delete globalThis.__ajsEnableJavaCallbacks;
const ui = require('ui');
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
// The legacy host drains at >=250ms, on top of the 120ms JS tick. Drain the
// previous phase fully before replacing listeners; this is not a latency test.
const settle = () => delay(650);
async function waitUntil(predicate, name) {
    const deadline = Date.now() + 3500;
    while (!predicate()) {
        if (Date.now() > deadline) throw new Error('Timed out waiting for ' + name);
        await delay(50);
    }
}

(async function () {
    const checks = [];
    function check(name, actual, expected) {
        const row = JSON.parse(JSON.stringify({name, actual, expected,
            ok: JSON.stringify(actual) === JSON.stringify(expected)}));
        checks.push(row); console.log('PROTOCOL_EVENT_CHECK ' + JSON.stringify(row));
    }
    let ready = false;
    class Page extends ui.Activity {
        get layoutXml() {
            return '<vertical padding="16"><button id="protocol_action" text="协议事件回归"/>'
                + '<switch id="protocol_toggle"/><input id="protocol_input"/></vertical>';
        }
        onContentViewSet() { ready = true; }
    }
    try {
        const page = ui.setMainActivity(Page).instance;
        for (let i = 0; !ready && i < 60; i++) await delay(80);
        if (!ready) throw new Error('Protocol Activity content timeout');
        const button = ui.findViewById('protocol_action');
        const toggle = ui.findViewById('protocol_toggle');
        const input = ui.findViewById('protocol_input');
        check('protocol handle returned', typeof button.__handleId, 'string');
        check('relookup preserves facade identity', ui.findViewById('protocol_action') === button, true);

        let once = 0, clicks = 0;
        check('subscription chains on facade', button.once('click', () => { once++; }) === button, true);
        button.on('click', () => { clicks++; });
        button.click(); button.click(); await settle();
        check('queued click once', once, 1); check('queued normal clicks', clicks, 2);
        button.removeAllListeners();

        let pending = 0; const removed = () => { pending++; };
        button.once('click', removed); button.off('click', removed);
        button.click(); await settle();
        check('off original once', pending, 0);

        const order = [];
        button.on('click', () => order.push('last'));
        button.prependListener('click', () => order.push('middle'));
        button.prependOnceListener('click', () => order.push('first'));
        button.click(); button.click(); await settle();
        check('queued prepend order', order, ['first', 'middle', 'last', 'middle', 'last']);
        button.removeAllListeners();

        let duplicates = 0; const duplicate = () => { duplicates++; };
        button.on('click', duplicate); button.on('click', duplicate);
        button.click(); await settle();
        check('duplicate registrations', duplicates, 2);
        button.off('click', duplicate); button.click(); await settle();
        check('remove single duplicate', duplicates, 3);
        button.removeAllListeners(); button.click(); await settle();
        check('clear stops callbacks', duplicates, 3);

        let nested = 0, regular = 0;
        button.once('click', () => { nested++; if (nested < 3) button.click(); });
        button.on('click', () => { regular++; });
        button.click(); await waitUntil(() => regular >= 2, 'nested queued click'); await settle();
        check('queued once reentry', nested, 1); check('queued nested regular', regular, 2);
        button.removeAllListeners();

        let checked = 0;
        toggle.once('check', () => { checked++; });
        toggle.setChecked(true); toggle.setChecked(false); await settle();
        check('queued check once', checked, 1); toggle.removeAllListeners();
        let texts = 0;
        input.once('text_change', () => { texts++; });
        input.setText('one'); input.setText('two'); await settle();
        check('queued text once', texts, 1); input.removeAllListeners();
        input.on('text_change', () => { texts++; });
        input.setText('three'); await settle();
        check('re-register does not duplicate native watchers', texts, 2); input.removeAllListeners();

        let marked = 0, found = 0;
        const marker = page.contentView.binding.protocol_action;
        marker.once('click', () => { marked++; });
        button.on('click', () => { found++; }); button.click(); button.click(); await settle();
        check('binding marker once shares facade', marked, 1);
        check('facade listener retained after marker registration', found, 2);
        marker.removeAllListeners(); button.click(); await settle();
        check('marker removal reaches facade', found, 2);
        check('shared listener count', button.listenerCount('click'), 0);
    } finally {
        ui.close();
        globalThis.__ajsEnableJavaCallbacks = enableCallbacks;
    }
    const result = {total: checks.length, failed: checks.filter(row => !row.ok)};
    console.log('PROTOCOL_EVENT_SUMMARY ' + JSON.stringify(result));
    if (result.failed.length) throw new Error('Protocol event regressions: ' + result.failed.length);
})().catch(error => { console.error(error && error.stack || error); process.exitCode = 1; });
