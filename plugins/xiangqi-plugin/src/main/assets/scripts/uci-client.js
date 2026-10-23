"use strict";
/**
 * Node.js 常驻 UCI 引擎客户端（Pikafish / Stockfish 风格协议）。
 *
 * 只负责一个 child_process 的生命周期与 UCI 行协议：
 *   - 显式 start(): uci/uciok -> setoption -> isready/readyok
 *   - 前台 analyze(): position + go movetime，等本次 bestmove
 *   - 后台 beginPonder()/finishPonder(): position + go ponder infinite
 *     -> ponderhit -> JS 计时 additionalMs -> stop -> 同一个 bestmove
 *   - stop() 总是排空当前搜索的 bestmove；超时则杀进程，绝不把旧 bestmove
 *     留给下一次搜索
 *
 * stdout 按行解析（跨 chunk、CRLF、多行），搜索输出按内部 search id 归属。
 * 不发送 ucinewgame / clear hash / 每步 quit，避免破坏 ponder 缓存。
 */

const childProcess = require("child_process");
const parseUci = require("./uci-parser.js").parseUci;

const FEN_RE = /^(?:[rnbakcpRNBAKCP1-9]+\/){9}[rnbakcpRNBAKCP1-9]+ [wb] - - [0-9]+ [1-9][0-9]*$/;
const MOVE_RE = /^[a-i][0-9][a-i][0-9]$/;
const RAW_TRUNCATED_MARKER = "[truncated]\n";

const DEFAULT_START_TIMEOUT = 15000;
const DEFAULT_STOP_TIMEOUT = 3000;
const DEFAULT_QUIT_TIMEOUT = 3000;
const DEFAULT_CONFIG_TIMEOUT = 5000;
const DEFAULT_MAX_RAW = 256 * 1024;
const DEFAULT_MAX_LINE = 256 * 1024;
const MAX_PONDER_MS = 10 * 60 * 1000;

function toInt(value, fallback) {
    const n = Number(value);
    return Number.isFinite(n) ? Math.trunc(n) : fallback;
}

function positiveInt(value, fallback, min, max) {
    if (value == null || value === "") { return fallback; }
    let n = toInt(value, fallback);
    if (!Number.isFinite(n)) { return fallback; }
    if (min != null && n < min) { n = min; }
    if (max != null && n > max) { n = max; }
    return n;
}

function hasLineBreak(text) {
    return /[\r\n]/.test(String(text == null ? "" : text));
}

function hasControlChar(text) {
    return /[\x00-\x1f\x7f]/.test(String(text == null ? "" : text));
}

function isValidFen(fen) {
    return typeof fen === "string" && fen.length > 0 && fen.length <= 256 &&
        !hasLineBreak(fen) && FEN_RE.test(fen);
}

function countMoves(tail) {
    if (!tail) { return 0; }
    const trimmed = tail.trim();
    return trimmed ? trimmed.split(/\s+/).length : 0;
}

/** positionCommand 只接受标准单行 UCI position，防止第二行命令注入。 */
function isValidPositionCommand(command) {
    if (typeof command !== "string" || command.length === 0 || command.length > 4096) { return false; }
    if (hasLineBreak(command) || hasControlChar(command)) { return false; }
    const cmd = command.trim();
    if (cmd !== command) { return false; }

    const fenMatch = /^position fen (.+?)( moves(?: [a-i][0-9][a-i][0-9])*)?$/.exec(cmd);
    if (fenMatch) {
        if (!FEN_RE.test(fenMatch[1])) { return false; }
        if (fenMatch[2] && countMoves(fenMatch[2].slice(" moves".length)) > 512) { return false; }
        return true;
    }
    const startMatch = /^position startpos( moves(?: [a-i][0-9][a-i][0-9])*)?$/.exec(cmd);
    if (startMatch) {
        if (startMatch[1] && countMoves(startMatch[1].slice(" moves".length)) > 512) { return false; }
        return true;
    }
    return false;
}

/** 输入校验：返回用于写入 UCI 的单个 position 命令。 */
function resolvePosition(fen, opts) {
    const options = opts || {};
    if (options.positionCommand != null) {
        if (typeof options.positionCommand !== "string" || hasLineBreak(options.positionCommand) ||
            hasControlChar(options.positionCommand)) {
            return { ok: false, error: "无效 positionCommand：必须是单行、无控制字符的 UCI position 命令" };
        }
        const command = options.positionCommand.trim();
        if (!isValidPositionCommand(command)) {
            return { ok: false, error: "无效 positionCommand：必须以 position fen/startpos 开头，moves 仅含合法 UCI 坐标" };
        }
        if (typeof fen === "string" && (hasLineBreak(fen) || hasControlChar(fen))) {
            return { ok: false, error: "无效 FEN：禁止换行或控制字符注入" };
        }
        return { ok: true, command: command };
    }
    if (!isValidFen(fen)) {
        return { ok: false, error: "无效 FEN：应带 走棋方/回合 字段（例「 w - - 0 1」），且必须单行" };
    }
    return { ok: true, command: "position fen " + fen };
}

function optionValueSafe(value) {
    return typeof value === "string" && value.length > 0 && value.length <= 1024 &&
        !hasLineBreak(value) && !hasControlChar(value);
}

