import * as net from 'net';
import { DeviceConnection } from '../src/connection';

const QUICKJS = [
  '// @engine quickjs',
  'var ok = false;',
  "try { ok = images.requestScreenCapture({ mode: 'accessibility' }); } catch (e) {}",
  "if (!ok) ok = requestScreenCapture('portrait');",
  "var screen = captureScreen({ mode: 'full' });",
  "var p = images.findColor(screen, '#3874CB', { region: [0, 502, 241, 267], threshold: 20 });",
  "if (!p) { console.log('Q0 no match'); } else {",
  "console.log('Q1 json=' + JSON.stringify(p));",
  "console.log('Q2 str=' + (p + ''));",
  "console.log('Q3 typ=' + (typeof p) + ' x=' + p.x + ' y=' + p.y);",
  "console.log('Q5 call=' + p.toString());",
  "console.log('Q6 keys=' + Object.keys(p).join(','));",
  'console.log(p); }',
  'screen.recycle();',
].join('\n');

const RHINO = [
  "requestScreenCapture('portrait');",
  'var screen = captureScreen();',
  "var p = findColor(screen, '#3874CB', { region: [0, 502, 241, 267], threshold: 20 });",
  "try { console.log('R2 json=' + JSON.stringify(p)); } catch (e) { console.log('R2 jsonERR=' + e); }",
  "console.log('R5 str=' + (p + ''));",
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
  console.log('--- QUICKJS ---');
  await conn.run('q-print.js', 'q-print.js', QUICKJS);
  await new Promise((r) => setTimeout(r, 5000));
  console.log('--- RHINO ---');
  await conn.run('r-print.js', 'r-print.js', RHINO);
  await new Promise((r) => setTimeout(r, 5000));
  conn.close();
}
main().catch((e) => { console.error('FAILED:', e); process.exit(1); });
