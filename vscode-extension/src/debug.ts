/**
 * AI.js Pro 断点调试（Rhino）—— 手写 DAP 适配器。
 *
 * 设计：VS Code 调试协议（DAP）请求 → 转成设备协议的 `debug_*` 命令；
 * 设备的 `debug_event` 推送 → 转成 DAP 事件（stopped / output / terminated）。
 *
 * V1 能力：
 *  - 断点（行断点，含运行时增删）、单步（next / stepIn / stepOut）、继续、终止
 *  - 调用栈（Rhino Dim 栈帧）、作用域变量树（设备端分配子引用）、eval（调试控制台/悬停）
 * 限制：
 *  - 只支持 Rhino（第一代 API）脚本；QuickJS 不支持断点
 *  - 手机侧同一时间只允许一个调试会话（与 App 内置编辑器调试互斥）
 */
import * as fs from 'fs';
import * as path from 'path';
import * as vscode from 'vscode';

import { DeviceConnection } from './connection';
import { DeviceManager } from './manager';

/** 设备侧 debug_event 的载荷。 */
interface DeviceDebugEvent {
  event: 'started' | 'stopped' | 'output' | 'terminated';
  reason?: string;
  line?: number;
  url?: string;
  name?: string;
  message?: string;
  frames?: { index: number; name: string; line: number; url: string }[];
}

interface LaunchArguments {
  program?: string;
  host?: string;
  name?: string;
  trace?: boolean;
}

type ResumeAction = 'continue' | 'next' | 'stepIn' | 'stepOut';

/** 一个调试会话（每次 F5 创建一个）。 */
class AijsproDebugSession implements vscode.DebugAdapter {
  private readonly emitter = new vscode.EventEmitter<vscode.DebugProtocolMessage>();
  readonly onDidSendMessage = this.emitter.event;

  private connection: DeviceConnection | undefined;
  private programPath: string | undefined;
  private scriptName = 'script.js';
  private scriptText = '';
  private breakpoints: number[] = [];
  private launchRequested = false;
  private configured = false;
  private running = false;

  /** 最近一次 stopped 事件的调用栈缓存（stackTrace 请求用）。 */
  private frames: { name: string; line: number; url: string }[] = [];

  private readonly disposables: { dispose(): void }[] = [];
  private readonly log: (message: string) => void;

  constructor(
    private readonly manager: DeviceManager,
    output: vscode.OutputChannel,
  ) {
    this.log = (message: string) => {
      output.appendLine(message);
      const sink = process.env.AIJSPRO_E2E_DEBUG_LOG;
      if (sink) {
        try {
          require('fs').appendFileSync(sink, `${message}\n`, 'utf8');
        } catch {
          // 仅测试用，写入失败不影响调试。
        }
      }
    };
  }

  dispose(): void {
    for (const item of this.disposables.splice(0)) {
      item.dispose();
    }
    if (this.running) {
      void this.connection?.debugTerminate();
    }
    this.connection = undefined;
    this.emitter.dispose();
  }

  handleMessage(message: vscode.DebugProtocolMessage): void {
    const request = message as {
      type?: string;
      seq?: number;
      command?: string;
      arguments?: any;
    };
    if (request?.type !== 'request' || typeof request.command !== 'string') {
      return;
    }
    void this.dispatch(request.command, request.arguments ?? {}, request.seq ?? 0);
  }

  // ---- 请求分发 ----

  private async dispatch(command: string, args: any, seq: number): Promise<void> {
    try {
      switch (command) {
        case 'initialize':
          this.respond(seq, command, {
            supportsConfigurationDoneRequest: true,
            supportsEvaluateForHovers: true,
            supportsTerminateRequest: true,
            supportsSetVariable: false,
            supportsRestartRequest: false,
            exceptionBreakpointFilters: [],
          });
          this.event('initialized');
          return;
        case 'launch':
          await this.onLaunch(args as LaunchArguments);
          this.respond(seq, command);
          return;
        case 'setBreakpoints':
          await this.onSetBreakpoints(args);
          this.respond(seq, command, {
            breakpoints: (args?.breakpoints ?? []).map((item: { line: number }) => ({
              verified: true,
              line: item.line,
            })),
          });
          return;
        case 'setFunctionBreakpoints':
          this.respond(seq, command, { breakpoints: [] });
          return;
        case 'setExceptionBreakpoints':
          this.respond(seq, command);
          return;
        case 'configurationDone':
          await this.onConfigurationDone();
          this.respond(seq, command);
          return;
        case 'threads':
          this.respond(seq, command, {
            threads: [{ id: 1, name: 'main（手机端脚本）' }],
          });
          return;
        case 'stackTrace':
          this.respond(seq, command, { stackFrames: this.stackFrames(), totalFrames: this.frames.length });
          return;
        case 'scopes':
          this.respond(seq, command, {
            scopes: this.running
              ? []
              : [{ name: '作用域', variablesReference: 1, expensive: false }],
          });
          return;
        case 'variables':
          await this.onVariables(args, seq, command);
          return;
        case 'evaluate':
          await this.onEvaluate(args, seq, command);
          return;
        case 'continue':
        case 'next':
        case 'stepIn':
        case 'stepOut':
          await this.onResume(command as ResumeAction, seq);
          return;
        case 'pause':
          this.respond(seq, command, undefined, 'Rhino 调试器不支持暂停正在运行的脚本');
          return;
        case 'terminate':
          await this.connection?.debugTerminate();
          this.respond(seq, command);
          return;
        case 'disconnect':
          await this.connection?.debugTerminate();
          this.running = false;
          this.respond(seq, command);
          return;
        default:
          this.respond(seq, command, undefined, `不支持的调试请求：${command}`);
          return;
      }
    } catch (error) {
      this.respond(seq, command, undefined, (error as Error).message);
    }
  }

