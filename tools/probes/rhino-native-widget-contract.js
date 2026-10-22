"ui";
"rhino";
/**
 * Rhino ui.Widget 原生 View 渲染回归探针（Mi8 / dev1200+）。
 *
 * 复现的原始故障：ui.Widget.render() 返回 JavaAdapter/原生 View 时，宿主把返回值
 * 当成 XML 字符串再 __inflate__，抛 SAXParseException: Unexpected token adapter1。
 *
 * 本探针只使用公开的 ui.Widget / defineAttr / onViewCreated / onFinishInflation 契约，
 * 不包含任何宿主修复适配。它覆盖：
 *   1. render() 返回真实 android.widget.TextView，且布局里就是同一个 View；
 *   2. onViewCreated 先于自定义属性回调，并能把方法挂到 View 上；
 *   3. 自定义属性落到真实 TextView（text / textColor），普通 XML 属性仍走原 inflater；
 *   4. XML w/h 生成正确的 LayoutParams，ui.post 布局后测量尺寸正确；
 *   5. Widget.view 与 ui.<id> / ui.findById 身份一致；
 *   6. onFinishInflation 在属性之后触发一次且拿到同一个 View；
 *   7. 老式 render() 返回 XML 字符串的组件不回归（子 View、属性、尺寸、生命周期）。
 *
 * 结果：RHINO_NATIVE_WIDGET_RESULT={checks,pass,total,...}，随后 ui.finish()。
 */

var RESULT_PREFIX = "RHINO_NATIVE_WIDGET_RESULT=";
var result = {engine: "rhino", checks: []};
var completed = false;

var nativeWidget = null;
var nativeRendered = null;
var legacyWidget = null;

function record(name, fn) {
    try {
        var value = fn();
        if (value === false) {
            throw new Error("returned false");
        }
        result.checks.push({name: name, ok: true});
    } catch (error) {
        result.checks.push({
            name: name,
            ok: false,
            error: String(error && error.message ? error.message : error)
        });
    }
}

function must(condition, message) {
    if (!condition) {
        throw new Error(message || "assertion failed");
    }
}

function indexOfLife(life, item) {
    for (var i = 0; i < life.length; i++) {
        if (life[i] === item) {
            return i;
        }
    }
    return -1;
}

function finish() {
    if (completed) {
        return;
    }
    completed = true;
    result.total = result.checks.length;
    result.pass = result.checks.filter(function (check) {
        return check.ok;
    }).length;
    result.passed = result.pass;
    result.ok = result.pass === result.total;
    console.log(RESULT_PREFIX + JSON.stringify(result));
    try {
        ui.finish();
    } catch (error) {
        console.error("RHINO_NATIVE_WIDGET_FINISH_ERROR=" + String(error));
    }
}

/* -------------------------------------------------------------------------
 * 1) 原生 View 组件：render() 直接返回 TextView（不是 XML 字符串）
 * ---------------------------------------------------------------------- */
function NativeTextWidget() {
    ui.Widget.call(this);
    var self = this;
    this.life = [];
    this.attrLog = [];
    this.viewCreatedCount = 0;
    this.finishedCount = 0;
    this.createdView = null;
    this.finishedView = null;
    this.viewReadyInOnViewCreated = false;
    this.finishedText = null;
    this.finishedColor = null;
    nativeWidget = this;

    // defineAttr 的注册发生在构造函数；真正的 setter 必须晚于 onViewCreated。
    // setter 故意调用 onViewCreated 才挂上的方法，顺序错了这里会失败。
    this.defineAttr("probeLabel", function (view, attrName, value, defaultSetter) {
        must(typeof view.applyProbeLabel === "function",
            "probeLabel setter ran before onViewCreated attached applyProbeLabel");
        view.applyProbeLabel(value);
        self.attrLog.push(attrName + "=" + value);
        self.life.push("attr:" + attrName);
    });
    this.defineAttr("probeColor", function (view, attrName, value, defaultSetter) {
        must(typeof view.applyProbeColor === "function",
            "probeColor setter ran before onViewCreated attached applyProbeColor");
        view.applyProbeColor(value);
        self.attrLog.push(attrName + "=" + value);
        self.life.push("attr:" + attrName);
    });
}

util.extend(NativeTextWidget, ui.Widget);

NativeTextWidget.prototype.render = function () {
    this.life.push("render");
    var view = new android.widget.TextView(activity);
    view.setText("render-default");
    view.setTextColor(android.graphics.Color.BLACK);
    this.rendered = view;
    nativeRendered = view;
    return view;
};

