import * as net from 'net';
import * as zlib from 'zlib';
import { EventEmitter } from 'events';

import {
  DeviceHello,
  encodeFrame,
  encodeJsonFrame,
  FrameDecoder,
  md5Hex,
  TYPE_BYTES,
  TYPE_GZIP_BYTES,
  TYPE_GZIP_JSON,
  TYPE_JSON,
} from './protocol';

interface Pending {
  resolve: (value: any) => void;
  reject: (error: Error) => void;
  timer: NodeJS.Timeout;
  command: string;
}

interface ByteWaiter {
  resolve: (value: Buffer) => void;
  reject: (error: Error) => void;
  timer: NodeJS.Timeout;
  command: string;
}

export interface FileEntry {
  path: string;
  size: number;
  mtime: number;
}

/**
 * 一条到手机（AI.js Pro）的扩展协议连接。
 *
 * <p>方向由手机决定：手机监听 9317 等电脑主动连，也回连电脑 8327；两种情况下
 * 都是设备先发 `hello`，我们回 `hello`（client_version ≥ 1）完成握手。
 */
export class DeviceConnection extends EventEmitter {
  readonly socket: net.Socket;
  hello: DeviceHello | null = null;
  /** 是否已完成 hello 握手。 */
  attached = false;
  /** 最近一次 run / run_project 的 id，用于 stop。 */
  lastRunId: string | undefined;
  private lastRunKind: 'script' | 'project' | undefined;

  private readonly decoder = new FrameDecoder();
  private nextMessageId = 1;
  private readonly pending = new Map<number, Pending>();
  private readonly bufferedBytes = new Map<string, Buffer>();
  private readonly byteWaiters = new Map<string, ByteWaiter>();
  private helloAnswered = false;
  private closed = false;

  constructor(
    socket: net.Socket,
    /** 用于日志的连接标签（如 `192.168.31.59:46638`）。 */
    public readonly label: string,
    private readonly log: (message: string) => void,
    /** Device listener address for reconnect/debug configuration. */
    public readonly deviceAddress?: string,
  ) {
    super();
    this.socket = socket;
    socket.setNoDelay(true);
    socket.on('data', (chunk: Buffer) => this.onData(chunk));
    socket.on('close', () => this.onClosed());
    socket.on('error', (error: NodeJS.ErrnoException) => {
      const code = error.code ? `${error.code} ` : '';
      this.log(`[连接 ${this.label}] socket 错误：${code}${error.message}`);
    });
  }

  /** 设备标识（hello.device_id 优先，握手前用连接标签）。 */
  get key(): string {
    return this.hello?.device_id || this.label;
  }

  get deviceName(): string {
    return this.hello?.device_name || this.label;
  }

  isClosed(): boolean {
    return this.closed;
  }

  close(): void {
    this.socket.destroy();
  }

  // ---- 命令 ----

  /** `command`：run / stop / stopAll / save / list_files / read_file / write_file … */
  async sendCommand(data: Record<string, unknown>, timeoutMs = 20000): Promise<any> {
    const command = commandName(data);
    const id = this.nextMessageId++;
    const result = await this.request(id, encodeJsonFrame({ id, type: 'command', data }), timeoutMs, command);
    return ensureResponseOk(result, command);
  }

  async run(name: string, id: string, script: string): Promise<void> {
    await this.sendCommand({ command: 'run', id, name, script });
    // 只在设备确认接收后记录，避免 run 失败时 stop 误停上一次脚本。
    this.lastRunId = id;
    this.lastRunKind = 'script';
  }

  async stop(id?: string): Promise<{ stopped: boolean; fallbackStopAll: boolean; stoppedCount?: number }> {
    const target = id ?? this.lastRunId;
    if (!target) {
      throw new Error('当前连接尚未运行脚本；如需停止设备上的全部任务，请使用“停止全部”');
    }
    const result = await this.sendCommand({ command: 'stop', id: target });
    // An individual stop must never silently turn into stopAll.
    if (!id && this.lastRunKind === 'project' && result?.stopped === undefined) {
      throw new Error('设备未确认停止该工程，请刷新任务状态或明确使用“停止全部”');
    }
    return { stopped: result?.stopped !== false, fallbackStopAll: false };
  }