  // ---- 各请求实现 ----

  private async onLaunch(args: LaunchArguments): Promise<void> {
    let candidate = args.program;
    if (!candidate || candidate === '${file}') {
      const editor = vscode.window.activeTextEditor;
      if (editor && editor.document.uri.scheme === 'file') {
        candidate = editor.document.fileName;
      }
    }
    if (!candidate || candidate === '${file}') {
      throw new Error('没有可调试的脚本：请在 launch.json 里指定 program，或先打开一个 .js 文件');
    }
    this.programPath = candidate;
    this.scriptText = await fs.promises.readFile(candidate, 'utf8');
    this.scriptName = path.basename(candidate);
    this.launchRequested = true;

    this.connection = await this.resolveConnection(args.host);
    this.attachConnection(this.connection);
    this.log(`[debug] ready: ${this.scriptName} @ ${this.connection.label}`);
  }

  private async resolveConnection(host: string | undefined): Promise<DeviceConnection> {
    const active = this.manager.activeConnection;
    if (active?.attached) {
      return active;
    }
    if (host) {
      this.log(`[debug] 正在连接 ${host} …`);
      await this.manager.connectTo(host);
      const deadline = Date.now() + 10000;
      while (Date.now() < deadline) {
        const current = this.manager.activeConnection;
        if (current?.attached) {
          return current;
        }
        await new Promise((resolve) => setTimeout(resolve, 250));
      }
      throw new Error(`连接 ${host} 超时（请确认手机侧已打开「电脑连接 → 服务器模式」）`);
    }
    const picked = await this.manager.pickConnection();
    if (!picked?.attached) {
      throw new Error('没有可用的设备连接：请先连接手机，或在 launch.json 里配置 host');
    }
    return picked;
  }

  private attachConnection(connection: DeviceConnection): void {
    for (const item of this.disposables.splice(0)) {
      item.dispose();
    }
    const onDebug = (event: DeviceDebugEvent) => this.onDeviceEvent(event);
    const onLog = (line: string) => {
      if (this.running) {
        this.event('output', { category: 'stdout', output: `${line}\n` });
      }
    };
    const onClosed = () => {
      if (this.running || this.launchRequested) {
        this.event('terminated');
        this.event('output', { category: 'console', output: '设备连接已断开\n' });
        this.running = false;
      }
    };
    connection.on('debugEvent', onDebug);
    connection.on('log', onLog);
    connection.on('closed', onClosed);
    this.disposables.push(
      new vscode.Disposable(() => {
        connection.removeListener('debugEvent', onDebug);
        connection.removeListener('log', onLog);
        connection.removeListener('closed', onClosed);
      }),
    );
    this.connection = connection;
  }

  private async onSetBreakpoints(args: any): Promise<void> {
    const lines: number[] = (args?.breakpoints ?? [])
      .map((item: { line: number }) => Number(item.line))
      .filter((line: number) => Number.isFinite(line) && line > 0);
    this.breakpoints = [...new Set(lines)].sort((a, b) => a - b);
    this.log(`[debug] breakpoints: ${this.breakpoints.join(', ') || 'none'}`);
    if (this.configured) {
      await this.connection?.debugSetBreakpoints(this.breakpoints);
    }
  }

  private async onConfigurationDone(): Promise<void> {
    if (!this.launchRequested) {
      throw new Error('调试会话尚未 launch');
    }
    this.configured = true;
    await this.connection?.debugRun(this.scriptName, this.scriptText, this.breakpoints);
  }

  private async onVariables(args: any, seq: number, command: string): Promise<void> {
    const reference = Number(args?.variablesReference ?? 0);
    if (!reference) {
      this.respond(seq, command, { variables: [] });
      return;
    }
    const deviceRef = reference - 1;
    const items = (await this.connection?.debugVariables(deviceRef)) ?? [];
    this.respond(seq, command, {
      variables: items.map((item) => ({
        name: item.name,
        value: item.value,
        variablesReference: typeof item.ref === 'number' ? item.ref + 1 : 0,
      })),
    });
  }

  private async onEvaluate(args: any, seq: number, command: string): Promise<void> {
    const expression = String(args?.expression ?? '');
    const frameId = Number(args?.frameId ?? 0);
    if (!expression) {
      this.respond(seq, command, { result: '', variablesReference: 0 });
      return;
    }
    if (!this.connection) {
      throw new Error('设备未连接');
    }
    if (this.running) {
      throw new Error('脚本正在运行：Rhino 调试器只能在暂停时求值');
    }
    const deviceFrame = frameId > 0 ? frameId - 1 : 0;
    const result = await this.connection.debugEvaluate(expression, deviceFrame);
    this.log(`[debug] eval ${expression} = ${result}`);
    this.respond(seq, command, { result, variablesReference: 0 });
  }

