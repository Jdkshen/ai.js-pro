import * as net from 'net';
import { EventEmitter } from 'events';
import * as vscode from 'vscode';

import { DeviceConnection } from './connection';

const HANDSHAKE_TIMEOUT_MS = 12000;
const DEFAULT_LISTEN_PORT = 8327;
const DEFAULT_DEVICE_PORT = 9317;

/**
 * 多设备连接管理：监听 8327 等手机回连，也可以主动拨手机的 9317；
 * 已连接过的设备地址写进 `aijspro.devices`，启动时自动重连。
 */
export class DeviceManager extends EventEmitter {
  private server: net.Server | undefined;
  private readonly connections = new Set<DeviceConnection>();
  private retryTimer: NodeJS.Timeout | undefined;
  private autoConnectEnabled = false;
  private disposed = false;
  private readonly ignoredHosts = new Set<string>();
  private readonly deviceIntents = new Map<string, number>();
  private configurationWrites: Promise<void> = Promise.resolve();
  /** 最近一次完成握手的连接 = “当前设备”。 */
  private active: DeviceConnection | undefined;
  private selectedDeviceKey: string | undefined;

  constructor(private readonly log: (message: string) => void) {
    super();
    for (const host of vscode.workspace.getConfiguration('aijspro').get<string[]>('ignoredDevices', [])) {
      this.ignoredHosts.add(deviceHost(host));
    }
  }

  get all(): DeviceConnection[] {
    return [...this.connections];
  }

  get activeConnection(): DeviceConnection | undefined {
    if (this.selectedDeviceKey) {
      return this.all.find((conn) => conn.key === this.selectedDeviceKey && conn.attached && !conn.isClosed());
    }
    return this.active && !this.active.isClosed() ? this.active : this.connections.values().next().value;
  }

  /** 启动 8327 监听（可在 `aijspro.listenPort` 自定义；手机记住电脑后会主动回连到这里）。 */
  startListening(): void {
    const rawPort = vscode.workspace.getConfiguration('aijspro').get<number>('listenPort', DEFAULT_LISTEN_PORT);
    let port: number | undefined;
    try {
      port = parsePort(String(rawPort), 'aijspro.listenPort');
    } catch (error) {
      this.log(`监听端口配置无效：${(error as Error).message}；已跳过监听，手机将无法主动回连（仍可手动连接）`);
    }
    if (port !== undefined) {
      const listenPort = port;
      const server = net.createServer((socket) => {
        const host = shortHost(socket.remoteAddress);
        const origin = formatHostPort(host, socket.remotePort ?? 0);
        if (this.ignoredHosts.has(deviceHost(host))) {
          this.log(`忽略已删除设备的回连：${origin}`);
          socket.destroy(); // 已删除的手机仍可能记着电脑，不能让回连重新把地址加入列表。
          return;
        }
        this.log(`手机回连：${origin}`);
        this.adopt(socket, origin, true);
      });
      server.on('error', (error: NodeJS.ErrnoException) => {
        const code = error.code ? `${error.code} ` : '';
        this.log(`监听 ${listenPort} 失败：${code}${error.message}（手机主动回连会受影响，仍可手动连接）`);
      });
      try {
        server.listen(listenPort, () => {
          const kind = listenPort === DEFAULT_LISTEN_PORT ? '默认' : '自定义';
          this.log(`正在监听 ${listenPort}（${kind}手机回连端口，可在 aijspro.listenPort 修改）…`);
        });
        this.server = server;
      } catch (error) {
        this.log(`监听 ${listenPort} 失败：${(error as Error).message}（手机主动回连会受影响，仍可手动连接）`);
        try {
          server.close();
        } catch {
          // 从未开始监听时 close 可能抛 ERR_SERVER_NOT_RUNNING。
        }
      }
    }

    this.autoConnectEnabled = vscode.workspace.getConfiguration('aijspro').get<boolean>('autoConnect', true);
    if (this.autoConnectEnabled) {
      this.retrySavedDevices();
      this.scheduleRetry(5000);
    }
  }