function parseBestmove(line) {
    const m = /^bestmove\s+(\S+)(?:\s+ponder\s+(\S+))?/i.exec(line);
    if (!m) { return { move: null, ponderMove: null }; }
    return {
        move: m[1],
        ponderMove: m[2] && m[2] !== "(none)" ? m[2] : null
    };
}

/** 状态栏用的轻量 info 解析（完整结果仍交给 pikafish.node.js 的 _parseUci）。 */
function parseInfoStatus(line) {
    const out = { multipv: 1 };
    let m;
    m = /\bmultipv (\d+)/.exec(line); if (m) { out.multipv = parseInt(m[1], 10); }
    m = /\bdepth (\d+)/.exec(line); if (m) { out.depth = parseInt(m[1], 10); }
    m = /\bseldepth (\d+)/.exec(line); if (m) { out.seldepth = parseInt(m[1], 10); }
    m = /\bnodes (\d+)/.exec(line); if (m) { out.nodes = parseInt(m[1], 10); }
    m = /\bnps (\d+)/.exec(line); if (m) { out.nps = parseInt(m[1], 10); }
    m = /\bhashfull (\d+)/.exec(line); if (m) { out.hashfull = parseInt(m[1], 10); }
    m = /\btime (\d+)/.exec(line); if (m) { out.timeMs = parseInt(m[1], 10); }
    m = /\bscore (cp|mate) (-?\d+)/.exec(line);
    if (m) {
        if (m[1] === "cp") { out.scoreCp = parseInt(m[2], 10); out.mate = undefined; }
        else { out.mate = parseInt(m[2], 10); out.scoreCp = undefined; }
    }
    m = /\bpv ((?:[a-i][0-9][a-i][0-9])(?: [a-i][0-9][a-i][0-9])*)/.exec(line);
    if (m) { out.pv = m[1].split(" "); out.move = out.pv[0]; }
    return out;
}

function copyEngineFields(source, target) {
    ["scoreCp", "mate", "depth", "seldepth", "nodes", "nps", "hashfull", "timeMs"].forEach(function (key) {
        if (source && source[key] !== undefined) { target[key] = source[key]; }
    });
}

function normalizeOptions(raw) {
    const options = raw && typeof raw === "object" ? raw : {};
    const maxRaw = positiveInt(options.maxRawBytes != null ? options.maxRawBytes : options.maxLogBytes,
        DEFAULT_MAX_RAW, 4096, 16 * 1024 * 1024);
    return {
        binary: typeof options.binary === "string" ? options.binary : "",
        evalFile: options.evalFile == null ? null : String(options.evalFile),
        threads: positiveInt(options.threads, 2, 1, 1024),
        hashMb: positiveInt(options.hashMb != null ? options.hashMb : options.hash, 64, 1, 65536),
        contempt: positiveInt(options.contempt, 0, -1000, 1000),
        spawn: typeof options.spawn === "function" ? options.spawn : childProcess.spawn,
        startTimeout: positiveInt(options.startTimeout != null ? options.startTimeout : options.handshakeTimeout,
            DEFAULT_START_TIMEOUT, 1, 120000),
        stopTimeout: positiveInt(options.stopTimeout, DEFAULT_STOP_TIMEOUT, 1, 120000),
        quitTimeout: positiveInt(options.quitTimeout, DEFAULT_QUIT_TIMEOUT, 1, 120000),
        configTimeout: positiveInt(options.configTimeout, DEFAULT_CONFIG_TIMEOUT, 1, 120000),
        analyzeTimeout: options.analyzeTimeout != null ? positiveInt(options.analyzeTimeout, 0, 1, 3600000) : null,
        maxRawBytes: maxRaw,
        maxLineBytes: positiveInt(options.maxLineBytes, Math.max(DEFAULT_MAX_LINE, maxRaw), 4096, 32 * 1024 * 1024)
    };
}

class UciEngine {
    constructor(options) {
        this._opts = options;
        this._proc = null;
        this._ready = false;
        this._starting = null;
        this._quitting = null;
        this._search = null;
        this._searchSeq = 0;
        this._multiPv = 1;
        this._phase = "idle";
        this._lineWaiters = new Set();
        this._opChain = Promise.resolve();
        this._lastResult = null;
        this._lastSearchStartedAt = null;
        this._startInfo = null;
    }

    _isProcAlive(proc) {
        return !!(proc && proc.alive && !proc.closeNotified && !proc.invalidated);
    }

    _isReady() {
        return !!(this._ready && this._isProcAlive(this._proc) && this._proc.ready);
    }

    _notReady() {
        return { ok: false, error: "引擎未启动或未就绪（请先 start）" };
    }

    _withOpLock(fn) {
        const prev = this._opChain;
        let release;
        const gate = new Promise(function (resolve) { release = resolve; });
        this._opChain = prev.then(function () { return gate; }, function () { return gate; });
        return prev.then(fn, fn).finally(function () { release(); });
    }

    start() {
        const self = this;
        if (this._starting) { return this._starting; }
        if (this._isReady()) {
            return Promise.resolve(Object.assign({}, this._startInfo, { ok: true, already: true }));
        }
        if (this._quitting) {
            return Promise.resolve({ ok: false, error: "引擎正在退出，不能启动" });
        }
        this._starting = this._doStart().catch(function (e) {
            return { ok: false, error: "启动异常：" + String((e && e.message) || e) };
        }).then(function (result) {
            self._starting = null;
            return result;
        });
        return this._starting;
    }

