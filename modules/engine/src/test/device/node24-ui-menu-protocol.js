// Run as main.js in a test project with res/layout/selection_main.xml and a
// compiled project-res.apk. Force the real :nodeui JSON/IPC compatibility path.
const enableCallbacks = globalThis.__ajsEnableJavaCallbacks;
delete globalThis.__ajsEnableJavaCallbacks;
const ui = require('ui'), delay = ms => new Promise(resolve => setTimeout(resolve, ms));
(async function () {
    const checks = [];
    function check(name, actual, expected) {
        const row = JSON.parse(JSON.stringify({name, actual, expected,
            ok: JSON.stringify(actual) === JSON.stringify(expected)}));
        checks.push(row); console.log('MENU_PROTOCOL_CHECK ' + JSON.stringify(row));
    }
    let ready = false;
    class Page extends ui.Activity {
        get layoutId() { return 'selection_main'; }
        onContentViewSet() { ready = true; }
    }
    try {
        await ui.loadAndroidResources(); ui.setMainActivity(Page);
        const deadline = Date.now() + 6000;
        while (!ready && Date.now() < deadline) await delay(80);
        if (!ready) throw new Error('Menu Activity content timeout');
        const ids = [ui.R.id.menu_a, ui.R.id.menu_b, ui.R.id.menu_c];
        for (const name of ['bottom', 'rail']) {
            const nav = ui.findViewById(name);
            check(name + ' stable identity', ui.findViewById(name) === nav, true);
            check(name + ' menu size', nav.getMenuSize(), 3);
            check(name + ' menu item index', nav.getMenuItemId(1), ids[1]);
            check(name + ' invalid menu index', nav.getMenuItemId(-1), -1);
            check(name + ' all menu ids', Array.from(nav.menuItems().itemIds), ids);
            nav.setSelectedItemId(ids[0]); await delay(650);
            const events = [];
            nav.once('item_selected', id => events.push(Number(id)));
            nav.setSelectedItemId(ids[1]);
            check(name + ' actual selected id', nav.getSelectedItemId(), ids[1]);
            const end = Date.now() + 3500;
            while (!events.length && Date.now() < end) await delay(50);
            await delay(400);
            check(name + ' once event', events, [ids[1]]);
            nav.setSelectedItemId(ids[0]); await delay(650);
            check(name + ' selection continues after once', nav.menuItems().selectedItemId, ids[0]);
            check(name + ' once removed', events.length, 1);
            nav.removeAllListeners();
        }
        const toolbar = ui.findViewById('toolbar');
        check('Toolbar menu size', toolbar.getMenuSize(), 3);
        check('Toolbar menu ids', Array.from(toolbar.menuItems().itemIds), ids);
        let rejected = false;
        try { toolbar.setSelectedItemId(ids[1]); } catch (error) { rejected = true; }
        check('non-selectable Toolbar rejects selection', rejected, true);
    } finally {
        ui.close(); globalThis.__ajsEnableJavaCallbacks = enableCallbacks;
    }
    const result = {total: checks.length, failed: checks.filter(row => !row.ok)};
    console.log('MENU_PROTOCOL_SUMMARY ' + JSON.stringify(result));
    if (result.failed.length) throw new Error('Menu regressions: ' + result.failed.length);
})().catch(error => { console.error(error.stack || error); process.exitCode = 1; });
