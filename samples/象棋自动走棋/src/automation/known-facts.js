// @engine quickjs
/**
 * 已知事实补齐表（「游戏画花了的格子」的账本）—— 从 auto-driver 抽出的容器模块。
 *
 * 语义（原实现直搬，行为不变）：
 *   · 记录 = `{ index, char, cycle, ply }`；`char === null` 表示「棋规上必为空」的格子
 *     （直线行进的途经格：空网格同样会被装饰盖住，没人补就永远差一个未知格）。
 *   · `upsert(record)` 按 index 去重：同 index **替换**（保持原位置）返回 true；新格追加返回 false。
 *   · `drop(indexes, onDrop)` 按「格子上的子确实走了」作废；返回作废条数；
 *     `onDrop(record)` 回调让调用方顺手清别的派生状态（如连续帧共识）。
 *   · `applyTo(chars)` 只把登记过、且当前确实是 `'?'` 的格子补成记录值（**不覆盖**已读出的字）。
 *   · `charAt(index)` 只认「有字」的记录（空子的记录不算：子走了记录就该失效）。
 *
 * 依据都是棋规唯一推出来的事实（票据 / 唯一合法着法），不是猜；那格能自己认出来就自动失效。
 */
"use strict";

function createKnownFacts() {
    /** [{ index, char, cycle, ply }] */
    let items = [];

    /** 数组副本（浅拷贝：元素仍是原记录对象，调用方可读不可依赖其可写性）。 */
    function list() {
        return items.slice();
    }

    /** 用列表整体替换（保留顺序；用于「遍历后决定留哪些」的补齐循环）。 */
    function replace(next) {
        items = (next || []).slice();
        return items.length;
    }

    /** 按 index 查记录（没有返回 null）。 */
    function find(index) {
        for (let i = 0; i < items.length; i++) {
            if (items[i] && items[i].index === index) {
                return items[i];
            }
        }
        return null;
    }

    /** 同 index 替换（返回 true）或追加（返回 false）。 */
    function upsert(record) {
        if (!record || record.index == null) {
            return false;
        }
        for (let i = 0; i < items.length; i++) {
            if (items[i] && items[i].index === record.index) {
                items[i] = record;
                return true;
            }
        }
        items.push(record);
        return false;
    }

    /**
     * 作废这些格子的记录（子离开/被吃）。
     * @param indexes 要作废的格下标
     * @param onDrop? 每作废一条回调一次（调用方顺手清理派生状态）
     * @returns 实际作废条数
     */
    function drop(indexes, onDrop) {
        if (!indexes || indexes.length === 0 || items.length === 0) {
            return 0;
        }
        const keep = [];
        let dropped = 0;
        for (let i = 0; i < items.length; i++) {
            const record = items[i];
            if (record && indexes.indexOf(record.index) >= 0) {
                dropped += 1;
                if (typeof onDrop === "function") {
                    onDrop(record);
                }
                continue;
            }
            keep.push(record);
        }
        if (dropped > 0) {
            items = keep;
        }
        return dropped;
    }

    /** 把登记过、且当前确实是 '?' 的格子补成记录值；没有补到就原样返回。 */
    function applyTo(chars) {
        if (!chars || !chars.length || items.length === 0) {
            return chars;
        }
        let out = null;
        for (let i = 0; i < items.length; i++) {
            const record = items[i];
            if (chars[record.index] === "?") {
                if (!out) {
                    out = chars.slice();
                }
                out[record.index] = record.char;
            }
        }
        return out || chars;
    }

    /**
     * 已知事实表里那一格的字；没有（或空网格记录）返回 null。
     * 用途：颜色翻转的硬闸 —— 已知事实说这格是我方炮，就不许 OCR/像素翻成黑炮。
     */
    function charAt(index) {
        for (let i = 0; i < items.length; i++) {
            const record = items[i];
            if (record && record.index === index && typeof record.char === "string" && record.char) {
                return record.char;
            }
        }
        return null;
    }

    function count() {
        return items.length;
    }

    function clear() {
        items = [];
    }

    return {
        list: list,
        replace: replace,
        find: find,
        upsert: upsert,
        drop: drop,
        applyTo: applyTo,
        charAt: charAt,
        count: count,
        clear: clear
    };
}

module.exports = { createKnownFacts: createKnownFacts };
