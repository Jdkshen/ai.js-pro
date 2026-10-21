"nodejs ui";
// Copy beside the unchanged bundled 自定义组件/colored-button.js before running.
const assert = require('assert');
const ui = require('ui');
const setMainActivity = ui.setMainActivity;
let opened;
ui.setMainActivity = function (Ctor, options) {
    opened = setMainActivity(Ctor, options);
    return opened;
};
require('./colored-button.js');
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
async function until(predicate) {
    for (let i = 0; i < 50; i++) {
        if (predicate()) return;
        await delay(100);
    }
    throw new Error('Widget callback did not complete');
}
(async () => {
    assert(opened && opened.real);
    const root = $java.findClass('com.stardust.autojs.engine.node24.NodeUiLocalSession').getContentView();
    const {btn1, btn2} = root.binding;
    const first = ui.Widget.of(btn1);
    const second = ui.Widget.of(btn2);
    assert(first && second, 'Widget.of must resolve real Java bindings');
    assert.strictEqual(first.view, btn1);
    assert.strictEqual(second.view, btn2);
    assert.strictEqual(ui.findViewById('btn1'), btn1);
    assert.strictEqual(ui.findViewById(btn2.getId()), btn2);
    assert.strictEqual(btn1.getBackgroundTintList().getDefaultColor(), -7123968);
    assert.strictEqual(btn2.getBackgroundTintList().getDefaultColor(), -14526512);
    assert.strictEqual(btn2.attr('color'), '#2257D0', 'initialAttributes setter must reach the real View');
    btn1.performClick();
    await until(() => first.attrs.color === '#6750A4');
    assert.strictEqual(btn1.getBackgroundTintList().getDefaultColor(), -10006364);
    assert.strictEqual(btn1.attr('color'), '#6750A4');
    // The unchanged second sample handler calls Widget.onClick -> this.view.on.
    assert.strictEqual(btn2.performClick(), true);
    let count = 0;
    const listener = source => { assert.strictEqual(source, btn2); count++; };
    btn2.on('click', listener);
    btn2.once('click', () => { count += 10; });
    btn2.performClick();
    await until(() => count === 11);
    btn2.performClick();
    await until(() => count === 12);
    btn2.off('click', listener);
    btn2.performClick();
    await delay(150);
    assert.strictEqual(count, 12, 'off and once must not leave extra callbacks');
    assert.strictEqual(btn2.attr({text: '属性已更新', color: '#934c00'}), btn2);
    assert.strictEqual(String(btn2.getText()), '属性已更新');
    assert.strictEqual(btn2.getBackgroundTintList().getDefaultColor(), -7123968);
    assert.throws(() => btn2.attr('_unknown_widget_attribute_', 'x'), /未知 View 属性/);
    console.log('NODE_CUSTOM_WIDGET_RESULT ' + JSON.stringify({ok: true,
        checks: ['real View identity', 'explicit and default colors', 'original click handlers',
            'custom attr setter', 'once/off', 'native attributes', 'attribute errors']}));
})().catch(error => {
    console.error(error.stack || error);
    process.exitCode = 1;
}).finally(() => ui.close());
