/**
 * 纯逻辑日志心跳：不 import vscode，供 node --test 直接测。
 *
 * 目的：把「脚本没有新日志」和「设备→扩展链路断了」区分开。
 * - 每个连接单独计数，互不影响；
 * - tick 只在连接静默达到阈值时输出一行心跳，正常刷日志不会刷屏；
 * - 之前静默达到 2 倍阈值、现在恢复日志时输出恢复行。
 */

export interface LogHeartbeatOptions {
  /** 心跳间隔/静默阈值（秒）。0 表示关闭心跳。 */
  intervalSeconds: number;
  /** 心跳/恢复/启动行输出；由扩展接到 OutputChannel。 */
  output: (line: string) => void;
  /** 可注入时钟（单测用）；默认 Date.now。 */
  now?: () => number;
}

export interface LogHeartbeatStatus {
  key: string;
  name: string;
  lines: number;
  connectedAt: number;
  lastLogAt: number | null;
  silentMs: number;
}

interface ConnectionState {
  key: string;
  name: string;
  lines: number;
  connectedAt: number;
  lastLogAt: number | null;
  lastActivityAt: number;
}

export function normalizeHeartbeatSeconds(value: unknown): number {
  const numeric = typeof value === 'number' ? value : typeof value === 'string' ? Number(value) : NaN;
  if (!Number.isFinite(numeric) || numeric < 0) return 30;
  return Math.min(3600, Math.floor(numeric));
}

export function formatSilence(ms: number): string {
  const seconds = Math.max(0, Math.round(ms / 1000));
  return seconds + 's';
}

function formatClock(timestamp: number): string {
  const date = new Date(timestamp);
  const pad = (value: number) => String(value).padStart(2, '0');
  return pad(date.getHours()) + ':' + pad(date.getMinutes()) + ':' + pad(date.getSeconds());
}

export class LogHeartbeat {
  private readonly output: (line: string) => void;
  private readonly now: () => number;
  private readonly connections = new Map<string, ConnectionState>();
  private intervalSecondsValue: number;

  constructor(options: LogHeartbeatOptions) {
    this.output = options.output;
    this.now = options.now ?? Date.now;
    this.intervalSecondsValue = normalizeHeartbeatSeconds(options.intervalSeconds);
  }

  get intervalSeconds(): number {
    return this.intervalSecondsValue;
  }

  get enabled(): boolean {
    return this.intervalSecondsValue > 0;
  }

  /** 运行中修改配置；0 表示关闭。 */
  setIntervalSeconds(value: unknown): void {
    this.intervalSecondsValue = normalizeHeartbeatSeconds(value);
  }

  /**
   * 连接建立：初始化每个连接的独立状态，并打印一次启动行。
   * 已存在时只更新名字，不重复打印。
   */
  connect(key: string, name: string, at: number = this.now()): void {
    const existing = this.connections.get(key);
    if (existing) {
      existing.name = name;
      return;
    }
    this.connections.set(key, {
      key,
      name,
      lines: 0,
      connectedAt: at,
      lastLogAt: null,
      lastActivityAt: at,
    });
    if (this.enabled) {
      this.output('[' + name + '] 日志心跳已开启（每 ' + this.intervalSecondsValue + 's 一次，静默时提示）');
    }
  }

  /** 收到一行设备日志；同时检测“长时间静默后恢复”。 */
  onLog(key: string, name: string, at: number = this.now()): void {
    let state = this.connections.get(key);
    if (!state) {
      state = {
        key,
        name,
        lines: 0,
        connectedAt: at,
        lastLogAt: null,
        lastActivityAt: at,
      };
      this.connections.set(key, state);
    }
    const silent = Math.max(0, at - state.lastActivityAt);
    state.name = name;
    state.lines += 1;
    state.lastLogAt = at;
    state.lastActivityAt = at;
    if (this.enabled && silent >= this.intervalSecondsValue * 2000) {
      this.output('[' + name + '] ▶ 日志恢复（静默 ' + formatSilence(silent) + '）');
    }
  }

  /** 定时 tick：只对静默达到阈值的连接输出心跳，正常刷日志时不输出。 */
  tick(at: number = this.now()): void {
    if (!this.enabled) return;
    const thresholdMs = this.intervalSecondsValue * 1000;
    for (const state of this.connections.values()) {
      const silent = Math.max(0, at - state.lastActivityAt);
      if (silent < thresholdMs) continue;
      const recent = state.lastLogAt === null ? '暂无日志' : formatClock(state.lastLogAt);
      this.output(
        '[' + state.name + '] ⏱ 链路正常，已收 ' + state.lines + ' 行；' +
          '脚本静默 ' + formatSilence(silent) + '（最近 ' + recent + '）',
      );
    }
  }

  /** 命令 aijspro.logStatus 用的一次性状态；不产生心跳行。 */
  status(at: number = this.now()): LogHeartbeatStatus[] {
    return [...this.connections.values()].map((state) => ({
      key: state.key,
      name: state.name,
      lines: state.lines,
      connectedAt: state.connectedAt,
      lastLogAt: state.lastLogAt,
      silentMs: Math.max(0, at - state.lastActivityAt),
    }));
  }

  statusLines(at: number = this.now()): string[] {
    return this.status(at).map((state) => {
      const recent = state.lastLogAt === null ? '暂无日志' : formatClock(state.lastLogAt);
      return '[' + state.name + '] 已收 ' + state.lines + ' 行；最近日志 ' + recent +
        '；静默 ' + formatSilence(state.silentMs);
    });
  }

  /** 连接关闭：清掉状态，避免 logStatus 显示幽灵连接。 */
  drop(key: string): void {
    this.connections.delete(key);
  }
}
