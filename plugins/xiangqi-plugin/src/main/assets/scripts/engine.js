/**
 * 插件自带的象棋引擎门面。
 *
 * 形状与宿主 `$pikafish` **完全一致**（available/isRunning/start/analyze/analyzePosition/stop/quit），
 * 所以「象棋自动走棋」工程里的 `src/engine/pikafish-engine.js` 只要 `setDriver(engine)` 就能把后端
 * 换成插件自己的引擎，业务代码一行不用改。
 *
 * 分工：原生 + Java 只负责「启停 UCI 会话、把原始输出拿回来」，info 行解析在这里做。
 * 解析口径必须与宿主一致（宿主在 Java 侧解析，见 modules/engine/.../QuickJsHostBridge#pikafishAnalyze）：
 * 同一路 multipv 取最后一条 info、move = pv 头、顶层分数/深度取自 1 号路。
 *
 * ⚠️ 只传数字/字符串/布尔给 Java（QuickJS 的 Java 互操作不支持把 JS 对象当参数）。
 */
"use strict";

var DEFAULTS = { threads: 2, hashMb: 128, contempt: 40, movetime: 1200, timeoutSlackMs: 15000 };

function valueOf(sdk, name) {
    return sdk ? sdk[name] : null;
}

function call(sdk, name, args) {
    var fn = valueOf(sdk, name);
    if (typeof fn !== "function") {
        throw new Error("插件 SDK 缺少 " + name + "()（宿主或插件版本不匹配）");
    }
    return decode(fn.apply(sdk, args));
}

/**
 * 统一两边的 Java 返回口径：QuickJS 的互操作会把「长得像 JSON 的字符串」解码成对象，
 * Rhino 则原样给字符串。这里都归一到「是 JSON 就解析」，调用方才不用分引擎写。
 */
function decode(value) {
    if (typeof value === "string") {
        try {
            return JSON.parse(value);
        } catch (error) {
            return value;
        }
    }
    return value;
}

function tryCall(sdk, name, args) {
    try {
        return call(sdk, name, args);
    } catch (error) {
        return null;
    }
}

function safeBoolean(value) {
    return value === true;
}

function clampInt(value, min, max, fallback) {
    var number = Math.round(Number(value));
    if (!isFinite(number)) {
        return fallback;
    }
    return Math.max(min, Math.min(max, number));
}

function describe(error) {
    return error && error.message ? String(error.message) : String(error);
}

/** "+12" / "12" → 12；其它（含 "lowerbound"）→ null。 */
function intAt(tokens, index) {
    if (index >= tokens.length) {
        return null;
    }
    var text = tokens[index];
    if (!/^[+-]?[0-9]+$/.test(text)) {
        return null;
    }
    var value = parseInt(text, 10);
    return isFinite(value) ? value : null;
}

/**
 * 解析 "info depth 14 seldepth 22 multipv 2 score cp 12 nodes 123 time 789 pv h2e2 ..."。
 * @returns {multipv, move, pv, scoreCp?, mate?, depth?, seldepth?, nodes?, timeMs?}
 */
function parseInfoLine(line) {
    var info = { multipv: 1, move: "", pv: "" };
    var tokens = String(line == null ? "" : line).split(/\s+/);
    for (var i = 0; i < tokens.length; i++) {
        var token = tokens[i];
        if (token === "multipv") {
            var number = intAt(tokens, i + 1);
            if (number !== null && number > 0) {
                info.multipv = number;
            }
        } else if (token === "depth") {
            info.depth = intAt(tokens, i + 1);
        } else if (token === "seldepth") {
            info.seldepth = intAt(tokens, i + 1);
        } else if (token === "nodes") {
            info.nodes = intAt(tokens, i + 1);
        } else if (token === "time") {
            info.timeMs = intAt(tokens, i + 1);
        } else if (token === "score") {
            var score = intAt(tokens, i + 2);
            if (tokens[i + 1] === "cp") {
                info.scoreCp = score;
            } else if (tokens[i + 1] === "mate") {
                info.mate = score;
            }
        } else if (token === "pv") {
            info.pv = tokens.slice(i + 1).join(" ");
            break;   // pv 之后没有别的字段
        }
    }
    if (info.pv) {
        var space = info.pv.indexOf(" ");
        info.move = space > 0 ? info.pv.substring(0, space) : info.pv;
    }
    return info;
}

