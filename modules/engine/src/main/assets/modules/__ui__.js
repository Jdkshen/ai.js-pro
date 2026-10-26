module.exports = function (runtime, global) {

    require("object-observe-lite.min")();
    require("array-observe.min")();

    var J = util.java;
    var ui = {};
    var _projectR = null;

    ui.__widgets__ = {};

    ui.__defineGetter__("emitter", ()=>  activity ? activity.getEventEmitter() : null);

    function ensureProjectResources() {
        if (!_projectR || !_projectR.drawable || Object.keys(_projectR.drawable).length === 0) {
            _projectR = null;
            try { ui.useAndroidResources(); } catch (e) { if (!_projectR) _projectR = buildHostUiR(); }
        }
    }

    /** Pro 工程未带资源表时，仍允许 ui.R 使用宿主内置 Auto.js 图标。 */
    function buildHostUiR() {
        var R = { drawable: {}, string: {}, layout: {}, id: {} };
        try {
            var resources = context.getResources();
            var names = ["ic_accessibility_black_48dp","ic_account_circle_black_48dp","ic_add_circle_outline_black_48dp","ic_alarm_black_48dp","ic_bug_report_black_48dp","ic_card_giftcard_black_48dp","ic_change_history_black_48dp","ic_clear_all_black_48dp","ic_color_lens_black_48dp","ic_done_all_black_48dp","ic_error_outline_black_48dp","ic_grade_black_48dp","ic_launch_black_48dp","ic_photo_size_select_large_black_48dp","ic_play_arrow_black_48dp","ic_speaker_notes_off_black_48dp","ic_stop_black_48dp","ic_subject_black_48dp","ic_volume_down_black_48dp","ic_whatshot_black_48dp"];
            for (var i = 0; i < names.length; i++) {
                var id = resources.getIdentifier(names[i], "drawable", context.getPackageName());
                if (id) R.drawable[names[i]] = id;
            }
        } catch (e) {}
        R.string.openDrawerContentDesc = 0;
        return R;
    }

    ui.layout = function (xml) {
        ensureProjectResources();
        if(typeof(activity) == 'undefined'){
            throw new Error("需要在ui模式下运行才能使用该函数");
        }
        runtime.ui.layoutInflater.setContext(activity);
        var view = runtime.ui.layoutInflater.inflate(xml, activity.window.decorView, false);
        ui.setContentView(view);
    }

    /**
     * 与 Pro 语义一致：工程模式下 {@code ui.layoutFile("main")} 按资源名找
     * {@code res/layout/main.xml}；其他情况还是「把参数当文件路径读进来」。
     */
    ui.layoutFile = function(file) {
        ensureProjectResources();
        var path = null;
        if (ui.__projectResources__ && typeof(file) == 'string') {
            path = ui.__projectResources__.layoutFile(file);
        }
        ui.layout(files.read(path == null ? file : path));
    }

    ui.inflate = function(xml, parent, attachToParent){
        ensureProjectResources();
        if(typeof(xml) == 'xml'){
            xml = xml.toXMLString();
        }
        parent = parent || null;
        attachToParent = !!attachToParent;
        let ctx;
        if(typeof(activity) == 'undefined') {
            ctx = new android.view.ContextThemeWrapper(context, com.stardust.autojs.R.style.ScriptTheme);
        } else {
            ctx = activity;
        }
        runtime.ui.layoutInflater.setContext(ctx);
        return runtime.ui.layoutInflater.inflate(xml.toString(), parent, attachToParent);
    }

    ui.__inflate__ = function(ctx, xml, parent, attachToParent){
        if(typeof(xml) == 'xml'){
            xml = xml.toXMLString();
        }
        parent = parent || null;
        attachToParent = !!attachToParent;
        return runtime.ui.layoutInflater.inflate(ctx, xml.toString(), parent, attachToParent);
    }

    ui.registerWidget = function(name, widget){
        if(typeof(widget) !== 'function'){
            throw new TypeError('widget should be a class-like function');
        }
        ui.__widgets__[name] = widget;
    }

    ui.setContentView = function (view) {
        ui.view = view;
        ui.run(function () {
            activity.setContentView(view);
        });
    }

    ui.findById = function (id) {
        if (!ui.view)
            return null;
        return ui.findByStringId(ui.view, id);
    }

    ui.findView = function(id) {
        return ui.findById(id);
    }

    ui.isUiThread = function () {
        let Looper = android.os.Looper;
        return Looper.myLooper() == Looper.getMainLooper();
    }

    ui.run = function (action) {
        if (ui.isUiThread()) {
            return action();
        }
        var err = null;
        var result;
        var disposable = global.threads.disposable();
        runtime.uiHandler.post(function () {
            try {
                result = action();
                disposable.setAndNotify(true);
            } catch (e) {
                err = e;
                disposable.setAndNotify(true);
            }
        });
        disposable.blockedGet();
        if (err) {
            throw err;
        }
        return result;
    }

    ui.post = function (action, delay) {
        if(delay == undefined){
            runtime.getUiHandler().post(wrapUiAction(action));
        }else{
            runtime.getUiHandler().postDelayed(wrapUiAction(action), delay);
        }
    }

    ui.statusBarColor = function (color) {
        if (typeof (color) == 'string') {
            color = android.graphics.Color.parseColor(color);
        }
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            ui.run(function () {
                activity.getWindow().setStatusBarColor(color);
            });
        }
    }

    ui.finish = function () {
        ui.run(function () {
            activity.finish();
        });
    }

    ui.findByStringId = function (view, id) {
        return com.stardust.autojs.core.ui.JsViewHelper.findViewByStringId(view, id);
    }

    /**
     * 启用工程模式（project.json 的 androidResources）的 Android 资源特性。
     *
     * 资源表由引擎侧 com.stardust.autojs.core.ui.inflater.ProjectResources 扫 res/ 目录生成，
     * ui.R 暴露的是**合成 id**（0x7e 段）—— 本引擎没有 aapt2，拿不到真 resource id，
     * 所以布局里的 @color/@string/@dimen/@drawable 引用由 inflater 自己按名字解析。
     * 需要真 id 的 framework 调用点（Resources.getColor、AnimationUtils.loadAnimation）见下面的垫片。
     */
    ui.useAndroidResources = function () {
        if (_projectR) {
            return _projectR;
        }
        var info = runtime.ui.__androidResources__;
        if (!info || !info.resources) {
            throw new Error('ui.useAndroidResources() 需要在工程模式下运行'
                    + (info && info.error ? '：' + info.error : ''));
        }
        ui.__projectResources__ = info.resources;
        _projectR = buildProjectR(info.resources);
        ui.R = _projectR;
        installProjectActivityShim(info.resources);
        return ui.R;
    };

    // Auto.js Pro 兼容：工程声明 androidResources 时，第一次读取 ui.R 自动加载资源表。
    ui.__defineGetter__("R", function () {
        if (!_projectR || !_projectR.drawable || Object.keys(_projectR.drawable).length === 0) {
            _projectR = null;
            try {
                ui.useAndroidResources();
            } catch (e) {
                // 普通脚本/原 Pro 工程没有资源表：回退到宿主内置图标，避免 ui.R 为 null。
                if (!_projectR) _projectR = buildHostUiR();
            }
        }
        return _projectR;
    });
    ui.__defineSetter__("R", function (value) { _projectR = value; });

    /** 把资源表摊成 Pro 那种 ui.R 形状：{ color: { mainColorPrimary: 0x7e000001 }, ... } */
    function buildProjectR(res) {
        var R = {};
        var types = res.types();
        for (var i = 0; i < types.length; i++) {
            var type = types[i];
            var names = res.names(type);
            var table = {};
            for (var j = 0; j < names.length; j++) {
                table[names[j]] = res.idOf(type, names[j]);
            }
            R[type] = table;
        }
        // Auto.js Pro compatibility: original projects use host built-in icons via ui.R.drawable.
        var drawableNames = ["ic_accessibility_black_48dp","ic_account_circle_black_48dp","ic_add_circle_outline_black_48dp","ic_alarm_black_48dp","ic_bug_report_black_48dp","ic_card_giftcard_black_48dp","ic_change_history_black_48dp","ic_clear_all_black_48dp","ic_color_lens_black_48dp","ic_done_all_black_48dp","ic_error_outline_black_48dp","ic_grade_black_48dp","ic_launch_black_48dp","ic_photo_size_select_large_black_48dp","ic_play_arrow_black_48dp","ic_speaker_notes_off_black_48dp","ic_stop_black_48dp","ic_subject_black_48dp","ic_volume_down_black_48dp","ic_whatshot_black_48dp"];
        if (!R.drawable) R.drawable = {};
        var hostResources = context.getResources();
        for (var k = 0; k < drawableNames.length; k++) {
            if (R.drawable[drawableNames[k]] === undefined) {
                var hostId = hostResources.getIdentifier(drawableNames[k], "drawable", context.getPackageName());
                if (hostId) R.drawable[drawableNames[k]] = hostId;
            }
        }
        if (!R.string) R.string = {};
        if (R.string.openDrawerContentDesc === undefined) R.string.openDrawerContentDesc = 0;
        return R;
    }

    /**
     * 工程模式的 activity 垫片。
     *
     * 合成 id 交给 framework 就失效了，而下面这几处几乎绕不开（脚本直接写在表达式里）：
     *   activity.resources.getColor(ui.R.color.x)
     *   activity.theme.applyStyle(ui.R.style.x, true)
     * 所以把 activity 换成一个「脚本侧覆盖优先」的代理（引擎侧 OverridingJavaObject），
     * 只拦需要按名字查表的几个方法，其余成员照旧落到真 Activity 上。
     */
    function installProjectActivityShim(res) {
        if (global.__activityOverridden__) {
            return;
        }
        var real = global.activity;
        if (real === undefined || real === null || typeof (real.getClass) != 'function') {
            // 不在 "ui"; 模式（没有宿主 Activity）时不用垫：工程资源的引用由 inflater 自己解析。
            return;
        }
        var Overriding = Packages.com.stardust.autojs.rhino.OverridingJavaObject;

        var resources = Overriding.of(global, real.resources);
        resources.__overrides__ = {
            getColor: function (id) {
                var color = res.colorById(id);
                return color === null ? real.resources.getColor(id) : color;
            },
            getColorStateList: function (id) {
                var color = res.colorById(id);
                return color === null ? real.resources.getColorStateList(id)
                        : android.content.res.ColorStateList.valueOf(color);
            },
            getString: function (id) {
                var text = res.stringById(id);
                return text === null ? real.resources.getString(id) : text;
            },
            getDimension: function (id) {
                var dimen = res.dimensionById(id);
                return dimen === null ? real.resources.getDimension(id)
                        : parseProjectDimension(dimen, real.resources.displayMetrics.density);
            }
        };

        var theme = Overriding.of(global, real.theme);
        theme.__overrides__ = {
            applyStyle: function (id, force) {
                var style = res.styleById(id);
                if (style === null) {
                    return real.theme.applyStyle(id, force);
                }
                return applyProjectStyle(style, res);
            }
        };

        var activity = Overriding.of(global, real);
        activity.__overrides__ = {
            resources: resources,
            theme: theme
        };
        global.activity = activity;
        global.__activityOverridden__ = true;
    }

    /**
     * 套用工程 style。
     *
     * 真资源由 aapt2 编进 theme，这里只能把「知道该落到哪」的属性落地：颜色类的
     * colorPrimary / colorPrimaryDark / colorAccent 记在 ui.projectTheme 上，
     * 并顺手刷一下状态栏（与 Pro 观感最接近的那一项）。
     */
    function applyProjectStyle(style, res) {
        var applied = {};
        var items = style.items();
        for (var i = 0; i < items.length; i++) {
            var color = projectColorOf(style.getItem(items[i]), res);
            if (color !== null) {
                applied[items[i]] = color;
            }
        }
        ui.projectStyleName = style.getName();
        ui.projectTheme = applied;
        var dark = applied.colorPrimaryDark !== undefined ? applied.colorPrimaryDark : applied.colorPrimary;
        if (dark !== undefined) {
            ui.statusBarColor(dark);
        }
        return applied;
    }

    /** style item 的值可能写成 @color/x，也可能是色值字面量。 */
    function projectColorOf(value, res) {
        if (typeof (value) != 'string') {
            return null;
        }
        var text = value.trim();
        if (text.indexOf('@color/') === 0) {
            return res.colorOf(text.substring('@color/'.length));
        }
        if (text.charAt(0) == '#') {
            return android.graphics.Color.parseColor(text);
        }
        if (text.indexOf('@android:color/') === 0) {
            return null;    // framework 颜色：让原来的路径去解析
        }
        return null;
    }

    /** 尺寸资源（"16dp" 等）→ 像素；只覆盖常用单位。 */
    function parseProjectDimension(value, density) {
        var matched = /^([0-9.]+)\s*([a-z%]*)$/.exec(String(value).trim());
        if (!matched) {
            return 0;
        }
        var number = parseFloat(matched[1]);
        switch (matched[2]) {
            case 'dp':
            case 'dip':
                return number * density;
            case 'sp':
                return number * density;
            case 'in':
                return number * density * 160;
            case 'mm':
                return number / 25.4 * density * 160;
            case 'pt':
                return number / 72 * density * 160;
            default:
                return number;      // px 或无单位
        }
    }

    runtime.ui.bindingContext = global;
    var layoutInflater = runtime.ui.layoutInflater;
    layoutInflater.setLayoutInflaterDelegate({
        beforeConvertXml: function (context, xml) {
            return null;
        },
        afterConvertXml: function (context, xml) {
            return xml;
        },
        afterInflation: function (context, result, xml, parent) {
            return result;
        },
        beforeInflation: function (context, xml, parent) {
            return null;
        },
        beforeInflateView: function (context, node, parent, attachToParent) {
            return null;
        },
        afterInflateView: function (context, view, node, parent, attachToParent) {
            let widget = view.widget;
            if(widget && context.get("root") != widget){
                widget.notifyAfterInflation(view);
            }
            return view;
        },
        beforeCreateView: function (context, node, viewName, parent, attrs) {
            if(ui.__widgets__.hasOwnProperty(viewName)){
                let Widget = ui.__widgets__[viewName];
                let widget = new Widget();
                let rendered = widget.renderInternal();
                if (rendered instanceof android.view.View) {
                    // Pro widgets may return an existing native/JavaAdapter View.
                    // beforeCreateView bypasses the normal afterCreateView callback.
                    // Bind methods/delegates before the outer attributes are applied.
                    context.put("widget", widget);
                    return this.afterCreateView(context, rendered, node, viewName, parent, attrs);
                }
                let ctx = layoutInflater.newInflateContext();
                ctx.put("root", widget);
                ctx.put("widget", widget);
                let view = ui.__inflate__(ctx, rendered, parent, false);
                return view;
            };
            return null;
        },
        afterCreateView: function (context, view, node, viewName, parent, attrs) {
            if (view.getClass().getName() == "com.stardust.autojs.core.ui.widget.JsListView" ||
                view.getClass().getName() == "com.stardust.autojs.core.ui.widget.JsGridView") {
                initListView(view);
            }
            var widget = context.get("widget");
            if(widget != null){
                widget.view = view;
                view.widget = widget;
                let viewAttrs = com.stardust.autojs.core.ui.ViewExtras.getViewAttributes(view, layoutInflater.resourceParser);
                viewAttrs.setViewAttributeDelegate({
                    has: function(name) {
                        return widget.hasAttr(name);
                    },
                    get: function(view, name, getter){
                        return widget.getAttr(view, name, getter);
                    },
                    set: function(view, name, value, setter) {
                        widget.setAttr(view, name, value, setter);
                    }
                });
                widget.notifyViewCreated(view);
            }
            return view;
        },
        beforeApplyAttributes: function (context, view, inflater, attrs, parent) {
            return false;
        },
        afterApplyAttributes: function (context, view, inflater, attrs, parent) {
            context.remove("widget");
        },
        beforeInflateChildren: function (context, inflater, node, parent) {
            return false;
        },
        afterInflateChildren: function (context, inflater, node, parent) {

        },
        afterApplyPendingAttributesOfChildren: function (context, inflater, view) {

        },
        beforeApplyPendingAttributesOfChildren: function (context, inflater, view) {
            return false;
        },
        beforeApplyAttribute: function (context, inflater, view, ns, attrName, value, parent, attrs) {
            var isDynamic = layoutInflater.isDynamicValue(value);
            if ((isDynamic && layoutInflater.getInflateFlags() == layoutInflater.FLAG_IGNORES_DYNAMIC_ATTRS)
                    || (!isDynamic && layoutInflater.getInflateFlags() == layoutInflater.FLAG_JUST_DYNAMIC_ATTRS)) {
                return true;
            }
            value = bind(value);
            let widget = context.get("widget");
            if(widget != null && widget.hasAttr(attrName)){
                widget.setAttr(view, attrName, value, (view, attrName, value)=>{
                    inflater.setAttr(view, ns, attrName, value, parent, attrs);
                });
            } else {
                inflater.setAttr(view, ns, attrName, value, parent, attrs);
            }
            this.afterApplyAttribute(context, inflater, view, ns, attrName, value, parent, attrs);
            return true;
        },
        afterApplyAttribute: function (context, inflater, view, ns, attrName, value, parent, attrs) {

        }
    });

    function bind(value) {
        var ctx = runtime.ui.bindingContext;
        if (ctx == null)
            return;
        var i = -1;
        while ((i = value.indexOf("{{", i + 1)) >= 0) {
            var j = value.indexOf("}}", i + 1);
            if (j < 0)
                return value;
            value = value.substring(0, i) + evalInContext(value.substring(i + 2, j), ctx) + value.substring(j + 2);
            i = j + 1;
        }
        return value;
    }

    function evalInContext(expr, ctx) {
        return global.__exitIfError__(function() {
            with(ctx) {
                return (function(){
                   return eval(expr);
                }).call(ctx);
            }
        });
    }

    function initListView(list) {
        list.setDataSourceAdapter({
            getItemCount: function (data) {
                return data.length;
            },
            getItem: function (data, i) {
                return data[i];
            },
            setDataSource: function (data) {
                // 脚本写 setDataSource(data, false) 时是「手动模式」：改完自己调 adapter.notifyItemXxx()。
                // 以前这里无条件装 Array.observe（object-observe 的 polyfill），于是
                // `Array.prototype.push.apply(items, 一万条)` 会逐条产生变更记录、再来回 indexOf
                // 查找（O(n²)，实测主线程 2.2 秒＋之后持续 100% CPU、界面完全不出帧）。
                if (!list.isDataSourceAutoNotify() || !Array.isArray(data)) {
                    return;
                }
                var adapter = list.getAdapter();
                Array.observe(data, function (changes) {
                    changes.forEach(change => {
                        if (change.type == 'splice') {
                            if (change.removed && change.removed.length > 0) {
                                adapter.notifyItemRangeRemoved(change.index, change.removed.length);
                            }
                            if (change.addedCount > 0) {
                                adapter.notifyItemRangeInserted(change.index, change.addedCount);
                            }
                        } else if (change.type == 'update') {
                            try{
                                adapter.notifyItemChanged(parseInt(change.name));
                            }catch(e){}
                        }
                    });
                });
            }
        });
    }

    function wrapUiAction(action, defReturnValue) {
        if (typeof (activity) != 'undefined') {
            return function () { return action(); };
        }
        return function () {
            return __exitIfError__(action, defReturnValue);
        }
    }

    ui.Widget = (function(){
        function Widget(){
            this.__attrs__ = {};
        }
        Widget.prototype.renderInternal = function(){
            if(typeof(this.render) === 'function'){
                return this.render();
            }
            return (< />)
        };
        Widget.prototype.defineAttr = function(attrName, getter, setter){
            var attrAlias = attrName;
            var applier = null;
            if(typeof(arguments[1]) == 'string'){
                attrAlias = arguments[1];
                if(arguments.length >= 3){
                    applier = arguments[2];
                }
            } else if(typeof(arguments[1]) == 'function' && typeof(arguments[2]) != 'function'){
                applier = arguments[1];
            }
            if(!(typeof(arguments[1]) == 'function' && typeof(arguments[2]) == 'function')){
                getter = ()=> {
                    return this[attrAlias];
                };
                setter = (view, attrName, value, setter)=> {
                    this[attrAlias] = value;
                    applier && applier(view, attrName, value, setter);
                };
            }
            this.__attrs__[attrName] = {
                getter: getter,
                setter: setter
            };
        };
        Widget.prototype.hasAttr = function(attrName){
            return this.__attrs__.hasOwnProperty(attrName);
        };
        Widget.prototype.setAttr = function(view, attrName, value, setter){
            this.__attrs__[attrName].setter(view, attrName, value, setter);
        };
        Widget.prototype.getAttr = function(view, attrName, getter){
            return this.__attrs__[attrName].getter(view, attrName, getter);
        };
        Widget.prototype.notifyViewCreated = function(view){
            if(typeof(this.onViewCreated) == 'function'){
                this.onViewCreated(view);
            }
        };
        Widget.prototype.notifyAfterInflation = function(view){
            if(typeof(this.onFinishInflation) == 'function'){
                this.onFinishInflation(view);
            }
        }
        return Widget;
    })();

    require("ui-accessibility")(ui, global);

    var proxy = runtime.ui;
    proxy.__proxy__ = {
        set: function (name, value) {
            ui[name] = value;
        },
        get: function (name) {
            if (!ui[name] && ui.view) {
                let v = ui.findById(name);
                if (v) {
                    return v;
                }
            }
            return ui[name];
        }
    };


    return proxy;
}

// Eager Pro resource compatibility: project androidResources should be available to modules too.
try {
    if (!_projectR) {
        ui.useAndroidResources();
    }
} catch (e) {
    // Non-project UI scripts have no resources; keep them running normally.
}
