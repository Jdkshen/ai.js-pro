// @engine quickjs
/**
 * 场景清单（**显式注册**，不扫目录）。
 *
 * 为什么不用扫目录：真机（QuickJS）侧没有 Node 的 `fs`，而 `files.listDir` 的行为/权限又是另一套；
 * 显式列出模块路径两边都能跑，顺序也稳定（日志可 diff）。
 *
 * 加一条场景：在 `tools/regress/cases/` 下新建模块（导出 `{ name, tags, run(t) }`），
 * 然后把路径加到下面这张表里 —— PC 与真机同时生效。
 */
"use strict";

module.exports = {
    cases: [
        "tools/regress/cases/king-rescue-yolo.js",
        "tools/regress/cases/king-rescue-glyph.js",
        "tools/regress/cases/king-rescue-decoy.js",
        "tools/regress/cases/king-infer-weak-ocr.js",
        "tools/regress/cases/gray-retry-reads-king.js",
        "tools/regress/cases/ocr-all-dead.js",
        "tools/regress/cases/geometry-shift.js",
        "tools/regress/cases/baseline-happy-path.js",
        "tools/regress/cases/driver-replay-turns.js",
        "tools/regress/cases/driver-replay-budget-rebuild.js",
        "tools/regress/cases/driver-replay-newsession.js"
    ]
};
