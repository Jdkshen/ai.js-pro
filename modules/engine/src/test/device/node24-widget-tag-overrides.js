"ui"; "nodejs";
const ui = require('ui'), fs = require('fs');
const result = {ok: false, checks: []};
const evidence = '/sdcard/Download/codex-widget-tag-overrides.json';
function check(name, value) { if (!value) throw Error('WIDGET_TAG_ASSERT '+name); result.checks.push(name); console.log('WIDGET_TAG_CHECK '+name); }
class Label extends ui.Widget {
    render() { return '<android.widget.TextView text="internal"/>'; }
    set attr$size(value) { this.size = value; }
    set attr$caption(value) { this.caption = value; this.view.setText(value); }
}
class Parent extends ui.Widget {
    render() {
        ui.Widget.register('button', Label); ui.Widget.register('grid', Label); ui.Widget.register('late-control', Label);
        return '<android.widget.LinearLayout orientation="vertical"><android.widget.FrameLayout id="slot"/></android.widget.LinearLayout>';
    }
}
(async function () {
try {
    ui.Widget.register('late-parent', Parent);
    const parent = ui.inflate('<late-parent><button id="late_button" size="" caption="button"/>'
        + '<grid id="late_grid" caption="grid"/><late-control id="late_custom" size="" caption="custom"/></late-parent>');
    const slot = parent.findView('slot');
    check('late children all inserted', slot.getChildCount() === 3);
    ['late_button','late_grid','late_custom'].forEach(id => check('late Widget '+id, ui.Widget.of(parent.findView(id)) instanceof Label));
    check('late malformed native dimension kept as custom empty string', ui.Widget.of(parent.findView('late_button')).size === '');
    check('late unknown custom tag retains empty value', ui.Widget.of(parent.findView('late_custom')).size === '');
    const list = ui.inflate('<com.stardust.autojs.engine.node24.NodeUiListView/>');
    list.setItemTemplate('<row caption="{{this.caption}}" size=""/>');
    list.setDataSource([{caption: 'item Widget'}]);
    ui.Widget.register('row', Label);
    const item = await ui.runOnUiThread(() => {
        const adapter = list.getClass().getMethod('getAdapter', []).invoke(list, []);
        const holder = adapter.createViewHolder(ui.inflate('<android.widget.FrameLayout/>'), 0);
        adapter.bindViewHolder(holder, 0);
        return holder.itemView.getChildAt(0);
    });
    check('item template uses late registration at bind time', ui.Widget.of(item) instanceof Label && String(item.getText()) === 'item Widget');
    ['button','grid','list','row','column','canvas','webview','switch','include','vertical','frame'].forEach(tag => {
        ui.Widget.register(tag, Label);
        const view = ui.inflate('<!-- ignored <'+tag+'/> --><'+tag+' id="overridden" size="large" caption="A&gt;B&amp;&#x4e2d;&#25991;"/>');
        check(tag+' uses registered constructor', ui.Widget.of(view) instanceof Label);
        check(tag+' preserves attributes and entities', ui.Widget.of(view).size === 'large' && String(view.getText()) === 'A>B&中文');
    });
    const upper = ui.inflate('<RoW caption="case"/>');
    check('Node alias casing does not discard Widget registration', ui.Widget.of(upper) instanceof Label);
    const native = ui.inflate('<android.widget.Button text="native"/>');
    check('explicit native class bypasses short-name override', ui.Widget.of(native) === undefined && String(native.getText()) === 'native');
    const explicitParent = ui.inflate('<android.widget.LinearLayout/>');
    const detached = ui.inflate('<button caption="detached" w="120" layout_weight="2"/>', explicitParent, false);
    check('override respects detached parent parameters', detached.getParent() === null && detached.getLayoutParams().weight === 2);
    const attached = ui.inflate('<button caption="attached"/>', explicitParent, true);
    check('override attaches exactly once', attached.getParent() === explicitParent && explicitParent.getChildCount() === 1);
    let failed = false; try { ui.inflate('<text size=""/>'); } catch (e) { failed = true; }
    check('unregistered native attribute errors are not swallowed', failed);
    class Loop extends ui.Widget { render() { return '<loop-tag/>'; } }
    ui.Widget.register('loop-tag', Loop); failed = false;
    try { ui.inflate('<loop-tag/>'); } catch (e) { failed = /32|nesting|嵌套/.test(String(e)); }
    check('recursive tag is bounded', failed);
    check('factory recovers after conversion and recursion errors', ui.Widget.of(ui.inflate('<button/>')) instanceof Label);
    result.ok = true;
} catch (error) { result.error = String(error.stack || error); console.error(result.error); process.exitCode = 1; }
finally { fs.writeFileSync(evidence, JSON.stringify(result, null, 2)); }

})();