  /**
   * 主动连接设备（host 可带端口，默认 9317；IPv6 写 `[地址]:端口`）。
   * 只发起 TCP 连接，hello 握手完成由 `connected` 事件通知；调用方无需等待握手。
   */
  async connectTo(host: string): Promise<void> {
    const target = splitHost(host, DEFAULT_DEVICE_PORT);
    const ip = normalizeHost(target.ip);
    const intent = (this.deviceIntents.get(ip) ?? 0) + 1;
    this.deviceIntents.set(ip, intent);
    await this.writeConfiguration(async () => {
      if (this.deviceIntents.get(ip) !== intent) return;
      await this.updateAddressList('ignoredDevices', (list) => list.filter((item) => deviceHost(item) !== ip));
    });
    if (this.deviceIntents.get(ip) !== intent) return;
    this.ignoredHosts.delete(ip); // 主动连接表示用户重新启用这个设备。
    this.dialDevice(host);
  }

  private dialDevice(host: string): void {
    if (this.disposed) return;
    const { ip, port } = splitHost(host, DEFAULT_DEVICE_PORT);
    const address = formatHostPort(ip, port);
    if (this.ignoredHosts.has(normalizeHost(ip))) return;
    if (this.isConnectedTo(ip, port)) {
      this.log(`${address} 已经连接，忽略重复连接`);
      return;
    }
    this.log(`正在连接手机 ${address}…`);
    const socket = net.createConnection({ host: ip, port });
    this.adopt(socket, address, false, host.trim());
  }

  /** 删除记住的地址、关闭该手机的连接并阻止自动拨号/回连；手动连接可重新启用。 */
  async forgetDevices(hosts: string[]): Promise<void> {
    const ips = new Set(hosts.map(deviceHost));
    if (ips.size === 0) return;
    for (const ip of ips) {
      this.ignoredHosts.add(ip);
      this.deviceIntents.set(ip, (this.deviceIntents.get(ip) ?? 0) + 1);
    }
    for (const conn of [...this.connections]) {
      let address = conn.socket.remoteAddress;
      if (!address) {
        try {
          address = splitHost(conn.label, DEFAULT_DEVICE_PORT).ip;
        } catch {
          address = undefined;
        }
      }
      if (address && ips.has(deviceHost(address))) conn.close();
    }
    // 串行保存，防止删除前已排队的 hello 保存操作把旧列表写回来。
    await this.writeConfiguration(async () => {
      await this.updateAddressList('devices', (list) => list.filter((host) => !ips.has(deviceHost(host))));
      await this.updateAddressList('ignoredDevices', (list) => [...new Set([...list.map(deviceHost), ...ips])]);
    });
    this.scheduleRetry(5000);
    this.log(`已删除设备：${[...ips].join('、')}，已停止这些地址的自动连接`);
    this.emit('changed');
  }

  disconnectActive(): void {
    const conn = this.activeConnection;
    conn?.close();
  }

  /** 「断开所有连接(Disconnect)」：手机侧会按记忆重新回连。 */
  disconnectAll(): void {
    for (const conn of [...this.connections]) {
      conn.close();
    }
  }

  /** 「选择设备(Select Device)」：把某个已连接设备设为当前设备。 */
  setActive(conn: DeviceConnection): void {
    this.selectedDeviceKey = conn.key;
    this.active = conn;
    this.emit('changed');
  }

  /** 「重新扫描设备(Scan Devices)」：对所有已记住的设备再拨一次（已连的跳过）。 */
  scanNow(): number {
    let tried = 0;
    for (const host of this.retryDevices()) {
      try {
        const { ip, port } = splitHost(host, DEFAULT_DEVICE_PORT);
        if (this.isConnectedTo(ip, port)) {
          continue;
        }
        this.dialDevice(host);
        tried++;
      } catch (error) {
        this.log(`重连 ${host} 失败：${(error as Error).message}`);
      }
    }
    return tried;
  }

