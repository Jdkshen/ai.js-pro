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

// test/probe-caps.ts
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
  lastRunKind;
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
    this.lastRunKind = "script";
    await this.sendCommand({ command: "run", id, name, script: script2 });
  }
  async stop(id) {
    const target = id ?? this.lastRunId;
    if (!target) {
      const stoppedCount = await this.stopAll();
      return { stopped: true, fallbackStopAll: true, stoppedCount };
    }
    const result = await this.sendCommand({ command: "stop", id: target });
    if (!id && this.lastRunKind === "project" && result?.stopped === void 0 && !result?.error) {
      const stoppedCount = await this.stopAll();
      return { stopped: true, fallbackStopAll: true, stoppedCount };
    }
    if (result?.error) throw new Error(String(result.error));
    return { stopped: result?.stopped !== false, fallbackStopAll: false };
  }
  async stopAll() {
    const result = await this.sendCommand({ command: "stopAll" });
    if (result?.error) throw new Error(String(result.error));
    return Number(result?.stoppedCount ?? 0);
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
    return (await this.readBytesVia({ command: "read_file", path }, timeoutMs)).bytes;
  }
  /** 布局分析：无障碍捕获当前窗口，返回 XML 文本（无障碍未开启时 reject）。 */
  async inspectLayout(timeoutMs = 2e4) {
    const { bytes } = await this.readBytesVia({ command: "inspect_layout" }, timeoutMs);
    return bytes.toString("utf8");
  }
  /** 图色助手：无障碍全屏截图（PNG + 原始像素尺寸，坐标为屏幕坐标）。 */
  async captureScreenshot(timeoutMs = 25e3) {
    const { result, bytes } = await this.readBytesVia({ command: "screenshot" }, timeoutMs);
    return {
      png: bytes,
      width: Number(result?.width ?? 0),
      height: Number(result?.height ?? 0)
    };
  }
  /** 发一条返回 bytes（配 md5）的命令：read_file / inspect_layout / screenshot 共用。 */
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
      return { result, bytes: buffered };
    }
    return { result, bytes: await this.waitForBytes(md5, 5e3) };
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
      this.lastRunKind = "project";
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

// test/probe-caps.ts
var host = process.argv[2] ?? "127.0.0.1";
var port = Number(process.argv[3] ?? 19317);
var script = `
var caps = {};
try {
  caps.engine = (typeof $engine !== 'undefined') ? String($engine) : (typeof engines !== 'undefined' ? 'engines' : '?');
} catch (e) { caps.engine = 'err'; }
try { caps.rhino = (typeof java !== 'undefined'); } catch (e) { caps.rhino = 'err'; }
try { caps.findColor = typeof images.findColor; } catch (e) { caps.findColor = 'err:' + e; }
try { caps.findMultiColors = typeof images.findMultiColors; } catch (e) { caps.findMultiColors = 'err'; }
try { caps.findImage = typeof images.findImage; } catch (e) { caps.findImage = 'err'; }
try { caps.fromBase64 = typeof images.fromBase64; } catch (e) { caps.fromBase64 = 'err'; }
try { caps.reqAccess = typeof images.requestAccessibilityScreenCapture; } catch (e) { caps.reqAccess = 'err'; }
try { caps.reqScreenCapture = typeof images.requestScreenCapture; } catch (e) { caps.reqScreenCapture = 'err'; }
try { caps.captureScreen = typeof captureScreen; } catch (e) { caps.captureScreen = 'err'; }
try { caps.pixel = typeof images.pixel; } catch (e) { caps.pixel = 'err'; }
try { caps.clip = typeof images.clip; } catch (e) { caps.clip = 'err'; }
console.log('CAPS ' + JSON.stringify(caps));
try {
  if (typeof images.requestScreenCapture === 'function') {
    var granted = images.requestScreenCapture({ mode: 'accessibility' });
    console.log('CAPS_ACCESS ' + granted);
  }
} catch (e) { console.log('CAPS_ACCESS_ERR ' + e); }
try {
  var img = captureScreen();
  console.log('CAPS_CAPTURE ' + img.getWidth() + 'x' + img.getHeight());
  var probeColor = images.pixel(img, Math.floor(img.getWidth() / 2), Math.floor(img.getHeight() / 2));
  console.log('CAPS_PIXEL ' + colors.toString(probeColor));
  var found = images.findColor(img, colors.toString(probeColor), { threshold: 0 });
  console.log('CAPS_FIND ' + (found ? found.x + ',' + found.y : 'null'));
  var tpl = images.clip(img, Math.floor(img.getWidth() / 2) - 20, 400, 40, 40);
  var p2 = images.findImage(img, tpl, { threshold: 0.9 });
  console.log('CAPS_FINDIMAGE ' + (p2 ? p2.x + ',' + p2.y : 'null'));
  var b64 = images.toBase64(tpl, 'png', 100);
  var back = images.fromBase64(b64);
  console.log('CAPS_B64 len=' + b64.length + ' back=' + (back ? back.getWidth() + 'x' + back.getHeight() : 'null'));
} catch (e) { console.log('CAPS_CAPTURE_ERR ' + e); }
`;
async function main() {
  const socket = net.createConnection({ host, port });
  const conn = new DeviceConnection(socket, `${host}:${port}`, (m) => console.log("[conn]", m));
  conn.on("log", (line) => console.log("[device]", line.trim()));
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error("hello \u8D85\u65F6")), 1e4);
    conn.once("connected", () => {
      clearTimeout(timer);
      resolve();
    });
    conn.once("closed", () => reject(new Error("\u63E1\u624B\u524D\u8FDE\u63A5\u88AB\u5173\u95ED")));
  });
  console.log("== \u5DF2\u8FDE\u63A5 ==", conn.deviceName, conn.hello?.app_version);
  await conn.run("caps-probe.js", "caps-probe.js", script);
  await new Promise((resolve) => setTimeout(resolve, 8e3));
  conn.close();
  console.log("== done ==");
}
main().catch((error) => {
  console.error("PROBE FAILED:", error);
  process.exitCode = 1;
});
