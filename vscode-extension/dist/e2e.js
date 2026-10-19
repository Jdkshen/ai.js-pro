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

// test/e2e-phone.ts
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
  async run(name, id, script) {
    this.lastRunId = id;
    this.lastRunKind = "script";
    await this.sendCommand({ command: "run", id, name, script });
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
  async save(name, script) {
    await this.sendCommand({ command: "save", id: name, name, script });
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
  async debugRun(name, script, breakpoints) {
    await this.sendCommand({ command: "debug_run", name, script, breakpoints }, 2e4);
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

// src/zip.ts
var zlib2 = __toESM(require("zlib"));
var crcTable;
function crc32(buf) {
  if (!crcTable) {
    crcTable = [];
    for (let n = 0; n < 256; n++) {
      let c = n;
      for (let k = 0; k < 8; k++) {
        c = c & 1 ? 3988292384 ^ c >>> 1 : c >>> 1;
      }
      crcTable[n] = c >>> 0;
    }
  }
  let crc = 4294967295;
  for (let i = 0; i < buf.length; i++) {
    crc = crcTable[(crc ^ buf[i]) & 255] ^ crc >>> 8;
  }
  return (crc ^ 4294967295) >>> 0;
}
function dosDateTime(date) {
  const time = (date.getHours() & 31) << 11 | (date.getMinutes() & 63) << 5 | date.getSeconds() / 2 & 31;
  const day = (date.getFullYear() - 1980 & 127) << 9 | (date.getMonth() + 1 & 15) << 5 | date.getDate() & 31;
  return { time, date: day };
}
function createZip(entries) {
  const localParts = [];
  const centralParts = [];
  let offset = 0;
  for (const entry of entries) {
    const name = Buffer.from(entry.path.replace(/\\/g, "/"), "utf8");
    const raw = entry.data;
    const deflated = zlib2.deflateRawSync(raw);
    const useDeflate = deflated.length < raw.length;
    const compressed = useDeflate ? deflated : raw;
    const method = useDeflate ? 8 : 0;
    const { time, date } = dosDateTime(entry.mtime ?? /* @__PURE__ */ new Date());
    const crc = crc32(raw);
    const local = Buffer.alloc(30);
    local.writeUInt32LE(67324752, 0);
    local.writeUInt16LE(20, 4);
    local.writeUInt16LE(2048, 6);
    local.writeUInt16LE(method, 8);
    local.writeUInt16LE(time, 10);
    local.writeUInt16LE(date, 12);
    local.writeUInt32LE(crc, 14);
    local.writeUInt32LE(compressed.length, 18);
    local.writeUInt32LE(raw.length, 22);
    local.writeUInt16LE(name.length, 26);
    local.writeUInt16LE(0, 28);
    localParts.push(local, name, compressed);
    const central = Buffer.alloc(46);
    central.writeUInt32LE(33639248, 0);
    central.writeUInt16LE(20, 4);
    central.writeUInt16LE(20, 6);
    central.writeUInt16LE(2048, 8);
    central.writeUInt16LE(method, 10);
    central.writeUInt16LE(time, 12);
    central.writeUInt16LE(date, 14);
    central.writeUInt32LE(crc, 16);
    central.writeUInt32LE(compressed.length, 20);
    central.writeUInt32LE(raw.length, 24);
    central.writeUInt16LE(name.length, 28);
    central.writeUInt16LE(0, 30);
    central.writeUInt16LE(0, 32);
    central.writeUInt16LE(0, 34);
    central.writeUInt16LE(0, 36);
    central.writeUInt32LE(0, 38);
    central.writeUInt32LE(offset, 42);
    centralParts.push(central, name);
    offset += local.length + name.length + compressed.length;
  }
  const centralSize = centralParts.reduce((sum, part) => sum + part.length, 0);
  const eocd = Buffer.alloc(22);
  eocd.writeUInt32LE(101010256, 0);
  eocd.writeUInt16LE(0, 4);
  eocd.writeUInt16LE(0, 6);
  eocd.writeUInt16LE(entries.length, 8);
  eocd.writeUInt16LE(entries.length, 10);
  eocd.writeUInt32LE(centralSize, 12);
  eocd.writeUInt32LE(offset, 16);
  eocd.writeUInt16LE(0, 20);
  return Buffer.concat([...localParts, ...centralParts, eocd]);
}

// test/e2e-phone.ts
var host = process.argv[2] ?? "127.0.0.1";
var port = Number(process.argv[3] ?? 9317);
var logs = [];
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
    await new Promise((resolve) => setTimeout(resolve, 200));
  }
  throw new Error(`\u7B49\u5F85\u8D85\u65F6\uFF1A${what}`);
}
async function main() {
  const socket = net.createConnection({ host, port });
  const conn = new DeviceConnection(socket, `${host}:${port}`, (message) => console.log("[conn]", message));
  conn.on("log", (line) => {
    logs.push(line);
    console.log("[device]", line.trim());
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
  console.log("\n== 1) list_files\uFF08\u6839\u76EE\u5F55\uFF0C\u975E\u9012\u5F52\uFF09==");
  const root = await conn.listFiles("", false);
  console.log(`files=${root.files.length} dirs=${root.dirs.length} truncated=${root.truncated}`);
  assert(root.files.length + root.dirs.length > 0, "\u811A\u672C\u76EE\u5F55\u4E3A\u7A7A");
  for (const entry of root.files.slice(0, 5)) {
    console.log(`   ${String(entry.size).padStart(8)}  ${entry.path}`);
  }
  console.log("\n== 2) read_file ==");
  const target = root.files.find((file) => file.size > 0 && file.size < 4096) ?? root.files[0];
  assert(target, "\u6CA1\u6709\u53EF\u8BFB\u6587\u4EF6");
  const bytes = await conn.readFile(target.path);
  console.log(`read ${target.path}: ${bytes.length} bytes\uFF08\u671F\u671B ${target.size}\uFF09`);
  console.log("head:", bytes.subarray(0, 48).toString("utf8").replace(/\r?\n/g, "\\n"));
  assert(bytes.length === target.size, "\u8BFB\u5230\u7684\u5B57\u8282\u6570\u4E0D\u5BF9");
  console.log("\n== 3) write_file + \u8BFB\u56DE ==");
  const marker = `TS_E2E_${Date.now()}`;
  await conn.writeFile("__aijs_ts_probe__/hello.txt", marker);
  const back = await conn.readFile("__aijs_ts_probe__/hello.txt");
  assert(back.toString("utf8") === marker, "\u5199\u56DE\u5185\u5BB9\u4E0D\u4E00\u81F4");
  console.log("\u5199\u56DE\u6821\u9A8C OK:", marker);
  console.log("\n== 4) run\uFF08\u811A\u672C + \u65E5\u5FD7\u56DE\u4F20\uFF09==");
  const runMarker = `TS_E2E_RUN_${Date.now()}`;
  await conn.run("ts-e2e.js", "ts-e2e.js", `console.log('${runMarker}');`);
  await waitFor(() => logs.some((line) => line.includes(runMarker)), 1e4, "\u811A\u672C\u65E5\u5FD7");
  console.log("\u6536\u5230\u811A\u672C\u65E5\u5FD7:", runMarker);
  console.log("\n== 5) run_project\uFF08zip \u63A8\u9001\uFF09==");
  const projectMarker = `TS_E2E_PROJECT_${Date.now()}`;
  const archive = createZip([
    {
      path: "project.json",
      data: Buffer.from(
        JSON.stringify({
          name: "ts-e2e-project",
          versionName: "1.0.0",
          versionCode: 1,
          packageName: "com.e2e.probe",
          main: "main.js"
        })
      )
    },
    { path: "main.js", data: Buffer.from(`console.log('${projectMarker}');`) }
  ]);
  console.log(`zip \u5927\u5C0F: ${archive.length} bytes`);
  await conn.sendBytesCommand("run_project", "ts-e2e-project", archive, []);
  await waitFor(() => logs.some((line) => line.includes(projectMarker)), 2e4, "\u5DE5\u7A0B\u65E5\u5FD7");
  console.log("\u6536\u5230\u5DE5\u7A0B\u65E5\u5FD7:", projectMarker);
  console.log("\n== 6) inspect_layout\uFF08\u5E03\u5C40\u5206\u6790\uFF09==");
  try {
    const xml = await conn.inspectLayout();
    console.log(`\u5E03\u5C40 XML ${xml.length} \u5B57\u7B26\uFF0C\u5934\u4E24\u884C\uFF1A`);
    console.log(xml.split("\n").slice(0, 2).join("\n"));
    assert(xml.includes("<hierarchy"), "\u5E03\u5C40\u8F93\u51FA\u7F3A\u5C11 <hierarchy>");
  } catch (error) {
    console.log("\u5E03\u5C40\u5206\u6790\u8FD4\u56DE\u9519\u8BEF\uFF08\u65E0\u969C\u788D\u672A\u5F00\u542F\u65F6\u5C5E\u9884\u671F\uFF09:", error.message);
  }
  console.log("\n== 7) screenshot\uFF08\u56FE\u8272\u52A9\u624B\u622A\u56FE\uFF09==");
  const shot = await conn.captureScreenshot();
  console.log(`PNG ${shot.width}x${shot.height}, ${(shot.png.length / 1024).toFixed(0)} KB`);
  assert(shot.png.subarray(0, 8).equals(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10])), "PNG \u6587\u4EF6\u5934\u4E0D\u5BF9");
  const ihdrWidth = shot.png.readUInt32BE(16);
  const ihdrHeight = shot.png.readUInt32BE(20);
  console.log(`PNG \u5934\u91CC\u7684\u5C3A\u5BF8\uFF1A${ihdrWidth}x${ihdrHeight}\uFF08\u4E0E response \u4E00\u81F4\uFF1A${shot.width}/${shot.height}\uFF09`);
  assert(ihdrWidth === shot.width && ihdrHeight === shot.height, "PNG \u5934\u5C3A\u5BF8\u4E0E response \u4E0D\u4E00\u81F4");
  console.log("\n== 8) stopAll ==");
  await conn.stopAll();
  conn.close();
  console.log("\nALL TS E2E CHECKS PASSED");
}
main().catch((error) => {
  console.error("\nE2E FAILED:", error);
  process.exit(1);
});
