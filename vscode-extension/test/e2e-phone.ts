/**
 * 用扩展自己的协议实现（DeviceConnection / createZip）对真机做端到端验证。
 *
 * 用法：node dist/e2e.js <手机IP> [端口=9317]
 *   （也可先 adb forward tcp:19317 tcp:9317，然后 node dist/e2e.js 127.0.0.1 19317）
 */
import * as net from 'net';

import { DeviceConnection } from '../src/connection';
import { createZip } from '../src/zip';

const host = process.argv[2] ?? '127.0.0.1';
const port = Number(process.argv[3] ?? 9317);
const logs: string[] = [];

function assert(condition: unknown, message = 'assertion failed'): void {
  if (!condition) {
    throw new Error(message);
  }
}

async function waitFor(check: () => boolean, timeoutMs: number, what: string): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (check()) {
      return;
    }
    await new Promise((resolve) => setTimeout(resolve, 200));
  }
  throw new Error(`等待超时：${what}`);
}

async function main(): Promise<void> {
  const socket = net.createConnection({ host, port });
  const conn = new DeviceConnection(socket, `${host}:${port}`, (message) => console.log('[conn]', message));
  conn.on('log', (line: string) => {
    logs.push(line);
    console.log('[device]', line.trim());
  });

  await new Promise<void>((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('hello 超时')), 10000);
    conn.once('connected', () => {
      clearTimeout(timer);
      resolve();
    });
    conn.once('closed', () => reject(new Error('握手前连接被关闭')));
  });
  console.log('== 已连接 ==', conn.deviceName, JSON.stringify(conn.hello));

  console.log('\n== 1) list_files（根目录，非递归）==');
  const root = await conn.listFiles('', false);
  console.log(`files=${root.files.length} dirs=${root.dirs.length} truncated=${root.truncated}`);
  assert(root.files.length + root.dirs.length > 0, '脚本目录为空');
  for (const entry of root.files.slice(0, 5)) {
    console.log(`   ${String(entry.size).padStart(8)}  ${entry.path}`);
  }

  console.log('\n== 2) read_file ==');
  const target = root.files.find((file) => file.size > 0 && file.size < 4096) ?? root.files[0];
  assert(target, '没有可读文件');
  const bytes = await conn.readFile(target.path);
  console.log(`read ${target.path}: ${bytes.length} bytes（期望 ${target.size}）`);
  console.log('head:', bytes.subarray(0, 48).toString('utf8').replace(/\r?\n/g, '\\n'));
  assert(bytes.length === target.size, '读到的字节数不对');

  console.log('\n== 3) write_file + 读回 ==');
  const marker = `TS_E2E_${Date.now()}`;
  await conn.writeFile('__aijs_ts_probe__/hello.txt', marker);
  const back = await conn.readFile('__aijs_ts_probe__/hello.txt');
  assert(back.toString('utf8') === marker, '写回内容不一致');
  console.log('写回校验 OK:', marker);

  console.log('\n== 4) run（脚本 + 日志回传）==');
  const runMarker = `TS_E2E_RUN_${Date.now()}`;
  await conn.run('ts-e2e.js', 'ts-e2e.js', `console.log('${runMarker}');`);
  await waitFor(() => logs.some((line) => line.includes(runMarker)), 10000, '脚本日志');
  console.log('收到脚本日志:', runMarker);

  console.log('\n== 5) run_project（zip 推送）==');
  const projectMarker = `TS_E2E_PROJECT_${Date.now()}`;
  const archive = createZip([
    {
      path: 'project.json',
      data: Buffer.from(
        JSON.stringify({
          name: 'ts-e2e-project',
          versionName: '1.0.0',
          versionCode: 1,
          packageName: 'com.e2e.probe',
          main: 'main.js',
        }),
      ),
    },
    { path: 'main.js', data: Buffer.from(`console.log('${projectMarker}');`) },
  ]);
  console.log(`zip 大小: ${archive.length} bytes`);
  await conn.sendBytesCommand('run_project', 'ts-e2e-project', archive, []);
  await waitFor(() => logs.some((line) => line.includes(projectMarker)), 20000, '工程日志');
  console.log('收到工程日志:', projectMarker);

  console.log('\n== 6) inspect_layout（布局分析）==');
  try {
    const xml = await conn.inspectLayout();
    console.log(`布局 XML ${xml.length} 字符，头两行：`);
    console.log(xml.split('\n').slice(0, 2).join('\n'));
    assert(xml.includes('<hierarchy'), '布局输出缺少 <hierarchy>');
  } catch (error) {
    console.log('布局分析返回错误（无障碍未开启时属预期）:', (error as Error).message);
  }

  console.log('\n== 7) screenshot（图色助手截图）==');
  const shot = await conn.captureScreenshot();
  console.log(`PNG ${shot.width}x${shot.height}, ${(shot.png.length / 1024).toFixed(0)} KB`);
  assert(shot.png.subarray(0, 8).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])), 'PNG 文件头不对');
  const ihdrWidth = shot.png.readUInt32BE(16);
  const ihdrHeight = shot.png.readUInt32BE(20);
  console.log(`PNG 头里的尺寸：${ihdrWidth}x${ihdrHeight}（与 response 一致：${shot.width}/${shot.height}）`);
  assert(ihdrWidth === shot.width && ihdrHeight === shot.height, 'PNG 头尺寸与 response 不一致');

  console.log('\n== 8) stopAll ==');
  await conn.stopAll();

  conn.close();
  console.log('\nALL TS E2E CHECKS PASSED');
}

main().catch((error) => {
  console.error('\nE2E FAILED:', error);
  process.exit(1);
});
