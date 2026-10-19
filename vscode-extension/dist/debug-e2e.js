"use strict";
var __create = Object.create;
var __defProp = Object.defineProperty;
var __getOwnPropDesc = Object.getOwnPropertyDescriptor;
var __getOwnPropNames = Object.getOwnPropertyNames;
var __getProtoOf = Object.getPrototypeOf;
var __hasOwnProp = Object.prototype.hasOwnProperty;
var __copyProps = (to, from, except, desc) => {
  if (from && typeof from === "object" || typeof from === "function") {
    for (let key of __getOwnPropNames(from))
      if (!__hasOwnProp.call(to, key) && key !== except)
        __defProp(to, key, { get: () => from[key], enumerable: !(desc = __getOwnPropDesc(from, key)) || desc.enumerable });
  }
  return to;
};
var __toESM = (mod, isNodeMode, target) => (target = mod != null ? __create(__getProtoOf(mod)) : {}, __copyProps(
  // If the importer is in node compatibility mode or this is not an ESM
  // file that has been converted to a CommonJS file using a Babel-
  // compatible transform (i.e. "__esModule" has not been set), then set
  // "default" to the CommonJS "module.exports" for node compatibility.
  isNodeMode || !mod || !mod.__esModule ? __defProp(target, "default", { value: mod, enumerable: true }) : target,
  mod
));

// test/debug-phone.ts
var net = __toESM(require("net"));

// src/connection.ts
var zlib = __toESM(require("zlib"));
var import_events = require("events");

// src/protocol.ts
var crypto = __toESM(require("crypto"));
var TYPE_JSON = 1;
var TYPE_BYTES = 2;
var TYPE_GZIP_JSON = 3;
var TYPE_GZIP_BYTES = 4;
var MAX_FRAME_LENGTH = 256 * 1024 * 1024;
function encodeFrame(type, payload) {
  const frame = Buffer.allocUnsafe(8 + payload.length);
  frame.writeUInt32BE(payload.length, 0);
  frame.writeUInt32BE(type >>> 0, 4);
  payload.copy(frame, 8);
  return frame;
}
function encodeJsonFrame(value) {
  return encodeFrame(TYPE_JSON, Buffer.from(JSON.stringify(value), "utf8"));
}
function md5Hex(data) {
  return crypto.createHash("md5").update(data).digest("hex");
}
var FrameDecoder = class {
  buffer = Buffer.alloc(0);
  append(chunk) {
    this.buffer = this.buffer.length === 0 ? chunk : Buffer.concat([this.buffer, chunk]);
    const frames = [];
    while (this.buffer.length >= 8) {
      const length = this.buffer.readUInt32BE(0);
      const type = this.buffer.readUInt32BE(4);
      if (length > MAX_FRAME_LENGTH) {
        throw new Error(`\u5E27\u957F\u5EA6\u975E\u6CD5\uFF1A${length}`);
      }
      if (this.buffer.length < 8 + length) {
        break;
      }
      frames.push({ type, payload: this.buffer.subarray(8, 8 + length) });
      this.buffer = this.buffer.subarray(8 + length);
    }
    return frames;
  }
};