  async stopAll(): Promise<number> {
    const result = await this.sendCommand({ command: 'stopAll' });
    return Number(result?.stoppedCount ?? 0);
  }

  async save(name: string, script: string): Promise<void> {
    await this.sendCommand({ command: 'save', id: name, name, script });
  }

  async writeFile(path: string, text: string): Promise<void> {
    await this.sendCommand({ command: 'write_file', path, script: text });
  }

  async listFiles(path: string, recursive: boolean): Promise<{ files: FileEntry[]; dirs: string[]; truncated: boolean }> {
    const result = await this.sendCommand(
      { command: 'list_files', path, recursive },
      recursive ? 60000 : 20000,
    );
    return {
      files: Array.isArray(result?.files) ? result.files : [],
      dirs: Array.isArray(result?.dirs) ? result.dirs : [],
      truncated: Boolean(result?.truncated),
    };
  }

  /** 读取设备文件：内容走 bytes 帧，response 里带 md5 用于配对。 */
  async readFile(path: string, timeoutMs = 30000): Promise<Buffer> {
    return (await this.readBytesVia({ command: 'read_file', path }, timeoutMs)).bytes;
  }

  /** 布局分析：无障碍捕获当前窗口，返回 XML 文本（无障碍未开启时 reject）。 */
  async inspectLayout(timeoutMs = 20000): Promise<string> {
    const { bytes } = await this.readBytesVia({ command: 'inspect_layout' }, timeoutMs);
    return bytes.toString('utf8');
  }

  /** 图色助手：无障碍全屏截图（PNG + 原始像素尺寸，坐标为屏幕坐标）。 */
  async captureScreenshot(timeoutMs = 25000): Promise<{ png: Buffer; width: number; height: number }> {
    const { result, bytes } = await this.readBytesVia({ command: 'screenshot' }, timeoutMs);
    return {
      png: bytes,
      width: Number(result?.width ?? 0),
      height: Number(result?.height ?? 0),
    };
  }

  /** 发一条返回 bytes（配 md5）的命令：read_file / inspect_layout / screenshot 共用。 */
  private async readBytesVia(
    data: Record<string, unknown>,
    timeoutMs: number,
  ): Promise<{ result: any; bytes: Buffer }> {
    const command = commandName(data);
    const id = this.nextMessageId++;
    const result = ensureResponseOk(
      await this.request(id, encodeJsonFrame({ id, type: 'command', data }), timeoutMs, command),
      command,
    );
    const md5 = String(result?.md5 ?? '');
    if (!md5) {
      throw new Error(`命令 ${command} 的响应缺少文件 md5`);
    }
    const buffered = this.bufferedBytes.get(md5);
    if (buffered) {
      this.bufferedBytes.delete(md5);
      return { result, bytes: buffered };
    }
    return { result, bytes: await this.waitForBytes(md5, 5000, command) };
  }

  /**
   * `bytes_command`：先发 zip 字节帧再发带 md5 的 json。
   * 这是 AI.js Pro 设备端现有的配对顺序，保持帧格式不变。
   */
  async sendBytesCommand(
    command: 'run_project' | 'save_project',
    name: string,
    archive: Buffer,
    deletedFiles: string[],
    timeoutMs = 180000,
  ): Promise<any> {
    const id = this.nextMessageId++;
    const md5 = md5Hex(archive);
    const response = this.awaitResponse(id, timeoutMs, command);
    try {
      // 顺序必须保持：bytes 帧在前，带 md5 的 bytes_command 在后（设备端按此顺序配对）。
      this.sendRaw(encodeFrame(TYPE_BYTES, archive));
      this.sendRaw(
        encodeJsonFrame({
          id,
          type: 'bytes_command',
          md5,
          data: { command, id: name, name, deletedFiles, override: true },
        }),
      );
    } catch (error) {
      this.cancelPending(id, error as Error);
      return response;
    }
    const result = ensureResponseOk(await response, command);
    if (command === 'run_project') {
      // 设备真正收下工程后才把 lastRunId 指向它，失败时不污染 stop 目标。
      this.lastRunId = name;
      this.lastRunKind = 'project';
    }
    return result;
  }

