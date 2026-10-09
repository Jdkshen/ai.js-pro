/**
 * 对比两个引擎里 findMultiColors / findColor 返回对象的「数据形状」。
 */
import * as net from 'net';
import { DeviceConnection } from '../src/connection';

const host = '127.0.0.1';
const port = 19317;
const body = [
  "var __g = false;",
  "try { __g = images.requestScreenCapture({ mode: 'accessibility' }); } catch (e) {}",
  "var screen = captureScreen({ mode: 'full' });",
  "var c1 = colors.toString(images.pixel(screen, 540, 1200));",
  "var c2 = colors.toString(images.pixel(screen, 541, 1200));",
  "var m = images.findMultiColors(screen, c1, [[1, 0, c2]], { region: [500, 1150, 200, 100], threshold: 24 });",
  "console.log('MULTI JSON ' + JSON.stringify(m));",
  "console.log('MULTI KEYS ' + (m ? Object.keys(m).join(',') : 'null') + ' TYPEOF ' + (typeof m));",
  "console.log('MULTI XY ' + (m ? (m.x + '/' + m.y) : 'null'));",
  "var f = images.findColor(screen, c1, { region: [500, 1150, 200, 100], threshold: 24 });",
  "console.log('FINDCOLOR JSON ' + JSON.stringify(f));",
  "console.log('FINDCOLOR KEYS ' + (f ? Object.keys(f).join(',') : 'null'));",
  "screen.recycle();",
  "console.log('SHAPE_OK');",
].join('\n');

async function run(name: string, script: string): Promise<void> {
  const socket = net.createConnection({ host, port });
  const conn = new DeviceConnection(socket, `${host}:${port}`, () => undefined);
  conn.on('log', (line: string) => console.log(`[${name}]`, line.trim()));
  await new Promise<void>((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('hello 超时')), 10000);
    conn.once('connected', () => { clearTimeout(timer); resolve(); });
    conn.once('closed', () => reject(new Error('连接关闭')));
  });
  await conn.run(`${name}.js`, `${name}.js`, script);
  await new Promise((resolve) => setTimeout(resolve, 5000));
  conn.close();
}

async function main(): Promise<void> {
  await run('shape-qjs', `// @engine quickjs\n${body}`);
  await run('shape-rhino', body);
  console.log('== done ==');
}

main().catch((error) => { console.error('FAILED:', error); process.exit(1); });