// src/connection.ts
var DeviceConnection = class extends import_events.EventEmitter {
  constructor(socket, label, log) {
    super();
    this.label = label;
    this.log = log;
    this.socket = socket;
    socket.setNoDelay(true);
    socket.on("data", (chunk) => this.onData(chunk));
    socket.on("close", () => this.onClosed());
    socket.on("error", (error) => {
      this.log(`[\u8FDE\u63A5 ${this.label}] socket \u9519\u8BEF\uFF1A${error.message}`);
    });
  }
  socket;
  hello = null;
  /** 是否已完成 hello 握手。 */
  attached = false;
  /** 最近一次 run / run_project 的 id，用于 stop。 */
  lastRunId;
  decoder = new FrameDecoder();
  nextMessageId = 1;
  pending = /* @__PURE__ */ new Map();
  bufferedBytes = /* @__PURE__ */ new Map();
  byteWaiters = /* @__PURE__ */ new Map();
  helloAnswered = false;
  closed = false;
  /** 设备标识（hello.device_id 优先，握手前用连接标签）。 */
  get key() {
    return this.hello?.device_id || this.label;
  }
  get deviceName() {
    return this.hello?.device_name || this.label;
  }
  isClosed() {
    return this.closed;
  }
  close() {
    this.socket.destroy();
  }
  // ---- 命令 ----
  /** `command`：run / stop / stopAll / save / list_files / read_file / write_file … */
  async sendCommand(data, timeoutMs = 2e4) {
    const id = this.nextMessageId++;
    this.sendRaw(encodeJsonFrame({ id, type: "command", data }));
    return this.awaitResponse(id, timeoutMs);
  }
  async run(name, id, script2) {
    this.lastRunId = id;
    await this.sendCommand({ command: "run", id, name, script: script2 });
  }
  async stop(id) {
    const target = id ?? this.lastRunId;
    if (!target) {
      await this.sendCommand({ command: "stopAll" });
      return;
    }
    await this.sendCommand({ command: "stop", id: target });
  }
  async stopAll() {
    await this.sendCommand({ command: "stopAll" });
  }
  async save(name, script2) {
    await this.sendCommand({ command: "save", id: name, name, script: script2 });
  }
  async writeFile(path, text) {
    const result = await this.sendCommand({ command: "write_file", path, script: text });
    if (result?.error) {
      throw new Error(String(result.error));
    }
  }
  async listFiles(path, recursive) {
    const result = await this.sendCommand(
      { command: "list_files", path, recursive },
      recursive ? 6e4 : 2e4
    );
    if (result?.error) {
      throw new Error(String(result.error));
    }
    return {
      files: Array.isArray(result?.files) ? result.files : [],
      dirs: Array.isArray(result?.dirs) ? result.dirs : [],
      truncated: Boolean(result?.truncated)
    };
  }
  /** 读取设备文件：内容走 bytes 帧，response 里带 md5 用于配对。 */
  async readFile(path, timeoutMs = 3e4) {
    return this.readBytesVia({ command: "read_file", path }, timeoutMs);
  }
  /** 布局分析：无障碍捕获当前窗口，返回 XML 文本（无障碍未开启时 reject）。 */
  async inspectLayout(timeoutMs = 2e4) {
    const bytes = await this.readBytesVia({ command: "inspect_layout" }, timeoutMs);
    return bytes.toString("utf8");
  }
  /** 发一条返回 bytes（配 md5）的命令：read_file / inspect_layout 共用。 */
  async readBytesVia(data, timeoutMs) {
    const id = this.nextMessageId++;
    this.sendRaw(encodeJsonFrame({ id, type: "command", data }));
    const result = await this.awaitResponse(id, timeoutMs);
    if (result?.error) {
      throw new Error(String(result.error));
    }
    const md5 = String(result?.md5 ?? "");
    const buffered = this.bufferedBytes.get(md5);
    if (buffered) {
      this.bufferedBytes.delete(md5);
      return buffered;
    }
    return this.waitForBytes(md5, 5e3);
  }
  /**
   * `bytes_command`：先发 zip 字节帧再发带 md5 的 json（与官方扩展顺序一致；
   * 设备端两种顺序都能配对）。
   */
  async sendBytesCommand(command, name, archive, deletedFiles, timeoutMs = 18e4) {
    const id = this.nextMessageId++;
    const md5 = md5Hex(archive);
    this.sendRaw(encodeFrame(TYPE_BYTES, archive));
    this.sendRaw(
      encodeJsonFrame({
        id,
        type: "bytes_command",
        md5,
        data: { command, id: name, name, deletedFiles, override: true }
      })
    );
    if (command === "run_project") {
      this.lastRunId = name;
    }
    return this.awaitResponse(id, timeoutMs);
  }
  // ---- 断点调试（Rhino） ----
  /** 启动调试会话：断点行号 1-based；事件通过 `debugEvent` 事件抛出。 */
  async debugRun(name, script2, breakpoints) {
    await this.sendCommand({ command: "debug_run", name, script: script2, breakpoints }, 2e4);
  }
  async debugSetBreakpoints(lines) {
    await this.sendCommand({ command: "debug_set_breakpoints", breakpoints: lines }, 2e4);
  }
  async debugResume(action) {
    await this.sendCommand({ command: "debug_resume", action }, 2e4);
  }
  async debugEvaluate(expr, frame) {
    const result = await this.sendCommand({ command: "debug_evaluate", expr, frame }, 2e4);
    if (result?.error) {
      throw new Error(String(result.error));
    }
    return String(result?.result ?? "");
  }
  async debugVariables(ref) {
    const result = await this.sendCommand({ command: "debug_variables", ref }, 2e4);
    if (result?.error) {
      throw new Error(String(result.error));
    }
    return Array.isArray(result?.variables) ? result.variables : [];
  }
  async debugTerminate() {
    try {
      await this.sendCommand({ command: "debug_terminate" }, 2e4);
    } catch {
    }
  }
  // ---- 内部 ----
  sendRaw(frame) {
    if (this.closed) {
      return;
    }
    this.socket.write(frame);
  }
  onData(chunk) {
    let frames;
    try {
      frames = this.decoder.append(chunk);
    } catch (error) {
      this.log(`[\u8FDE\u63A5 ${this.label}] ${error.message}`);
      this.close();
      return;
    }
    for (const frame of frames) {
      this.dispatch(frame.type, frame.payload);
    }
  }
  dispatch(type, payload) {
    if (type === TYPE_BYTES || type === TYPE_GZIP_BYTES) {
      const data = type === TYPE_GZIP_BYTES ? zlib.gunzipSync(payload) : payload;
      this.onBytes(data);
      return;
    }
    if (type !== TYPE_JSON && type !== TYPE_GZIP_JSON) {
      this.log(`[\u8FDE\u63A5 ${this.label}] \u672A\u77E5\u5E27\u7C7B\u578B ${type}`);
      return;
    }
    const text = type === TYPE_GZIP_JSON ? zlib.gunzipSync(payload).toString("utf8") : payload.toString("utf8");
    let message;
    try {
      message = JSON.parse(text);
    } catch {
      this.log(`[\u8FDE\u63A5 ${this.label}] \u65E0\u6CD5\u89E3\u6790 JSON \u5E27`);
      return;
    }
    this.onMessage(message);
  }
  onMessage(message) {
    const type = typeof message?.type === "string" ? message.type : "";
    if (type === "hello") {
      this.onHello(message);
      return;
    }
    if (type === "log") {
      this.emit("log", String(message?.data?.log ?? ""));
      return;
    }
    if (type === "debug_event") {
      this.emit("debugEvent", message?.data ?? {});
      return;
    }
    if (type.startsWith("response_")) {
      const id = Number(type.slice("response_".length));
      const pending = this.pending.get(id);
      if (pending) {
        this.pending.delete(id);
        clearTimeout(pending.timer);
        pending.resolve(message?.data ?? {});
      }
      return;
    }
    this.log(`[\u8FDE\u63A5 ${this.label}] \u672A\u5904\u7406\u7684\u6D88\u606F\uFF1A${type}`);
  }
  onHello(message) {
    if (!this.helloAnswered) {
      this.helloAnswered = true;
      this.sendRaw(
        encodeJsonFrame({
          id: this.nextMessageId++,
          type: "hello",
          data: { client_version: 4 }
        })
      );
    }
    this.socket.setTimeout(0);
    this.hello = message?.data ?? {};
    if (!this.attached) {
      this.attached = true;
      this.emit("connected");
    }
  }
  onBytes(payload) {
    const md5 = md5Hex(payload);
    const waiter = this.byteWaiters.get(md5);
    if (waiter) {
      this.byteWaiters.delete(md5);
      waiter(payload);
      return;
    }
    this.bufferedBytes.set(md5, payload);
    while (this.bufferedBytes.size > 8) {
      const oldest = this.bufferedBytes.keys().next().value;
      this.bufferedBytes.delete(oldest);
    }
  }
  waitForBytes(md5, timeoutMs) {
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.byteWaiters.delete(md5);
        reject(new Error("\u7B49\u5F85\u6587\u4EF6\u5185\u5BB9\u8D85\u65F6\uFF08bytes \u5E27\u672A\u5230\u8FBE\uFF09"));
      }, timeoutMs);
      this.byteWaiters.set(md5, (data) => {
        clearTimeout(timer);
        resolve(data);
      });
    });
  }
  awaitResponse(id, timeoutMs) {
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error(`\u547D\u4EE4\u54CD\u5E94\u8D85\u65F6\uFF08id=${id}\uFF09`));
      }, timeoutMs);
      this.pending.set(id, { resolve, reject, timer });
    });
  }
  onClosed() {
    if (this.closed) {
      return;
    }
    this.closed = true;
    for (const [, pending] of this.pending) {
      clearTimeout(pending.timer);
      pending.reject(new Error("\u8FDE\u63A5\u5DF2\u65AD\u5F00"));
    }
    this.pending.clear();
    this.emit("closed");
  }
};

