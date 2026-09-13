// @engine quickjs
/**
 * 本地引擎：把 `search.js` 包成一个会讲 UCI 的对象（**同步**计算，不用线程）。
 *
 * 用法（和真的 UCI 引擎一样）：
 *   const engine = local.create();
 *   engine.send("uci")            -> ["id name ...", "uciok"]
 *   engine.send("isready")        -> ["readyok"]
 *   engine.send("position fen ...") -> []
 *   engine.send("go movetime 1000") -> ["info ...", "bestmove b2e2"]
 *
 * 之所以同步：QuickJS 侧起线程要把局面和结果来回搬，收益不大；一次算棋 1~2 秒，
 * 放在点击流程里是可以接受的（上层用「先算完再点」的顺序即可）。
 */
"use strict";

const fenModule = require("../chess/fen.js");
const legal = require("../chess/legal-moves.js");
const search = require("./search.js");
const uci = require("./uci.js");

const ENGINE_NAME = "xiangqi-js";
const ENGINE_VERSION = "0.1.0";

function create(options) {
    const opts = options || {};
    const config = {
        defaultMovetime: opts.movetime == null ? 1000 : opts.movetime,
        defaultDepth: opts.depth == null ? 64 : opts.depth
    };
    const state = {
        // 当前局面：board 是 90 长数组；moves 是已走的 UCI 着法文本（仅用于对外展示/转发）
        board: null,
        side: "w",
        fen: null,
        moves: [],
        log: []
    };

    function setFen(text) {
        const parsed = fenModule.parseFen(text);
        if (!parsed.ok) {
            throw new Error("FEN 解析失败：" + parsed.errors.join("；"));
        }
        state.board = parsed.board;
        state.side = parsed.sideToMove === "b" ? "b" : "w";
        state.fen = text;
        state.moves = [];
    }

    function applyUciMove(text) {
        const list = search.generateMoves(state.board, state.side === "w", false);
        const move = uci.parseMove(text, list);
        if (!move) {
            throw new Error("着法不合法或无法解析：" + text);
        }
        const applied = legal.applyMove(state.board, move);
        state.board = applied.board;
        state.side = state.side === "w" ? "b" : "w";
        state.moves.push(uci.moveToUci(move));
        state.fen = null;
    }

    function currentFen() {
        if (state.fen) {
            return state.fen;
        }
        if (!state.board) {
            return null;
        }
        return fenModule.buildFen(state.board, state.side);
    }

    function go(goOptions) {
        if (!state.board) {
            return ["info string 还没有设置局面，先发 position"];
        }
        const merged = {
            movetime: goOptions && goOptions.movetime != null ? goOptions.movetime : config.defaultMovetime,
            depth: goOptions && goOptions.depth != null ? goOptions.depth : config.defaultDepth
        };
        const result = search.search(state.board, state.side, merged);
        const lines = [];
        if (result.ok) {
            lines.push(uci.infoLine({
                depth: result.depth,
                score: result.score,
                nodes: result.nodes,
                timeMs: result.ms,
                pv: result.pvIccs
            }));
        } else {
            lines.push("info string " + result.reason);
        }
        lines.push(uci.bestmoveLine(result.ok ? result.move : null));
        return lines;
    }

    /** 处理一行命令，返回要输出的一行或多行（行为对齐真 UCI 引擎）。 */
    function send(line) {
        const parsed = uci.tokenize(line);
        const command = parsed.command.toLowerCase();
        if (command === "uci") {
            return [
                "id name " + ENGINE_NAME + " " + ENGINE_VERSION,
                "id author ai.js pro",
                "option name MoveTime type spin default " + config.defaultMovetime +
                    " min 100 max 30000",
                "uciok"
            ];
        }
        if (command === "isready") {
            return ["readyok"];
        }
        if (command === "ucinewgame") {
            state.board = null;
            state.side = "w";
            state.fen = null;
            state.moves = [];
            return [];
        }
        if (command === "position") {
            try {
                // position [fen <fen> | startpos] [moves ...]
                const args = parsed.args;
                let index = 0;
                if (args[0] === "startpos") {
                    setFen(fenModule.START_FEN);
                    index = 1;
                } else if (args[0] === "fen") {
                    const movesAt = args.indexOf("moves");
                    const end = movesAt < 0 ? args.length : movesAt;
                    setFen(args.slice(1, end).join(" "));
                    index = end;
                } else {
                    return ["info string position 命令不完整"];
                }
                if (args[index] === "moves") {
                    for (let i = index + 1; i < args.length; i++) {
                        applyUciMove(args[i]);
                    }
                }
                return [];
            } catch (error) {
                return ["info string " + (error && error.message ? error.message : error)];
            }
        }
        if (command === "go") {
            return go(uci.parseGo(line));
        }
        if (command === "quit") {
            return [];
        }
        return [];
    }

    return {
        name: ENGINE_NAME + " " + ENGINE_VERSION,
        send: send,
        setFen: setFen,
        currentFen: currentFen,
        sideToMove: function () { return state.side; },
        board: function () { return state.board; },
        go: go,
        config: config
    };
}

const XQ_LOCAL_ENGINE = {
    ENGINE_NAME: ENGINE_NAME,
    ENGINE_VERSION: ENGINE_VERSION,
    create: create
};

module.exports = XQ_LOCAL_ENGINE;
