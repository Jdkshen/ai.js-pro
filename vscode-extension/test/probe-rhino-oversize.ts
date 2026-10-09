import * as net from 'net';
import { DeviceConnection } from '../src/connection';

const RHINO = [
  "requestScreenCapture('portrait');",
  'var screen = captureScreen();',
  'var clipped = images.clip(screen, 100, 100, 32, 32);',
  'var oversized = images.resize(clipped, [64, 64]);',
  'try {',
  "var r = images.findImage(screen, oversized, { region: [100, 100, 32, 32], threshold: 0.8 });",
  "console.log('RHINO oversize result=' + r);",
  "} catch (e) { console.log('RHINO oversize error=' + e); }",
].join('\n');

function connectOnce(): Promise<DeviceConnection> {
  return new Promise((resolve, reject) => {
    const socket = net.createConnection({ host: '127.0.0.1', port: 19317 });
    const conn = new DeviceConnection(socket, 'local', () => undefined);
    socket.once('error', reject);
    conn.once('connected', () => resolve(conn));
    conn.once('closed', () => reject(new Error('closed')));
  });
}

async function connectWithRetry(): Promise<DeviceConnection> {
  for (let attempt = 0; attempt < 40; attempt += 1) {
    try { return await connectOnce(); } catch { await new Promise((r) => setTimeout(r, 1000)); }
  }
  throw new Error('no device connection');
}

async function main(): Promise<void> {
  const conn = await connectWithRetry();
  console.log('[connected]');
  conn.on('log', (line: string) => console.log('[device]', line.trim()));
  await conn.run('r-oversize.js', 'r-oversize.js', RHINO);
  await new Promise((r) => setTimeout(r, 5000));
  conn.close();
}
main().catch((e) => { console.error('FAILED:', e); process.exit(1); });