    async _doStart() {
        const o = this._opts;
        if (!o.binary) { return { ok: false, error: "缺少引擎二进制路径（options.binary）" }; }
        if (hasLineBreak(o.binary) || hasControlChar(o.binary)) {
            return { ok: false, error: "无效 binary：禁止换行或控制字符" };
        }
        if (o.evalFile) {
            if (!optionValueSafe(o.evalFile)) {
                return { ok: false, error: "无效 evalFile：禁止换行或控制字符" };
            }
        }

        let child;
        try {
            child = o.spawn(o.binary, [], { stdio: ["pipe", "pipe", "pipe"], windowsHide: true });
        } catch (e) {
            return { ok: false, error: "无法启动引擎进程：" + String((e && e.message) || e) };
        }
        if (!child || typeof child.on !== "function" || !child.stdin || !child.stdout) {
            try { if (child && typeof child.kill === "function") { child.kill(); } } catch (e) { }
            return { ok: false, error: "spawn 未返回可用的子进程（需要 stdin/stdout）" };
        }

        const proc = {
            child: child,
            alive: true,
            ready: false,
            invalidated: false,
            closeNotified: false,
            collectOptions: true,
            startedAt: Date.now(),
            out: "",
            stderr: "",
            optionLines: [],
            engineName: null,
            exitWaiters: new Set(),
            killTimer: null,
            staleBestmoves: 0
        };
        this._proc = proc;
        this._ready = false;
        this._phase = "idle";
        this._attachProc(proc);

        const deadline = Date.now() + o.startTimeout;
        try {
            const uciWait = Math.max(1, deadline - Date.now());
            const handshake = await this._request(
                proc, "uci", function (line) { return line === "uciok"; }, uciWait, "uci/uciok 握手"
            );
            if (!handshake.ok) { return this._startFail(proc, handshake.error); }
            if (!this._isProcAlive(proc) || this._proc !== proc) {
                return this._startFail(proc, "UCI 握手期间引擎进程已退出");
            }

            const commands = [];
            if (o.evalFile) { commands.push("setoption name EvalFile value " + o.evalFile); }
            commands.push("setoption name Threads value " + o.threads);
            commands.push("setoption name Hash value " + o.hashMb);
            commands.push("setoption name Contempt value " + o.contempt);
            commands.push("setoption name Ponder value true");
            for (let i = 0; i < commands.length; i++) {
                if (!this._writeLine(proc, commands[i])) {
                    return this._startFail(proc, "配置引擎失败：" + commands[i]);
                }
            }

            const readyWait = Math.max(1, deadline - Date.now());
            const ready = await this._request(
                proc, "isready", function (line) { return line === "readyok"; }, readyWait, "isready/readyok 就绪"
            );
            if (!ready.ok) { return this._startFail(proc, ready.error); }
            if (!this._isProcAlive(proc) || this._proc !== proc) {
                return this._startFail(proc, "配置完成前引擎进程已退出");
            }

            proc.ready = true;
            proc.collectOptions = false;
            this._ready = true;
            this._phase = "idle";
            this._multiPv = 1;
            this._threads = o.threads;
            this._startInfo = {
                ok: true,
                backend: "uci-process",
                binary: o.binary,
                pid: child.pid == null ? null : child.pid,
                evalFile: o.evalFile || null,
                threads: o.threads,
                hashMb: o.hashMb,
                contempt: o.contempt,
                ponder: true,
                multiPv: this._multiPv,
                engine: proc.engineName,
                options: proc.optionLines.slice(0, 256),
                startedAt: proc.startedAt
            };
            return Object.assign({}, this._startInfo);
        } catch (e) {
            return this._startFail(proc, "启动异常：" + String((e && e.message) || e));
        }
    }

    _startFail(proc, error) {
        proc.collectOptions = false;
        if (this._proc === proc) { this._ready = false; this._phase = "idle"; }
        this._killProc(proc, "start-failed");
        const result = { ok: false, error: String(error || "引擎启动失败") };
        if (proc && proc.stderr) { result.stderr = proc.stderr.slice(-4096); }
        return result;
    }

    _attachProc(proc) {
        const self = this;
        const child = proc.child;
        try {
            if (child.stdout.setEncoding) { child.stdout.setEncoding("utf8"); }
            child.stdout.on("data", function (chunk) { self._onStdout(proc, chunk); });
        } catch (e) {
            this._onProcClose(proc, "无法读取引擎 stdout：" + String((e && e.message) || e));
            return;
        }
        if (child.stderr) {
            try {
                if (child.stderr.setEncoding) { child.stderr.setEncoding("utf8"); }
                child.stderr.on("data", function (chunk) {
                    const text = Buffer.isBuffer(chunk) ? chunk.toString("utf8") : String(chunk);
                    proc.stderr = (proc.stderr + text).slice(-8192);
                });
            } catch (e) { /* stderr 只用于诊断，读取失败不影响协议 */ }
        }
        try {
            if (child.stdin && typeof child.stdin.on === "function") {
                child.stdin.on("error", function (err) {
                    if (!proc.closeNotified) {
                        self._onProcClose(proc, "引擎 stdin 写入失败：" + String((err && err.message) || err));
                    }
                });
            }
            child.on("error", function (err) {
                self._onProcClose(proc, "引擎进程错误：" + String((err && err.message) || err));
            });
            child.on("exit", function (code, signal) {
                const detail = "code=" + (code == null ? "null" : code) +
                    (signal ? ", signal=" + signal : "");
                self._onProcClose(proc, "引擎进程退出（" + detail + "）");
            });
            child.on("close", function (code, signal) {
                const detail = "code=" + (code == null ? "null" : code) +
                    (signal ? ", signal=" + signal : "");
                self._onProcClose(proc, "引擎进程关闭（" + detail + "）");
            });
        } catch (e) {
            this._onProcClose(proc, "无法监听引擎进程事件：" + String((e && e.message) || e));
        }
    }

