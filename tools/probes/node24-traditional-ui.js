"nodejs ui";
// Copy beside the unchanged 传统界面/main.node.js.
const assert = require('assert');
const ui = require('ui');
const original = ui.setMainActivity;
let opened;
ui.setMainActivity = (Ctor, options) => (opened = original(Ctor, options));
require('./main.node.js');
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
async function until(fn) {
    for (let i = 0; i < 100; i++) {
        const value = fn();
        if (value) return value;
        await pause(100);
    }
    throw new Error('Traditional UI check timed out');
}
function textIn(view, text) {
    try { if (String(view.getText()) === text) return view; } catch (_) {}
    try {
        for (let i = 0; i < view.getChildCount(); i++) {
            const found = textIn(view.getChildAt(i), text);
            if (found) return found;
        }
    } catch (_) {}
    return null;
}
(async () => {
    const instance = opened.instance;
    const root = instance.contentView;
    const {grid, list, canvas} = root.binding;
    assert(grid.__class.endsWith('NodeUiGridView'));
    assert(list.__class.endsWith('NodeUiListView'));
    assert(canvas.__class.endsWith('NodeUiCanvasView'));
    assert.strictEqual(grid.getSpanCount(), 2);
    await until(() => grid.getNodeItemCount() === 4 && list.getNodeItemCount() === 4);
    const firstGrid = await until(() => grid.findViewHolderForAdapterPosition(0));
    assert(textIn(firstGrid.itemView, 'Buttons'));
    const lastGrid = await until(() => grid.findViewHolderForAdapterPosition(3));
    assert(textIn(lastGrid.itemView, 'CheckBox & Radio'), 'XML entities must survive conversion');
    assert.strictEqual(firstGrid.itemView.performClick(), true);
    const first = await until(() => list.findViewHolderForAdapterPosition(0));
    await until(() => first.itemView.binding.enabled.hasOnClickListeners() || first.itemView.hasOnClickListeners());
    first.itemView.binding.enabled.toggle();
    await until(() => instance.listData[0].enabled === false);
    await until(() => textIn(list.findViewHolderForAdapterPosition(0).itemView, 'Hello').getCurrentTextColor() === -65536);
    const before = instance.listData.length;
    list.findViewHolderForAdapterPosition(0).itemView.binding.delete.performClick();
    await until(() => instance.listData.length === before - 1 && list.getNodeItemCount() === before - 1);
    assert.strictEqual(instance.listData[0].text, 'World');
    instance.listData.push({text: 'New', enabled: false});
    list.getAdapter().notifyItemInserted(instance.listData.length - 1);
    await until(() => list.getNodeItemCount() === before);
    list.scrollToPosition(before - 1);
    const last = await until(() => list.findViewHolderForAdapterPosition(before - 1));
    await until(() => textIn(last.itemView, 'New'));
    const switched = last.itemView.binding.enabled;
    switched.toggle();
    await until(() => instance.listData[before - 1].enabled === true);
    // Use another real draw listener to observe ongoing callbacks from the unchanged sample.
    let frames = 0;
    canvas.on('draw', realCanvas => {
        assert.strictEqual(realCanvas.__class, 'android.graphics.Canvas');
        frames++;
    });
    await until(() => frames >= 5);
    assert(instance.getWindow());
    instance._showMaterial3Alert('Probe', '真实 Activity 参数');
    await pause(300);
    console.log('NODE_TRADITIONAL_UI_RESULT ' + JSON.stringify({ok: true, frames,
        checks: ['real grid/list/canvas', 'template data and ampersand', 'grid click',
            'switch updates JS and text color', 'delete and insert', 'recycled position', 'continuous drawing', 'real Activity dialog']}));
})().catch(error => {
    console.error(error.stack || error);
    process.exitCode = 1;
}).finally(() => ui.close());
