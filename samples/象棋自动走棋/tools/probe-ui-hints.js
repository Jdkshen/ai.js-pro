// @engine quickjs
/**
 * 实验探针 v3：「游戏选中提示」能不能当识别旁证？
 * —— 借 Pro象棋 的思路（读 canmove/caneat 标记），在我们链上实测一遍。
 *
 * 流程（全程只按压「自家棋子」换选，不落子，对局无损；实测这游戏选中后只认
 * 「换选自家子 / 合法目标」，没有取消操作）：
 *   ① 截图 → 定位 → 字形识别出一张盘面 → 棋规算出每枚子的合法着法；
 *   ② 按 P1（着法最多、红方优先）→ 截图 → 检测绿色可走点 → 对照 P1 着法表；
 *   ③ 按 P2（另一枚自家子）→ 截图 → 对照 P2 着法表；换选差分（P1 点消失、P2 点出现）；
 *   ④ 附带：把 Pro象棋 包里的 canmove/caneat/selected 模板按几档缩放做模板匹配
 *      （看看那套模板在 K40 皮肤上用不用得上）。
 *   ⑤ 实测结果（2026-10-06 12:5x，e2 炮）：可走点=绿色小圆点(≈14px，(158,168,93))；
 *      点击通道必须用 shizuku `input swipe x y x y 300`（a11y tap 进不了这个游戏）。
 *
 * 用法：push 进工程目录后从工程根跑（靠 __dirname 找模块）；模板放 /sdcard/probe/。
 * 结果看 logcat（前缀 [uihint]）。
 */
"use strict";

const BASE_DIR = (function () {
    try {
        if (typeof __dirname !== "undefined" && __dirname) {
            return __dirname.replace(/\/tools$/, "");
        }
    } catch (ignore) { }
    return ".";
}());

function load(relative) {
    const path = BASE_DIR === "." ? relative : (BASE_DIR + "/" + relative);
    return require(path);
}

const adapter = load("src/runtime/aijs-adapter.js");
const locateBoardModule = load("src/vision/locate-board.js");
const pipelineFactory = load("src/vision/pipeline-factory.js");
const legal = load("src/chess/legal-moves.js");

const TPL_DIR = "/sdcard/probe";
const TPL_FILES = ["canmove.png", "caneat.png", "selected.png"];
/** 模板匹配的缩放档（Pro象棋 的模板来自别的分辨率，K40 格距 114.85 vs 它 board.png ≈90 ⇒ 约 1.27） */
const TPL_SCALES = [1.27, 1.15, 1.4, 1.0];

function log(text) {
    try { console.log("[uihint] " + text); } catch (ignore) { }
}
function pause(ms) {
    try { if (typeof sleep === "function") { sleep(ms); } } catch (ignore) { }
}
function rgbOf(pix) {
    if (pix == null) { return null; }
    const n = Number(pix);
    return [(n >>> 16) & 255, (n >>> 8) & 255, n & 255];
}

/** 绿点检测（可走点）：实测点色 ≈(158,168,93)（g 明显高于 b、不低于 r）；木纹/棋子/红黑字都踩不中 */
const GOFFS = [-0.12, -0.08, -0.04, 0, 0.04, 0.08, 0.12];
function isGreenDot(rgb) {
    return !!rgb && (rgb[1] - rgb[2]) >= 55 && rgb[1] >= 140 && (rgb[1] - rgb[0]) >= -10;
}
function countGreenDots(image, grid) {
    const out = new Array(90);
    for (let r = 0; r < 10; r++) {
        for (let c = 0; c < 9; c++) {
            const cx = grid.originX + c * grid.stepX;
            const cy = grid.originY + r * grid.stepY;
            let hits = 0, sr = 0, sg = 0, sb = 0;
            for (let i = 0; i < GOFFS.length; i++) {
                for (let j = 0; j < GOFFS.length; j++) {
                    const x = Math.round(cx + GOFFS[j] * grid.stepX);
                    const y = Math.round(cy + GOFFS[i] * grid.stepY);
                    let pix = null;
                    try { pix = images.pixel(image, x, y); } catch (ignore) { pix = null; }
                    const rgb = rgbOf(pix);
                    if (isGreenDot(rgb)) { hits += 1; sr += rgb[0]; sg += rgb[1]; sb += rgb[2]; }
                }
            }
            out[r * 9 + c] = hits >= 2
                ? { hits: hits, color: [Math.round(sr / hits), Math.round(sg / hits), Math.round(sb / hits)] }
                : null;
        }
    }
    return out;
}

