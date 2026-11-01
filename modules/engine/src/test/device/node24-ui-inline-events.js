// Run with the Node engine on an idle test device. Closes only its own UI.
require('rhino').install();
const ui = require('ui');

(async function () {
    const checks = [];
    function check(name, actual, expected) {
        const ok = JSON.stringify(actual) === JSON.stringify(expected);
        checks.push(JSON.parse(JSON.stringify({name, ok, actual, expected})));
        console.log('INLINE_EVENT_CHECK ' + JSON.stringify(checks[checks.length - 1]));
    }
    try {
        const root = ui.layout('<vertical padding="16">'
            + '<button id="inline_action" text="监听回归"/>'
            + '<switch id="inline_toggle" text="开关"/>'
            + '<input id="inline_input" text=""/></vertical>');
        check('layout returns native root', root.__ajsJavaObject__, true);
        const button = root.binding.inline_action;
        const toggle = root.binding.inline_toggle;
        const input = root.binding.inline_input;
        check('binding returns native button', button && button.__ajsJavaObject__, true);
        check('binding has actual Android id', Number(button.getId()) > 0, true);
        check('binding cache identity', root.binding.inline_action === button, true);
        check('missing binding is absent', root.binding.no_such_inline_view === undefined, true);
        await ui.runOnUiThread(function () {
            let clicks = 0;
            const onClick = function () { clicks++; };
            button.on('click', onClick);
            button.performClick(); button.performClick();
            check('native clicks', clicks, 2);
            button.off('click', onClick); button.performClick();
            check('off stops native delivery', clicks, 2);

            let once = 0;
            button.once('click', function () { once++; });
            button.performClick(); button.performClick();
            check('native once', once, 1);
            check('once listener count', button.listenerCount('click'), 0);
            button.removeAllListeners();
            let removedOnce = 0;
            const pending = function () { removedOnce++; };
            button.once('click', pending); button.off('click', pending); button.performClick();
            check('off original once callback', removedOnce, 0);
            button.removeAllListeners();

            let duplicates = 0;
            const repeated = function () { duplicates++; };
            button.on('click', repeated); button.on('click', repeated); button.performClick();
            check('duplicate registrations', duplicates, 2);
            button.removeListener('click', repeated); button.performClick();
            check('remove one duplicate', duplicates, 3);
            button.removeAllListeners('click'); button.performClick();
            check('clear stops native delivery', duplicates, 3);
            button.removeAllListeners();

            const order = [];
            button.on('click', function () { order.push('last'); });
            button.prependListener('click', function () { order.push('middle'); });
            button.prependOnceListener('click', function () { order.push('first'); });
            button.performClick(); button.performClick();
            check('native prepend order', order, ['first', 'middle', 'last', 'middle', 'last']);
            button.removeAllListeners();

            let reentrantOnce = 0, regular = 0;
            button.once('click', function () {
                reentrantOnce++;
                // Guard a broken implementation so baseline testing cannot recurse forever.
                if (reentrantOnce < 3) button.performClick();
            });
            button.on('click', function () { regular++; });
            button.performClick();
            check('once removed before reentry', reentrantOnce, 1);
            check('reentrant regular callbacks', regular, 2);
            button.removeAllListeners();

            let mixed = 0;
            button.once('click', function () { mixed++; });
            button.emit('click', button); button.performClick();
            check('emit and native share once state', mixed, 1);
            button.removeAllListeners();

            let checksReceived = 0;
            toggle.setChecked(false);
            toggle.once('check', function () { checksReceived++; });
            toggle.setChecked(true); toggle.setChecked(false);
            check('native check once', checksReceived, 1);
            toggle.removeAllListeners();

            let texts = 0;
            input.once('text_change', function () { texts++; });
            input.setText('first'); input.setText('second');
            check('native text_change once', texts, 1);
            input.removeAllListeners();

            let idlessClicks = 0;
            const idless = ui.inflate('<button text="无显式 ID"/>');
            idless.once('click', function () { idlessClicks++; });
            idless.performClick(); idless.performClick();
            check('idless native view once', idlessClicks, 1);
            idless.removeAllListeners();
        });
        const replacement = ui.layout('<vertical><button id="inline_action" text="新界面"/></vertical>');
        const newButton = replacement.binding.inline_action;
        check('replacement has a distinct native view', newButton !== button, true);
        await ui.runOnUiThread(function () {
            let replacementClicks = 0;
            newButton.once('click', function () { replacementClicks++; });
            button.performClick();
            check('old view cannot trigger replacement listeners', replacementClicks, 0);
            newButton.performClick(); newButton.performClick();
            check('replacement native once', replacementClicks, 1);
            newButton.removeAllListeners();
        });
    } finally {
        ui.close();
    }
    const summary = {total: checks.length, failed: checks.filter(x => !x.ok)};
    console.log('INLINE_EVENT_SUMMARY ' + JSON.stringify(summary));
    if (summary.failed.length) throw new Error('Inline View event regressions: ' + summary.failed.length);
})().catch(function (error) {
    console.error(error && error.stack || error);
    process.exitCode = 1;
});
