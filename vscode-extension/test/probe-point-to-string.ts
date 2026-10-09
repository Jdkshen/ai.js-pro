import * as net from 'net';
import { DeviceConnection } from '../src/connection';

const QUICKJS = [
  '// @engine quickjs',
  'var ok = false;',
  "try { ok = images.requestScreenCapture({ mode: 'accessibility' }); } catch (e) {}",
  "if (!ok) ok = requestScreenCapture('portrait');",
  "var screen = captureScreen({ mode: 'full' });",
  'var cx = Math.floor(screen.width / 2);',
  'var cy = Math.floor(screen.height / 2);',
  'var c = images.pixel(screen, cx, cy);',
  'var region = [cx - 60, cy - 60, 120, 120];',
  "var p = images.findColor(screen, c, { region: region, threshold: 4 });",
  "console.log('A1 json=' + JSON.stringify(p));",
  "console.log('A2 str=' + (p && ('' + p)));",
  "console.log('A3 call=' + (p && p.toString()));",
  "console.log('A4 keys=' + (p ? Object.keys(p).join(',') : 'n/a'));",
  "var m = images.findMultiColors(screen, c, [[0, 0, c]], { region: region, threshold: 4 });",
  "console.log('B1 str=' + (m && ('' + m)));",
  "var points = images.findAllPointsForColor(screen, c, { region: [cx, cy, 8, 8], threshold: 0 });",
  "console.log('C1 n=' + points.length + ' str=' + (points.length ? ('' + points[0]) : 'n/a'));",
  'try {',
  'var tpl = images.clip(screen, cx, cy, 16, 16);',
  'var found = images.findImage(screen, tpl, { threshold: 0.9, region: region });',
  "console.log('D1 str=' + (found && ('' + found)) + ' json=' + JSON.stringify(found));",
  'tpl.recycle();',
  "} catch (e) { console.log('D0 err=' + e); }",
  'screen.recycle();',
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
    try {
      return await connectOnce();
    } catch {
      await new Promise((r) => setTimeout(r, 1000));
    }
  }
  throw new Error('no device connection');
}

async function main(): Promise<void> {
  const conn = await connectWithRetry();
  console.log('[connected]');
  conn.on('log', (line: string) => console.log('[device]', line.trim()));
  await conn.run('q-tostring.js', 'q-tostring.js', QUICKJS);
  await new Promise((r) => setTimeout(r, 8000));
  conn.close();
}
main().catch((e) => { console.error('FAILED:', e); process.exit(1); });