    _onStdout(proc, chunk) {
        if (!proc || proc.closeNotified || this._proc !== proc) { return; }
        let text;
        try {
            text = Buffer.isBuffer(chunk) ? chunk.toString("utf8") : String(chunk == null ? "" : chunk);
        } catch (e) { return; }
        if (!text) { return; }
        proc.out += text;

        while (true) {
            const cr = proc.out.indexOf("\r");
            const lf = proc.out.indexOf("\n");
            let index;
            if (cr < 0) { index = lf; } else if (lf < 0) { index = cr; } else { index = Math.min(cr, lf); }
            if (index < 0) { break; }
            const line = proc.out.slice(0, index);
            const ch = proc.out[index];
            const skip = ch === "\r" && proc.out[index + 1] === "\n" ? 2 : 1;
            proc.out = proc.out.slice(index + skip);
            this._onLine(proc, line);
            if (proc.closeNotified || this._proc !== proc) { return; }
        }
        if (proc.out.length > this._opts.maxLineBytes) {
            proc.out = proc.out.slice(-this._opts.maxLineBytes);
            proc.lineTruncated = true;
        }
    }

    _onLine(proc, rawLine) {
        if (!proc) { return; }
        let line = String(rawLine == null ? "" : rawLine).trim();
        if (!line) { return; }
        if (line.length > this._opts.maxRawBytes) { line = line.slice(-this._opts.maxRawBytes); }
        proc.lastLineAt = Date.now();

        const waiters = Array.from(this._lineWaiters);
        for (let i = 0; i < waiters.length; i++) {
            const waiter = waiters[i];
            if (waiter.proc === proc && !waiter.done && waiter.predicate(line)) {
                this._settleWaiter(waiter, { ok: true, line: line });
            }
        }

        if (proc.collectOptions) {
            if (line.indexOf("id name ") === 0) { proc.engineName = line.slice("id name ".length).trim(); }
            else if (line.indexOf("option name ") === 0 && proc.optionLines.length < 256) {
                proc.optionLines.push(line);
            }
        }

        const search = this._search;
        if (search && search.proc === proc && !search.done) {
            if (line === "readyok" || line === "uciok") { return; }
            this._appendSearchLine(search, line);
            if (/^bestmove\b/i.test(line)) {
                this._handleBestmove(proc, line);
            } else if (/^info\b/i.test(line)) {
                this._updateSearchInfo(search, line);
            }
            return;
        }
        if (/^bestmove\b/i.test(line)) { proc.staleBestmoves += 1; }
    }

    _handleBestmove(proc, line) {
        const search = this._search;
        if (!search || search.proc !== proc || search.done) {
            proc.staleBestmoves += 1;
            return;
        }
        const parsed = parseBestmove(line);
        search.bestmove = parsed.move;
        search.ponderMove = parsed.ponderMove;
        search.bestmoveAt = Date.now();
        this._finishSearch(search, {});
    }

    _appendSearchLine(search, line) {
        search.raw += line + "\n";
        if (search.raw.length > this._opts.maxRawBytes) {
            const keep = Math.max(0, this._opts.maxRawBytes - RAW_TRUNCATED_MARKER.length);
            search.raw = RAW_TRUNCATED_MARKER + search.raw.slice(-keep);
            search.rawTruncated = true;
        }
    }

    _updateSearchInfo(search, line) {
        const parsed = parseInfoStatus(line);
        const multipv = parsed.multipv || 1;
        const prev = search.infos[multipv] || {};
        const next = Object.assign({}, prev, parsed);
        next.multipv = multipv;
        search.infos[multipv] = next;
        if (multipv === 1) { search.info = next; }
        else if (!search.info) { search.info = next; }
    }

    _request(proc, command, predicate, timeoutMs, label) {
        const self = this;
        return new Promise(function (resolve) {
            if (!self._isProcAlive(proc) || self._proc !== proc) {
                resolve({ ok: false, error: label + "：引擎进程未运行" });
                return;
            }
            const timeout = Math.max(1, Math.floor(timeoutMs));
            const waiter = {
                proc: proc,
                predicate: predicate,
                label: label,
                done: false,
                timer: null,
                resolve: resolve
            };
            waiter.timer = setTimeout(function () {
                self._settleWaiter(waiter, { ok: false, error: label + "超时（" + timeout + "ms）" });
            }, timeout);
            self._lineWaiters.add(waiter);
            if (!self._writeLine(proc, command)) {
                self._settleWaiter(waiter, { ok: false, error: label + "：写入引擎失败" });
            }
        });
    }

    _settleWaiter(waiter, result) {
        if (!waiter || waiter.done) { return; }
        waiter.done = true;
        if (waiter.timer) { clearTimeout(waiter.timer); waiter.timer = null; }
        this._lineWaiters.delete(waiter);
        try { waiter.resolve(result); } catch (e) { /* resolve 本身不会抛，防御 */ }
    }