// test/debug-phone.ts
var host = process.argv[2] ?? "127.0.0.1";
var port = Number(process.argv[3] ?? 9317);
var logs = [];
var events = [];
var waiters = [];
function assert(condition, message = "assertion failed") {
  if (!condition) {
    throw new Error(message);
  }
}
async function waitFor(check, timeoutMs, what) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (check()) {
      return;
    }
    await new Promise((resolve) => setTimeout(resolve, 150));
  }
  throw new Error(`\u7B49\u5F85\u8D85\u65F6\uFF1A${what}`);
}
function findStopped(fromIndex, line) {
  for (let i = fromIndex; i < events.length; i++) {
    const event = events[i];
    if (event.event === "stopped" && (line === void 0 || event.line === line)) {
      return i;
    }
  }
  return -1;
}
function dumpEvents() {
  return events.map((event) => JSON.stringify(event)).join("\n  ");
}
var script = [
  "var a = 1;",
  // 1
  "function work() {",
  // 2
  "    var b = 2;",
  // 3
  "    var sum = a + b;",
  // 4  ← 断点
  "    console.log('DEBUG_SUM ' + sum);",
  // 5
  "    return sum;",
  // 6
  "}",
  // 7
  "work();",
  // 8
  "console.log('DEBUG_END');"
  // 9
].join("\n");
async function main() {
  const socket = net.createConnection({ host, port });
  const conn = new DeviceConnection(socket, `${host}:${port}`, (message) => console.log("[conn]", message));
  conn.on("log", (line) => {
    logs.push(line);
    console.log("[device]", line.trim());
  });
  conn.on("debugEvent", (event) => {
    events.push(event);
    console.log("[debug]", JSON.stringify(event));
    for (const wake of waiters.splice(0)) {
      wake();
    }
  });
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error("hello \u8D85\u65F6")), 1e4);
    conn.once("connected", () => {
      clearTimeout(timer);
      resolve();
    });
    conn.once("closed", () => reject(new Error("\u63E1\u624B\u524D\u8FDE\u63A5\u88AB\u5173\u95ED")));
  });
  console.log("== \u5DF2\u8FDE\u63A5 ==", conn.deviceName, JSON.stringify(conn.hello));
  console.log("\n== 1) debug_run\uFF08\u65AD\u70B9\u5728\u7B2C 4 \u884C\uFF09==");
  await conn.debugRun("probe-debug.js", script, [4]);
  await waitFor(() => events.some((event) => event.event === "stopped"), 15e3, "\u9996\u6B21\u505C\u6B62");
  const first = events.find((event) => event.event === "stopped");
  console.log(`\u9996\u6B21\u505C\u6B62\uFF1Aline=${first.line} reason=${first.reason} frames=${first.frames?.length ?? 0}`);
  console.log("\n== 2) \u7EE7\u7EED\u5230\u7B2C 4 \u884C\u65AD\u70B9 ==");
  let stoppedIndex = events.findIndex((event) => event.event === "stopped");
  while (events[stoppedIndex].line !== 4) {
    console.log(`  \u5F53\u524D\u505C\u5728 line=${events[stoppedIndex].line}\uFF0Ccontinue \u2026`);
    const cursor = events.length;
    await conn.debugResume("continue");
    await waitFor(() => events.length > cursor, 15e3, "\u4E0B\u4E00\u6B21 debug \u4E8B\u4EF6");
    stoppedIndex = findStopped(cursor, 4);
    if (stoppedIndex < 0) {
      const last = events[events.length - 1];
      if (last.event === "terminated") {
        throw new Error("\u811A\u672C\u5728\u5230\u8FBE\u7B2C 4 \u884C\u65AD\u70B9\u524D\u7ED3\u675F\uFF1A\n  " + dumpEvents());
      }
      stoppedIndex = events.findLastIndex((event) => event.event === "stopped");
      if (stoppedIndex < 0) {
        throw new Error("\u672A\u6536\u5230\u505C\u6B62\u4E8B\u4EF6\uFF1A\n  " + dumpEvents());
      }
    }
  }
  const stopped = events[stoppedIndex];
  console.log(`\u547D\u4E2D\uFF1Aline=${stopped.line} reason=${stopped.reason} name=${stopped.name}`);
  console.log("\u8C03\u7528\u6808\uFF1A");
  for (const frame of stopped.frames ?? []) {
    console.log(`   #${frame.index} ${frame.name} @${frame.line} (${frame.url})`);
  }
  assert(stopped.line === 4, `\u671F\u671B\u505C\u5728\u7B2C 4 \u884C\uFF0C\u5B9E\u9645 ${stopped.line}`);
  assert((stopped.frames?.length ?? 0) >= 2, "\u8C03\u7528\u6808\u5E27\u6570\u5E94 \u2265 2\uFF08work + \u5168\u5C40\uFF09");
  console.log("\n== 3) debug_evaluate ==");
  const sum = await conn.debugEvaluate("a + b", 0);
  console.log(`a + b = ${sum}`);
  assert(sum.includes("3"), `eval \u671F\u671B 3\uFF0C\u5B9E\u9645 ${sum}`);
  const localB = await conn.debugEvaluate("b", 0);
  console.log(`b = ${localB}`);
  assert(localB.includes("2"), `eval b \u671F\u671B 2\uFF0C\u5B9E\u9645 ${localB}`);
  console.log("\n== 4) debug_variables\uFF08\u4F5C\u7528\u57DF\u5BF9\u8C61\uFF09==");
  const vars = await conn.debugVariables(0);
  console.log("\u53D8\u91CF\uFF1A", vars.map((item) => `${item.name}=${item.value}`).join(", "));
  const b = vars.find((item) => item.name === "b");
  assert(b, "\u4F5C\u7528\u57DF\u91CC\u5E94\u80FD\u770B\u5230\u5C40\u90E8\u53D8\u91CF b");
  console.log("\n== 5) \u5355\u6B65\uFF08next\uFF09==");
  const beforeStep = events.length;
  await conn.debugResume("next");
  await waitFor(() => events.length > beforeStep && events[events.length - 1].event === "stopped", 15e3, "\u5355\u6B65\u505C\u6B62");
  const afterStep = events[events.length - 1];
  console.log(`next \u540E\u505C\u5728 line=${afterStep.line} reason=${afterStep.reason}`);
  assert(afterStep.line === 5, `\u5355\u6B65\u671F\u671B\u5230\u7B2C 5 \u884C\uFF0C\u5B9E\u9645 ${afterStep.line}`);
  console.log("\n== 6) \u6539\u65AD\u70B9 + \u7EE7\u7EED\u5230\u7ED3\u675F ==");
  await conn.debugSetBreakpoints([]);
  const beforeRun = events.length;
  await conn.debugResume("continue");
  await waitFor(
    () => logs.some((line) => line.includes("DEBUG_SUM")) && logs.some((line) => line.includes("DEBUG_END")),
    15e3,
    "\u811A\u672C\u65E5\u5FD7"
  );
  console.log("\u811A\u672C\u65E5\u5FD7 OK\uFF08DEBUG_SUM / DEBUG_END \u5747\u5DF2\u56DE\u4F20\uFF09");
  await waitFor(() => events.slice(beforeRun).some((event) => event.event === "terminated"), 15e3, "\u7ED3\u675F\u4E8B\u4EF6");
  const terminated = events.find((event) => event.event === "terminated");
  console.log("\u7ED3\u675F\u4E8B\u4EF6\uFF1A", JSON.stringify(terminated));
  console.log("\n== 7) debug_terminate\uFF08\u8DD1\u5230\u65AD\u70B9\u540E\u5F3A\u5236\u7ED3\u675F\uFF09==");
  await conn.debugRun("probe-debug.js", script, [4]);
  await waitFor(() => events.some((event) => event.event === "stopped" && event.line === 4), 15e3, "\u91CD\u65B0\u547D\u4E2D\u7B2C 4 \u884C");
  const beforeTerminate = events.length;
  await conn.debugTerminate();
  await waitFor(
    () => events.slice(beforeTerminate).some((event) => event.event === "terminated"),
    15e3,
    "terminate \u540E\u7684\u7ED3\u675F\u4E8B\u4EF6"
  );
  console.log("\u5F3A\u5236\u7ED3\u675F OK");
  await conn.stopAll();
  conn.close();
  console.log("\nALL DEBUG E2E CHECKS PASSED");
}
main().catch((error) => {
  console.error("\nDEBUG E2E FAILED:", error);
  process.exitCode = 1;
});
