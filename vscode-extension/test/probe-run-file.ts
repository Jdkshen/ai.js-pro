import * as fs from 'fs';
import * as net from 'net';
import { DeviceConnection } from '../src/connection';

const file = 'c:\\Users\\18101\\Documents\\ai-js-pro\\apps\\app\\src\\main\\assets\\sample\\QuickJS 新引擎\\项目与打包\\象棋自动走棋\\测.js';
const script = fs.readFileSync(file, 'utf8');

async function main(): Promise<void> {
  const socket = net.createConnection({ host: '127.0.0.1', port: 19317 });
  const conn = new DeviceConnection(socket, 'local', () => undefined);
  conn.on('log', (line: string) => console.log('[device]', line.trim()));
  await new Promise<void>((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('hello timeout')), 10000);
    conn.once('connected', () => { clearTimeout(timer); resolve(); });
    conn.once('closed', () => reject(new Error('closed')));
  });
  await conn.run('ce-shi.js', 'ce-shi.js', script);
  await new Promise((r) => setTimeout(r, 6000));
  conn.close();
}
main().catch((e) => { console.error('FAILED:', e); process.exit(1); });
