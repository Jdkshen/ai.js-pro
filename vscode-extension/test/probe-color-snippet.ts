/**
 * 验证「图色助手」新生成的代码片段（对齐官方示例写法）在真机上能跑通：
 *   1. 授权（无障碍优先）+ captureScreen({mode:'full'})
 *   2. images.pixel 取色（QuickJS 原始像素口径）
 *   3. images.findColor（6 位色 + region + threshold 24）
 *   4. images.findMultiColors（采样两点 + region + threshold 20）
 *   5. frame.recycle()
 *
 * 用法：node dist/probe-color-snippet.js <host> [port] [x] [y]
 */
import * as net from 'net';

import { DeviceConnection } from '../src/connection';

const host = process.argv[2] ?? '127.0.0.1';
const port = Number(process.argv[3] ?? 19317);
const X = Number(process.argv[4] ?? 540);
const Y = Number(process.argv[5] ?? 1200);

const script = [
  '// @engine quickjs',
  '// 图色助手片段验证（与生成代码同写法）',
  'var __granted = false;',
  "try { __granted = images.requestScreenCapture({ mode: 'accessibility' }); } catch (e) {}",
  "if (!__granted) __granted = requestScreenCapture('portrait');",
  "if (!__granted) throw new Error('截图授权失败');",
  "var screen = captureScreen({ mode: 'full' });",
  `var color = images.pixel(screen, ${X}, ${Y});`,
  "console.log('PICK ' + colors.toString(color));",
  "var six = '#' + colors.toString(color).slice(-6).toUpperCase();",
  'var point = images.findColor(screen, six, {',
  '    region: [440, 1100, 200, 200],',
  '    threshold: 24',
  '});',
  "console.log('FIND six=' + six + ' -> ' + JSON.stringify(point));",
  `var c2 = colors.toString(images.pixel(screen, ${X + 1}, ${Y + 1}));`,
  'var multi = images.findMultiColors(screen, colors.toString(color), [[1, 1, c2]], {',
  '    region: [440, 1100, 200, 200],',
  '    threshold: 20',
  '});',
  "console.log('MULTI -> ' + JSON.stringify(multi));",
  "console.log('SIZE ' + screen.width + 'x' + screen.height + ' native ' + screen.pixelWidth + 'x' + screen.pixelHeight);",
  'screen.recycle();',
  "console.log('STYLE_OK');",
].join('\n');

async function main(): Promise<void> {
  const socket = net.createConnection({ host, port });
  const conn = new DeviceConnection(socket, `${host}:${port}`, (m) => console.log('[conn]', m));
  conn.on('log', (line: string) => console.log('[device]', line.trim()));
  await new Promise<void>((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('hello 超时')), 10000);
    conn.once('connected', () => {
      clearTimeout(timer);
      resolve();
    });
    conn.once('closed', () => reject(new Error('握手前连接被关闭')));
  });
  console.log('== 已连接 ==', conn.deviceName, conn.hello?.app_version);
  await conn.run('snippet-style.js', 'snippet-style.js', script);
  await new Promise((resolve) => setTimeout(resolve, 8000));
  conn.close();
  console.log('== done ==');
}

main().catch((error) => {
  console.error('PROBE FAILED:', error);
  process.exit(1);
});
