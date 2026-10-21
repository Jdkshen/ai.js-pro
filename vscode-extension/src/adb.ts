import { execFile } from 'child_process';
import * as fs from 'fs';
import * as path from 'path';

export interface UsbDevice { serial: string; state: string; name: string }

export function parseAdbDevices(output: string): UsbDevice[] {
  return output.split(/\r?\n/).flatMap((line) => {
    const match = /^(\S+)\s+(device|unauthorized|offline)\b(.*)$/.exec(line.trim());
    if (!match) return [];
    const model = /\bmodel:(\S+)/.exec(match[3])?.[1];
    return [{ serial: match[1], state: match[2], name: model?.replace(/_/g, ' ') || match[1] }];
  });
}

/** Only forwards created by this extension instance are removed on shutdown. */
export class UsbDevices {
  private forwards = new Map<string, { adb: string; port: number }>();
  private disposed = false;
  constructor(private readonly configuredPath: () => string = () => '') {}

  private async adbPath(): Promise<string> {
    const configured = this.configuredPath().trim();
    if (configured) return configured;
    const name = process.platform === 'win32' ? 'adb.exe' : 'adb';
    const roots = [process.env.ANDROID_SDK_ROOT, process.env.ANDROID_HOME,
      process.env.LOCALAPPDATA && path.join(process.env.LOCALAPPDATA, 'Android', 'Sdk'),
      process.platform === 'win32' ? 'C:/Android' : undefined];
    for (const root of roots) {
      if (!root) continue;
      const candidate = path.join(root, 'platform-tools', name);
      if (fs.existsSync(candidate)) return candidate;
    }
    return name;
  }

  private run(adb: string, args: string[]): Promise<string> {
    return new Promise((resolve, reject) => {
      execFile(adb, args, { timeout: 8000, windowsHide: true, maxBuffer: 1024 * 1024 }, (error, stdout, stderr) => {
        if (error) {
          const code = (error as NodeJS.ErrnoException).code;
          reject(new Error(code === 'ENOENT' ? '找不到 adb，请在 aijspro.adbPath 设置 Android platform-tools 的 adb 路径'
            : String(stderr).trim() || error.message));
        } else resolve(String(stdout).trim());
      });
    });
  }

  async list(): Promise<UsbDevice[]> {
    return parseAdbDevices(await this.run(await this.adbPath(), ['devices', '-l']));
  }

  addressFor(serial: string): string | undefined {
    const value = this.forwards.get(serial);
    return value ? `127.0.0.1:${value.port}` : undefined;
  }

  async connectAddress(serial: string): Promise<string> {
    if (this.disposed) throw new Error('USB 连接管理器已关闭');
    const existing = this.forwards.get(serial);
    if (existing) return `127.0.0.1:${existing.port}`;
    const adb = await this.adbPath();
    const value = await this.run(adb, ['-s', serial, 'forward', 'tcp:0', 'tcp:9317']);
    const port = Number(value);
    if (!/^\d+$/.test(value) || port < 1 || port > 65535) throw new Error(`USB 端口转发失败：${value}`);
    if (this.disposed) {
      await this.run(adb, ['-s', serial, 'forward', '--remove', `tcp:${port}`]);
      throw new Error('USB 连接已取消');
    }
    this.forwards.set(serial, { adb, port });
    return `127.0.0.1:${port}`;
  }

  async dispose(): Promise<void> {
    this.disposed = true;
    const entries = [...this.forwards];
    this.forwards.clear();
    await Promise.all(entries.map(([serial, { adb, port }]) =>
      this.run(adb, ['-s', serial, 'forward', '--remove', `tcp:${port}`]).catch(() => {})));
  }
}
