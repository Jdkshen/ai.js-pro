// Run the same source through Rhino, QuickJS and Node; creates no visible window.
var colorProbeNode = typeof process !== 'undefined' && process.versions && process.versions.node;
if (colorProbeNode) require('rhino').install();
var colorProbeUi = colorProbeNode ? require('ui') : ui;
var colorProbeContext = colorProbeNode ? $autojs.androidContext : context;
var colorProbeSession = colorProbeNode
    ? $autojs.java.findClass('com.stardust.autojs.engine.node24.NodeUiLocalSession')
    : Packages.com.stardust.autojs.engine.node24.NodeUiLocalSession;
var colorProbeText = colorProbeNode ? $autojs.java.findClass('android.widget.TextView') : android.widget.TextView;
var colorProbeChecks = [];
function colorProbeCheck(name, value) {
    if (!value) throw Error('TEXT_COLOR_ASSERT ' + name);
    colorProbeChecks.push(name);
}
colorProbeUi.run(function () {
    var view = new colorProbeText(colorProbeContext);
    colorProbeSession.viewAttribute(view, 'textColor', '#112233', true);
    colorProbeCheck('native text color', (Number(view.getCurrentTextColor()) >>> 0) === 0xff112233);
    colorProbeCheck('live color alias', String(colorProbeSession.viewAttribute(view, 'color', '', false)) === '#FF112233');
    view.setTextColor(-16711936);
    colorProbeCheck('direct Java update visible', String(colorProbeSession.viewAttribute(view, 'textColor', '', false)) === '#FF00FF00');
    colorProbeSession.viewAttribute(view, 'textColorHint', '#334455', true);
    colorProbeCheck('hint color', String(colorProbeSession.viewAttribute(view, 'textColorHint', '', false)) === '#FF334455');
    colorProbeSession.viewAttribute(view, 'color', '@android:color/holo_blue_light', true);
    var resources = view.getResources(), id = resources.getIdentifier('holo_blue_light', 'color', 'android');
    colorProbeCheck('Android resource color', Number(view.getCurrentTextColor()) === Number(resources.getColor(id)));
    var previous = Number(view.getCurrentTextColor()), failed = false;
    try { colorProbeSession.viewAttribute(view, 'color', '@android:color/codex_nonexistent_color', true); }
    catch (error) { failed = true; }
    colorProbeCheck('invalid resource surfaces', failed);
    colorProbeCheck('failed update preserves current color', Number(view.getCurrentTextColor()) === previous);
});
if (colorProbeChecks.length !== 7) throw Error('Text color callback did not complete');
console.log('SHARED_TEXT_COLOR_OK ' + JSON.stringify(colorProbeChecks));
