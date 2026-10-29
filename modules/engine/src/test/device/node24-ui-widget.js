"ui"; "nodejs";
const ui = require('ui'), fs = require('fs');
const resultFile = '/sdcard/Download/codex-node-ui-widget-result.json';
const result = {ok: false, checks: [], startedAt: Date.now()}, trace = [];
function check(name, value) {
    if (!value) throw Error('NODE_WIDGET_ASSERT ' + name);
    result.checks.push(name); console.log('NODE_WIDGET_CHECK', name);
}
class Badge extends ui.Widget {
    constructor() { super(); this.seed = 'constructor'; this.attr$label = 'field'; trace.push('constructor'); }
    created() { this.seed = 'created'; trace.push('created'); }
    get initialAttributes() {
        if (this.seed === undefined) throw Error('defaults read inside super');
        trace.push('initial');
        return {text: this.seed, alpha: '0.5', count: '4', label: 'must not overwrite field'};
    }
    get attr$count() { return this.count; }
    set attr$count(value) { this.count = Number(value); this.view.setTag(String(value)); }
    get attr$readonly() { return 'readonly'; }
    render() { trace.push('render'); return '<text text="internal"/>'; }
    mounted(view) { super.mounted(); trace.push('mounted'); check('view assigned before mounted', this.view === view); }
}
class Inherited extends Badge {}
try {
    ui.Widget.register('compat-badge', Inherited);
    check('create unknown is undefined', ui.Widget.create('unknown-widget') === undefined);
    const fresh = ui.Widget.create('compat-badge');
    check('create subtype', fresh instanceof Inherited);
    check('create only constructs', trace.join(',') === 'constructor');
    let earlyError = false;
    try { void fresh.view; } catch (error) { earlyError = String(error).includes('not mounted'); }
    check('unmounted view gives clear error', earlyError);
    trace.length = 0;
    const view = ui.inflate('<compat-badge id="one" text="external" count="9"/>');
    const badge = ui.Widget.of(view);
    check('constructor and created order', trace.join(',') === 'constructor,created,initial,render,mounted');
    check('Widget.of and extras agree', badge instanceof Inherited && view.extras['autojs.widget'] === badge);
    check('external text overrides render', String(view.getText()) === 'external');
    check('native default alpha applied', view.getAlpha() === 0.5);
    check('custom XML setter receives native view', badge.count === 9 && String(view.getTag()) === '9');
    check('custom data default preserves field', badge.attr$label === 'field');
    check('inherited descriptors detected', badge.hasAttribute('count') && badge.hasAttribute('label'));
    badge.setAttribute('count', '12');
    check('setAttribute calls inherited setter', badge.getAttribute('count') === 12 && String(view.getTag()) === '12');
    view.attr('label', 'single');
    check('view.attr writes JS data field', badge.getAttribute('label') === 'single');
    view.attr({label: 'batch', count: '15'});
    check('batch attributes handle field and setter', badge.attr$label === 'batch' && badge.count === 15);
    check('unknown getter uses fallback', badge.getAttribute('nativeText', () => String(view.getText())) === 'external');
    badge.setAttribute('textColor', '#ff00ff', value => view.attr('textColor', value));
    check('unknown setter uses native fallback', (view.getCurrentTextColor() >>> 0) === 0xffff00ff);
    check('dynamic color getter reads native value', view.attr('textColor') === '#FFFF00FF');
    view.attr('color', '#112233');
    check('color alias updates native text', (view.getCurrentTextColor() >>> 0) === 0xff112233);
    view.setTextColor(-16711936);
    check('color getter follows direct Java changes', view.attr('color') === '#FF00FF00');
    view.attr('textColorHint', '#334455');
    check('hint color updates and reads live', view.attr('textColorHint') === '#FF334455');
    view.attr('color', '@android:color/holo_blue_light');
    const expectedColor = view.getResources().getColor($autojs.java.findClass('android.R$color').holo_blue_light);
    check('Android named color resource resolves', view.getCurrentTextColor() === expectedColor);
    let fallback;
    badge.setAttribute('readonly', 'ignored', value => { fallback = value; });
    check('readonly attribute preserves getter', fallback === 'ignored' && badge.getAttribute('readonly') === 'readonly');
    const data = ui.inflate('<compat-badge id="two" label="external field"/>');
    check('XML data field applied', ui.Widget.of(data).attr$label === 'external field');
    class DefaultBadge extends Badge { render() { return '<text/>'; } }
    ui.Widget.register('default-badge', DefaultBadge);
    const defaults = ui.inflate('<default-badge android:id="defaults"/>');
    check('default reads initialized state', String(defaults.getText()) === 'created');
    check('default custom setter runs after native creation', String(defaults.getTag()) === '4');
    check('ordinary view has no Widget', ui.Widget.of(ui.inflate('<text/>')) === undefined);
    class Bad extends ui.Widget { created() { throw Error('expected creation failure'); } render() { return '<text/>'; } }
    ui.Widget.register('bad-widget', Bad);
    let surfaced = false;
    try { ui.inflate('<bad-widget/>'); } catch (error) { surfaced = String(error).includes('expected creation failure'); }
    check('creation failure reaches caller', surfaced);
    result.ok = true;
} catch (error) {
    result.error = String(error.stack || error); console.error(result.error); process.exitCode = 1;
} finally {
    result.finishedAt = Date.now(); fs.writeFileSync(resultFile, JSON.stringify(result, null, 2));
}
