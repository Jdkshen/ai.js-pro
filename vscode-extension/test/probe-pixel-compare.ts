/**
 * 像素对比探针：验证「图色助手面板里的颜色」与「脚本 captureScreen() 读到的颜色」是否一致。
 *
 *   - 面板截图走 DevPluginScreenshotService（无障碍截图 → PNG → 扩展本地解码）
 *   - 脚本截图走 images.requestScreenCapture({mode:'accessibility'}) + captureScreen()
 *   - 同一时刻各取一次，比较同坐标像素；脚本内再连拍两次看是否稳定
 *
 * 用法：node dist/probe-pixel-compare.js <host> [port] [x] [y]
 */
import * as net from 'net';
import * as zlib from 'zlib';

import { DeviceConnection } from '../src/connection';

const host = process.argv[2] ?? '127.0.0.1';
const port = Number(process.argv[3] ?? 19317);
const px = Number(process.argv[4] ?? 109);
const py = Number(process.argv[5] ?? 1009);

// ---- 最小 PNG 解码（8bit 非隔行 RGB/RGBA）----
function decodePng(buffer: Buffer): { width: number; height: number; channels: number; data: Buffer } {
  if (buffer.readUInt32BE(0) !== 0x89504e47) {
    throw new Error('不是 PNG');
  }
  let offset = 8;
  let width = 0;
  let height = 0;
  let colorType = 6;
  const idat: Buffer[] = [];
  while (offset < buffer.length) {
    const length = buffer.readUInt32BE(offset);
    const type = buffer.toString('ascii', offset + 4, offset + 8);
    const data = buffer.subarray(offset + 8, offset + 8 + length);
    if (type === 'IHDR') {
      width = data.readUInt32BE(0);
      height = data.readUInt32BE(4);
      const bitDepth = data.readUInt8(8);
      colorType = data.readUInt8(9);
      const interlace = data.readUInt8(12);
      if (bitDepth !== 8 || interlace !== 0 || (colorType !== 6 && colorType !== 2)) {
        throw new Error(`不支持的 PNG 格式 depth=${bitDepth} color=${colorType} interlace=${interlace}`);
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
  return { width, height, channels, data: out };
}

function pixelHex(png: { width: number; channels: number; data: Buffer }, x: number, y: number): string {
  const index = (y * png.width + x) * png.channels;
  const r = png.data[index];
  const g = png.data[index + 1];
  const b = png.data[index + 2];
  const a = png.channels === 4 ? png.data[index + 3] : 255;
  const hex = (n: number) => ('0' + n.toString(16)).slice(-2);
  return `#${hex(a)}${hex(r)}${hex(g)}${hex(b)}`;
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
  console.log(`== 目标像素 (${px}, ${py}) ==`);

  const points = [
    [109, 1009],
    [540, 1200],
    [900, 300],
    [30, 60],
  ];
  const shotA = await conn.captureScreenshot();
  const pngA = decodePng(shotA.png);
  console.log(
    `[扩展通道 A] ${shotA.width}x${shotA.height} ` +
      points.map(([x, y]) => `(${x},${y})=${pixelHex(pngA, x, y)}`).join(' '),
  );
  const script = [
    'var __g = false;',
    'try { __g = images.requestScreenCapture({ mode: "accessibility" }); } catch (e) {}',
    'function __sample() {',
    '  var img = captureScreen();',
    '  var parts = [img.getWidth() + "x" + img.getHeight()];',
    ...points.map(
      ([x, y]) => `  parts.push(colors.toString(images.pixel(img, ${x}, ${y})));`,
    ),
    '  return parts.join(" ");',
    '}',
    'console.log("PXCMP1 " + __sample());',
    'console.log("PXCMP2 " + __sample());',
  ].join('\n');
  await conn.run('px-compare.js', 'px-compare.js', script);
  await new Promise((resolve) => setTimeout(resolve, 7000));

  const shotB = await conn.captureScreenshot();
  const pngB = decodePng(shotB.png);
  console.log(
    `[扩展通道 B] ${shotB.width}x${shotB.height} ` +
      points.map(([x, y]) => `(${x},${y})=${pixelHex(pngB, x, y)}`).join(' '),
  );

  const shotC = await conn.captureScreenshot();
  const pngC = decodePng(shotC.png);
  console.log(
    `[扩展通道 C] ${shotC.width}x${shotC.height} ` +
      points.map(([x, y]) => `(${x},${y})=${pixelHex(pngC, x, y)}`).join(' '),
  );

  conn.close();
  console.log('== done ==');
}

main().catch((error) => {
  console.error('PROBE FAILED:', error);
  process.exit(1);
});
