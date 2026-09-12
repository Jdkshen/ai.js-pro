var global = this;

runtime.init();

(function () {
    //重定向importClass使得其支持字符串参数
    global.importClass =
        (function () {
            var __importClass__ = importClass;
            return function (pack) {
                if (typeof (pack) == "string") {
                    __importClass__(Packages[pack]);
                } else {
                    __importClass__(pack);
                }
            }
        })();

    //内部函数
    global.__asGlobal__ = function (obj, functions) {
        var len = functions.length;
        for (var i = 0; i < len; i++) {
            var funcName = functions[i];
            var func = obj[funcName]
            if (!func) {
                continue;
            }
            (function (obj, funcName, func) {
                global[funcName] = function () {
                    return func.apply(obj, arguments);
                };
            })(obj, funcName, func);
        }
    }

    global.__exitIfError__ = function (action, defReturnValue) {
        try {
            return action();
        } catch (err) {
            if (err instanceof java.lang.Throwable) {
                exit(err);
            } else if (err instanceof Error) {
                exit(new org.mozilla.javascript.EvaluatorException(err.name + ": " + err.message, err.fileName, err.lineNumber));
            } else {
                exit();
            }
            return defReturnValue;
        }
    };

     // 初始化基础模块
     global.timers = require('__timers__.js')(runtime, global);

     //初始化不依赖环境的模块
     global.JSON = require('__json2__.js');
     global.util = require('__util__.js');
     global.device = runtime.device;
     global.sqlite = runtime.sqlite;
     global.crypto = runtime.crypto;
     global.zips = runtime.zips;
     global.Promise = require('promise.js');
 
     //设置JavaScriptBridges用于与Java层的交互和数据转换
     runtime.bridges.setBridges(require('__bridges__.js'));

    // Auto.js Pro 的顶层脚本也能用 CommonJS 的 module / exports（模块内部的局部变量会遮蔽它）
    if (typeof module === 'undefined') {
        global.module = { exports: {} };
    }
    if (typeof exports === 'undefined') {
        global.exports = global.module.exports;
    }
    require("__globals__")(runtime, global);
    //初始化一般模块
    (function (scope) {
        var modules = ['app', 'automator', 'console', 'dialogs', 'io', 'selector', 'shell', 'web', 'ui',
            "images", "threads", "events", "engines", "RootAutomator", "http", "storages", "floaty",
            "sensors", "media", "plugins", "yolo", "continuation"];
        var len = modules.length;
        for (var i = 0; i < len; i++) {
            var m = modules[i];
            scope[m] = require('__' + m + '__')(scope.runtime, scope);
        }
        // Auto.js Pro exposes the UI module also as $ui; mirror that alias.
        scope.$ui = scope.ui;
        // Auto.js Pro 的 `$` 前缀模块名（$files / $images / $threads / $crypto ...）：
        // 给已有模块统一挂一份别名，Pro 示例可以不改一行直接跑。
        var aliasNames = ['app', 'automator', 'console', 'dialogs', 'ui', 'images', 'threads', 'events',
            'engines', 'http', 'storages', 'floaty', 'sensors', 'media', 'plugins', 'yolo', 'continuation',
            'shell', 'selector', 'web', 'io', 'timers', 'util', 'device', 'sqlite', 'crypto', 'zips',
            'files', 'RootAutomator'];
        for (var j = 0; j < aliasNames.length; j++) {
            var name = aliasNames[j];
            if (scope[name] !== undefined) {
                scope['$' + name] = scope[name];
            }
        }
        // App 模块注入的 Pro 风格模块（如 $work_manager）：由 App 侧 putProperty 注册，这里暴露成全局。
        var injected = ['work_manager'];
        for (var k = 0; k < injected.length; k++) {
            var injectedName = injected[k];
            var injectedModule = null;
            try {
                injectedModule = runtime.getProperty(injectedName);
            } catch (e) {
                injectedModule = null;
            }
            if (injectedModule) {
                scope[injectedName] = injectedModule;
                scope['$' + injectedName] = injectedModule;
            }
        }
    })(global);

    importClass(android.view.KeyEvent);
    importClass(com.stardust.autojs.core.util.Shell);
    importClass(android.graphics.Paint);
    Canvas = com.stardust.autojs.core.graphics.ScriptCanvas;
    Image = com.stardust.autojs.core.image.ImageWrapper;

    //重定向require以便支持相对路径和npm模块
    Module = require("jvm-npm.js");
    require = Module.require;


})();