    _failLineWaiters(proc, error) {
        const waiters = Array.from(this._lineWaiters);
        for (let i = 0; i < waiters.length; i++) {
            const waiter = waiters[i];
            if (!proc || waiter.proc === proc) {
                this._settleWaiter(waiter, { ok: false, error: String(error) });
            }
        }
    }

    _writeLine(proc, line) {
        if (!this._isProcAlive(proc)) { return false; }
        if (typeof line !== "string" || line.length === 0 || hasLineBreak(line)) { return false; }
        const stdin = proc.child && proc.child.stdin;
        if (!stdin || typeof stdin.write !== "function") { return false; }
        if (stdin.destroyed || stdin.writable === false) { return false; }
        try {
            stdin.write(line + "\n");
            return true;
        } catch (e) {
            return false;
        }
    }

    _createSearch(mode, proc, meta) {
        const id = ++this._searchSeq;
        let resolve;
        const promise = new Promise(function (res) { resolve = res; });
        // 后台 ponder 即使调用方不 await，也不会产生 unhandled rejection。
        promise.catch(function () { });
        return {
            id: id,
            token: "search-" + id,
            mode: mode,
            proc: proc,
            meta: meta || {},
            promise: promise,
            resolve: resolve,
            done: false,
            startedAt: Date.now(),
            raw: "",
            rawTruncated: false,
            infos: {},
            info: null,
            bestmove: null,
            bestmoveAt: null,
            ponderMove: null,
            ponderHit: false,
            ponderHitAt: null,
            additionalMs: null,
            externalStop: false,
            stopSent: false,
            stopAt: null,
            hitTimer: null,
            timer: null,
            timeoutKind: null
        };
    }

    async _setMultiPv(multiPv) {
        if (multiPv === this._multiPv) { return { ok: true, multiPv: multiPv }; }
        if (!this._isReady() || this._search) {
            return { ok: false, error: "只能在引擎空闲时修改 MultiPV" };
        }
        const proc = this._proc;
        if (!this._writeLine(proc, "setoption name MultiPV value " + multiPv)) {
            const result = { ok: false, error: "设置 MultiPV 失败" };
            this._killProc(proc, "setoption-multipv-failed");
            return result;
        }
        const ready = await this._request(
            proc, "isready", function (line) { return line === "readyok"; },
            this._opts.configTimeout, "MultiPV isready/readyok"
        );
        if (!ready.ok) {
            this._killProc(proc, "multipv-ready-failed");
            return { ok: false, error: ready.error };
        }
        this._multiPv = multiPv;
        return { ok: true, multiPv: multiPv };
    }

    async _setThreads(threads) {
        if (threads === this._threads) { return { ok: true }; }
        if (!this._isReady() || this._search) { return { ok: false, error: "只能在空闲时调整线程数" }; }
        const proc = this._proc;
        if (!this._writeLine(proc, "setoption name Threads value " + threads)) { return { ok: false, error: "设置线程失败" }; }
        const ready = await this._request(proc, "isready", line => line === "readyok", this._opts.configTimeout, "Threads ready");
        if (!ready.ok) { this._killProc(proc, "threads-ready-failed"); return ready; }
        this._threads = threads;
        return { ok: true };
    }

    async analyze(fen, opts) {
        let options;
        if (opts != null && typeof opts === "object") {
            options = opts;
        } else if (typeof opts === "number") {
            options = {
                movetime: opts,
                timeout: arguments[2],
                multiPv: arguments[3]
            };
        } else {
            options = {};
        }

        const position = resolvePosition(fen, options);
        if (!position.ok) { return position; }
        if (!this._isReady()) { return this._notReady(); }

        const movetime = positiveInt(
            options.movetime != null ? options.movetime : options.movetimeMs, 1000, 1, 86400000
        );
        let multiPv = this._multiPv;
        if (options.multiPv != null) {
            multiPv = positiveInt(options.multiPv, 0, 1, 500);
            if (multiPv < 1) { return { ok: false, error: "multiPv 必须为 1..500 的整数" }; }
        }
        let timeout;
        if (options.timeout != null || options.timeoutMs != null) {
            timeout = positiveInt(options.timeout != null ? options.timeout : options.timeoutMs,
                Math.max(8000, movetime + 5000), 1, 3600000);
        } else if (this._opts.analyzeTimeout != null) {
            timeout = this._opts.analyzeTimeout;
        } else {
            timeout = Math.max(8000, movetime + 5000);
        }

        const self = this;
        return this._withOpLock(async function () {
            if (!self._isReady()) { return self._notReady(); }
            if (self._search) {
                const drained = await self.stop();
                if (!drained || drained.ok !== true) {
                    return {
                        ok: false,
                        error: "排空旧搜索失败：" + String((drained && drained.error) || "未知错误"),
                        raw: drained && drained.raw
                    };
                }
                if (!self._isReady()) { return self._notReady(); }
            }
            const configured = await self._setThreads(positiveInt(options.threads, self._opts.threads, 1, 1024));
            if (!configured.ok) { return configured; }
            if (options.multiPv != null) {
                const changed = await self._setMultiPv(multiPv);
                if (!changed.ok) { return changed; }
            }
            if (self._search) {
                return { ok: false, error: "引擎正忙：" + self._phase };
            }
            if (!self._isReady()) { return self._notReady(); }

            const proc = self._proc;
            const search = self._createSearch("analyze", proc, {
                movetime: movetime,
                multiPv: multiPv,
                timeout: timeout,
                positionCommand: position.command
            });
            self._search = search;
            self._phase = "thinking";
            self._lastSearchStartedAt = search.startedAt;

            const positionOk = self._writeLine(proc, position.command);
            const goOk = positionOk && self._writeLine(proc, "go movetime " + movetime);
            if (!positionOk || !goOk) {
                self._finishSearch(search, { error: "发送前台搜索命令失败" });
                self._killProc(proc, "analyze-write-failed");
                return await search.promise;
            }
            if (!search.done) { self._armSearchTimeout(search, timeout, "analyze"); }
            return await search.promise;
        });
    }