  dispose(): void {
    if (this.retryTimer) clearTimeout(this.retryTimer);
    this.retryTimer = undefined;
    this.disposed = true;
    this.autoConnectEnabled = false;
    this.server?.close();
    for (const conn of this.connections) {
      conn.close();
    }
    this.connections.clear();
  }

  /** 按 label/设备名/路径挑一个连接（多设备时用 QuickPick）。 */
  async pickConnection(): Promise<DeviceConnection | undefined> {
    const list = this.all.filter((conn) => conn.attached);
    if (list.length === 0) {
      return undefined;
    }
    if (this.selectedDeviceKey) return list.find((conn) => conn.key === this.selectedDeviceKey);
    if (list.length === 1) {
      return list[0];
    }
    const picked = await vscode.window.showQuickPick(
      list.map((conn) => ({
        label: conn.deviceName,
        description: conn.label,
        conn,
      })),
      { placeHolder: '选择设备' },
    );
    return picked?.conn;
  }

  private adopt(socket: net.Socket, label: string, inbound: boolean, dialedHost?: string): void {
    const startedAt = Date.now();
    // 连上但一直不完成 hello：多半不是 AI.js Pro 电脑连接（或端口被别的程序占用）。
    // 握手成功会由 DeviceConnection.onHello 清掉这个超时。
    socket.setTimeout(HANDSHAKE_TIMEOUT_MS, () => {
      this.log(`[连接 ${label}] 等待手机 hello 超时（${Math.round(HANDSHAKE_TIMEOUT_MS / 1000)} 秒），主动断开`);
      socket.destroy();
    });
    const conn = new DeviceConnection(socket, label, this.log,
      inbound ? formatHostPort(shortHost(socket.remoteAddress), DEFAULT_DEVICE_PORT) : dialedHost);
    this.connections.add(conn);
    conn.once('connected', () => {
      const host = inbound ? shortHost(socket.remoteAddress) : dialedHost;
      if (host && this.ignoredHosts.has(deviceHost(host))) {
        conn.close(); // 删除与 hello 同时发生时，不接受这条迟到的连接。
        return;
      }
      if (!this.selectedDeviceKey || conn.key === this.selectedDeviceKey) this.active = conn;
      if (host) {
        this.rememberDevice(host);
      }
      this.log(
        `已连接：${conn.deviceName}（${conn.label}，app ${conn.hello?.app_version ?? '?'}，` +
          `协议 v${conn.hello?.server_version ?? '?'}，握手 ${Date.now() - startedAt}ms）`,
      );
      this.emit('changed');
      if (this.autoConnectEnabled) this.scheduleRetry(30000);
    });
    conn.once('closed', () => {
      this.connections.delete(conn);
      if (this.active === conn) {
        this.active = undefined;
      }
      if (conn.attached) {
        this.log(`已断开：${conn.deviceName}（${conn.label}，连接 ${formatDuration(Date.now() - startedAt)}）`);
      } else {
        this.log(`连接失败：${conn.label}（未完成 hello 握手，${formatDuration(Date.now() - startedAt)}）`);
      }
      this.emit('changed');
      if (this.autoConnectEnabled) this.scheduleRetry(3000);
    });
    conn.on('log', (line: string) => this.emit('deviceLog', conn, line));
  }

  /** An adb forward is identified by host AND port; multiple phones share 127.0.0.1. */
  connectionAt(address: string): DeviceConnection | undefined {
    try {
      const { ip, port } = splitHost(address, DEFAULT_DEVICE_PORT);
      return this.all.find((conn) => conn.attached && !conn.isClosed() && this.matchesEndpoint(conn, ip, port));
    } catch { return undefined; }
  }

  private matchesEndpoint(conn: DeviceConnection, ip: string, port: number): boolean {
    try {
      const target = splitHost(conn.deviceAddress || conn.label, DEFAULT_DEVICE_PORT);
      return normalizeHost(target.ip) === normalizeHost(ip) && target.port === port;
    } catch { return false; }
  }

  private isConnectedTo(ip: string, port: number): boolean {
    for (const conn of this.connections) {
      if (!conn.isClosed() && this.matchesEndpoint(conn, ip, port)) {
        return true;
      }
    }
    return false;
  }

