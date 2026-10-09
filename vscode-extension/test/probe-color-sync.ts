/**
 * 色彩一致性探针：找出「面板截图（DevPluginScreenshotService）」与
 * 「脚本 captureScreen()+images.pixel」在同一时刻为什么可能不一致。
 *
 * 做法：
 *   1. 脚本侧：截图 → 打印 images.pixel(109,1009) → 把**同一帧**保存成 PNG 落盘
 *   2. Node 侧：读回该 PNG，用最小解码器取同坐标像素，并列出 PNG 块（看有没有色彩配置）
 *   3. 同时把扩展通道（DevPluginScreenshotService）的 PNG 也解出来对比
 *
 * 用法：node dist/probe-color-sync.js <host> [port]
 */
import * as net from 'net';
import * as zlib from 'zlib';

import { DeviceConnection } from '../src/connection';

const host = process.argv[2] ?? '127.0.0.1';
const port = Number(process.argv[3] ?? 19317);
const X = 109;
const Y = 1009;

interface Png {
  width: number;
  height: number;
  channels: number;
  data: Buffer;
  chunks: string[];
}

function decodePng(buffer: Buffer): Png {
  if (buffer.readUInt32BE(0) !== 0x89504e47) {
    throw new Error('不是 PNG');
  }
  let offset = 8;
  let width = 0;
  let height = 0;
  let colorType = 6;
  const idat: Buffer[] = [];
  const chunks: string[] = [];
  while (offset < buffer.length) {
    const length = buffer.readUInt32BE(offset);
    const type = buffer.toString('ascii', offset + 4, offset + 8);
    const data = buffer.subarray(offset + 8, offset + 8 + length);
    chunks.push(type + (type === 'IHDR' || type === 'iCCP' || type === 'sRGB' ? `(${length})` : ''));
    if (type === 'IHDR') {
      width = data.readUInt32BE(0);
      height = data.readUInt32BE(4);
      const bitDepth = data.readUInt8(8);
      colorType = data.readUInt8(9);
      const interlace = data.readUInt8(12);
      if (bitDepth !== 8 || interlace !== 0 || (colorType !== 6 && colorType !== 2)) {
        throw new Error(`不支持的 PNG depth=${bitDepth} color=${colorType} interlace=${interlace}`);
      }
    } else if (type === 'IDAT') {
      idat.push(Buffer.from(data));
    } else if (type === 'IEND') {
      break;
    }
    offset += 12 + length;
  }
  const raw = zlib.inflateSync(Buffer.concat(idat));
  const channels = colorType === 6 ? 4 : 3;
  const stride = width * channels;
  const out = Buffer.alloc(height * stride);
  let pos = 0;
  let prev = Buffer.alloc(stride);
  for (let y = 0; y < height; y++) {
    const filter = raw[pos++];
    const line = raw.subarray(pos, pos + stride);
    pos += stride;
    const current = Buffer.alloc(stride);
    for (let i = 0; i < stride; i++) {
      const left = i >= channels ? current[i - channels] : 0;
      const up = prev[i];
      const upLeft = i >= channels ? prev[i - channels] : 0;
      let value = line[i];
      if (filter === 1) {
        value += left;
      } else if (filter === 2) {
        value += up;
      } else if (filter === 3) {
        value += Math.floor((left + up) / 2);
      } else if (filter === 4) {
        const p = left + up - upLeft;
        const pa = Math.abs(p - left);
        const pb = Math.abs(p - up);
        const pc = Math.abs(p - upLeft);
        value += pa <= pb && pa <= pc ? left : pb <= pc ? up : upLeft;
      }
      current[i] = value & 0xff;
    }
    current.copy(out, y * stride);
    prev = current;
  }
  return { width, height, channels, data: out, chunks };
}

function pixelHex(png: Png, x: number, y: number): string {
  const index = (y * png.width + x) * png.channels;
  const hex = (n: number) => ('0' + n.toString(16)).slice(-2);
  const a = png.channels === 4 ? png.data[index + 3] : 255;
  return `#${hex(a)}${hex(png.data[index])}${hex(png.data[index + 1])}${hex(png.data[index + 2])}`;
}

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

  const sample = [
    'var __g = false;',
    'try { __g = images.requestScreenCapture({ mode: "accessibility" }); } catch (e) {}',
    'var img = captureScreen();',
    `console.log("FRAME_PX " + colors.toString(images.pixel(img, ${X}, ${Y})));`,
  ].join('\n');

  // 同一块屏幕，分别用 QuickJS / Rhino 取同一个像素
  await conn.run('sample-quickjs.js', 'sample-quickjs.js', `// @engine quickjs\n${sample}`);
  await new Promise((resolve) => setTimeout(resolve, 3000));
  await conn.run('sample-rhino.js', 'sample-rhino.js', sample);
  await new Promise((resolve) => setTimeout(resolve, 3000));

  // 脚本自己保存的那一帧（Rhino 通道，PNG 落盘）
  const saveScript = [
    sample,
    'var img2 = captureScreen();',
    'images.save(img2, "__px-frame.png");',
    'console.log("FRAME_SAVED");',
  ].join('\n');
  await conn.run('color-sync.js', 'color-sync.js', saveScript);
  await new Promise((resolve) => setTimeout(resolve, 6000));

  // 脚本自己保存的那一帧
  try {
    const frame = await conn.readFile('__px-frame.png');
    const png = decodePng(frame);
    console.log(
      `[脚本帧 PNG] ${png.width}x${png.height} (${X},${Y})=${pixelHex(png, X, Y)} chunks=${png.chunks.join(',')}`,
    );
  } catch (error) {
    console.log('[脚本帧 PNG] 读取失败：', (error as Error).message);
  }

  // 扩展通道（面板用的那份）
  const shot = await conn.captureScreenshot();
  const png = decodePng(shot.png);
  console.log(
    `[扩展通道 PNG] ${png.width}x${png.height} (${X},${Y})=${pixelHex(png, X, Y)} chunks=${png.chunks.join(',')}`,
  );

  conn.close();
  console.log('== done ==');
}

main().catch((error) => {
  console.error('PROBE FAILED:', error);
  process.exit(1);
});
