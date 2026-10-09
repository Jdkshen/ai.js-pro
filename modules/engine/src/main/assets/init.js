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
     require('rhino-map.js')(global);
     global.JSON = require('__json2__.js');
     global.util = require('__util__.js');
     global.device = runtime.device;
     global.sqlite = runtime.sqlite;
     global.crypto = runtime.crypto;
     global.zips = runtime.zips;    // Auto.js Pro 的 $debug（文档 2.9）：内存 dump / 堆栈 / 内存泄露检测
    global.$debug = runtime.debug;
    // Auto.js Pro 的 $power_manager（文档 2.24）：忽略电池优化
    global.$power_manager = runtime.powerManager;     // Auto.js Pro 的 $crypto（文档 2.8）：Java 侧只负责算法，这里把结果包装成文档里的
     // 对象形状（Key 带 data/algorithm/format，KeyPair 带 publicKey/privateKey）。
     (function (crypto) {
         function Key(data, options) {
             if (!(this instanceof Key)) {
                 return new Key(data, options);
             }
             options = options || {};
             this.input = options.input || null;
             if (data && typeof data.getEncoded === 'function' && typeof data.getAlgorithm === 'function') {
                 this.__key__ = data;
                 this.data = data.getEncoded();
                 this.algorithm = data.getAlgorithm();
                 this.format = data.getFormat();
             } else {
                 this.data = data;
                 this.algorithm = options.algorithm || null;
                 this.format = options.format || null;
             }
         }
         function KeyPair(publicKey, privateKey, options) {
             if (!(this instanceof KeyPair)) {
                 return new KeyPair(publicKey, privateKey, options);
             }
             this.publicKey = (publicKey instanceof Key) ? publicKey : new Key(publicKey, options);
             this.privateKey = (privateKey instanceof Key) ? privateKey : new Key(privateKey, options);
         }
         crypto.Key = Key;
         crypto.KeyPair = KeyPair;
         var javaGenerateKeyPair = crypto.__generateKeyPair;
         crypto.generateKeyPair = function (algorithm, length) {
             var pair = javaGenerateKeyPair.call(crypto, algorithm, length);
             return new KeyPair(new Key(pair.getPublic()), new Key(pair.getPrivate()));
         };
     })(global.crypto);
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
            "sensors", "media", "plugins", "yolo", "ocr", "continuation"];
        var len = modules.length;
        for (var i = 0; i < len; i++) {
            var m = modules[i];
            scope[m] = require('__' + m + '__')(scope.runtime, scope);
        }
        // Auto.js Pro exposes the UI module also as $ui; mirror that alias.
        scope.$ui = scope.ui;
        // Pro 文档的压缩模块叫 `$zip`（不是 `$zips`），另外把 ZipFile 类暴露成全局名。
        if (scope.zips !== undefined) {
            scope.$zip = scope.zips;
        }
        // Auto.js Pro 的 `$` 前缀模块名（$files / $images / $threads / $crypto ...）：
        // 给已有模块统一挂一份别名，Pro 示例可以不改一行直接跑。
        var aliasNames = ['app', 'automator', 'console', 'dialogs', 'ui', 'images', 'threads', 'events',
            'engines', 'http', 'storages', 'floaty', 'sensors', 'media', 'plugins', 'yolo', 'continuation',
            'shell', 'selector', 'web', 'io', 'timers', 'util', 'device', 'sqlite', 'crypto', 'zips',
            'files', 'RootAutomator', 'colors', 'ocr'];
        for (var j = 0; j < aliasNames.length; j++) {
            var name = aliasNames[j];
            if (scope[name] !== undefined) {
                scope['$' + name] = scope[name];
            }
        }
        // PDF §2.18.8：文档用法是 `let ColorMapping = $colors.mapping;`，之后要能
        // `ColorMapping.singleton` / `new ColorMapping()`。Java 字段里存 Class 拿到的只是
        // 一个包装过的 Class（不能 new），所以 $colors 这里用一层 JS 对象：
        // 其余成员照旧走原型链，不受影响。
        importClass(com.stardust.autojs.core.image.ColorMapping);
        if (scope.colors !== undefined) {
            var colorsProxy = Object.create(scope.colors);
            colorsProxy.mapping = ColorMapping;
            scope.$colors = colorsProxy;
        }
        // App 模块注入的 Pro 风格模块（如 $work_manager）：由 App 侧 putProperty 注册，这里暴露成全局。
        var injected = ['work_manager', 'settings'];
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
    importClass(com.stardust.autojs.runtime.api.ZipFile);
    importClass(android.graphics.Paint);
    Canvas = com.stardust.autojs.core.graphics.ScriptCanvas;
    Image = com.stardust.autojs.core.image.ImageWrapper;

    //重定向require以便支持相对路径和npm模块
    Module = require("jvm-npm.js");
    require = Module.require;


})();

