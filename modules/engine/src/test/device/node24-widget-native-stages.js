"ui"; "nodejs";
const ui = require('ui'), fs = require('fs');
const evidence = '/sdcard/Download/codex-widget-native-stages.json';
const result = {ok: false, checks: [], startedAt: Date.now()};
function check(name, condition) {
    if (!condition) throw Error('WIDGET_STAGE_ASSERT ' + name);
    result.checks.push(name); console.log('WIDGET_STAGE_CHECK', name);
}
function near(a, b) { return Math.abs(Number(a) - b) < 0.0001; }
const panels = [], natives = [], aliases = [];
class Panel extends ui.Widget {
    constructor() { super(); this.order = ['constructor']; panels.push(this); }
    created() { this.order.push('created'); }
    render(context, parent) {
        this.context = context; this.parent = parent; this.order.push('render');
        return '<vertical padding="4"><text id="render_child" text="internal"/><frame id="slot"/></vertical>';
    }
    rootViewCreated(view) {
        this.order.push('root');
        check('XML root precedes children', view.getChildCount() === 0);
        check('root Widget identity already bound', ui.Widget.of(view) === this && this.view === view);
        check('child binding absent at root stage', view.binding.render_child === undefined);
    }
    get initialAttributes() {
        this.order.push('initial');
        check('initial defaults can access real View', this.view.getChildCount() === 2);
        check('external XML child exists before defaults', !!this.view.findView('external_child'));
        return {alpha: '0.5', padding: '6', count: '4'};
    }
    get attr$count() { return this.count; }
    set attr$count(value) { this.count = Number(value); this.view.setTag(String(value)); }
    inflated() {
        this.order.push('inflated');
        check('negative binding cache cleared after children', !!this.view.binding.render_child);
        check('initial custom value precedes external value', this.count === 4);
        check('initial alpha precedes external alpha', near(this.view.getAlpha(), 0.5));
        const density = this.view.getResources().getDisplayMetrics().density;
        check('internal padding beats default padding', this.view.getPaddingLeft() === Math.round(4 * density));
    }
    mounted() {
        this.order.push('mounted');
        check('external setter applied before mounted', this.count === 9 && String(this.view.getTag()) === '9');
        check('external native attribute applied last', near(this.view.getAlpha(), 0.8));
    }
}
class Native extends ui.Widget {
    constructor() { super(); this.order = []; natives.push(this); }
    get contentSlot() { return 'native_slot'; }
    render(context, parent) {
        this.context = context; this.parent = parent; this.order.push('render');
        const Linear = $autojs.java.findClass('android.widget.LinearLayout');
        const root = new Linear(context);
        root.setOrientation(1);
        const slot = ui.inflate('<frame id="native_slot"/>', root, false);
        root.addView(slot);
        return root;
    }
    rootViewCreated(view) {
        this.order.push('root');
        check('native render keeps its own children', view.getChildCount() === 1);
        check('native slot is empty at root stage', view.findView('native_slot').getChildCount() === 0);
    }
    get initialAttributes() { return {alpha: '0.6'}; }
    inflated() { this.order.push('inflated'); check('native initial alpha applied', near(this.view.getAlpha(), 0.6)); }
    mounted() { this.order.push('mounted'); }
}
class Alias extends ui.Widget {
    constructor() { super(); this.order = []; aliases.push(this); }
    get contentSlot() { return 'native_slot'; }
    render() { this.order.push('render'); return '<probe-native/>'; }
    rootViewCreated(view) { this.order.push('root'); check('alias root is still before XML children', view.findView('native_slot').getChildCount() === 0); }
    inflated() { this.order.push('inflated'); }
    mounted() { this.order.push('mounted'); }
}
try {
    ui.Widget.register('probe-panel', Panel); ui.Widget.register('probe-native', Native); ui.Widget.register('probe-alias', Alias);
    const parent = ui.inflate('<vertical/>');
    const panel = ui.inflate('<probe-panel id="panel" alpha="0.8" count="9" padding="12" w="210" h="100" margin="3" layout_weight="2"><text id="external_child" text="external"/></probe-panel>', parent, true);
    const widget = panels[0], lp = panel.getLayoutParams(), density = panel.getResources().getDisplayMetrics().density;
    check('render receives actual explicit parent', widget.parent === parent);
    check('render Context matches actual View', widget.context === panel.getContext());
    check('correct LinearLayout parameters', String(lp.getClass().getName()) === 'android.widget.LinearLayout$LayoutParams');
    check('external dimensions and weight applied', lp.width === Math.round(210 * density) && near(lp.weight, 2));
    check('external margins applied', lp.leftMargin === Math.round(3 * density));
    check('attached exactly once', parent.getChildCount() === 1 && panel.getParent() === parent);
    check('all XML stages ordered', widget.order.join(',') === 'constructor,created,render,root,initial,inflated,mounted');
    check('external padding wins at mounted', panel.getPaddingLeft() === Math.round(12 * density));
    const native = ui.inflate('<probe-native id="native" alpha="0.9"><text id="native_external" text="external"/></probe-native>', parent, false);
    check('native View remains detached when requested', native.getParent() === null && parent.getChildCount() === 1);
    check('native external child routed to slot', native.findView('native_slot').getChildCount() === 1);
    check('native render receives correct parent', natives[0].parent === parent);
    check('native external alpha wins', near(native.getAlpha(), 0.9));
    check('native stages ordered', natives[0].order.join(',') === 'render,root,inflated,mounted');
    const alias = ui.inflate('<probe-alias id="alias"><text text="alias child"/></probe-alias>');
    check('alias mounted Widget identity', ui.Widget.of(alias) === aliases[0]);
    check('alias stages ordered', aliases[0].order.join(',') === 'render,root,inflated,mounted');
    check('alias content slot receives children', alias.findView('native_slot').getChildCount() === 1);
    check('root render gets null rather than synthetic parent', natives[1].parent === null);
    class Bad extends ui.Widget { render() { return 7; } }
    ui.Widget.register('bad-stage', Bad);
    let failed = false; try { ui.inflate('<bad-stage/>'); } catch (error) { failed = /render/.test(String(error)); }
    check('invalid render reaches caller', failed);
    class BadMounted extends ui.Widget { render() { return '<text/>'; } mounted() { throw Error('expected mounted failure'); } }
    ui.Widget.register('bad-mounted', BadMounted);
    failed = false; try { ui.inflate('<bad-mounted/>', parent, true); } catch (error) { failed = String(error).includes('expected mounted failure'); }
    check('mounted error reaches caller without attachment', failed && parent.getChildCount() === 1);
    class AliasedAttributes extends ui.Widget {
        render() { return '<text size="16" text="before"/>'; }
        set attr$size(value) { this.customSize = value; }
        set attr$caption(value) { this.caption = value; this.view.setText(value); }
    }
    ui.Widget.register('aliased-attributes', AliasedAttributes);
    const named = ui.inflate('<aliased-attributes size="large" caption="&quot;A&amp;B&#x4e2d;&#25991;&quot;"/>');
    check('custom size alias retains original name and value', ui.Widget.of(named).customSize === 'large');
    check('custom attributes decode XML entities exactly once', String(named.getText()) === '"A&B中文"');
    result.ok = true;
} catch (error) { result.error = String(error.stack || error); console.error(result.error); process.exitCode = 1; }
finally { result.finishedAt = Date.now(); fs.writeFileSync(evidence, JSON.stringify(result, null, 2)); }