  private async onResume(action: ResumeAction, seq: number): Promise<void> {
    if (!this.connection) {
      throw new Error('设备未连接');
    }
    await this.connection.debugResume(action);
    this.running = true;
    this.respond(seq, action, { allThreadsContinued: true });
  }

  // ---- 设备事件 → DAP 事件 ----

  private onDeviceEvent(event: DeviceDebugEvent): void {
    switch (event.event) {
      case 'started':
        this.frames = [];
        this.running = true;
        this.event('process', { name: this.scriptName, startMethod: 'launch' });
        this.event('thread', { reason: 'started', threadId: 1 });
        this.event('output', {
          category: 'console',
          output: `AI.js Pro: 开始调试 ${this.scriptName}\n`,
        });
        return;
      case 'stopped':
        this.running = false;
        this.frames = event.frames ?? [];
        this.log(`[debug] stopped line=${event.line} reason=${event.reason} frames=${this.frames.length}`);
        this.event('output', {
          category: 'console',
          output: `AI.js Pro: ${event.reason === 'step' ? '单步' : '断点'}停止于第 ${event.line} 行\n`,
        });
        this.event('stopped', {
          reason: event.reason === 'step' ? 'step' : 'breakpoint',
          threadId: 1,
          allThreadsStopped: true,
          preserveFocusHint: false,
        });
        return;
      case 'output':
        this.event('output', { category: 'stdout', output: `${event.message ?? ''}\n` });
        return;
      case 'terminated':
        this.running = false;
        this.frames = [];
        this.log('[debug] terminated');
        this.event('terminated');
        return;
      default:
        this.log(`[debug] 未知设备事件：${JSON.stringify(event)}`);
    }
  }

  private stackFrames(): any[] {
    return this.frames.map((frame, index) => ({
      id: index + 1,
      name: frame.name && frame.name.length > 0 ? frame.name : '(anonymous)',
      line: frame.line,
      column: 1,
      source: this.sourceFor(frame.url),
    }));
  }

  private sourceFor(url: string): { name: string; path?: string } {
    if (this.programPath && (url.includes(this.scriptName) || this.scriptName.includes(url))) {
      return { name: this.scriptName, path: this.programPath };
    }
    return { name: url || this.scriptName };
  }

  // ---- 发送封装 ----

  private respond(seq: number, command: string, body?: any, message?: string): void {
    const response: any = {
      type: 'response',
      request_seq: seq,
      command,
      success: message === undefined,
    };
    if (message !== undefined) {
      response.message = message;
    }
    if (body !== undefined) {
      response.body = body;
    }
    this.send(response);
  }

  private event(event: string, body?: any): void {
    this.send({ type: 'event', event, body });
  }

  private send(message: any): void {
    this.emitter.fire(message as vscode.DebugProtocolMessage);
  }
}

/** 调试会话工厂（注册到 VS Code）。 */
export function createDebugAdapterFactory(
  manager: DeviceManager,
  output: vscode.OutputChannel,
): vscode.DebugAdapterDescriptorFactory {
  return {
    createDebugAdapterDescriptor(): vscode.ProviderResult<vscode.DebugAdapterDescriptor> {
      return new vscode.DebugAdapterInlineImplementation(new AijsproDebugSession(manager, output));
    },
  };
}

/** 默认调试配置（没有 launch.json 时给初始配置）。 */
export function createDebugConfigurationProvider(): vscode.DebugConfigurationProvider {
  return {
    provideDebugConfigurations(): vscode.ProviderResult<vscode.DebugConfiguration[]> {
      return [
        {
          type: 'aijspro',
          request: 'launch',
          name: 'AI.js Pro: 调试当前文件（Rhino）',
          program: '${file}',
        },
      ];
    },
    resolveDebugConfiguration(
      _folder: vscode.WorkspaceFolder | undefined,
      config: vscode.DebugConfiguration,
    ): vscode.ProviderResult<vscode.DebugConfiguration> {
      if (!config.type && !config.request && !config.name) {
        // F5 且没有 launch.json → 用当前文件生成
        const editor = vscode.window.activeTextEditor;
        if (!editor || editor.document.uri.scheme !== 'file') {
          vscode.window.showWarningMessage('AI.js Pro: 请先打开一个 .js 文件再调试');
          return undefined;
        }
        config.type = 'aijspro';
        config.request = 'launch';
        config.name = 'AI.js Pro: 调试当前文件（Rhino）';
        config.program = editor.document.fileName;
      }
      if (!config.program) {
        config.program = '${file}';
      }
      if (!config.host) {
        const remembered = vscode.workspace
          .getConfiguration('aijspro')
          .get<string[]>('devices', []);
        if (remembered.length === 1) {
          config.host = remembered[0];
        }
      }
      return config;
    },
  };
}
