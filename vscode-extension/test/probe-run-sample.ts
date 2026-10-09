import * as fs from 'fs';
import * as net from 'net';
import { DeviceConnection } from '../src/connection';

const file = process.argv[2];
if (!file) { console.error('usage: node dist/probe-run-sample.js <js-file>'); process.exit(1); }
const script = fs.readFileSync(file, 'utf8');
// PROBE_WAIT_MS: how long to keep listening for device logs (default 15000)
const waitMs = Number(process.env.PROBE_WAIT_MS || 15000);
// PROBE_EXPECT: optional marker; exit 2 when the marker never appears
const expect = process.env.PROBE_EXPECT || '';
const logLines: string[] = [];

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
  console.log('[connected] ' + file.split(/[\\/]/).pop());
  conn.on('log', (line: string) => { logLines.push(line); console.log('[device]', line.trim()); });
  await conn.run('sample-run.js', 'sample-run.js', script);
  // wait up to waitMs; when expecting a marker, stop as soon as it shows up
  const deadline = Date.now() + waitMs;
  while (Date.now() < deadline) {
    await new Promise((r) => setTimeout(r, 500));
    if (expect && logLines.some((l) => l.indexOf(expect) >= 0)) { break; }
  }
  await new Promise((r) => setTimeout(r, 500));
  conn.close();
  if (expect) {
    const hit = logLines.some((l) => l.indexOf(expect) >= 0);
    if (!hit) {
      console.error('EXPECT-MISSING: ' + expect);
      process.exit(2);
    }
    console.log('EXPECT-OK: ' + expect);
  }
}
main().catch((e) => { console.error('FAILED:', e); process.exit(1); });
