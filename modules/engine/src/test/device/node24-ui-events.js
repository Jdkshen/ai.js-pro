"ui nodejs";
// Real Android callbacks, not a shape-only API probe. Run on an idle test device.
const ui = require('ui'), fs = require('fs');
require('rhino').install();
const resultFile = '/sdcard/Download/codex-node-ui-events-result.json';
const result = {ok: false, checks: [], startedAt: Date.now()};
function check(name, value) {
    if (!value) throw Error('NODE_UI_EVENT_ASSERT ' + name);
    result.checks.push(name);
    console.log('NODE_UI_EVENT_CHECK', name);
}
function finish(error) {
    if (error) { result.error = String(error.stack || error); console.error(result.error); }
    else result.ok = true;
    result.finishedAt = Date.now();
    fs.writeFileSync(resultFile, JSON.stringify(result, null, 2));
    ui.close();
    if (error) process.exitCode = 1;
}
const widgets = [];
class ProbeWidget extends ui.Widget {
    constructor() { super(); widgets.push(this); }
    render() { return '<text text="Widget probe"/>'; }
}
ui.Widget.register('compat-probe', ProbeWidget);
class Main extends ui.Activity {
    get layoutXml() {
        return '<vertical id="root"><text id="target" text="Native callback probe" width="*" height="80"/>'
            + '<compat-probe id="widget"/></vertical>';
    }
    onContentViewSet(root) {
        setTimeout(() => {
            try {
                const binding = root.binding, v = binding.target;
                check('findView name and native identity', root.findView('target') === v);
                check('findView missing is null', root.findView('missing_probe_view') === null);
                v.extras.state = {answer: 42};
                check('extras stable across Java lookup', root.findView('target').extras.state.answer === 42);
                check('Widget public extras', root.binding.widget.extras['autojs.widget'] === widgets[0]);
                check('Widget.of native binding', ui.Widget.of(root.binding.widget) === widgets[0]);

                let received;
                v.on('long_click', e => { received = e; return true; });
                check('long_click return value does not consume', v.performLongClick() === false);
                check('long_click event view', received.view === v && received.consumed === false);
                v.once('long_click', e => { e.consumed = true; });
                check('long_click consumed reaches Android', v.performLongClick() === true);
                check('once removed before next native callback', v.performLongClick() === false);
                v.removeAllListeners('long_click');
                v.setLongClickable(false); v.setClickable(false);

                const MotionEvent = android.view.MotionEvent, order = [];
                let phaseEvent;
                const down = e => { phaseEvent = e; order.push('down'); e.consumed = true; };
                v.on('touch_down', down);
                v.on('touch', e => {
                    order.push('touch');
                    check('MotionEvent native getters', e.getX() === 17 && e.getY() === 29);
                    check('touch event view', e.view === v);
                    if (e.getAction() === 0 && e.consumed) check('phase shares generic event', e === phaseEvent);
                    const copy = MotionEvent.obtain(e);
                    check('event can pass back to Java', copy.getX() === 17);
                    copy.recycle();
                });
                function touch(action) {
                    const now = android.os.SystemClock.uptimeMillis();
                    const event = MotionEvent.obtain(now, now, action, 17, 29, 0);
                    try { return v.dispatchTouchEvent(event); }
                    finally { event.recycle(); }
                }
                check('touch consumed reaches Android', touch(0) === true);
                check('touch phase before generic', order.join(',') === 'down,touch');
                v.off('touch_down', down);
                check('touch removal restores Android default', touch(0) === false);
                v.removeAllListeners('touch');

                const keys = [], KeyEvent = android.view.KeyEvent;
                v.on('key_down', (code, e, source) => {
                    keys.push('down'); e.consumed = true;
                    check('key native event and view', code === 66 && e.getKeyCode() === 66 && e.view === v && source === v);
                });
                v.on('key_up', () => keys.push('up'));
                v.on('key', () => keys.push('key'));
                check('key consumed reaches Android', v.dispatchKeyEvent(new KeyEvent(0, 66)) === true);
                check('key up keeps Android default', v.dispatchKeyEvent(new KeyEvent(1, 66)) === false);
                check('key phases before generic', keys.join(',') === 'down,key,up,key');
                v.removeAllListeners();

                let scroll;
                v.on('scroll_change', e => { scroll = e; });
                v.scrollTo(3, 40);
                check('scroll callback values', scroll && scroll.scrollX === 3 && scroll.scrollY === 40
                    && scroll.oldScrollX === 0 && scroll.oldScrollY === 0 && scroll.view === v);
                v.removeAllListeners();
                const calls = [];
                v.on('click', () => calls.push('last'));
                v.prependOnceListener('click', () => calls.push('first'));
                check('emitter native listener introspection', v.listenerCount('click') === 2
                    && v.listeners('click').length === 2 && v.eventNames().includes('click'));
                v.performClick(); v.performClick();
                check('prependOnceListener native ordering', calls.join(',') === 'first,last,last');
                v.setMaxListeners(23);
                check('max listener controls', v.getMaxListeners() === 23);
                v.removeAllListeners();
                check('all listener removal', v.listenerCount('click') === 0);

                const replacement = ui.inflate('<text id="target" text="replacement"/>');
                root.removeView(v); root.addView(replacement);
                root.clearViewBindingCache();
                check('binding cache invalidation after replacement', root.binding.target === replacement);
                check('existing binding reference invalidated', binding.target === replacement);
                check('findView resolves replacement', root.findView('target') === replacement);
                check('replacement has independent extras', replacement.extras.state === undefined);
                finish();
            } catch (error) { finish(error); }
        }, 200);
    }
}
ui.setMainActivity(Main);