/** 按压：走和面板同一条通道（shizuku `input swipe x y x y 300`；实测 a11y tap 进不了这个游戏） */
function makePress() {
    let backend = null;
    try {
        const caps = adapter.capabilities ? adapter.capabilities() : null;
        backend = caps && caps.shizuku ? "shizuku" : (caps && caps.shell ? "root" : null);
    } catch (ignore) { }
    if (typeof shell === "function") {
        log("按压通道：shell/" + (backend || "默认") + "（input swipe 300ms）");
        return function (x, y) {
            const options = { timeout: 20000 };
            if (backend) { options[backend] = true; }
            try { shell("input swipe " + x + " " + y + " " + x + " " + y + " 300", options); }
            catch (error) { log("按压失败：" + error); }
        };
    }
    log("按压通道：无障碍 tap（300ms）——注意：实测进不了这个游戏，仅作兜底");
    const tap = adapter.createTap({ pressMs: 300 }).tap;
    return function (x, y) { try { tap(x, y); } catch (error) { log("按压失败：" + error); } };
}

function main() {
    if (!adapter.requestCapture("portrait")) {
        log("截图授权失败");
        return;
    }
    try { if (locateBoardModule.configureSift) { locateBoardModule.configureSift({ baseDir: BASE_DIR }); } }
    catch (ignore) { }
    const frame0 = adapter.capture("full", 2000);
    if (!frame0) {
        log("拿不到帧");
        return;
    }
    const located = locateBoardModule.locateBoardOnScreen({ frame: frame0, timeoutMs: 2500 });
    if (!located || !located.ok || !located.grid) {
        log("定位失败：" + ((located && located.reason) || "?"));
        return;
    }
    const grid = located.grid;
    log("几何 origin=(" + grid.originX.toFixed(1) + "," + grid.originY.toFixed(1) + ") step=(" +
        grid.stepX.toFixed(1) + "," + grid.stepY.toFixed(1) + ")");

    const glyph = pipelineFactory.createGlyphSource({ adapter: adapter, baseDir: BASE_DIR, log: log });
    const rec = glyph.recognize(frame0, grid, { fresh: true });
    if (!rec || !rec.ok) {
        log("识别失败：" + ((rec && rec.reason) || "?"));
        return;
    }
    const board = rec.chars;
    log("盘面：子 " + board.filter(function (p) { return !!p; }).length + "｜未知 " + rec.unknownCells);

    // 挑候选棋子：**优先我方（红）**、炮优先、着法多的优先（开局红炮 b2/h2 = 12 步，含一记隔子吃）
    const sides = { w: legal.allLegalMoves(board, "w"), b: legal.allLegalMoves(board, "b") };
    const candidates = [];
    for (let idx = 0; idx < 90; idx++) {
        const ch = board[idx];
        if (!ch || ch === "?" || ch === ".") { continue; }
        const col = idx % 9, row = Math.floor(idx / 9);
        const side = (ch === ch.toUpperCase()) ? "w" : "b";
        const moves = sides[side].filter(function (m) { return m.fromCol === col && m.fromRow === row; });
        if (moves.length < 2) { continue; }
        const score = moves.length + (String(ch).toLowerCase() === "c" ? 3 : 0) + (side === "w" ? 5 : 0);
        candidates.push({ idx: idx, col: col, row: row, ch: ch, side: side, moves: moves, score: score });
    }
    candidates.sort(function (a, b) { return b.score - a.score; });
    if (candidates.length < 2) {
        log("候选不足（" + candidates.length + " 个）：" + candidates.map(function (cand) {
            return "(" + cand.col + "," + cand.row + ")" + cand.ch + "×" + cand.moves.length;
        }).join("　"));
        return;
    }
    const p1 = candidates[0];
    const p2 = candidates.filter(function (cand) { return cand.idx !== p1.idx; })[0];
    log("候选：" + candidates.slice(0, 3).map(function (cand) {
        return "(" + cand.col + "," + cand.row + ")" + cand.ch + "×" + cand.moves.length;
    }).join("　"));
    log("P1=(" + p1.col + "," + p1.row + ")" + p1.ch + "×" + p1.moves.length +
        "｜P2=(" + p2.col + "," + p2.row + ")" + p2.ch + "×" + p2.moves.length);

    const press = makePress();
    /** 把「某枚子的选中帧」里的绿点检测结果 vs 棋规着法表对照输出 */
    function reportDots(dots, cand, label) {
        const destSet = {};
        cand.moves.forEach(function (m) { destSet[m.toCol + "," + m.toRow] = m; });
        const hit = [], extra = [], miss = [];
        for (let idx = 0; idx < 90; idx++) {
            const col = idx % 9, row = Math.floor(idx / 9);
            const key = col + "," + row;
            const dot = dots[idx];
            if (dot) {
                if (destSet[key]) {
                    hit.push(key + (destSet[key].captured ? "×" : ""));
                } else {
                    extra.push(key + "(hits" + dot.hits + " rgb" + dot.color.join(",") + ")");
                }
            } else if (destSet[key]) {
                miss.push(key + (destSet[key].captured ? "×" : ""));
            }
        }
        log(label + "：画点 " + (hit.length + extra.length) + " 个｜命中着法 " + hit.length + "/" + cand.moves.length +
            "｜非着法点 " + (extra.length ? extra.join(" ") : "无") +
            "｜未画的着法格 " + (miss.length ? miss.join(" ") : "无"));
    }

    // ① 按 P1（换选到 P1；实测：选中后只认「换选自家子 / 合法目标」）
    const c1x = Math.round(grid.originX + p1.col * grid.stepX);
    const c1y = Math.round(grid.originY + p1.row * grid.stepY);
    log("按压 P1 (" + c1x + "," + c1y + ") 选择「" + p1.ch + "」");
    press(c1x, c1y);
    pause(900);
    const frame1 = adapter.capture("full", 2000);
    if (!frame1) { log("P1 帧拿不到"); return; }
    const dots1 = countGreenDots(frame1, grid);
    reportDots(dots1, p1, "P1 选中态");
    try { images.save(frame1, TPL_DIR + "/hints-p1.png"); log("已存 hints-p1.png"); } catch (ignore) { }

    // ② 按 P2（再换选到 P2）
    const c2x = Math.round(grid.originX + p2.col * grid.stepX);
    const c2y = Math.round(grid.originY + p2.row * grid.stepY);
    log("按压 P2 (" + c2x + "," + c2y + ") 选择「" + p2.ch + "」");
    press(c2x, c2y);
    pause(900);
    const frame2 = adapter.capture("full", 2000);
    if (!frame2) { log("P2 帧拿不到"); return; }
    const dots2 = countGreenDots(frame2, grid);
    reportDots(dots2, p2, "P2 选中态");
    try { images.save(frame2, TPL_DIR + "/hints-p2.png"); log("已存 hints-p2.png"); } catch (ignore) { }

    // ③ 换选差分（P1 的点应消失、P2 的点应出现）
    const gone = [], appeared = [];
    for (let idx = 0; idx < 90; idx++) {
        const a = !!dots1[idx], b = !!dots2[idx];
        if (a && !b) { gone.push((idx % 9) + "," + Math.floor(idx / 9)); }
        if (!a && b) { appeared.push((idx % 9) + "," + Math.floor(idx / 9)); }
    }
    log("换选差分：消失 " + (gone.length ? gone.join(" ") : "无") + "｜出现 " + (appeared.length ? appeared.join(" ") : "无"));

    // ④ 模板匹配扫档（Pro象棋 的三张模板；用 P1 帧）
    try {
        const g = images.grayscale(frame1);
        const pad = Math.round(grid.stepX * 0.9);
        const region = [
            Math.max(0, Math.round(grid.originX - pad)),
            Math.max(0, Math.round(grid.originY - pad)),
            Math.min(1080, Math.round(grid.originX + 8 * grid.stepX + pad)) - Math.max(0, Math.round(grid.originX - pad)),
            Math.min(2400, Math.round(grid.originY + 9 * grid.stepY + pad)) - Math.max(0, Math.round(grid.originY - pad))
        ];
        for (let t = 0; t < TPL_FILES.length; t++) {
            const path = TPL_DIR + "/" + TPL_FILES[t];
            let tpl = null;
            try { tpl = images.read(path); } catch (error) { tpl = null; }
            if (!tpl) { log("模板缺失 " + path); continue; }
            const tg = images.grayscale(tpl);
            for (let s = 0; s < TPL_SCALES.length; s++) {
                const scale = TPL_SCALES[s];
                let use = tg;
                try { use = images.resize(tg, [Math.round(105 * scale), Math.round(105 * scale)]); } catch (ignore) { use = tg; }
                let res = null;
                try {
                    res = images.matchTemplate(g, use, { region: region, threshold: 0.6, max: 12 });
                } catch (error) { log("模板 " + TPL_FILES[t] + " ×" + scale + " 匹配异常：" + error); continue; }
                const ms = (res && res.matches) ? res.matches : [];
                if (ms.length) {
                    const top = ms[0];
                    const sim = top.similarity == null ? 0 : Number(top.similarity);
                    log("模板 " + TPL_FILES[t] + " ×" + scale + "：命中 " + ms.length +
                        " 处，最高 " + sim.toFixed(3) +
                        " @(" + ((top.point && top.point.x) || "?") + "," + ((top.point && top.point.y) || "?") + ")");
                } else {
                    log("模板 " + TPL_FILES[t] + " ×" + scale + "：0 命中");
                }
            }
        }
    } catch (error) {
        log("模板匹配段异常：" + error);
    }

    log("实验完成（最后 P2 处于选中态——这游戏选中后没有取消操作，随下一次点击自然覆盖；未走任何子）");
}

main();
