
module.exports = function (runtime, scope) {
    var rtConsole = runtime.console;
    var console = {};

    console.assert = function (value, message) {
        message = message || "";
        rtConsole.assertTrue(value, message);
    }

    console.rawInput = rtConsole.rawInput.bind(rtConsole);

    console.input = function (data, param) {
        return eval(console.rawInput.call(console, [].slice(arguments)) + "");
    }

    console.log = function () {
        rtConsole.log(util.format.apply(util, arguments));
    }

    console.verbose = function () {
        rtConsole.verbose(util.format.apply(util, arguments));
    }

    console.print = function () {
        rtConsole.print(android.util.Log.DEBUG, util.format.apply(util, arguments));
    }

    console.info = function () {
        rtConsole.info(util.format.apply(util, arguments));
    }

    console.warn = function () {
        rtConsole.warn(util.format.apply(util, arguments));
    }

    console.error = function () {
        rtConsole.error(util.format.apply(util, arguments));
    }

    var timers = {}, ascu = android.os.SystemClock.uptimeMillis;
    console.time = console.time || function (label) {
        label = label || "default";
        timers[label] = ascu();
    }

    console.timeEnd = console.timeEnd || function (label) {
        label = label || "default";
        var result = ascu() - timers[label];
        delete timers[label];
        console.log(label + ": " + result + "ms");
    }

    console.trace = console.trace || function captureStack(message) {
        var k = {};
        Error.captureStackTrace(k, captureStack);
        console.log(util.format.apply(util, arguments) + "\n" + k.stack);
    };

    // log4j-android（de.mindpipe…）并非所有运行时都打包。缺它时旧实现会直接抛
    // "TypeError: [JavaPackage …] is not a function, it is object"，把整个脚本打断
    // （真实用户脚本 华仔部落_佣兵战纪 就死在这一行）。现在：有 log4j 照旧配置，
    // 没有就降级为内置日志并提示一次，绝不因为日志配置让脚本挂掉。
    console.setGlobalLogConfig = function (config) {
        var LogConfiguratorClass = null;
        try {
            var log4jPackage = Packages["de.mindpipe.android.logging.log4j"];
            if (log4jPackage) {
                LogConfiguratorClass = log4jPackage.LogConfigurator;
            }
        } catch (e) {
            LogConfiguratorClass = null;
        }
        if (LogConfiguratorClass) {
            try {
                var logConfigurator = new LogConfiguratorClass();
                if (config.file) {
                    logConfigurator.setFileName(files.path(config.file));
                    logConfigurator.setUseFileAppender(true);
                }
                logConfigurator.setFilePattern(option(config.filePattern, "%m%n"))
                logConfigurator.setMaxFileSize(option(config.maxFileSize, 512 * 1024));
                logConfigurator.setImmediateFlush(option(config.immediateFlush, true));
                var rootLevel = option(config.rootLevel, "ALL");
                var levelClass = null;
                try { levelClass = org.apache.log4j.Level; } catch (e2) { levelClass = null; }
                if (levelClass) {
                    logConfigurator.setRootLevel(levelClass[rootLevel.toUpperCase()]);
                }
                logConfigurator.setMaxBackupSize(option(config.maxBackupSize, 5));
                logConfigurator.setResetConfiguration(option(config.resetConfiguration, true));
                logConfigurator.configure();
                return;
            } catch (e3) {
                // 有类但配置失败：继续走降级路径，不打断脚本。
            }
        }
        try {
            var fallbackFile = (config && config.file) ? files.path(config.file) : null;
            if (fallbackFile) {
                var target = new java.io.File(fallbackFile);
                var parent = target.getParentFile();
                if (parent && !parent.exists()) { parent.mkdirs(); }
            }
            scope.__aijsLogConfig = {
                level: (config && config.rootLevel) ? String(config.rootLevel).toUpperCase() : "ALL",
                file: fallbackFile,
                degraded: true,
                reason: "log4j (de.mindpipe.android.logging.log4j.LogConfigurator) 在当前运行时不可用"
            };
            if (typeof rtConsole !== "undefined" && rtConsole && rtConsole.warn) {
                rtConsole.warn("console.setGlobalLogConfig 已降级：当前运行时没有 log4j" +
                    (fallbackFile ? "（已记录文件路径 " + fallbackFile + "；日志仍可在应用内日志页查看）" : "（日志仍可在应用内日志页查看）"));
            }
        } catch (e4) {
            // 降级路径也失败时保持静默：日志配置不应该让脚本挂掉。
        }
    }

    function option(value, def) {
        return value == undefined ? def : value;
    }

    console.show = rtConsole.show.bind(rtConsole);
    console.hide = rtConsole.hide.bind(rtConsole);
    console.clear = rtConsole.clear.bind(rtConsole);
    console.setSize = rtConsole.setSize.bind(rtConsole);
    console.setPosition = rtConsole.setPosition.bind(rtConsole);
    console.setTitle = rtConsole.setTitle.bind(rtConsole);

    scope.print = console.print.bind(console);
    scope.log = console.log.bind(console);
    scope.err = console.error.bind(console);
    scope.openConsole = console.show.bind(console);
    scope.clearConsole = console.clear.bind(console);

    return console;
}