    async beginPonder(fen, opts) {
        const options = opts && typeof opts === "object" ? opts : {};
        const position = resolvePosition(fen, options);
        if (!position.ok) { return position; }
        if (!this._isReady()) { return this._notReady(); }

        let multiPv = this._multiPv;
        if (options.multiPv != null) {
            multiPv = positiveInt(options.multiPv, 0, 1, 500);
            if (multiPv < 1) { return { ok: false, error: "multiPv 必须为 1..500 的整数" }; }
        }

        const self = this;
        return this._withOpLock(async function () {
            if (!self._isReady()) { return self._notReady(); }
            if (self._search) {
                return { ok: false, error: "引擎正忙（" + self._phase + "）", phase: self._phase };
            }
            const configured = await self._setThreads(positiveInt(options.threads, self._opts.threads, 1, 1024));
            if (!configured.ok) { return configured; }
            if (options.multiPv != null) {
                const changed = await self._setMultiPv(multiPv);
                if (!changed.ok) { return changed; }
            }
            if (self._search) {
                return { ok: false, error: "引擎正忙（" + self._phase + "）", phase: self._phase };
            }
            if (!self._isReady()) { return self._notReady(); }

            const proc = self._proc;
            const search = self._createSearch("ponder", proc, {
                multiPv: multiPv,
                positionCommand: position.command
            });
            self._search = search;
            self._phase = "pondering";
            self._lastSearchStartedAt = search.startedAt;

            const positionOk = self._writeLine(proc, position.command);
            const goOk = positionOk && self._writeLine(proc, "go ponder infinite");
            if (!positionOk || !goOk) {
                self._finishSearch(search, { error: "发送后台思考命令失败" });
                self._killProc(proc, "ponder-write-failed");
                return { ok: false, id: search.id, error: "发送后台思考命令失败" };
            }
            return { ok: true, id: search.id, token: search.token, phase: "pondering" };
        });
    }

    async finishPonder(additionalMs) {
        const search = this._search;
        if (!search || search.mode !== "ponder") {
            return { ok: false, error: "没有进行中的后台思考", phase: this._phase };
        }
        if (search.ponderHit || this._phase !== "pondering") {
            return { ok: false, error: "后台思考已命中或正在停止", phase: this._phase };
        }

        let ms;
        if (additionalMs == null) { ms = 1800; }
        else if (typeof additionalMs === "object" && additionalMs) {
            ms = toInt(additionalMs.additionalMs, 1800);
        } else {
            ms = toInt(additionalMs, 1800);
        }
        if (ms < 0) { ms = 0; }
        if (ms > MAX_PONDER_MS) { ms = MAX_PONDER_MS; }

        if (!this._isReady() || search.proc !== this._proc) {
            this._finishSearch(search, { error: "引擎进程已不可用" });
            return await search.promise;
        }

        search.ponderHit = true;
        search.ponderHitAt = Date.now();
        search.additionalMs = ms;
        this._phase = "thinking";
        if (!this._writeLine(search.proc, "ponderhit")) {
            this._finishSearch(search, { error: "发送 ponderhit 失败" });
            this._killProc(search.proc, "ponderhit-write-failed");
            return await search.promise;
        }
        if (search.done) { return await search.promise; }

        const self = this;
        search.hitTimer = setTimeout(function () {
            search.hitTimer = null;
            if (search.done || self._search !== search || !search.ponderHit) { return; }
            self._sendStopForSearch(search);
        }, ms);
        this._armSearchTimeout(search, ms + this._opts.stopTimeout + 1000, "ponder");
        return await search.promise;
    }

    async stop() {
        const search = this._search;
        if (!search) {
            if (this._phase !== "idle") { this._phase = "idle"; }
            return { ok: true, stopped: false, phase: "idle" };
        }

        search.externalStop = true;
        if (!search.stopSent) {
            this._sendStopForSearch(search);
        } else {
            if (search.hitTimer) { clearTimeout(search.hitTimer); search.hitTimer = null; }
            if (!search.done && search.timeoutKind !== "stop") {
                this._armSearchTimeout(search, this._opts.stopTimeout, "stop");
            }
        }
        return await search.promise;
    }

    _sendStopForSearch(search) {
        if (!search || search.done) { return; }
        if (search.stopSent) { return; }
        if (search.hitTimer) { clearTimeout(search.hitTimer); search.hitTimer = null; }

        search.stopSent = true;
        search.stopAt = Date.now();
        if (this._search === search) { this._phase = "stopping"; }
        if (!this._writeLine(search.proc, "stop")) {
            this._finishSearch(search, { error: "发送 stop 失败" });
            this._killProc(search.proc, "stop-write-failed");
            return;
        }
        if (!search.done) { this._armSearchTimeout(search, this._opts.stopTimeout, "stop"); }
    }