  // ---- 断点调试（Rhino） ----

  /** 启动调试会话：断点行号 1-based；事件通过 `debugEvent` 事件抛出。 */
  async debugRun(name: string, script: string, breakpoints: number[]): Promise<void> {
    await this.sendCommand({ command: 'debug_run', name, script, breakpoints }, 20000);
  }

  async debugSetBreakpoints(lines: number[]): Promise<void> {
    await this.sendCommand({ command: 'debug_set_breakpoints', breakpoints: lines }, 20000);
  }

  async debugResume(action: 'continue' | 'next' | 'stepIn' | 'stepOut'): Promise<void> {
    await this.sendCommand({ command: 'debug_resume', action }, 20000);
  }

  async debugEvaluate(expr: string, frame: number): Promise<string> {
    const result = await this.sendCommand({ command: 'debug_evaluate', expr, frame }, 20000);
    return String(result?.result ?? '');
  }

  async debugVariables(ref: number): Promise<{ name: string; value: string; ref?: number }[]> {
    const result = await this.sendCommand({ command: 'debug_variables', ref }, 20000);
    return Array.isArray(result?.variables) ? result.variables : [];
  }

  async debugTerminate(): Promise<void> {
    try {
      await this.sendCommand({ command: 'debug_terminate' }, 20000);
    } catch {
      // 设备已结束会话时忽略。
    }
  }

  // ---- 内部 ----

  /** 发送一条请求并等待对应 response；断线时抛错，绝不能静默等满超时。 */
  private request(id: number, frame: Buffer, timeoutMs: number, command: string): Promise<any> {
    // 先挂 pending 再写，避免极快 response（或同步 socket 实现）在 awaitResponse 前到达。
    const response = this.awaitResponse(id, timeoutMs, command);
    try {
      this.sendRaw(frame);
    } catch (error) {
      this.cancelPending(id, error as Error);
    }
    return response;
  }

  private cancelPending(id: number, error: Error): void {
    const pending = this.pending.get(id);
    if (!pending) return;
    clearTimeout(pending.timer);
    this.pending.delete(id);
    pending.reject(error);
  }

  private sendRaw(frame: Buffer): void {
    if (this.closed || this.socket.destroyed) {
      throw new Error(`连接已断开，命令未发送（${this.label}）`);
    }
    this.socket.write(frame);
  }

  private onData(chunk: Buffer): void {
    let frames: { type: number; payload: Buffer }[];
    try {
      frames = this.decoder.append(chunk);
    } catch (error) {
      this.log(`[连接 ${this.label}] ${(error as Error).message}`);
      this.close();
      return;
    }
    for (const frame of frames) {
      try {
        this.dispatch(frame.type, frame.payload);
      } catch (error) {
        // 坏 gzip / 事件监听异常不能冒泡到 socket 'data'，否则会打穿扩展宿主。
        this.log(`[连接 ${this.label}] 处理帧失败（type=${frame.type}）：${(error as Error).message}`);
        this.close();
        return;
      }
    }
  }

  private dispatch(type: number, payload: Buffer): void {
    if (type === TYPE_BYTES || type === TYPE_GZIP_BYTES) {
      const data = type === TYPE_GZIP_BYTES ? zlib.gunzipSync(payload) : payload;
      this.onBytes(data);
      return;
    }
    if (type !== TYPE_JSON && type !== TYPE_GZIP_JSON) {
      this.log(`[连接 ${this.label}] 未知帧类型 ${type}`);
      return;
    }
    const text =
      type === TYPE_GZIP_JSON ? zlib.gunzipSync(payload).toString('utf8') : payload.toString('utf8');
    let message: any;
    try {
      message = JSON.parse(text);
    } catch {
      this.log(`[连接 ${this.label}] 无法解析 JSON 帧`);
      return;
    }
    this.onMessage(message);
  }