NativeTextWidget.prototype.onViewCreated = function (view) {
    this.life.push("viewCreated");
    this.viewCreatedCount++;
    this.createdView = view;
    // afterCreateView 应先设置 widget.view 再通知 onViewCreated。
    this.viewReadyInOnViewCreated = (this.view === view);
    view.applyProbeLabel = function (value) {
        view.setText(String(value));
    };
    view.applyProbeColor = function (value) {
        view.setTextColor(android.graphics.Color.parseColor(String(value)));
    };
};

NativeTextWidget.prototype.onFinishInflation = function (view) {
    this.life.push("finish");
    this.finishedCount++;
    this.finishedView = view;
    this.finishedText = String(view.getText());
    this.finishedColor = view.getCurrentTextColor();
};

/* -------------------------------------------------------------------------
 * 2) 老式 XML 组件：render() 返回 XML 字符串，验证不回归
 * ---------------------------------------------------------------------- */
function LegacyXmlWidget() {
    ui.Widget.call(this);
    var self = this;
    this.life = [];
    this.attrLog = [];
    this.viewCreatedCount = 0;
    this.finishedCount = 0;
    this.createdView = null;
    this.finishedView = null;
    this.viewReadyInOnViewCreated = false;
    this.finishedText = null;
    legacyWidget = this;

    this.defineAttr("legacyLabel", function (view, attrName, value, defaultSetter) {
        must(typeof view.setLegacyLabel === "function",
            "legacyLabel setter ran before onViewCreated attached setLegacyLabel");
        view.setLegacyLabel(value);
        self.attrLog.push(attrName + "=" + value);
        self.life.push("attr:" + attrName);
    });
}

util.extend(LegacyXmlWidget, ui.Widget);

LegacyXmlWidget.prototype.render = function () {
    this.life.push("render");
    return '<vertical padding="4dp">'
        + '<text id="legacyText" text="legacy-default" textSize="14sp"/>'
        + '</vertical>';
};

LegacyXmlWidget.prototype.onViewCreated = function (view) {
    this.life.push("viewCreated");
    this.viewCreatedCount++;
    this.createdView = view;
    this.viewReadyInOnViewCreated = (this.view === view);
    // 老式 XML 的 onViewCreated 可能早于子 View 完成 inflate，所以这里只挂方法，
    // 真正取 legacyText 是在属性回调（子 View 已完成）和 onFinishInflation 时。
    view.setLegacyLabel = function (value) {
        var child = view.legacyText;
        must(child != null, "legacy widget child legacyText is missing");
        child.setText(String(value));
    };
};

LegacyXmlWidget.prototype.onFinishInflation = function (view) {
    this.life.push("finish");
    this.finishedCount++;
    this.finishedView = view;
    var child = view.legacyText;
    this.finishedText = child == null ? null : String(child.getText());
};

/* -------------------------------------------------------------------------
 * 3) 建立界面。原生 View 与老式 XML 组件放在同一个 XML 布局里。
 * ---------------------------------------------------------------------- */
try {
    ui.registerWidget("native-probe", NativeTextWidget);
    ui.registerWidget("legacy-probe", LegacyXmlWidget);

    ui.layout(
        '<vertical id="probeRoot" padding="12dp" background="#101218">'
        + '<native-probe id="nativeProbe" w="180dp" h="48dp"'
        + ' probeLabel="native-view-ok" probeColor="#ff5722" alpha="0.5"/>'
        + '<legacy-probe id="legacyProbe" w="200dp" h="60dp" marginTop="8dp"'
        + ' legacyLabel="legacy-view-ok"/>'
        + '</vertical>'
    );

} catch (error) {
    result.checks.push({
        name: "layout_inflate",
        ok: false,
        error: String(error && error.message ? error.message : error)
    });
    finish();
}

/* -------------------------------------------------------------------------
 * 4) ui.post 等待真实布局，然后只在 UI 线程做断言
 * ---------------------------------------------------------------------- */
function waitForLayout(callback) {
    var attempts = 0;

    function poll() {
        attempts++;
        var ready = false;
        try {
            var nativeView = nativeWidget && nativeWidget.view;
            var legacyView = legacyWidget && legacyWidget.view;
            ready = !!(nativeView && legacyView
                && nativeView.getWidth() > 0 && nativeView.getHeight() > 0
                && legacyView.getWidth() > 0 && legacyView.getHeight() > 0);
        } catch (ignored) {
            ready = false;
        }
        if (ready || attempts >= 40) {
            callback(attempts);
            return;
        }
        ui.post(poll, 50);
    }

    ui.post(poll);
}

