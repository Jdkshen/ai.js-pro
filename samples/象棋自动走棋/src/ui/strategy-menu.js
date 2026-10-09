// @engine quickjs
"use strict";
const settings = require("../app/session-settings.js");
const records = require("../services/opening-results.js");
const names = {win:"胜", draw:"和", loss:"负", excluded:"不计入"};
function show(report, onChange) {
    try {
        const v = settings.read();
        const selected = dialogs.select(records.LABEL + " · 策略与战绩", [
            "关键步加时复核：" + (v.criticalReview ? "开" : "关"),
            "手机预思考（最多 " + (v.adaptiveSearch ? "0.7" : "2.5") + " 秒）：" + (v.prethink ? "开" : "关"),
            "对面应手预判（画蓝箭头）：" + settings.replyLabel(v.replyGear),
            "开局参考本对手战绩：" + (v.openingLearning ? "开" : "关"),
            "自动调节算棋时间：" + (v.adaptiveSearch ? "开（时间值为总上限）" : "关（固定时间）"),
            "登记 / 更正对局结果（胜、和、负）", "查看战绩与统计规则"
        ]);
        if (selected === 2) {
            // ★ 2026-10-06：档位 = 单次搜索预算 × 画几路（关 = 不算对面、省一份算力）
            const gears = ["off", "quick", "std", "deep"];
            const choice = dialogs.select("对面应手预判档位（下一次出手生效）", [
                "关（不算对面，省一份算力）", "快：500ms × 3 路", "标准：900ms × 5 路", "深：1500ms × 5 路"
            ]);
            if (choice < 0) return;
            const saved = settings.write({ replyGear: gears[choice] });
            if (onChange) onChange();
            report(saved.ok ? "预判档位已保存：" + settings.replyLabel(gears[choice]) + "。下一手生效。" : saved.reason);
            return;
        }
        const toggleKeys = { 0: "criticalReview", 1: "prethink", 3: "openingLearning", 4: "adaptiveSearch" };
        if (toggleKeys[selected]) {
            const k = toggleKeys[selected], patch = {}; patch[k] = !v[k];
            const saved = settings.write(patch); if (onChange) onChange();
            report(saved.ok ? "策略已保存，下次搜索生效。" : saved.reason); return;
        }
        if (selected === 5) {
            const games = records.list().reverse();
            if (!games.length) {report("暂无已确认棋谱。开始一局后再登记结果。"); return;}
            const index = dialogs.select("选择要登记的对局（我方视角）", games.map(function (g) {
                return new Date(g.startedAt).toLocaleString() + " · " + (g.side === "b" ? "黑" : "红") +
                    " · " + g.moves.length + " 手 · " + (names[g.outcome] || "待确认") + (g.eligible ? "" : " · 棋谱不完整，不参与开局筛选");
            }));
            if (index < 0 || !games[index]) return;
            const game = games[index];
            const choice = dialogs.select("确认游戏实际结果（" + (game.side === "b" ? "我方黑" : "我方红") + "）",
                ["我方胜", "和棋", "我方负", "误记 / 未完成，不计入"]);
            if (choice < 0 || choice > 3) return;
            const outcome = ["win","draw","loss","excluded"][choice];
            if (!dialogs.confirm("保存对局结果", "将 " + new Date(game.startedAt).toLocaleString() + " 这局记为：" + names[outcome] + "？")) return;
            const saved = records.result(game.id, outcome);
            report(saved.ok ? "已登记「" + names[outcome] + "」。同一局更正会替换旧结果，不重复计数。" : saved.reason);
        } else if (selected === 6) {
            const games = records.list(), counts = {win:0,draw:0,loss:0}, eligible = {win:0,draw:0,loss:0};
            games.forEach(function (g) {if (g.outcome in counts) {counts[g.outcome]++; if(g.eligible) eligible[g.outcome]++;}});
            dialogs.alert(records.LABEL, "人工确认：" + counts.win + " 胜 / " + counts.draw + " 和 / " + counts.loss + " 负\n" +
                "完整棋谱：" + eligible.win + " 胜 / " + eligible.draw + " 和 / " + eligible.loss + " 负\n" +
                "红黑分开统计。每个安全候选至少 5 局，平滑得分差达到 10% 才参考战绩；只在引擎评估相近的开局候选中挑选。\n" +
                "未完成、未确认或棋谱不完整的对局不参与筛选。所有记录保存在手机，清除应用数据会删除记录。样本少时不能据此断言更容易获胜。");
        }
    } catch (error) {report("策略/战绩未完成：" + error);}
}
module.exports = {show:show};