    _armSearchTimeout(search, timeoutMs, kind) {
        const self = this;
        if (!search || search.done) { return; }
        if (search.timer) { clearTimeout(search.timer); search.timer = null; }
        const ms = Math.max(1, toInt(timeoutMs, 1));
        const labels = {
            analyze: "分析超时（" + ms + "ms 内未收到 bestmove）",
            stop: "等待 stop 后的 bestmove 超时（" + ms + "ms）",
            ponder: "等待 ponderhit 后的 bestmove 超时（" + ms + "ms）"
        };
        const error = labels[kind] || ("搜索超时（" + ms + "ms）");
        search.timeoutKind = kind;
        search.timer = setTimeout(function () {
            search.timer = null;
            search.timeoutKind = null;
            if (search.done) { return; }
            self._finishSearch(search, { error: error, timedOut: true });
            self._killProc(search.proc, error);
        }, ms);
    }

    _clearSearchTimers(search) {
        if (!search) { return; }
        if (search.timer) { clearTimeout(search.timer); search.timer = null; }
        if (search.hitTimer) { clearTimeout(search.hitTimer); search.hitTimer = null; }
        search.timeoutKind = null;
    }

    _resultRaw(search) {
        return search.raw || "";
    }

    _buildResult(search) {
        const raw = this._resultRaw(search);
        let parsed = null;
        try { parsed = parseUci(raw); } catch (e) {
            return {
                ok: false,
                id: search.id,
                error: "解析引擎输出失败：" + String((e && e.message) || e),
                raw: raw
            };
        }
        if (!parsed || parsed.ok !== true) {
            return {
                ok: false,
                id: search.id,
                error: (parsed && parsed.error) || "引擎未返回可解析的 bestmove",
                raw: raw
            };
        }

        const result = {
            ok: true,
            id: search.id,
            move: parsed.move,
            pv: Array.isArray(parsed.pv) ? parsed.pv.slice() : [],
            candidates: Array.isArray(parsed.candidates) ? parsed.candidates : [],
            threads: this._threads,
            hashMb: this._opts.hashMb,
            positionPlies: search.meta.positionCommand && search.meta.positionCommand.indexOf(" moves ") >= 0
                ? countMoves(search.meta.positionCommand.split(" moves ")[1]) : 0,
            raw: raw
        };
        copyEngineFields(parsed, result);
        const info = search.info || {};
        if (result.pv.length === 0 && Array.isArray(info.pv)) { result.pv = info.pv.slice(); }
        if (result.depth === undefined && info.depth !== undefined) { result.depth = info.depth; }
        if (result.seldepth === undefined && info.seldepth !== undefined) { result.seldepth = info.seldepth; }
        if (result.nodes === undefined && info.nodes !== undefined) { result.nodes = info.nodes; }
        if (result.nps === undefined && info.nps !== undefined) { result.nps = info.nps; }
        if (result.hashfull === undefined && info.hashfull !== undefined) { result.hashfull = info.hashfull; }
        if (result.scoreCp === undefined && info.scoreCp !== undefined) { result.scoreCp = info.scoreCp; }
        if (result.mate === undefined && info.mate !== undefined) { result.mate = info.mate; }
        if (result.timeMs === undefined) {
            result.timeMs = info.timeMs !== undefined ? info.timeMs : Math.max(0, Date.now() - search.startedAt);
        }
        if (search.ponderMove && MOVE_RE.test(search.ponderMove)) { result.ponderMove = search.ponderMove; }
        return result;
    }

    _finishSearch(search, outcome) {
        if (!search || search.done) { return; }
        search.done = true;
        this._clearSearchTimers(search);
        if (this._search === search) {
            this._search = null;
            this._phase = "idle";
        }

        let result;
        if (outcome && outcome.error) {
            result = {
                ok: false,
                id: search.id,
                error: String(outcome.error),
                raw: this._resultRaw(search)
            };
            if (outcome.timedOut) { result.timedOut = true; }
            if (outcome.quit) { result.quit = true; }
        } else {
            result = this._buildResult(search);
            result.id = search.id;
            if (search.externalStop) { result.stopped = true; }
            if (search.ponderHit) { result.ponderHit = true; }
            if (search.additionalMs != null) { result.additionalMs = search.additionalMs; }
            if (search.bestmoveAt) { result.elapsedMs = Math.max(0, search.bestmoveAt - search.startedAt); }
        }
        this._lastResult = result;
        this._lastSearchStartedAt = search.startedAt;
        try { search.resolve(result); } catch (e) { /* 结果对象已构造，忽略防御 */ }
    }

    _killProc(proc, reason) {
        if (!proc) { return; }
        proc.invalidated = true;
        if (this._proc === proc) { this._ready = false; }
        try {
            if (proc.child && typeof proc.child.kill === "function") { proc.child.kill(); }
        } catch (e) { /* 进程可能已退出 */ }
        if (!proc.closeNotified && !proc.killTimer) {
            proc.killTimer = setTimeout(function () {
                proc.killTimer = null;
                if (proc.closeNotified || !proc.alive) { return; }
                try {
                    if (proc.child && typeof proc.child.kill === "function") { proc.child.kill("SIGKILL"); }
                } catch (e) { /* 已结束 */ }
            }, 300);
            if (proc.killTimer.unref) { proc.killTimer.unref(); }
        }
    }