function runChecks(layoutPollAttempts) {
    record("assertions_on_ui_thread", function () {
        must(ui.isUiThread(), "final assertions must run on the Android UI thread");
    });

    record("layout_inflated_both_widgets", function () {
        must(nativeWidget != null, "native widget instance was not created");
        must(legacyWidget != null, "legacy widget instance was not created");
        must(nativeWidget.view != null, "native widget.view is null");
        must(legacyWidget.view != null, "legacy widget.view is null");
        must(ui.nativeProbe != null, "ui.nativeProbe is missing");
        must(ui.legacyProbe != null, "ui.legacyProbe is missing");
    });

    var nativeView = nativeWidget ? nativeWidget.view : null;
    var legacyView = legacyWidget ? legacyWidget.view : null;
    var metrics = activity.getResources().getDisplayMetrics();
    var density = metrics.density;
    var expectedNativeWidth = Math.round(180 * density);
    var expectedNativeHeight = Math.round(48 * density);
    var expectedLegacyWidth = Math.round(200 * density);
    var expectedLegacyHeight = Math.round(60 * density);
    var expectedColor = android.graphics.Color.parseColor("#ff5722");

    result.device = String(android.os.Build.MODEL);
    result.sdkInt = android.os.Build.VERSION.SDK_INT;
    result.density = density;
    result.layoutPollAttempts = layoutPollAttempts;

    record("layout_ready_after_ui_post", function () {
        must(layoutPollAttempts > 0, "ui.post was not used");
        must(nativeView.getMeasuredWidth() > 0 && nativeView.getMeasuredHeight() > 0,
            "native view was not measured");
        must(legacyView.getMeasuredWidth() > 0 && legacyView.getMeasuredHeight() > 0,
            "legacy view was not measured");
    });

    record("render_returns_real_android_view", function () {
        must(nativeRendered != null, "render() was not called");
        var className = String(nativeRendered.getClass().getName());
        must(className === "android.widget.TextView",
            "render() returned " + className + ", expected android.widget.TextView");
    });

    record("native_inflated_view_is_same_render_view", function () {
        must(nativeWidget.view === nativeRendered,
            "widget.view is not the exact View returned by render()");
        must(String(nativeView.getClass().getName()) === "android.widget.TextView",
            "inflated native widget is " + nativeView.getClass().getName());
    });

    record("on_view_created_attached_methods_to_native_view", function () {
        must(nativeWidget.viewCreatedCount === 1,
            "onViewCreated count=" + nativeWidget.viewCreatedCount);
        must(nativeWidget.viewReadyInOnViewCreated,
            "widget.view was not set before onViewCreated");
        must(typeof nativeView.applyProbeLabel === "function",
            "applyProbeLabel was not attached to the real View");
        must(typeof nativeView.applyProbeColor === "function",
            "applyProbeColor was not attached to the real View");
    });

    record("on_view_created_precedes_custom_attr_setters", function () {
        var created = indexOfLife(nativeWidget.life, "viewCreated");
        var label = indexOfLife(nativeWidget.life, "attr:probeLabel");
        var color = indexOfLife(nativeWidget.life, "attr:probeColor");
        must(created >= 0, "onViewCreated did not run");
        must(label > created, "probeLabel setter ran before/without onViewCreated");
        must(color > created, "probeColor setter ran before/without onViewCreated");
    });

    record("custom_attrs_reach_android_view", function () {
        must(String(nativeView.getText()) === "native-view-ok",
            "TextView text=" + String(nativeView.getText()));
        must(nativeView.getCurrentTextColor() === expectedColor,
            "TextView color=0x" + (nativeView.getCurrentTextColor() >>> 0).toString(16));
    });

    record("ordinary_xml_attr_still_uses_native_inflater", function () {
        must(Math.abs(nativeView.getAlpha() - 0.5) < 0.001,
            "alpha=" + nativeView.getAlpha());
    });

    record("native_lifecycle_order", function () {
        var life = nativeWidget.life;
        var created = indexOfLife(life, "viewCreated");
        var label = indexOfLife(life, "attr:probeLabel");
        var color = indexOfLife(life, "attr:probeColor");
        var finished = indexOfLife(life, "finish");
        must(life[0] === "render", "first lifecycle event is not render: " + life.join(","));
        must(created > 0, "onViewCreated missing");
        must(label > created && color > created, "custom attrs before onViewCreated");
        must(finished > label && finished > color,
            "onFinishInflation before custom attrs: " + life.join(","));
    });

    record("native_xml_w_h_layout_params", function () {
        var params = nativeView.getLayoutParams();
        must(params != null, "native view has no LayoutParams");
        must(params.width === expectedNativeWidth,
            "lp.width=" + params.width + ", expected " + expectedNativeWidth);
        must(params.height === expectedNativeHeight,
            "lp.height=" + params.height + ", expected " + expectedNativeHeight);
    });

    record("native_measured_size_after_layout", function () {
        must(nativeView.getMeasuredWidth() === expectedNativeWidth,
            "measuredWidth=" + nativeView.getMeasuredWidth());
        must(nativeView.getMeasuredHeight() === expectedNativeHeight,
            "measuredHeight=" + nativeView.getMeasuredHeight());
        must(nativeView.getWidth() === expectedNativeWidth,
            "width=" + nativeView.getWidth());
        must(nativeView.getHeight() === expectedNativeHeight,
            "height=" + nativeView.getHeight());
    });

    record("widget_view_and_ui_id_identity", function () {
        must(nativeWidget.view === ui.nativeProbe,
            "widget.view !== ui.nativeProbe");
        must(ui.nativeProbe === ui.findById("nativeProbe"),
            "ui.nativeProbe !== ui.findById('nativeProbe')");
        must(nativeWidget.createdView === nativeWidget.view,
            "onViewCreated received a different View");
    });

    record("on_finish_inflation_native", function () {
        must(nativeWidget.finishedCount === 1,
            "onFinishInflation count=" + nativeWidget.finishedCount);
        must(nativeWidget.finishedView === nativeView,
            "onFinishInflation received a different View");
        must(nativeWidget.finishedText === "native-view-ok",
            "onFinishInflation saw text=" + nativeWidget.finishedText);
        must(nativeWidget.finishedColor === expectedColor,
            "onFinishInflation saw a different color");
    });

    record("legacy_xml_render_still_inflates", function () {
        var className = String(legacyView.getClass().getName());
        must(className.indexOf("LinearLayout") >= 0,
            "legacy root class=" + className);
        must(legacyView.getChildCount() >= 1, "legacy root has no child");
        var child = legacyView.legacyText;
        must(child != null, "legacy child legacyText is missing");
        must(String(child.getText()) === "legacy-view-ok",
            "legacy child text=" + String(child.getText()));
    });

    record("legacy_lifecycle_and_method_from_on_view_created", function () {
        must(legacyWidget.viewCreatedCount === 1,
            "legacy onViewCreated count=" + legacyWidget.viewCreatedCount);
        must(legacyWidget.viewReadyInOnViewCreated,
            "legacy widget.view was not set before onViewCreated");
        var created = indexOfLife(legacyWidget.life, "viewCreated");
        var attr = indexOfLife(legacyWidget.life, "attr:legacyLabel");
        var finished = indexOfLife(legacyWidget.life, "finish");
        must(created >= 0, "legacy onViewCreated missing");
        must(attr > created, "legacy custom attr before onViewCreated");
        must(finished > attr, "legacy onFinishInflation before custom attr");
    });

    record("legacy_xml_w_h_layout_params_and_measured_size", function () {
        var params = legacyView.getLayoutParams();
        must(params != null, "legacy view has no LayoutParams");
        must(params.width === expectedLegacyWidth,
            "legacy lp.width=" + params.width + ", expected " + expectedLegacyWidth);
        must(params.height === expectedLegacyHeight,
            "legacy lp.height=" + params.height + ", expected " + expectedLegacyHeight);
        must(legacyView.getMeasuredWidth() === expectedLegacyWidth,
            "legacy measuredWidth=" + legacyView.getMeasuredWidth());
        must(legacyView.getMeasuredHeight() === expectedLegacyHeight,
            "legacy measuredHeight=" + legacyView.getMeasuredHeight());
    });

    record("legacy_widget_view_and_ui_id_identity", function () {
        must(legacyWidget.view === ui.legacyProbe,
            "legacy widget.view !== ui.legacyProbe");
        must(ui.legacyProbe === ui.findById("legacyProbe"),
            "ui.legacyProbe !== ui.findById('legacyProbe')");
        must(legacyWidget.createdView === legacyWidget.view,
            "legacy onViewCreated received a different View");
    });

    record("on_finish_inflation_legacy", function () {
        must(legacyWidget.finishedCount === 1,
            "legacy onFinishInflation count=" + legacyWidget.finishedCount);
        must(legacyWidget.finishedView === legacyView,
            "legacy onFinishInflation received a different View");
        must(legacyWidget.finishedText === "legacy-view-ok",
            "legacy onFinishInflation saw text=" + legacyWidget.finishedText);
    });
}

if (!completed) {
    try {
        waitForLayout(function (attempts) {
            try {
                runChecks(attempts);
            } catch (error) {
                result.checks.push({
                    name: "probe_runtime",
                    ok: false,
                    error: String(error && error.message ? error.message : error)
                });
            } finally {
                finish();
            }
        });
    } catch (error) {
        result.checks.push({
            name: "schedule_layout_checks",
            ok: false,
            error: String(error && error.message ? error.message : error)
        });
        finish();
    }
}
