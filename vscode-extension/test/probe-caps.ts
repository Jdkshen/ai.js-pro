/**
 * 能力探针：在手机上跑一段脚本，打印 images/* 相关 API 与截图能力。
 *
 * 用法：node dist/probe-caps.js <host> [port]
 */
import * as net from 'net';

import { DeviceConnection } from '../src/connection';

const host = process.argv[2] ?? '127.0.0.1';
const port = Number(process.argv[3] ?? 19317);

const script = `
var caps = {};
try {
  caps.engine = (typeof $engine !== 'undefined') ? String($engine) : (typeof engines !== 'undefined' ? 'engines' : '?');
} catch (e) { caps.engine = 'err'; }
try { caps.rhino = (typeof java !== 'undefined'); } catch (e) { caps.rhino = 'err'; }
try { caps.findColor = typeof images.findColor; } catch (e) { caps.findColor = 'err:' + e; }
try { caps.findMultiColors = typeof images.findMultiColors; } catch (e) { caps.findMultiColors = 'err'; }
try { caps.findImage = typeof images.findImage; } catch (e) { caps.findImage = 'err'; }
try { caps.fromBase64 = typeof images.fromBase64; } catch (e) { caps.fromBase64 = 'err'; }
try { caps.reqAccess = typeof images.requestAccessibilityScreenCapture; } catch (e) { caps.reqAccess = 'err'; }
try { caps.reqScreenCapture = typeof images.requestScreenCapture; } catch (e) { caps.reqScreenCapture = 'err'; }
try { caps.captureScreen = typeof captureScreen; } catch (e) { caps.captureScreen = 'err'; }
try { caps.pixel = typeof images.pixel; } catch (e) { caps.pixel = 'err'; }
try { caps.clip = typeof images.clip; } catch (e) { caps.clip = 'err'; }
console.log('CAPS ' + JSON.stringify(caps));
try {
  if (typeof images.requestScreenCapture === 'function') {
    var granted = images.requestScreenCapture({ mode: 'accessibility' });
    console.log('CAPS_ACCESS ' + granted);
  }
} catch (e) { console.log('CAPS_ACCESS_ERR ' + e); }
try {
  var img = captureScreen();
  console.log('CAPS_CAPTURE ' + img.getWidth() + 'x' + img.getHeight());
  var probeColor = images.pixel(img, Math.floor(img.getWidth() / 2), Math.floor(img.getHeight() / 2));
  console.log('CAPS_PIXEL ' + colors.toString(probeColor));
  var found = images.findColor(img, colors.toString(probeColor), { threshold: 0 });
  console.log('CAPS_FIND ' + (found ? found.x + ',' + found.y : 'null'));
  var tpl = images.clip(img, Math.floor(img.getWidth() / 2) - 20, 400, 40, 40);
  var p2 = images.findImage(img, tpl, { threshold: 0.9 });
  console.log('CAPS_FINDIMAGE ' + (p2 ? p2.x + ',' + p2.y : 'null'));
  var b64 = images.toBase64(tpl, 'png', 100);
  var back = images.fromBase64(b64);
  console.log('CAPS_B64 len=' + b64.length + ' back=' + (back ? back.getWidth() + 'x' + back.getHeight() : 'null'));
} catch (e) { console.log('CAPS_CAPTURE_ERR ' + e); }
`;

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
  await conn.run('caps-probe.js', 'caps-probe.js', script);
  await new Promise((resolve) => setTimeout(resolve, 8000));
  conn.close();
  console.log('== done ==');
}

main().catch((error) => {
  console.error('PROBE FAILED:', error);
  process.exitCode = 1;
});