    _onProcClose(proc, reason) {
        if (!proc || proc.closeNotified) { return; }
        const message = String(reason || "引擎进程已结束");

        if (proc.out && proc.out.trim()) {
            const tail = proc.out.trim();
            proc.out = "";
            try { this._onLine(proc, tail); } catch (e) { /* 尾行尽力解析 */ }
        }

        proc.closeNotified = true;
        proc.alive = false;
        proc.ready = false;
        proc.collectOptions = false;
        if (proc.killTimer) { clearTimeout(proc.killTimer); proc.killTimer = null; }
        if (this._proc === proc) { this._ready = false; }

        this._failLineWaiters(proc, message);
        const exitWaiters = Array.from(proc.exitWaiters);
        for (let i = 0; i < exitWaiters.length; i++) {
            exitWaiters[i].finish(true);
        }

        const search = this._search;
        if (search && search.proc === proc) {
            this._finishSearch(search, { error: message });
        }
        if (this._proc === proc && !this._search) { this._phase = "idle"; }
    }

    _waitForExit(proc, timeoutMs) {
        if (!proc || !proc.alive || proc.closeNotified) { return Promise.resolve(true); }
        return new Promise(function (resolve) {
            const entry = {
                timer: null,
                done: false,
                finish: function (value) {
                    if (entry.done) { return; }
                    entry.done = true;
                    if (entry.timer) { clearTimeout(entry.timer); entry.timer = null; }
                    proc.exitWaiters.delete(entry);
                    resolve(value);
                }
            };
            entry.timer = setTimeout(function () { entry.finish(false); }, Math.max(1, timeoutMs));
            proc.exitWaiters.add(entry);
        });
    }

    async quit() {
        if (this._quitting) { return this._quitting; }
        const self = this;
        this._quitting = this._doQuit().catch(function (e) {
            return { ok: false, error: "退出异常：" + String((e && e.message) || e) };
        }).then(function (result) {
            self._quitting = null;
            return result;
        });
        return this._quitting;
    }

    async _doQuit() {
        const proc = this._proc;
        const search = this._search;
        if (search) { this._finishSearch(search, { error: "引擎已退出（quit）", quit: true }); }
        this._ready = false;
        this._phase = "idle";
        this._failLineWaiters(proc, "引擎已退出（quit）");

        if (!proc) { return { ok: true, exited: true, already: true }; }
        if (!proc.alive || proc.closeNotified) {
            if (this._proc === proc) { this._proc = null; }
            return { ok: true, exited: true, already: true };
        }

        const wrote = this._writeLine(proc, "quit");
        let exited = wrote ? await this._waitForExit(proc, this._opts.quitTimeout) : false;

        if (!exited) {
            this._killProc(proc, "quit-timeout");
            exited = await this._waitForExit(proc, 300);
        } else if (proc.killTimer) {
            clearTimeout(proc.killTimer);
            proc.killTimer = null;
        }
        if (this._proc === proc) { this._proc = null; }
        if (exited) {
            return wrote ? { ok: true, exited: true } : { ok: true, exited: true, forced: true };
        }
        return { ok: false, error: "退出超时，已强制终止引擎进程", forced: true };
    }

    status() {
        const search = this._search;
        const last = this._lastResult;
        const info = search ? (search.info || null) : null;
        const fallback = !search && last && last.ok === true ? last : {};
        const now = Date.now();
        const activeElapsed = search ? Math.max(0, now - search.startedAt) : 0;
        const pick = function (key) {
            if (info && info[key] !== undefined) { return info[key]; }
            return fallback[key];
        };
        const status = {
            running: this._isReady(),
            phase: this._phase,
            id: search ? search.id : (last ? last.id : null),
            mode: search ? search.mode : null,
            startedAt: search ? search.startedAt : null,
            elapsedMs: activeElapsed,
            depth: pick("depth"),
            seldepth: pick("seldepth"),
            timeMs: info && info.timeMs !== undefined ? info.timeMs : (search ? activeElapsed : fallback.timeMs),
            nodes: pick("nodes"),
            nps: pick("nps"),
            hashfull: pick("hashfull"),
            scoreCp: pick("scoreCp"),
            mate: pick("mate"),
            pv: info && Array.isArray(info.pv) ? info.pv.slice() : (Array.isArray(fallback.pv) ? fallback.pv.slice() : []),
            move: search && search.bestmove ? search.bestmove : (fallback.move || null),
            bestmove: search && search.bestmove ? search.bestmove : (fallback.move || null),
            multipv: this._multiPv,
            threads: this._threads,
            hashMb: this._opts.hashMb,
            positionPlies: search && search.meta.positionCommand && search.meta.positionCommand.indexOf(" moves ") >= 0
                ? countMoves(search.meta.positionCommand.split(" moves ")[1]) : (search ? 0 : fallback.positionPlies),
            ponderHit: !!(search && search.ponderHit),
            stopSent: !!(search && search.stopSent),
            lastResult: last || null
        };
        if (this._proc && this._proc.child) { status.pid = this._proc.child.pid == null ? null : this._proc.child.pid; }
        return status;
    }
}

function create(options) {
    return new UciEngine(normalizeOptions(options));
}

module.exports = { create: create };
