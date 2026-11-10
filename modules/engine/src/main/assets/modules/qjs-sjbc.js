/**
 * QuickJS 侧的 `sjbc` 入口（与 Rhino 的 `modules/__globals__.js` 里那段**同源**：只调 SjbcApi，不复制实现）。
 *
 * 宿主 op：`QuickJsHostBridge.sjbcCall(op, path, entryIndex)` → **JSON 字符串**（白名单只有 run / runEntry，不做任意反射）。
 * JS 侧 `JSON.parse` 后即可**按属性访问**：`r.steps` / `r.prints[0]` / `r.ok`（prints 是真 JS 数组）。
 *
 * task-89：`forEngine(id)` 在「id 取不到 / 该 id 没注册」时会抛 `IllegalStateException: 脚本引擎已经结束`
 * （`QuickJsHostBridge.java:317-323` 查 `sActiveBridges`，注册发生在 `setOwnerEngineId`，见 :304-314）。
 * 这里把失败**点名为可诊断的错误**并给出 `sjbc.debug()`，避免只看到一句「引擎已经结束」查不动。
 */
(function (g) {
    'use strict';

    function engineId() {
        try {
            if (g.engines && typeof g.engines.myEngine === 'function') {
                var id = Number(g.engines.myEngine().id);
                return isFinite(id) ? id : -1;
            }
        } catch (ignored) {
        }
        return -1;
    }

    function host() {
        var Java = g.Java;
        if (!Java || typeof Java.type !== 'function') {
            throw new Error('sjbc 需要 QuickJS Java 桥（当前宿主不可用）');
        }
        var id = engineId();
        var bridge = Java.type('com.stardust.autojs.engine.QuickJsHostBridge');
        try {
            return bridge.forEngine(id);
        } catch (firstError) {
            // task-89：id 取不到/未注册时退回「唯一活动 bridge」（多 engine 并存时不猜，直接报错）。
            try {
                return bridge.forCurrentEngine();
            } catch (secondError) {
                throw new Error('sjbc 入口不可用：forEngine(' + id + ') 与 forCurrentEngine() 都失败（'
                        + (id <= 0 ? 'engines.myEngine().id 取不到' : '该引擎 id 未注册')
                        + '，activeBridgeCount=' + bridge.activeBridgeCount()
                        + '，详见 QuickJsHostBridge.java:317-350）：' + firstError + ' / ' + secondError);
            }
        }
    }

    function call(op, path, entryIndex) {
        if (path === undefined || path === null || String(path).length === 0) {
            throw new Error('sjbc.' + op + ' 需要非空路径');
        }
        var bridge = host();
        var payload = bridge.sjbcCall(String(op), String(path),
                entryIndex === undefined || entryIndex === null ? '' : String(entryIndex));
        return JSON.parse(String(payload));
    }

    var sjbc = {
        run: function (path) {
            return call('run', path, null);
        },
        runEntry: function (path, entryIndex) {
            return call('runEntry', path, entryIndex);
        },
        /** task-89 诊断：一次调用看清「引擎 id / 宿主 op / 桥能不能拿到」（只读，不执行字节码）。 */
        debug: function () {
            var id = engineId();
            var resolved = false;
            var resolveError = null;
            var via = null;
            var count = -1;
            try {
                var bridge = g.Java && typeof g.Java.type === 'function'
                        ? g.Java.type('com.stardust.autojs.engine.QuickJsHostBridge') : null;
                count = bridge ? bridge.activeBridgeCount() : -1;
                if (bridge) {
                    try {
                        resolved = !!bridge.forEngine(id);
                        via = 'forEngine';
                    } catch (firstError) {
                        resolved = !!bridge.forCurrentEngine();
                        via = 'forCurrentEngine(fallback)';
                    }
                }
            } catch (error) {
                resolveError = String(error);
            }
            return {
                engineId: id,
                hasEngines: !!(g.engines && typeof g.engines.myEngine === 'function'),
                activeBridgeCount: count,
                bridgeResolved: resolved,
                bridgeResolvedVia: via,
                bridgeError: resolveError,
                hostOp: 'sjbcCall'
            };
        },
        hostOp: 'sjbcCall'
    };

    g.sjbc = sjbc;
    if (g.__aiModernModules) {
        g.__aiModernModules.sjbc = sjbc;
    }
    try {
        if (g.console && typeof g.console.verbose === 'function') {
            g.console.verbose('sjbc 入口已装载（QuickJS，hostOp=sjbcCall）');
        }
    } catch (ignored) {
    }
})(typeof globalThis !== 'undefined' ? globalThis : this);