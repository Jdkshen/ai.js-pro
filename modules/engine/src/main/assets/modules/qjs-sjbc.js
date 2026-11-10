/**
 * QuickJS 侧的 `sjbc` 入口（与 Rhino 的 `modules/__globals__.js` 里那段**同源**：只调 SjbcApi，不复制实现）。
 *
 * 宿主 op：`QuickJsHostBridge.sjbcCall(op, path, entryIndex)` → **JSON 字符串**（白名单只有 run / runEntry，不做任意反射）。
 * JS 侧 `JSON.parse` 后即可**按属性访问**：`r.steps` / `r.prints[0]` / `r.ok`（prints 是真 JS 数组），不需要 `r.get('steps')`。
 * 字段名与 Rhino 侧逐字一致：ok / prints / steps / entries / codeBytes / poolSize / entryIndex / path / certShaUsed / certSource / uncaught。
 */
(function (g) {
    'use strict';

    function host() {
        var Java = g.Java;
        if (!Java || typeof Java.type !== 'function') {
            throw new Error('sjbc 需要 QuickJS Java 桥（当前宿主不可用）');
        }
        var id = 0;
        try {
            id = Number(g.engines.myEngine().id);
        } catch (ignored) {
        }
        return Java.type('com.stardust.autojs.engine.QuickJsHostBridge').forEngine(id);
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
        /** 装配钉用：告诉脚本侧这个对象是谁给的（真机日志可查）。 */
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