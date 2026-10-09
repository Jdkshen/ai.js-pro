// @engine quickjs
/**
 * 循环历史（长捉 / 长将）：记录我方每次「局面 + 出手」，按中国象棋规则给出该禁的着法。
 *
 * 为什么单独成模块：这段逻辑已经在真机上误伤过一次，又差点漏禁一次——
 *   · 2026-09-21 09:08：一次 CANCEL_JOB 后的重算把同一手记了两遍 → 第三手误触禁手；
 *   · 同局面同手**跨回合**摆回来（长将）如果一律去重，就永远禁不掉。
 * 单独成模块才能直接写单测把这两条盯住（见 tools/self-check.js）。
 */
"use strict";

const LOOP_HISTORY_LIMIT = 12;

function createLoopHistory() {
    const records = [];   // [{ key, iccs, epoch }]，只留最近 LOOP_HISTORY_LIMIT 条

    /**
     * 记一次「在这个局面决定走这一手」。
     * key = 局面+行棋方的指纹；epoch = 回合世代（每确认落定一手 +1）。
     * 同一世代里连着记（同 key 同 iccs）只算一次——那是取消重算/重复发令；
     * 跨世代要照记——那才是真的把局面摆回来了。
     */
    function record(key, iccs, epoch) {
        if (!key || !iccs) {
            return;
        }
        const n = records.length;
        if (n > 0 && records[n - 1].key === key && records[n - 1].iccs === String(iccs) &&
            records[n - 1].epoch === epoch) {
            return;
        }
        records.push({ key: key, iccs: String(iccs), epoch: epoch });
        while (records.length > LOOP_HISTORY_LIMIT) {
            records.shift();
        }
    }

    /** 当前局面的「疑似循环」禁用着法（没有就返回 null）。 */
    function bansFor(key) {
        if (!key) {
            return null;
        }
        const banned = {};
        let sameCount = 0;
        for (let i = 0; i < records.length; i++) {
            if (records[i].key === key) {
                sameCount += 1;
            }
        }
        // ① 同一局面已经反复出现（这次是第 3 次起）⇒ 这个局面走过的着法都别再走
        if (sameCount >= 2) {
            for (let i = 0; i < records.length; i++) {
                if (records[i].key === key) {
                    banned[records[i].iccs] = true;
                }
            }
        }
        // ② 摆成 A,B,A 了，现在又轮到 B 局面 ⇒ 禁掉 B 局面上一轮走的那手，直接断环
        const n = records.length;
        if (n >= 3 &&
            records[n - 3].key === records[n - 1].key &&
            records[n - 2].key === key) {
            banned[records[n - 2].iccs] = true;
        }
        const list = Object.keys(banned);
        return list.length ? list : null;
    }

    function clear() {
        records.length = 0;
    }

    function size() {
        return records.length;
    }

    return {
        record: record,
        bansFor: bansFor,
        clear: clear,
        size: size
    };
}

module.exports = {
    LOOP_HISTORY_LIMIT: LOOP_HISTORY_LIMIT,
    createLoopHistory: createLoopHistory
};