  private matchesHost(conn: DeviceConnection, ip: string): boolean {
    const target = normalizeHost(ip);
    if (normalizeHost(shortHost(conn.socket.remoteAddress)) === target) return true;
    try {
      return normalizeHost(splitHost(conn.label, DEFAULT_DEVICE_PORT).ip) === target;
    } catch {
      return false;
    }
  }

  private retrySavedDevices(): void {
    for (const host of this.retryDevices()) {
      try {
        const { ip, port } = splitHost(host, DEFAULT_DEVICE_PORT);
        if (!this.isConnectedTo(ip, port)) {
          this.dialDevice(host);
        }
      } catch (error) {
        this.log(`自动连接 ${host} 失败：${(error as Error).message}`);
      }
    }
  }

  /** Offline devices get a short retry; once connected, reduce background traffic. */
  private scheduleRetry(delayMs: number): void {
    if (this.retryTimer) clearTimeout(this.retryTimer);
    this.retryTimer = undefined;
    if (!this.autoConnectEnabled || this.disposed || this.retryDevices().length === 0) return;
    this.retryTimer = setTimeout(() => {
      this.retryTimer = undefined;
      if (!this.autoConnectEnabled) return;
      this.retrySavedDevices();
      const allRememberedConnected = this.retryDevices().every((host) => {
        try {
          return !!this.connectionAt(host);
        } catch {
          return false;
        }
      });
      this.scheduleRetry(allRememberedConnected ? 30000 : 5000);
    }, delayMs);
  }

  private rememberDevice(host: string): void {
    let target: { ip: string; port: number };
    try {
      target = splitHost(host, DEFAULT_DEVICE_PORT);
    } catch {
      return;
    }
    const ip = normalizeHost(target.ip);
    if (!ip || isLoopbackHost(ip)) {
      return; // USB 转发的回环地址不值得记住（与手机侧同样的策略）。
    }
    const savedHost = formatHostPort(ip, target.port);
    void this.writeConfiguration(async () => {
      if (this.ignoredHosts.has(ip)) return;
      const list = savedDevices();
      if (list.some((item) => isSameAddress(item, ip, target.port))) return;
      await vscode.workspace.getConfiguration('aijspro')
        .update('devices', [...list, savedHost], vscode.ConfigurationTarget.Global);
      this.log(`已记住设备地址：${savedHost}`);
    }).catch((error) => this.log(`保存设备地址失败：${(error as Error).message}`));
  }

  private retryDevices(): string[] {
    return savedDevices().filter((host) => {
      if (this.ignoredHosts.has(deviceHost(host))) return false;
      try {
        splitHost(host, DEFAULT_DEVICE_PORT);
        return true;
      } catch {
        return false; // 历史设置里的坏地址不再参与轮询，手动连接时会给出明确错误。
      }
    });
  }

  private writeConfiguration(action: () => Promise<void>): Promise<void> {
    const operation = this.configurationWrites.then(action);
    this.configurationWrites = operation.catch(() => {});
    return operation;
  }

  /** 同时处理用户/工作区覆盖，避免删除后被另一层旧设置再次带回来。 */
  private async updateAddressList(key: string, transform: (list: string[]) => string[]): Promise<void> {
    const configuration = vscode.workspace.getConfiguration('aijspro');
    const values = configuration.inspect<string[]>(key);
    const scopes: [vscode.ConfigurationTarget, string[] | undefined][] = [
      [vscode.ConfigurationTarget.Global, values?.globalValue ?? []],
      [vscode.ConfigurationTarget.Workspace, values?.workspaceValue],
      [vscode.ConfigurationTarget.WorkspaceFolder, values?.workspaceFolderValue],
    ];
    for (const [target, value] of scopes) {
      if (!value) continue;
      const next = transform(value);
      if (JSON.stringify(next) !== JSON.stringify(value)) await configuration.update(key, next, target);
    }
  }
}

function deviceHost(host: string): string {
  try {
    return normalizeHost(splitHost(host, DEFAULT_DEVICE_PORT).ip);
  } catch {
    return normalizeHost(host);
  }
}

