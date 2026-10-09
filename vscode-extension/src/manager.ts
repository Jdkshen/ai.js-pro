import * as net from 'net';
import { EventEmitter } from 'events';
import * as vscode from 'vscode';

import { DeviceConnection } from './connection';

const HANDSHAKE_TIMEOUT_MS = 12000;

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
    return this.active && !this.active.isClosed() ? this.active : this.connections.values().next().value;
  }

  /** 启动 8327 监听（手机记住电脑后会主动回连到这里）。 */
  startListening(): void {
    const port = vscode.workspace.getConfiguration('aijspro').get<number>('listenPort', 8327);
    const server = net.createServer((socket) => {
      if (this.ignoredHosts.has(deviceHost(shortHost(socket.remoteAddress)))) {
        socket.destroy(); // 已删除的手机仍可能记着电脑，不能让回连重新把地址加入列表。
        return;
      }
      this.log(`手机回连：${socket.remoteAddress}:${socket.remotePort}`);
      this.adopt(socket, `${shortHost(socket.remoteAddress)}:${socket.remotePort}`, true);
    });
    server.on('error', (error: NodeJS.ErrnoException) => {
      this.log(`监听 ${port} 失败：${error.message}（手机主动回连会受影响，仍可手动连接）`);
    });
    server.listen(port, () => this.log(`正在监听 ${port}，等待手机回连…`));
    this.server = server;

    this.autoConnectEnabled = vscode.workspace.getConfiguration('aijspro').get<boolean>('autoConnect', true);
    if (this.autoConnectEnabled) {
      this.retrySavedDevices();
      this.scheduleRetry(5000);
    }
  }

  /** 主动连接设备（host 可带端口，默认 9317）。 */
  async connectTo(host: string): Promise<void> {
    const ip = deviceHost(host);
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
    if (this.disposed || this.ignoredHosts.has(deviceHost(host))) return;
    const { ip, port } = splitHost(host, 9317);
    if (this.isConnectedTo(ip)) {
      this.log(`${host} 已经连接，忽略重复连接`);
      return;
    }
    this.log(`正在连接 ${ip}:${port}…`);
    const socket = net.createConnection({ host: ip, port });
    this.adopt(socket, `${ip}:${port}`, false, host.trim());
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
      const address = conn.socket.remoteAddress || splitHost(conn.label, 9317).ip;
      if (ips.has(deviceHost(shortHost(address)))) conn.close();
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
    this.active = conn;
    this.emit('changed');
  }

  /** 「重新扫描设备(Scan Devices)」：对所有已记住的设备再拨一次（已连的跳过）。 */
  scanNow(): number {
    let tried = 0;
    for (const host of this.retryDevices()) {
      const { ip } = splitHost(host, 9317);
      if (this.isConnectedTo(ip)) {
        continue;
      }
      try {
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
    // 连上但一直不完成 hello：多半不是 Pro 扩展服务器（或端口被别的程序占用）。
    // 握手成功会由 DeviceConnection.onHello 清掉这个超时。
    socket.setTimeout(HANDSHAKE_TIMEOUT_MS, () => {
      socket.destroy(new Error('握手超时'));
    });
    const conn = new DeviceConnection(socket, label, this.log);
    this.connections.add(conn);
    conn.once('connected', () => {
      const host = inbound ? shortHost(socket.remoteAddress) : dialedHost;
      if (host && this.ignoredHosts.has(deviceHost(host))) {
        conn.close(); // 删除与 hello 同时发生时，不接受这条迟到的连接。
        return;
      }
      this.active = conn;
      if (host) {
        this.rememberDevice(host);
      }
      this.log(
        `已连接：${conn.deviceName}（${conn.label}，app ${conn.hello?.app_version ?? '?'}，协议 v${conn.hello?.server_version ?? '?'}）`,
      );
      this.emit('changed');
      if (this.autoConnectEnabled) this.scheduleRetry(30000);
    });
    conn.once('closed', () => {
      this.connections.delete(conn);
      if (this.active === conn) {
        this.active = undefined;
      }
      this.log(`已断开：${conn.deviceName}（${conn.label}）`);
      this.emit('changed');
      if (this.autoConnectEnabled) this.scheduleRetry(3000);
    });
    conn.on('log', (line: string) => this.emit('deviceLog', conn, line));
  }

  private isConnectedTo(ip: string): boolean {
    for (const conn of this.connections) {
      if (this.matchesHost(conn, ip)) {
        return true;
      }
    }
    return false;
  }

  private matchesHost(conn: DeviceConnection, ip: string): boolean {
    return conn.label.startsWith(`${ip}:`) || shortHost(conn.socket.remoteAddress) === ip;
  }

  private retrySavedDevices(): void {
    for (const host of this.retryDevices()) {
      const { ip } = splitHost(host, 9317);
      if (!this.isConnectedTo(ip)) {
        try {
          this.dialDevice(host);
        } catch (error) {
          this.log(`自动连接 ${host} 失败：${(error as Error).message}`);
        }
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
        const { ip } = splitHost(host, 9317);
        return this.all.some((conn) => conn.attached && this.matchesHost(conn, ip));
      });
      this.scheduleRetry(allRememberedConnected ? 30000 : 5000);
    }, delayMs);
  }

  private rememberDevice(host: string): void {
    const ip = deviceHost(host);
    if (!host || ip === '127.0.0.1' || host === '::1' || ip === 'localhost') {
      return; // USB 转发的回环地址不值得记住（与手机侧同样的策略）。
    }
    void this.writeConfiguration(async () => {
      if (this.ignoredHosts.has(deviceHost(host))) return;
      const list = savedDevices();
      if (list.includes(host)) return;
      await vscode.workspace.getConfiguration('aijspro')
        .update('devices', [...list, host], vscode.ConfigurationTarget.Global);
      this.log(`已记住设备地址：${host}`);
    }).catch((error) => this.log(`保存设备地址失败：${(error as Error).message}`));
  }

  private retryDevices(): string[] {
    return savedDevices().filter((host) => !this.ignoredHosts.has(deviceHost(host)));
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
  return shortHost(splitHost(host, 9317).ip).toLowerCase();
}

export function savedDevices(): string[] {
  return vscode.workspace.getConfiguration('aijspro').get<string[]>('devices', []);
}

function splitHost(host: string, defaultPort: number): { ip: string; port: number } {
  const trimmed = host.trim();
  const match = /^(.*):(\d+)$/.exec(trimmed);
  if (match && match[1]) {
    return { ip: match[1], port: Number(match[2]) };
  }
  return { ip: trimmed, port: defaultPort };
}

/** `::ffff:192.168.31.59` → `192.168.31.59`。 */
function shortHost(address: string | undefined): string {
  if (!address) {
    return '';
  }
  return address.startsWith('::ffff:') ? address.slice(7) : address;
}
