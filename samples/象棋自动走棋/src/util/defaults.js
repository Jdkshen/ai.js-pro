// @engine quickjs
/**
 * 默认值合并。
 *
 * 为什么要单独写一个：`Object.assign({}, DEFAULTS, options)` 在 **options 里存在该键但值是
 * `undefined`** 时会把默认值覆盖成 undefined —— 真机踩过两次：
 *   1) `frameQuality: undefined` → 空格全被判成「未知」（90 格全 ?）
 *   2) `sampleStep: undefined` → 采样步进变成 NaN → 阵营一个都判不出来
 * 调用方「没传这个字段」时，本意就是「用默认值」，所以这里显式忽略 undefined。
 */
"use strict";

function mergeDefaults(defaults, options) {
    const result = {};
    const hasOwn = Object.prototype.hasOwnProperty;
    for (const key in defaults) {
        if (hasOwn.call(defaults, key)) {
            result[key] = defaults[key];
        }
    }
    if (options) {
        for (const key in options) {
            if (hasOwn.call(options, key) && options[key] !== undefined) {
                result[key] = options[key];
            }
        }
    }
    return result;
}

module.exports = {
    mergeDefaults: mergeDefaults
};
