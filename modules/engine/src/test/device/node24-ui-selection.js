require('rhino').install();
const ui = require('ui');
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
(async function () {
    const checks = [];
    function check(name, actual, expected) {
        const row = JSON.parse(JSON.stringify({name, actual, expected,
            ok: JSON.stringify(actual) === JSON.stringify(expected)}));
        checks.push(row); console.log('SELECTION_CHECK ' + JSON.stringify(row));
    }
    try {
        const root = ui.layout('<vertical padding="16"><text text="原生选中事件回归"/><spinner id="selection_spinner"/></vertical>');
        await ui.runOnUiThread(function () {
            for (const tag of ['com.google.android.material.bottomnavigation.BottomNavigationView',
                    'com.google.android.material.navigationrail.NavigationRailView']) {
                const nav = ui.inflate('<' + tag + ' width="*" height="100"/>');
                const menu = nav.getMenu();
                menu.add(0, 101, 0, '第一项'); menu.add(0, 102, 1, '第二项');
                nav.setSelectedItemId(101);
                const events = [];
                nav.once('item_selected', (id, source) => events.push([Number(id), source === nav]));
                nav.setSelectedItemId(102);
                check(tag + ' once callback', events, [[102, true]]);
                check(tag + ' actual selection', Number(nav.getSelectedItemId()), 102);
                nav.setSelectedItemId(101);
                check(tag + ' once removed', events.length, 1);
                check(tag + ' selection after once', Number(nav.getSelectedItemId()), 101);
                nav.removeAllListeners();
            }
        });
        const spinner = root.binding.selection_spinner, events = [];
        await ui.runOnUiThread(function () {
            const items = new java.util.ArrayList(); items.add('第一行'); items.add('第二行'); items.add('第三行');
            spinner.setAdapter(new android.widget.ArrayAdapter(spinner.getContext(), android.R.layout.simple_spinner_item, items));
        });
        await new Promise(resolve => ui.afterFirstFrame(root, resolve)); await delay(150);
        await ui.runOnUiThread(function () {
            spinner.once('item_selected', (id, parent, position, child) => events.push(
                [Number(id), parent === spinner, Number(position), !!child]));
            spinner.setSelection(2);
        });
        const deadline = Date.now() + 3500;
        while (!events.length && Date.now() < deadline) await delay(50);
        check('Spinner actual callback and arguments', events, [[2, true, 2, true]]);
        check('Spinner selected row', Number(spinner.getSelectedItemPosition()), 2);
        await ui.runOnUiThread(() => spinner.setSelection(1)); await delay(250);
        check('Spinner once removed', events.length, 1);
        check('Spinner can select after once', Number(spinner.getSelectedItemPosition()), 1);
        spinner.removeAllListeners();
    } finally { ui.close(); }
    const result = {total: checks.length, failed: checks.filter(row => !row.ok)};
    console.log('SELECTION_SUMMARY ' + JSON.stringify(result));
    if (result.failed.length) throw new Error('Selection regressions: ' + result.failed.length);
})().catch(error => { console.error(error.stack || error); process.exitCode = 1; });