/**
 * 原生输出（"info ..." 行 + "bestmove ..." 行）→ 与宿主 `$pikafish.analyze` 同形状的结果。
 * @returns {ok:true, move, candidates:[…], pv, scoreCp?, mate?, depth?, seldepth?, nodes?, timeMs?, raw}
 *          或 {ok:false, error}
 */
function parseResult(rawText) {
    var raw = rawText == null ? "" : String(rawText);
    if (raw.indexOf("error:") === 0) {
        return { ok: false, error: raw.substring(6).trim() };
    }
    var bestmoveLine = null;
    var byPv = {};
    var multipvs = [];
    var lines = raw.split("\n");
    for (var i = 0; i < lines.length; i++) {
        var line = lines[i].trim();
        if (line.indexOf("bestmove") === 0) {
            bestmoveLine = line;
            continue;
        }
        if (line.indexOf("info ") !== 0) {
            continue;
        }
        var info = parseInfoLine(line);
        if (!info.move) {
            continue;   // 没有 pv 的 info（如 "info depth 1 currmove ..."）不算候选
        }
        if (byPv[info.multipv] === undefined) {
            multipvs.push(info.multipv);
        }
        byPv[info.multipv] = info;   // 同一路取最后一条：搜索逐层加深，后面的更深
    }
    multipvs.sort(function (a, b) { return a - b; });

    var bestmove = null;
    if (bestmoveLine) {
        var parts = bestmoveLine.split(/\s+/);
        bestmove = parts.length >= 2 ? parts[1] : null;
    }
    var usableBestmove = bestmove != null && bestmove !== "" && bestmove !== "(none)";
    if (multipvs.length === 0 && !usableBestmove) {
        return { ok: false, error: raw === "" ? "引擎没有输出" : raw };
    }
    if (multipvs.length === 0) {
        // 极快收敛（单步杀这类）可能只有 bestmove、没有 info：当成一个无分数的候选
        byPv[1] = { multipv: 1, move: bestmove, pv: bestmove };
        multipvs = [1];
    }

    var candidates = [];
    for (var j = 0; j < multipvs.length; j++) {
        var item = byPv[multipvs[j]];
        var entry = { multipv: item.multipv, move: item.move, pv: item.pv == null ? "" : item.pv };
        if (item.scoreCp != null) entry.scoreCp = item.scoreCp;
        if (item.mate != null) entry.mate = item.mate;
        if (item.depth != null) entry.depth = item.depth;
        if (item.seldepth != null) entry.seldepth = item.seldepth;
        if (item.nodes != null) entry.nodes = item.nodes;
        if (item.timeMs != null) entry.timeMs = item.timeMs;
        candidates.push(entry);
    }
    var top = candidates[0];
    var result = { ok: true, move: usableBestmove ? bestmove : top.move, candidates: candidates,
        pv: top.pv, raw: raw };
    if (top.scoreCp != null) result.scoreCp = top.scoreCp;
    if (top.mate != null) result.mate = top.mate;
    if (top.depth != null) result.depth = top.depth;
    if (top.seldepth != null) result.seldepth = top.seldepth;
    if (top.nodes != null) result.nodes = top.nodes;
    if (top.timeMs != null) result.timeMs = top.timeMs;
    return result;
}

