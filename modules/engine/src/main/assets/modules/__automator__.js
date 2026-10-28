
module.exports = function(runtime, global){
    var automator = {};
    // task-159：:script 远程模式判定（RemoteAccessibilityBridge.isRemote）。
    function __isRemote__() {
        try {
            return !!(runtime.accessibilityBridge && typeof runtime.accessibilityBridge.isRemote === 'function'
                && runtime.accessibilityBridge.isRemote());
        } catch (e) { return false; }
    }
    function __remoteSelector__() {
        return new com.stardust.autojs.runtime.api.remote.RemoteUiSelector();
    }

    function performAction(action, args){
        if(args.length == 4){
            return action(runtime.automator.bounds(args[0], args[1], args[2], args[3]));
        }else if(args.length == 2){
            return action(runtime.automator.text(args[0], args[1]));
        }else {
            return action(runtime.automator.text(args[0], -1));
        }
    }

    automator.click = function(){
        if(arguments.length == 2 && typeof(arguments[0]) == 'number' && typeof(arguments[1]) == 'number'){
            return runtime.automator.click(arguments[0], arguments[1]);
        }
        return performAction(function(target){
            return runtime.automator.click(target);
        }, arguments);
    }

    automator.longClick = function(a, b, c, d){
        if(arguments.length == 2 && typeof(arguments[0]) == 'number' && typeof(arguments[1]) == 'number'){
            return  runtime.automator.longClick(arguments[0], arguments[1]);
        }
        return performAction(function(target){
            return runtime.automator.longClick(target);
        }, arguments);
    }

     automator.press = runtime.automator.press.bind(runtime.automator);
     automator.gesture = runtime.automator.gesture.bind(runtime.automator, 0);
     automator.gestureAsync = runtime.automator.gestureAsync.bind(runtime.automator, 0);
     automator.swipe = runtime.automator.swipe.bind(runtime.automator);
     automator.gestures  = function(){
        return runtime.automator.gestures(toStrokes(arguments));
     }

     automator.gesturesAsync = function(){
         runtime.automator.gesturesAsync(toStrokes(arguments));
     }

     function toStrokes(args){
        var screenMetrics = runtime.getScreenMetrics();
        var len = args.length;
        var strokes = java.lang.reflect.Array.newInstance(android.accessibilityservice.GestureDescription.StrokeDescription, len);
        for(var i = 0; i < len; i++){
            var gesture = args[i];
            var pointsIndex = 1;
            if(typeof(gesture[1]) == 'number'){
                var start = gesture[0];
                var delay = gesture[1];
                pointsIndex = 2;
            }else{
                var start = 0;
                var delay = gesture[0];
            }
            var gestureLen = gesture.length;
            var path = new android.graphics.Path();
            path.moveTo(screenMetrics.scaleX(gesture[pointsIndex][0]), screenMetrics.scaleY(gesture[pointsIndex][1]));
            for(var j = pointsIndex + 1; j < gestureLen; j++){
                path.lineTo(screenMetrics.scaleX(gesture[j][0]), screenMetrics.scaleY(gesture[j][1]));
            }
            strokes[i] = new android.accessibilityservice.GestureDescription.StrokeDescription(path, start, delay);
        }
        return strokes;
     }

    automator.scrollDown = function(a, b, c, d){
        if(arguments.length == 0)
            return runtime.automator.scrollMaxForward();
        if(arguments.length == 1 && typeof a === 'number')
            return runtime.automator.scrollForward(a);
        return performAction(function(target){
            return runtime.automator.scrollForward(target);
        }, arguments);
    }

    automator.scrollUp = function(a, b, c, d){
         if(arguments.length == 0)
            return runtime.automator.scrollMaxBackward();
         if(arguments.length == 1 && typeof a === 'number')
            return runtime.automator.scrollBackward(a);
          return performAction(function(target){
            return runtime.automator.scrollBackward(target);
        }, arguments);
    }

    automator.setText = function(a, b){
        if(arguments.length == 1){
            return runtime.automator.setText(runtime.automator.editable(-1), a);
        }else{
            return runtime.automator.setText(runtime.automator.editable(a), b);
        }
    }

    // PDF §2.1.3.2（Pro 8.8.0 新增）：使用无障碍权限截图，返回 Image
    automator.takeScreenshot = function(){
        return runtime.automator.takeScreenshot();
    }

    // PDF §2.1.3.3（Pro 8.8.0 新增）：切换到指定包名的输入法
    automator.switchToInputMethod = function(packageName){
        return runtime.automator.switchToInputMethod(String(packageName));
    }

    // PDF §2.1.3.4（Pro 8.8.0 新增）：模拟耳机键（挂断/接听、播放/暂停）
    automator.headsetHook = function(){
        return runtime.automator.headsetHook();
    }

    automator.input = function(a, b){
        if(arguments.length == 1){
            return runtime.automator.appendText(runtime.automator.editable(-1), a);
        }else{
            return runtime.automator.appendText(runtime.automator.editable(a), b);
        }
    }

    var modes = {
        "normal": 0,
        "fast": 1
    }

    const flagsMap = {
        "findOnUiThread": 1,
        "useUsageStats": 2,
        "useShell": 4
    };

    var auto = function(mode){
        if(mode){
            global.auto.setMode(mode);
        }
        runtime.accessibilityBridge.ensureServiceEnabled();
    }

    auto.waitFor = function(){
        runtime.accessibilityBridge.waitForServiceEnabled();
    }

    // PDF §2.1.4（v9.0.14 新增）：清空无障碍缓存（控件已出现但选择器找不到时用）
    auto.clearCache = function(){
        runtime.accessibilityBridge.clearCache();
    }

    auto.setMode = function(modeStr){
        if(typeof(modeStr) !== "string"){
            throw new TypeError("mode should be a string");
        }
        let mode = modes[modeStr];
        if(mode == undefined){
            throw new Error("unknown mode for auto.setMode(): " + modeStr)
        }
        runtime.accessibilityBridge.setMode(mode);
    }

    auto.setFlags = function(flags){
        let flagStrings;
        if(Array.isArray(flags)){
            flagStrings = flags;
        } else if(typeof(flags) == "string"){
            flagStrings = [flags];
        } else {
            throw new TypeError("flags = " + flags);
        }
        let flagsInt = 0;
        for(let i = 0; i < flagStrings.length; i++){
            let flag = flagsMap[flagStrings[i]];
            if(flag == undefined){
                throw new Error("unknown flag for auto.setFlags(): " + flagStrings[i]);
            }
            flagsInt |= flag;
        }
        runtime.accessibilityBridge.setFlags(flagsInt);
    }

    auto.__defineGetter__("service", function() {
        var service = runtime.accessibilityBridge.getService();
        if (service != null) {
            return service;
        }
        // task-163：:script 进程没有本地 AccessibilityService，返回远程代理对象（非 null）。
        // 代理只覆盖常用面：enabled / 无障碍事件订阅 / openSettings；平台 Service 的
        // getWindows()、getServiceInfo() 等系统 binder 能力无法跨进程伪造（见报告"无法对齐项"）。
        if (__isRemote__()) {
            if (!auto.__remoteServiceProxy) {
                var proxy = {
                    __remoteProxy: true,
                    get enabled() {
                        try {
                            return com.stardust.autojs.runtime.api.remote.RemoteAutomationBridge
                                .isAccessibilityReady();
                        } catch (error) {
                            return false;
                        }
                    },
                    isEnabled: function() {
                        return proxy.enabled;
                    },
                    on: function(type, listener) {
                        runtime.events.on(type, listener);
                        return proxy;
                    },
                    once: function(type, listener) {
                        runtime.events.once(type, listener);
                        return proxy;
                    },
                    off: function(type, listener) {
                        runtime.events.removeListener(type, listener);
                        return proxy;
                    },
                    removeListener: function(type, listener) {
                        return proxy.off(type, listener);
                    },
                    openSettings: function() {
                        try {
                            app.startActivity({ action: 'android.settings.ACCESSIBILITY_SETTINGS' });
                            return true;
                        } catch (error) {
                            return false;
                        }
                    },
                    disableSelf: function() {
                        return false;
                    },
                    getServiceInfo: function() {
                        return { eventTypes: 0, flags: 0, notificationTimeout: 0 };
                    }
                };
                auto.__remoteServiceProxy = proxy;
            }
            return auto.__remoteServiceProxy;
        }
        return null;
    });

    auto.__defineGetter__("windows", function() {
        // task-159：主进程 dump 只有活动窗口，多窗口列表暂不可对齐（auto.windowRoots 可用）。
        if (__isRemote__()) { return []; }
        var service = auto.service;
        return service == null ? [] : util.java.toJsArray(service.getWindows(), true);
    });

    auto.__defineGetter__("root", function() {
        if (__isRemote__()) { return __remoteSelector__().root(); }
        var root = runtime.accessibilityBridge.getRootInCurrentWindow();
        if(root == null){
            return null;
        }
        return com.stardust.automator.UiObject.Companion.createRoot(root);
    });

    auto.__defineGetter__("rootInActiveWindow", function() {
        if (__isRemote__()) { return __remoteSelector__().root(); }
        var root = runtime.accessibilityBridge.getRootInActiveWindow();
        if(root == null){
            return null;
        }
        return com.stardust.automator.UiObject.Companion.createRoot(root);
    });

    auto.__defineGetter__("windowRoots", function() {
        if (__isRemote__()) {
            var collection = __remoteSelector__().windowRoots();
            var roots = [];
            for (var i = 0; i < collection.size(); i++) { roots.push(collection.get(i)); }
            return roots;
        }
        return util.java.toJsArray(runtime.accessibilityBridge.windowRoots(), false)
            .map(root => com.stardust.automator.UiObject.Companion.createRoot(root));
    });


    auto.setWindowFilter = function(filter){
        runtime.accessibilityBridge.setWindowFilter(new com.stardust.autojs.core.accessibility.AccessibilityBridge.WindowFilter(filter));
    }

    global.auto = auto;

    global.__asGlobal__(runtime.automator, ['back', 'home', 'powerDialog', 'notifications', 'quickSettings', 'recents', 'splitScreen']);
    global.__asGlobal__(automator, ['click', 'longClick', 'press', 'swipe', 'gesture', 'gestures', 'gestureAsync', 'gesturesAsync', 'scrollDown', 'scrollUp', 'input', 'setText']);

    // task-159：远程选择器链（text/desc/id/... -> RemoteUiSelector -> 主进程 findNodes）。
    if (__isRemote__()) {
        var remoteSelectorMethods = ['text', 'textContains', 'textStartsWith', 'textEndsWith', 'textMatches',
            'desc', 'descContains', 'descStartsWith', 'descEndsWith', 'descMatches',
            'id', 'idContains', 'idStartsWith', 'idEndsWith', 'idMatches',
            'className', 'classNameContains', 'classNameStartsWith', 'classNameEndsWith', 'classNameMatches',
            'packageName', 'packageNameContains', 'packageNameStartsWith', 'packageNameEndsWith', 'packageNameMatches',
            'clickable', 'enabled', 'scrollable', 'depth', 'bounds', 'algorithm', 'filter', 'addFilter'];
        global.selector = function () { return __remoteSelector__(); };
        remoteSelectorMethods.forEach(function (method) {
            global[method] = function () {
                var selector = __remoteSelector__();
                if (typeof selector[method] === 'function') {
                    var value = selector[method].apply(selector, arguments);
                    return value === undefined ? selector : value;
                }
                return selector;
            };
        });
        global.$remoteAccessibility = {
            isRemote: function () { return true; },
            screenshot: function (path) { return runtime.accessibilityBridge.screenshotTo(String(path)); }
        };
    }

    return automator;
}