  private onMessage(message: any): void {
    const type = typeof message?.type === 'string' ? message.type : '';
    if (type === 'hello') {
      this.onHello(message);
      return;
    }
    if (type === 'log') {
      this.emit('log', String(message?.data?.log ?? ''));
      return;
    }
    if (type === 'debug_event') {
      this.emit('debugEvent', message?.data ?? {});
      return;
    }
    if (type.startsWith('response_')) {
      const id = Number(type.slice('response_'.length));
      const pending = this.pending.get(id);
      if (pending) {
        this.pending.delete(id);
        clearTimeout(pending.timer);
        pending.resolve(message?.data ?? {});
      }
      return;
    }
    this.log(`[连接 ${this.label}] 未处理的消息：${type}`);
  }

  private onHello(message: any): void {
    if (!this.helloAnswered) {
      this.helloAnswered = true;
      this.sendRaw(
        encodeJsonFrame({
          id: this.nextMessageId++,
          type: 'hello',
          data: { client_version: 4, client_name: 'AI.js Pro', extension_version: require('../package.json').version },
        }),
      );
    }
    // 拨号阶段设的握手超时必须清掉：否则空闲连接会在超时时间点被误杀。
    this.socket.setTimeout(0);
    this.hello = message?.data ?? {};
    if (!this.attached) {
      this.attached = true;
      this.emit('connected');
    }
  }

  private onBytes(payload: Buffer): void {
    const md5 = md5Hex(payload);
    const waiter = this.byteWaiters.get(md5);
    if (waiter) {
      this.byteWaiters.delete(md5);
      clearTimeout(waiter.timer);
      waiter.resolve(payload);
      return;
    }
    this.bufferedBytes.set(md5, payload);
    while (this.bufferedBytes.size > 8) {
      const oldest = this.bufferedBytes.keys().next().value as string;
      this.bufferedBytes.delete(oldest);
    }
  }

  private waitForBytes(md5: string, timeoutMs: number, command: string): Promise<Buffer> {
    if (this.closed || this.socket.destroyed) {
      return Promise.reject(new Error(`连接已断开（${command} 的文件内容未到达）`));
    }
    return new Promise<Buffer>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.byteWaiters.delete(md5);
        reject(new Error(`等待 ${command} 文件内容超时（${formatTimeout(timeoutMs)}，bytes 帧未到达）`));
      }, timeoutMs);
      this.byteWaiters.set(md5, { resolve, reject, timer, command });
    });
  }

  private awaitResponse(id: number, timeoutMs: number, command: string): Promise<any> {
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error(`命令 ${command} 响应超时（${formatTimeout(timeoutMs)}，id=${id}）`));
      }, timeoutMs);
      this.pending.set(id, { resolve, reject, timer, command });
    });
  }

  private onClosed(): void {
    if (this.closed) {
      return;
    }
    this.closed = true;
    for (const [, pending] of this.pending) {
      clearTimeout(pending.timer);
      pending.reject(new Error(`连接已断开（命令 ${pending.command} 未完成）`));
    }
    this.pending.clear();
    for (const [, waiter] of this.byteWaiters) {
      clearTimeout(waiter.timer);
      waiter.reject(new Error(`连接已断开（等待 ${waiter.command} 的文件内容时连接关闭）`));
    }
    this.byteWaiters.clear();
    this.bufferedBytes.clear();
    this.emit('closed');
  }
}

function commandName(data: Record<string, unknown>): string {
  const command = data?.command;
  return typeof command === 'string' && command ? command : 'command';
}

function ensureResponseOk(result: any, command: string): any {
  if (result?.error !== undefined && result?.error !== null && result?.error !== '') {
    throw new Error(`命令 ${command} 失败：${String(result.error)}`);
  }
  return result;
}

function formatTimeout(timeoutMs: number): string {
  return timeoutMs >= 1000 ? `${Math.round(timeoutMs / 1000)} 秒` : `${timeoutMs} 毫秒`;
}