/** 一次搜索：movetime 下限 1ms，multiPv 1..8，depth 0(不指定)..40（与宿主同口径）。 */
function search(sdk, input, withHistory, movetimeMs, timeoutMs, multiPv, depth) {
    var movetime = clampInt(movetimeMs, 1, 600000, DEFAULTS.movetime);
    var timeout = clampInt(timeoutMs, 0, 600000, 0);
    var pv = clampInt(multiPv, 1, 8, 1);
    var limit = clampInt(depth, 0, 40, 0);
    var raw;
    try {
        raw = call(sdk, "engineAnalyze", [String(input == null ? "" : input), movetime, timeout, pv, limit,
            withHistory === true]);
    } catch (error) {
        return { ok: false, error: "插件引擎调用失败：" + describe(error) };
    }
    return parseResult(raw);
}

/**
 * 建立驱动对象。
 * @param sdk 插件 SDK 实例（XiangqiPlugin 的 Java 代理）
 */
function create(sdk) {
    var started = false;
    var lastStartError = "";
    return {
        DEFAULTS: DEFAULTS,
        /** 插件的原生引擎在不在（.so 是否加载成功）。 */
        available: function () {
            return safeBoolean(tryCall(sdk, "engineAvailable", []));
        },
        /** 原生库加载失败原因（成功时是空串）。 */
        loadError: function () {
            var error = tryCall(sdk, "engineLoadError", []);
            return error == null ? "" : String(error);
        },
        isRunning: function () {
            return safeBoolean(tryCall(sdk, "engineRunning", []));
        },
        nnuePath: function () {
            var path = tryCall(sdk, "nnuePath", []);
            return path == null ? "" : String(path);
        },
        /** UCI 选项清单（自检用：确认 Contempt 等选项真的注册在引擎里）。 */
        options: function () {
            var value = tryCall(sdk, "engineOptions", []);
            return value == null ? [] : value;
        },
        configuredThreads: function () {
            var value = tryCall(sdk, "engineConfiguredThreads", []);
            return typeof value === "number" ? value : 0;
        },
        /**
         * 启动（或复用）会话；已启动但参数变化时，由原生侧在两次搜索之间重设 UCI 选项。
         * @param options {evalFile?, threads?, hashMb?, contempt?}
         */
        start: function (options) {
            var opts = options || {};
            var result = tryCall(sdk, "engineStart", [
                opts.evalFile == null ? null : String(opts.evalFile),
                clampInt(opts.threads, 1, 128, DEFAULTS.threads),
                clampInt(opts.hashMb, 16, 4096, DEFAULTS.hashMb),
                clampInt(opts.contempt, -200, 200, DEFAULTS.contempt)
            ]);
            if (result == null || result.ok !== true) {
                started = false;
                lastStartError = result && result.error ? String(result.error) : "插件引擎启动失败";
                return { ok: false, error: lastStartError };
            }
            started = true;
            lastStartError = "";
            return result;
        },
        lastError: function () {
            return lastStartError;
        },
        /** 算一步棋（阻塞）：与 `$pikafish.analyze(fen, movetime, timeout, multiPv, depth)` 同形状。 */
        analyze: function (fen, movetimeMs, timeoutMs, multiPv, depth) {
            return search(sdk, fen, false, movetimeMs, timeoutMs, multiPv, depth);
        },
        /** 从已确认着法历史搜索：与 `$pikafish.analyzePosition(command, …)` 同形状。 */
        analyzePosition: function (positionCommand, movetimeMs, timeoutMs, multiPv, depth) {
            return search(sdk, positionCommand, true, movetimeMs, timeoutMs, multiPv, depth);
        },
        /** 打断正在跑的搜索（analyze 会带着 bestmove 正常返回）。 */
        stop: function () {
            tryCall(sdk, "engineStop", []);
        },
        /** 退出引擎线程（幂等；下次 start 重新拉起）。 */
        quit: function () {
            tryCall(sdk, "engineQuit", []);
            started = false;
        }
    };
}

module.exports = {
    create: create,
    /** 纯函数（离线自检直接测）。 */
    parseResult: parseResult,
    parseInfoLine: parseInfoLine,
    DEFAULTS: DEFAULTS
};