export function savedDevices(): string[] {
  return vscode.workspace.getConfiguration('aijspro').get<string[]>('devices', []);
}

/**
 * 解析 `host` / `host:port` / `[IPv6]` / `[IPv6]:port`。
 * 裸 IPv6（含多个冒号）整体当地址，不会再被简单 split 成「地址 + 数字端口」。
 */
function splitHost(host: string, defaultPort: number): { ip: string; port: number } {
  const trimmed = (host ?? '').trim();
  if (!trimmed) {
    throw new Error('设备地址为空');
  }
  let ip = trimmed;
  let rawPort: string | undefined;
  if (trimmed.startsWith('[')) {
    const end = trimmed.indexOf(']');
    if (end < 0) {
      throw new Error(`设备地址格式无效：${trimmed}（IPv6 请写成 [地址]:端口）`);
    }
    ip = trimmed.slice(1, end).trim();
    const rest = trimmed.slice(end + 1);
    if (rest) {
      if (!rest.startsWith(':')) {
        throw new Error(`设备地址格式无效：${trimmed}（IPv6 请写成 [地址]:端口）`);
      }
      rawPort = rest.slice(1);
    }
    if (net.isIP(ip) !== 6) {
      throw new Error(`IPv6 地址无效：${ip}`);
    }
  } else if (trimmed.includes(':')) {
    const colonCount = trimmed.split(':').length - 1;
    if (colonCount > 1) {
      if (net.isIP(trimmed) !== 6) {
        throw new Error(`设备地址格式无效：${trimmed}（带端口的 IPv6 请写成 [地址]:端口）`);
      }
      ip = trimmed; // 裸 IPv6，最后一个冒号段不是端口。
    } else {
      const index = trimmed.lastIndexOf(':');
      ip = trimmed.slice(0, index).trim();
      rawPort = trimmed.slice(index + 1);
    }
  }
  if (!ip || /\s/.test(ip)) {
    throw new Error(`设备地址格式无效：${trimmed}`);
  }
  const port = rawPort === undefined
    ? defaultPort
    : parsePort(rawPort, trimmed);
  return { ip, port };
}

function parsePort(value: string, source: string): number {
  if (!/^\d+$/.test(value)) {
    throw new Error(`端口无效：${value}（${source}，端口应为 1-65535）`);
  }
  const port = Number(value);
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    throw new Error(`端口无效：${value}（${source}，端口应为 1-65535）`);
  }
  return port;
}

function formatHostPort(host: string, port: number): string {
  const clean = stripBrackets(host);
  return `${clean.includes(':') ? `[${clean}]` : clean}:${port}`;
}

/** `::ffff:192.168.31.59` → `192.168.31.59`；IPv6 统一转小写，便于比较。 */
function shortHost(address: string | undefined): string {
  if (!address) {
    return '';
  }
  const clean = stripBrackets(address.trim());
  const mapped = /^::ffff:/i.test(clean) ? clean.slice(7) : clean;
  return mapped.toLowerCase();
}

function normalizeHost(host: string): string {
  return shortHost(host);
}

function stripBrackets(host: string): string {
  return host.startsWith('[') && host.endsWith(']') ? host.slice(1, -1) : host;
}

function isLoopbackHost(host: string): boolean {
  const value = normalizeHost(host);
  return value === 'localhost'
    || value === '::1'
    || value === '0:0:0:0:0:0:0:1'
    || value.startsWith('127.');
}

function isSameAddress(host: string, ip: string, port: number): boolean {
  try {
    const target = splitHost(host, DEFAULT_DEVICE_PORT);
    return normalizeHost(target.ip) === normalizeHost(ip) && target.port === port;
  } catch {
    return false;
  }
}

function formatDuration(milliseconds: number): string {
  const seconds = Math.max(0, Math.round(milliseconds / 1000));
  return seconds < 60 ? `${seconds} 秒` : `${Math.floor(seconds / 60)} 分 ${seconds % 60} 秒`;
}
