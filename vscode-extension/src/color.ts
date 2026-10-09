/**
 * AI.js Pro 图色助手（Screenshot & Pick Color）。
 *
 * 流程：命令 → 手机无障碍全屏截图（`screenshot` 命令）→ PNG 落到扩展存储 → Webview
 * 里放大/取色/框选/多点 → 生成 Auto.js 代码插入编辑器、或把框选区域存成模板图片。
 *
 * 取色/坐标都是**屏幕原始像素**（截图不缩放），生成的 `images.pixel(img, x, y)` 可直接用。
 */
import * as fs from 'fs';
import * as path from 'path';
import * as vscode from 'vscode';

import { DeviceConnection } from './connection';
import { pickScriptEditor } from './editor-tracker';
import { DeviceManager } from './manager';

interface Point {
  x: number;
  y: number;
}

interface MultiPoint extends Point {
  argb: string;
}

export class ColorAssistant {
  private panel: vscode.WebviewPanel | undefined;
  private conn: DeviceConnection | undefined;
  private shotUri: vscode.Uri | undefined;
  private shotWidth = 0;
  private shotHeight = 0;
  private busy = false;
  private logListener: { dispose(): void } | undefined;
  private pendingTest:
    | { resolve: (result: any) => void; timer: NodeJS.Timeout }
    | undefined;
  private readonly log: (message: string) => void;

  constructor(
    private readonly context: vscode.ExtensionContext,
    private readonly manager: DeviceManager,
    log: (message: string) => void,
  ) {
    this.log = log;
  }

  /** 把片段插到目标脚本文件的光标处；实在没有脚本编辑器才新建临时文件。 */
  private async insert(code: string, what: string): Promise<void> {
    const target = pickScriptEditor();
    if (target) {
      await target.edit((builder) => builder.insert(target.selection.active, code));
      vscode.window.setStatusBarMessage(
        `AI.js Pro: ${what}已插入 ${path.basename(target.document.fileName)}`,
        3000,
      );
      return;
    }
    const document = await vscode.workspace.openTextDocument({ content: code, language: 'javascript' });
    await vscode.window.showTextDocument(document, { preview: true });
    vscode.window.setStatusBarMessage(`AI.js Pro: ${what}已插入临时文件（当前没有打开的文件）`, 3000);
  }

  async open(): Promise<void> {
    const conn = await this.manager.pickConnection();
    if (!conn?.attached) {
      vscode.window.showWarningMessage('AI.js Pro: 没有可用的设备连接（先连接手机）');
      return;
    }
    this.conn = conn;
    this.watchDeviceLog(conn);
    const created = !this.panel;
    if (!this.panel) {
      this.panel = vscode.window.createWebviewPanel(
        'aijsproColor',
        'AI.js Pro 图色助手',
        vscode.ViewColumn.Beside,
        {
          enableScripts: true,
          retainContextWhenHidden: true,
          localResourceRoots: [this.storageRoot()],
        },
      );
      this.panel.webview.html = buildHtml();
      this.panel.onDidDispose(() => {
        this.panel = undefined;
      });
      this.panel.webview.onDidReceiveMessage((message) => void this.onMessage(message));
    }
    this.panel.reveal(vscode.ViewColumn.Beside, true);
    if (!created) {
      // 新面板由 webview 的 ready 消息触发首次截图，避免重复截两张。
      await this.refresh();
    }
  }

  private storageRoot(): vscode.Uri {
    return this.context.storageUri ?? this.context.globalStorageUri;
  }

  /** 监听设备日志，从中抽取测试脚本回传的 `AIJS_TEST {...}` 结果行。 */
  private watchDeviceLog(conn: DeviceConnection): void {
    this.logListener?.dispose();
    const onLog = (line: string) => {
      const match = /AIJS_TEST (\{.*\})\s*$/.exec(line.trim());
      if (!match) {
        return;
      }
      const pending = this.takePendingTest();
      if (!pending) {
        return;
      }
      let parsed: any = { error: '结果解析失败' };
      try {
        parsed = JSON.parse(match[1]);
      } catch {
        // 保留默认错误
      }
      clearTimeout(pending.timer);
      pending.resolve(parsed);
    };
    conn.on('log', onLog);
    this.logListener = { dispose: () => conn.removeListener('log', onLog) };
  }

  /** 取走等待中的测试回调（同步清空，避免重复解析多条日志）。 */
  private takePendingTest():
    | { resolve: (result: any) => void; timer: NodeJS.Timeout }
    | undefined {
    const pending = this.pendingTest;
    this.pendingTest = undefined;
    return pending;
  }

  /** 「测试」：把一段临时脚本发到手机上跑（无障碍重新截图 → 找色/找图），回传命中坐标。 */
  async runDeviceTest(message: any): Promise<void> {
    const conn = this.conn;
    if (!conn) {
      vscode.window.showWarningMessage('AI.js Pro: 设备未连接');
      return;
    }
    if (this.pendingTest) {
      vscode.window.showWarningMessage('AI.js Pro: 上一个测试还没结束');
      return;
    }
    const script = buildTestScript(message);
    if (!script) {
      this.post({ type: 'testResult', result: { kind: message?.kind, error: '参数不足' } });
      return;
    }
    this.post({ type: 'testBusy', on: true });
    try {
      const resultPromise = new Promise<any>((resolve) => {
        const timer = setTimeout(
          () => resolve({ kind: message?.kind, error: '等待手机结果超时（脚本没跑起来？）' }),
          12000,
        );
        this.pendingTest = { resolve, timer };
      });
      await conn.run('aijs-color-test.js', 'aijs-color-test.js', script);
      const result = await resultPromise;
      this.log(`[图色] test ${JSON.stringify(result)}`);
      this.post({ type: 'testResult', result });
    } catch (error) {
      this.post({
        type: 'testResult',
        result: { kind: message?.kind, error: (error as Error).message },
      });
    } finally {
      const stale = this.takePendingTest();
      if (stale) {
        clearTimeout(stale.timer);
      }
      this.post({ type: 'testBusy', on: false });
    }
  }

  private async refresh(): Promise<void> {
    if (!this.conn || this.busy) {
      return;
    }
    this.busy = true;
    this.post({ type: 'busy', on: true });
    try {
      const shot = await this.conn.captureScreenshot();
      const dir = vscode.Uri.joinPath(this.storageRoot(), 'screenshots');
      await fs.promises.mkdir(dir.fsPath, { recursive: true });
      const file = vscode.Uri.joinPath(dir, `shot-${Date.now()}.png`);
      await fs.promises.writeFile(file.fsPath, stripColorProfile(shot.png));
      this.shotUri = file;
      this.shotWidth = shot.width;
      this.shotHeight = shot.height;
      const uri = this.panel?.webview.asWebviewUri(file).toString() ?? '';
      this.post({
        type: 'shot',
        uri,
        width: shot.width,
        height: shot.height,
        device: this.conn.deviceName,
      });
      this.log(
        `[图色] ${this.conn.deviceName} screenshot ${shot.width}x${shot.height}, ` +
          `${(shot.png.length / 1024).toFixed(0)} KB`,
      );
    } catch (error) {
      const message = (error as Error).message;
      this.post({ type: 'error', message });
      this.log(`[图色] 截图失败：${message}`);
      vscode.window.showErrorMessage(`AI.js Pro 截图失败：${message}`);
    } finally {
      this.busy = false;
      this.post({ type: 'busy', on: false });
    }
  }

  private post(message: unknown): void {
    void this.panel?.webview.postMessage(message);
  }

  private async onMessage(message: any): Promise<void> {
    switch (message?.type) {
      case 'ready':
        await this.refresh();
        return;
      case 'refresh':
        await this.refresh();
        return;
      case 'copy':
        await vscode.env.clipboard.writeText(String(message.text ?? ''));
        vscode.window.setStatusBarMessage('AI.js Pro: 已复制到剪贴板', 3000);
        return;
      case 'insertCode':
        await this.insert(String(message.code ?? ''), '代码');
        return;
      case 'insertPick':
        await this.insert(
          pickCode(
            Number(message.x ?? 0),
            Number(message.y ?? 0),
            String(message.argb ?? ''),
          ),
          '取色代码',
        );
        return;
      case 'insertFindColor':
        await this.insert(
          findColorCode(
            Number(message.x ?? 0),
            Number(message.y ?? 0),
            String(message.argb ?? ''),
            this.shotWidth,
            this.shotHeight,
          ),
          '找色代码',
        );
        return;
      case 'insertFindMulti':
        await this.insert(
          findMultiCode(
            {
              x: Number(message.first?.x ?? 0),
              y: Number(message.first?.y ?? 0),
              argb: String(message.first?.argb ?? ''),
            },
            Array.isArray(message.others)
              ? message.others.map((item: any) => ({
                  dx: Number(item?.dx ?? 0),
                  dy: Number(item?.dy ?? 0),
                  argb: String(item?.argb ?? ''),
                }))
              : [],
            this.shotWidth,
            this.shotHeight,
          ),
          '多点找色代码',
        );
        return;
      case 'saveTemplate':
        await this.saveTemplate(String(message.dataUrl ?? ''), String(message.name ?? ''));
        return;
      case 'saveShot':
        await this.saveShotCopy();
        return;
      case 'test':
        await this.runDeviceTest(message);
        return;
      default:
        return;
    }
  }

  /** 把框选区域（webview 里裁好的 PNG）存到工作区 templates/，并插入找图代码。 */
  private async saveTemplate(dataUrl: string, name: string): Promise<void> {
    const base64 = dataUrl.replace(/^data:image\/png;base64,/, '');
    if (!base64) {
      vscode.window.showWarningMessage('AI.js Pro: 模板图片数据为空');
      return;
    }
    const folder = vscode.workspace.workspaceFolders?.[0];
    const safe = sanitizeName(name) || `tpl-${Date.now()}`;
    const relative = `templates/${safe}.png`;
    let target: vscode.Uri;
    if (folder) {
      target = vscode.Uri.joinPath(folder.uri, 'templates', `${safe}.png`);
    } else {
      const picked = await vscode.window.showSaveDialog({
        defaultUri: vscode.Uri.file(`${safe}.png`),
        filters: { PNG: ['png'] },
      });
      if (!picked) {
        return;
      }
      target = picked;
    }
    await fs.promises.mkdir(path.dirname(target.fsPath), { recursive: true });
    await fs.promises.writeFile(target.fsPath, Buffer.from(base64, 'base64'));
    await this.insert(findImageCode(relative), '找图代码');
    this.post({ type: 'saved', relPath: relative });
    vscode.window.setStatusBarMessage(`AI.js Pro: 模板已保存到 ${target.fsPath}`, 5000);
    this.log(`[图色] 模板已保存：${target.fsPath}`);
  }

  /** 把本次截图另存到工作区（默认 shot-<时间>.png）。 */
  private async saveShotCopy(): Promise<void> {
    if (!this.shotUri) {
      vscode.window.showWarningMessage('AI.js Pro: 还没有截图');
      return;
    }
    const folder = vscode.workspace.workspaceFolders?.[0];
    const name = `shot-${new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19)}.png`;
    let target: vscode.Uri;
    if (folder) {
      target = vscode.Uri.joinPath(folder.uri, name);
    } else {
      const picked = await vscode.window.showSaveDialog({
        defaultUri: vscode.Uri.file(name),
        filters: { PNG: ['png'] },
      });
      if (!picked) {
        return;
      }
      target = picked;
    }
    await fs.promises.copyFile(this.shotUri.fsPath, target.fsPath);
    vscode.window.setStatusBarMessage(`AI.js Pro: 截图已保存到 ${target.fsPath}`, 5000);
  }
}

// ---- 代码片段生成（对齐官方示例：sample/QuickJS 新引擎/图色处理/*.js） ----

/**
 * 生成代码公共头：截图授权 + 全屏帧 `screen`。写法与官方图色示例一致：
 * `requestScreenCapture('portrait')` + `captureScreen({ mode: 'full' })`。
 * 授权优先无障碍通道（不弹授权），失败回退系统录屏授权（会弹窗）。
 */
function capturePreamble(): string[] {
  return [
    '// 截图授权：无障碍优先（不弹授权），失败回退系统录屏授权',
    'var __granted = false;',
    "try { __granted = images.requestScreenCapture({ mode: 'accessibility' }); } catch (e) {}",
    "if (!__granted) __granted = requestScreenCapture('portrait');",
    "if (!__granted) throw new Error('截图授权失败');",
    "var screen = captureScreen({ mode: 'full' });",
  ];
}

/** 面板的 #AARRGGBB → 示例风格的 '#RRGGBB'（大写）。 */
function sixHex(argb: string): string {
  const hex = String(argb).replace(/[^0-9a-fA-F]/g, '');
  const body = hex.length >= 6 ? hex.slice(-6) : hex.padStart(6, '0');
  return '#' + body.toUpperCase();
}

/** 围绕这些点、按屏幕尺寸夹紧的 region（x, y, w, h），四周各留 100px。 */
function regionAround(
  points: { x: number; y: number }[],
  width: number,
  height: number,
): string | undefined {
  if (points.length === 0 || !width || !height) {
    return undefined;
  }
  const pad = 100;
  const xs = points.map((point) => point.x);
  const ys = points.map((point) => point.y);
  const left = Math.max(0, Math.min(...xs) - pad);
  const top = Math.max(0, Math.min(...ys) - pad);
  const right = Math.min(width, Math.max(...xs) + pad);
  const bottom = Math.min(height, Math.max(...ys) + pad);
  return `[${left}, ${top}, ${right - left}, ${bottom - top}]`;
}

function pickCode(x: number, y: number, argb: string): string {
  return [
    `// AI.js Pro 图色助手：单点取色 @${x},${y}（面板取样 ${sixHex(argb)}，颜色口径=QuickJS）`,
    ...capturePreamble(),
    `var color = images.pixel(screen, ${x}, ${y});`,
    `console.log(colors.toString(color) + ' @ ${x},${y}');   // #AARRGGBB`,
    'screen.recycle();',
    '',
  ].join('\n');
}

function findColorCode(x: number, y: number, argb: string, width: number, height: number): string {
  const region = regionAround([{ x, y }], width, height);
  const lines = [
    `// AI.js Pro 图色助手：找色（取样点 ${x},${y} → ${sixHex(argb)}，颜色口径=QuickJS）`,
    ...capturePreamble(),
    `var point = images.findColor(screen, '${sixHex(argb)}', {`,
  ];
  if (region) {
    lines.push(`    region: ${region},   // x, y, w, h：取样点附近，按需调整`);
  }
  lines.push(
    '    threshold: 24',
    '});',
    'console.log(JSON.stringify(point));   // 命中 {x, y}，否则 null',
    'screen.recycle();',
    '',
  );
  return lines.join('\n');
}

function findMultiCode(
  first: MultiPoint,
  others: { dx: number; dy: number; argb: string }[],
  width: number,
  height: number,
): string {
  const entries = others.map((item) => `    [${item.dx}, ${item.dy}, '${sixHex(item.argb)}'],`);
  const region = regionAround(
    [
      { x: first.x, y: first.y },
      ...others.map((item) => ({ x: first.x + item.dx, y: first.y + item.dy })),
    ],
    width,
    height,
  );
  const lines = [
    `// AI.js Pro 图色助手：多点找色（基准点 ${first.x},${first.y} → ${sixHex(first.argb)}，共 ${others.length + 1} 个点）`,
    ...capturePreamble(),
    `var point = images.findMultiColors(screen, '${sixHex(first.argb)}', [`,
    ...entries,
    '], {',
  ];
  if (region) {
    lines.push(`    region: ${region},`);
  }
  lines.push(
    '    threshold: 20',
    '});',
    'console.log(JSON.stringify(point));   // 命中 {x, y}，否则 null',
    'screen.recycle();',
    '',
  );
  return lines.join('\n');
}

function findImageCode(relativePath: string): string {
  return [
    '// AI.js Pro 图色助手：找图（模板在工作区 templates/，用「推送并运行整个工程」同步到手机）',
    ...capturePreamble(),
    `var template = images.read('${relativePath}');`,
    'var point = images.findImage(screen, template, { threshold: 0.9 });',
    'console.log(JSON.stringify(point));   // 命中 {x, y}，否则 null',
    'if (template.recycle) template.recycle();',
    'screen.recycle();',
    '',
  ].join('\n');
}

/**
 * 把 PNG 里的 iCCP/sBIT 等色彩配置块去掉。
 *
 * 手机无障碍截图的缓冲区是**广色域**（P3）：PNG 里带 iCCP 时，浏览器的 canvas 会做
 * 色彩管理，面板读到的值 = sRGB 转换值（≈Rhino 脚本的 images.pixel）；去掉配置块后
 * canvas 按 sRGB 直读，值 = 手机原始像素（= QuickJS 脚本的 images.pixel）。
 * 用户用 QuickJS 跑生成的代码，所以面板统一按「原始像素」口径。
 */
function stripColorProfile(png: Buffer): Buffer {
  if (png.length < 8 || png.readUInt32BE(0) !== 0x89504e47) {
    return png;
  }
  const pieces: Buffer[] = [png.subarray(0, 8)];
  let offset = 8;
  while (offset + 12 <= png.length) {
    const length = png.readUInt32BE(offset);
    const type = png.toString('ascii', offset + 4, offset + 8);
    const chunk = png.subarray(offset, offset + 12 + length);
    if (type !== 'iCCP' && type !== 'sBIT' && type !== 'gAMA' && type !== 'sRGB') {
      pieces.push(chunk);
    }
    offset += 12 + length;
    if (type === 'IEND') {
      break;
    }
  }
  return Buffer.concat(pieces);
}

/**
 * 生成手机侧「测试」脚本：无障碍重新截一张屏 → 按参数找色/找图 → 打印 `AIJS_TEST {...}`。
 * 只依赖设备已有的 `run` 命令与 images API，不需要新增协议。
 *
 * 固定用 **QuickJS**（`// @engine quickjs`）：QuickJS 的 images.pixel 返回**原始像素值**，
 * 与面板截图（已去掉色彩配置）同一口径；Rhino 会把广色域转成 sRGB，数值会不同。
 *
 * 生成的脚本统一用 `__aijsResult(对象)` 输出一行 JSON（手写序列化，避免依赖 JSON 全局）。
 */
function buildTestScript(payload: any): string | undefined {
  const kind = String(payload?.kind ?? '');
  const parsedThreshold = Number(payload?.threshold);
  const threshold = Number.isFinite(parsedThreshold) ? parsedThreshold : 4;

  const header = [
    '// @engine quickjs',
    '// AI.js Pro 图色助手测试脚本（临时，跑完即弃）',
    'var __aijsOut = function (text) { console.log("AIJS_TEST " + text); };',
    'var __aijsQ = function (text) {',
    '  return String(text).replace(/["\\n\\r]/g, " ").replace(/\\s+/g, " ");',
    '};',
    'var __aijsResult = function (obj) {',
    '  var parts = [];',
    '  for (var key in obj) {',
    '    var value = obj[key];',
    "    parts.push('\"' + key + '\":' + (typeof value === 'string' ? '\"' + __aijsQ(value) + '\"' : String(value)));",
    '  }',
    '  __aijsOut("{" + parts.join(",") + "}");',
    '};',
    `var __aijsFail = function (error) { __aijsResult({ kind: "${kind}", error: String(error) }); };`,
    'var __img = null;',
    'try {',
    '  var __granted = false;',
    '  try { __granted = images.requestScreenCapture({ mode: "accessibility" }); } catch (e0) {}',
    '  if (!__granted) { try { __granted = images.requestScreenCapture(true); } catch (e1) {} }',
    '  __img = captureScreen();',
    '} catch (e) { __aijsFail(e); }',
    'if (__img) {',
    '  try {',
    '    var __size = "?";',
    '    try {',
    '      if (typeof __img.getWidth === "function") __size = __img.getWidth() + "x" + __img.getHeight();',
    '      else if (typeof __img.width === "number") __size = __img.width + "x" + __img.height;',
    '    } catch (e2) {}',
  ];
  const tail = ['  } catch (e) { __aijsFail(e); }', '}'];
  const body: string[] = [];

  if (kind === 'pick') {
    const x = Math.round(Number(payload.x ?? 0));
    const y = Math.round(Number(payload.y ?? 0));
    const argb = String(payload.argb ?? '');
    body.push(
      `    var __c = images.pixel(__img, ${x}, ${y});`,
      '    var __cs = colors.toString(__c);',
      `    __aijsResult({ kind: 'pick', hit: String(__cs).toLowerCase() === '${argb.toLowerCase()}', x: ${x}, y: ${y}, color: __cs, size: __size });`,
    );
  } else if (kind === 'findColor') {
    const argb = String(payload.argb ?? '');
    body.push(
      `    var __p = images.findColor(__img, '${argb}', { threshold: ${threshold} });`,
      "    if (__p) { __aijsResult({ kind: 'findColor', hit: true, x: __p.x, y: __p.y, size: __size }); } else { __aijsResult({ kind: 'findColor', hit: false, size: __size }); }",
    );
  } else if (kind === 'findMulti') {
    const first = payload.first ?? {};
    const others: any[] = Array.isArray(payload.others) ? payload.others : [];
    const entries = others.map(
      (item: any) =>
        `      [${Math.round(Number(item?.dx ?? 0))}, ${Math.round(Number(item?.dy ?? 0))}, '${String(item?.argb ?? '')}'],`,
    );
    body.push(
      '    var __paths = [',
      ...entries,
      '    ];',
      `    var __p = images.findMultiColors(__img, '${String(first.argb ?? '')}', __paths, { threshold: ${threshold} });`,
      "    if (__p) { __aijsResult({ kind: 'findMulti', hit: true, x: __p.x, y: __p.y, size: __size }); } else { __aijsResult({ kind: 'findMulti', hit: false, size: __size }); }",
    );
  } else if (kind === 'findImage') {
    const base64 = String(payload.dataUrl ?? '').replace(/^data:image\/png;base64,/, '');
    if (!base64 || base64.length > 400000) {
      return undefined;
    }
    const imageThreshold = Number.isFinite(Number(payload.imageThreshold))
      ? Number(payload.imageThreshold)
      : 0.9;
    body.push(
      `    var __tpl = images.fromBase64('${base64}');`,
      `    var __p = images.findImage(__img, __tpl, { threshold: ${imageThreshold} });`,
      "    if (__p) { __aijsResult({ kind: 'findImage', hit: true, x: __p.x, y: __p.y, size: __size }); } else { __aijsResult({ kind: 'findImage', hit: false, size: __size }); }",
    );
  } else {
    return undefined;
  }

  return [...header, ...body, ...tail].join('\n');
}

function sanitizeName(name: string): string {
  return name
    .trim()
    .replace(/\.png$/i, '')
    .replace(/[\\/:*?"<>|\s]+/g, '_')
    .slice(0, 60);
}

// ---- Webview 页面 ----

function buildHtml(): string {
  return `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8" />
<style>
  * { box-sizing: border-box; }
  body { margin: 0; height: 100vh; display: flex; flex-direction: column;
         font-family: var(--vscode-font-family); color: var(--vscode-foreground);
         background: var(--vscode-editor-background); }
  #toolbar { display: flex; flex-wrap: wrap; gap: 6px; padding: 6px 8px; align-items: center;
             border-bottom: 1px solid var(--vscode-panel-border); }
  button { background: var(--vscode-button-secondaryBackground, #3a3d41); color: var(--vscode-button-secondaryForeground, #ccc);
           border: 1px solid transparent; padding: 4px 10px; border-radius: 4px; cursor: pointer; font-size: 12px; }
  button:hover { background: var(--vscode-button-secondaryHoverBackground, #45494e); }
  button.active { background: var(--vscode-button-background, #0e639c); color: var(--vscode-button-foreground, #fff); }
  button:disabled { opacity: 0.45; cursor: default; }
  .sep { width: 1px; height: 20px; background: var(--vscode-panel-border); margin: 0 4px; }
  #stage { position: relative; flex: 1; overflow: hidden; background: #101010; cursor: crosshair; }
  #base { position: absolute; left: 0; top: 0; transform-origin: 0 0; image-rendering: pixelated; }
  #overlay { position: absolute; left: 0; top: 0; pointer-events: none; }
  #status { padding: 5px 10px; font-size: 12px; border-top: 1px solid var(--vscode-panel-border);
            display: flex; gap: 16px; align-items: center; flex-wrap: wrap; }
  .chip { display: inline-flex; align-items: center; gap: 5px; }
  .swatch { display: inline-block; width: 14px; height: 14px; border: 1px solid #888; border-radius: 2px;
            vertical-align: middle; }
  code { font-family: var(--vscode-editor-font-family); }
  #toast { position: absolute; left: 50%; bottom: 18px; transform: translateX(-50%);
           background: rgba(0,0,0,0.78); padding: 6px 14px; border-radius: 14px; font-size: 12px;
           opacity: 0; transition: opacity .18s; pointer-events: none; }
  #hint { position: absolute; inset: 0; display: flex; align-items: center; justify-content: center;
          color: #888; font-size: 14px; }
</style>
</head>
<body>
<div id="toolbar">
  <button id="modePick" class="active" title="单击取色并固定该点，拖拽平移">取色</button>
  <button id="modeRegion" title="拖拽框选区域">框选</button>
  <button id="modeMulti" title="依次点击多个点，生成多点找色">多点</button>
  <span class="sep"></span>
  <button id="zoomOut">−</button>
  <button id="zoomFit">适应</button>
  <button id="zoom100">100%</button>
  <button id="zoomIn">＋</button>
  <span class="sep"></span>
  <button id="copyColor" disabled>复制颜色</button>
  <button id="pickCode" disabled>插入取色代码</button>
  <button id="findColor" disabled>插入找色代码</button>
  <button id="pickClear" disabled>清除固定点</button>
  <button id="regionCoords" disabled>复制区域坐标</button>
  <button id="template" disabled>保存模板并插入找图代码</button>
  <button id="multiClear" disabled>清空点</button>
  <button id="multiUndo" disabled>撤销一点</button>
  <button id="multiCode" disabled>插入多点找色代码</button>
  <span class="sep"></span>
  <button id="testPick" disabled>测试取色</button>
  <button id="testFindColor" disabled>测试找色</button>
  <button id="testFindMulti" disabled>测试多点</button>
  <button id="testFindImage" disabled>测试找图</button>
  <span class="sep"></span>
  <button id="refresh">重新截图</button>
  <button id="saveShot" disabled>截图另存到工作区</button>
</div>
<div id="stage">
  <canvas id="base"></canvas>
  <canvas id="overlay"></canvas>
  <div id="hint">正在获取手机截图…（手机需开启 AI.js Pro 无障碍服务）</div>
  <div id="toast"></div>
</div>
<div id="status">
  <span class="chip">坐标 <code id="pos">-</code></span>
  <span class="chip">固定 <code id="pinpos">-</code></span>
  <span class="chip"><span class="swatch" id="swatch"></span><code id="argb">-</code></span>
  <span class="chip"><code id="rgbhex">-</code></span>
  <span class="chip">int <code id="intval">-</code></span>
  <span class="chip">rgb <code id="rgbval">-</code></span>
  <span class="chip">悬停 <span class="swatch" id="hoverswatch"></span><code id="hovercolor">-</code></span>
  <span class="chip">测试 <code id="test">-</code></span>
  <span class="chip">多点 <code id="pointcount">0</code></span>
  <span class="chip">像素口径：原始值（与 QuickJS 脚本一致）</span>
  <span class="chip" id="modehint"></span>
</div>
<script>
  var vscode = acquireVsCodeApi();
  var $ = function (id) { return document.getElementById(id); };
  var base = $('base'), overlay = $('overlay'), stage = $('stage');
  var bctx = base.getContext('2d', { willReadFrequently: true });
  var octx = overlay.getContext('2d');
  var W = 0, H = 0;                 // 截图原始像素尺寸
  var zoom = 1, tx = 0, ty = 0;     // 视图变换： translate(tx,ty) scale(zoom)
  var mode = 'pick';
  var cursor = null;                // {x,y,vx,vy} 悬停位置（图像坐标 + 视图坐标）
  var color = null;                 // 当前主显示颜色 {r,g,b,a}（固定点优先，否则悬停）
  var pen = null;                   // 固定点（单击取色后锁住）{x,y,r,g,b,a}
  var region = null;                // 框选 {x,y,w,h} 图像坐标
  var points = [];                  // 多点 [{x,y,r,g,b,a}]
  var testHit = null;               // 测试命中的坐标 {x,y}
  var pressed = false, panned = false, dragStart = null, panStart = null;
  var lastTemplate = null;

  function toast(text) {
    var el = $('toast');
    el.textContent = text;
    el.style.opacity = '1';
    clearTimeout(el._t);
    el._t = setTimeout(function () { el.style.opacity = '0'; }, 1400);
  }
  function hex2(n) { return ('0' + n.toString(16)).slice(-2); }
  function argbOf(c) { return '#' + hex2(c.a) + hex2(c.r) + hex2(c.g) + hex2(c.b); }
  function rgbHexOf(c) { return '#' + hex2(c.r) + hex2(c.g) + hex2(c.b); }
  function intOf(c) { return ((c.a << 24) | (c.r << 16) | (c.g << 8) | c.b) >>> 0; }

  function applyTransform() {
    base.style.transform = 'translate(' + tx + 'px,' + ty + 'px) scale(' + zoom + ')';
    draw();
  }
  function fit() {
    var rect = stage.getBoundingClientRect();
    if (!W || !H) return;
    zoom = Math.min(rect.width / W, rect.height / H);
    tx = (rect.width - W * zoom) / 2;
    ty = (rect.height - H * zoom) / 2;
    applyTransform();
  }
  function resizeOverlay() {
    var rect = stage.getBoundingClientRect();
    overlay.width = rect.width;
    overlay.height = rect.height;
    draw();
  }
  function toImage(clientX, clientY) {
    var rect = stage.getBoundingClientRect();
    var vx = clientX - rect.left, vy = clientY - rect.top;
    return { x: Math.floor((vx - tx) / zoom), y: Math.floor((vy - ty) / zoom), vx: vx, vy: vy };
  }
  function toView(x, y) { return { x: x * zoom + tx, y: y * zoom + ty }; }

  function pixel(x, y) {
    if (x < 0 || y < 0 || x >= W || y >= H) return null;
    var d = bctx.getImageData(x, y, 1, 1).data;
    return { r: d[0], g: d[1], b: d[2], a: d[3] };
  }

  function setColor(c, x, y) {
    color = c;
    if (!c) { $('swatch').style.background = 'transparent'; return; }
    $('swatch').style.background = rgbHexOf(c);
    $('argb').textContent = argbOf(c);
    $('rgbhex').textContent = rgbHexOf(c);
    $('intval').textContent = intOf(c) + ' (0x' + intOf(c).toString(16) + ')';
    $('rgbval').textContent = c.r + ',' + c.g + ',' + c.b;
    $('copyColor').disabled = false;
    $('pickCode').disabled = false;
    $('findColor').disabled = false;
    $('testPick').disabled = false;
    $('testFindColor').disabled = false;
  }

  /** 悬停色单独显示，不影响固定点。 */
  function setHover(c) {
    if (!c) {
      $('hoverswatch').style.background = 'transparent';
      $('hovercolor').textContent = '-';
      return;
    }
    $('hoverswatch').style.background = rgbHexOf(c);
    $('hovercolor').textContent = argbOf(c);
  }

  /** 单击取色：把颜色固定下来（移动鼠标不再改它）。 */
  function pinPoint(x, y, c) {
    pen = { x: x, y: y, r: c.r, g: c.g, b: c.b, a: c.a };
    setColor(pen, x, y);
    $('pinpos').textContent = x + ', ' + y;
    $('pickClear').disabled = false;
  }

  function clearPin() {
    pen = null;
    $('pinpos').textContent = '-';
    $('pickClear').disabled = true;
    var live = cursor ? pixel(cursor.x, cursor.y) : null;
    setColor(live, cursor ? cursor.x : 0, cursor ? cursor.y : 0);
    draw();
  }

  /** 操作（复制/插代码/测试）优先用固定点，否则用悬停点。 */
  function actionPoint() {
    if (pen) return { x: pen.x, y: pen.y };
    if (cursor && cursor.x >= 0 && cursor.y >= 0 && cursor.x < W && cursor.y < H) {
      return { x: cursor.x, y: cursor.y };
    }
    return null;
  }

  function actionColor() {
    return pen ? pen : color;
  }

  function setMode(next) {
    mode = next;
    $('modePick').className = next === 'pick' ? 'active' : '';
    $('modeRegion').className = next === 'region' ? 'active' : '';
    $('modeMulti').className = next === 'multi' ? 'active' : '';
    $('modehint').textContent = next === 'pick' ? '单击取色（点击后固定）/ 拖拽平移'
      : next === 'region' ? '拖拽框选区域' : '依次点击取色点（首点为基准；拖拽平移）';
    stage.style.cursor = next === 'region' ? 'crosshair' : (next === 'multi' ? 'copy' : 'crosshair');
    draw();
  }

  function draw() {
    if (!octx) return;
    octx.clearRect(0, 0, overlay.width, overlay.height);
    // 框选
    if (region) {
      var p1 = toView(region.x, region.y);
      var p2 = toView(region.x + region.w, region.y + region.h);
      octx.fillStyle = 'rgba(80,160,255,0.20)';
      octx.fillRect(p1.x, p1.y, p2.x - p1.x, p2.y - p1.y);
      octx.strokeStyle = 'rgba(120,190,255,0.95)';
      octx.lineWidth = 1;
      octx.strokeRect(p1.x + 0.5, p1.y + 0.5, p2.x - p1.x - 1, p2.y - p1.y - 1);
    }
    // 多点标记
    for (var i = 0; i < points.length; i++) {
      var v = toView(points[i].x, points[i].y);
      octx.strokeStyle = i === 0 ? '#ff5252' : '#4caf50';
      octx.lineWidth = 2;
      octx.beginPath();
      octx.arc(v.x + zoom / 2, v.y + zoom / 2, Math.max(5, zoom * 1.2), 0, Math.PI * 2);
      octx.stroke();
      octx.fillStyle = i === 0 ? '#ff5252' : '#4caf50';
      octx.font = '11px monospace';
      octx.fillText(String(i), v.x + 7, v.y - 4);
    }
    // 固定点标记（单击取色锁住的点）
    if (pen) {
      var pv = toView(pen.x, pen.y);
      octx.strokeStyle = '#ffd54f';
      octx.lineWidth = 2;
      octx.strokeRect(pv.x - 1, pv.y - 1, zoom + 2, zoom + 2);
      octx.beginPath();
      octx.arc(pv.x + zoom / 2, pv.y + zoom / 2, Math.max(7, zoom * 1.4), 0, Math.PI * 2);
      octx.stroke();
      octx.fillStyle = '#ffd54f';
      octx.font = '12px monospace';
      octx.fillText(argbOf(pen) + '  ' + pen.x + ',' + pen.y, pv.x + 12, pv.y - 8);
    }
    // 测试命中标记
    if (testHit) {
      var th = toView(testHit.x, testHit.y);
      octx.strokeStyle = '#00e5ff';
      octx.lineWidth = 2;
      octx.setLineDash([4, 3]);
      octx.beginPath();
      octx.arc(th.x + zoom / 2, th.y + zoom / 2, Math.max(9, zoom * 2), 0, Math.PI * 2);
      octx.stroke();
      octx.setLineDash([]);
      octx.fillStyle = '#00e5ff';
      octx.font = '12px monospace';
      octx.fillText('测试 ' + testHit.x + ',' + testHit.y, th.x + 12, th.y - 6);
    }
    // 十字线 + 放大镜
    if (cursor && cursor.x >= 0 && cursor.y >= 0 && cursor.x < W && cursor.y < H) {
      octx.strokeStyle = 'rgba(255,80,80,0.85)';
      octx.lineWidth = 1;
      octx.beginPath();
      octx.moveTo(cursor.vx, 0); octx.lineTo(cursor.vx, overlay.height);
      octx.moveTo(0, cursor.vy); octx.lineTo(overlay.width, cursor.vy);
      octx.stroke();
      drawMagnifier();
    }
  }

  function drawMagnifier() {
    var N = 11, Z = 12, pad = 14, size = N * Z;
    var mx = cursor.vx + pad, my = cursor.vy + pad;
    if (mx + size > overlay.width) mx = cursor.vx - size - pad;
    if (my + size > overlay.height) my = cursor.vy - size - pad;
    if (mx < 0 || my < 0) return;
    octx.save();
    octx.imageSmoothingEnabled = false;
    var sx = cursor.x - Math.floor(N / 2), sy = cursor.y - Math.floor(N / 2);
    octx.fillStyle = 'rgba(0,0,0,0.75)';
    octx.fillRect(mx - 1, my - 1, size + 2, size + 2);
    octx.drawImage(base, sx, sy, N, N, mx, my, size, size);
    octx.strokeStyle = 'rgba(255,255,255,0.12)';
    octx.lineWidth = 1;
    for (var i = 1; i < N; i++) {
      octx.beginPath(); octx.moveTo(mx + i * Z, my); octx.lineTo(mx + i * Z, my + size); octx.stroke();
      octx.beginPath(); octx.moveTo(mx, my + i * Z); octx.lineTo(mx + size, my + i * Z); octx.stroke();
    }
    var c = Math.floor(N / 2);
    octx.strokeStyle = '#ff5252';
    octx.lineWidth = 2;
    octx.strokeRect(mx + c * Z + 1, my + c * Z + 1, Z - 2, Z - 2);
    octx.restore();
  }

  // ---- 交互 ----
  stage.addEventListener('mousemove', function (e) {
    var p = toImage(e.clientX, e.clientY);
    cursor = p;
    $('pos').textContent = (p.x >= 0 && p.y >= 0 && p.x < W && p.y < H) ? p.x + ', ' + p.y : '-';
    if (pressed) {
      if (mode === 'region' && dragStart) {
        var x1 = Math.min(dragStart.x, p.x), y1 = Math.min(dragStart.y, p.y);
        var x2 = Math.max(dragStart.x, p.x), y2 = Math.max(dragStart.y, p.y);
        region = { x: Math.max(0, x1), y: Math.max(0, y1),
                   w: Math.min(W, x2) - Math.max(0, x1), h: Math.min(H, y2) - Math.max(0, y1) };
        updateRegionButtons();
      } else if (panStart) {
        var moved = Math.abs(e.clientX - panStart.cx) + Math.abs(e.clientY - panStart.cy);
        // 先过了阈值才算平移（否则轻微拖动会把单击吃掉）
        if (!panned && moved > 8) panned = true;
        if (panned) {
          tx = panStart.tx + (e.clientX - panStart.cx);
          ty = panStart.ty + (e.clientY - panStart.cy);
          applyTransform();
        }
        return;
      }
    }
    if (mode !== 'region') {
      var live = pixel(p.x, p.y);
      setHover(live);
      if (!pen) setColor(live, p.x, p.y);
    }
    draw();
  });
  stage.addEventListener('mousedown', function (e) {
    if (e.button !== 0) return;
    pressed = true;
    panned = false;
    var p = toImage(e.clientX, e.clientY);
    if (mode === 'region') {
      dragStart = { x: Math.max(0, Math.min(W - 1, p.x)), y: Math.max(0, Math.min(H - 1, p.y)) };
      region = { x: dragStart.x, y: dragStart.y, w: 0, h: 0 };
    } else {
      panStart = { cx: e.clientX, cy: e.clientY, tx: tx, ty: ty };
    }
    e.preventDefault();
  });
  stage.addEventListener('mouseup', function (e) {
    if (!pressed) return;
    pressed = false;
    var p = toImage(e.clientX, e.clientY);
    // 按住后的总位移才是判据：微小抖动仍算单击（多点模式下点才不会丢）
    var moved = panStart ? Math.abs(e.clientX - panStart.cx) + Math.abs(e.clientY - panStart.cy) : 0;
    var wasPan = panned || moved > 8;
    panStart = null;
    if (mode === 'region') {
      dragStart = null;
      if (region && (region.w < 2 || region.h < 2)) { region = null; }
      updateRegionButtons();
    } else if (!wasPan) {
      var c = pixel(p.x, p.y);
      if (c) {
        pinPoint(p.x, p.y, c);
        if (mode === 'multi') {
          points.push({ x: p.x, y: p.y, r: c.r, g: c.g, b: c.b, a: c.a });
          updateMultiButtons();
          toast('已添加点 ' + points.length + '（首点为基准）');
        } else {
          toast('已固定 ' + argbOf(c) + ' @ ' + p.x + ',' + p.y + '（移动鼠标不再改变）');
        }
      }
    }
    draw();
  });
  stage.addEventListener('mouseleave', function () { cursor = null; setHover(null); draw(); });
  stage.addEventListener('wheel', function (e) {
    var rect = stage.getBoundingClientRect();
    var cx = e.clientX - rect.left, cy = e.clientY - rect.top;
    var k = e.deltaY < 0 ? 1.25 : 0.8;
    var nz = Math.min(8, Math.max(0.1, zoom * k));
    tx = cx - (cx - tx) * (nz / zoom);
    ty = cy - (cy - ty) * (nz / zoom);
    zoom = nz;
    applyTransform();
    e.preventDefault();
  }, { passive: false });
  window.addEventListener('resize', resizeOverlay);

  // ---- 按钮 ----
  $('modePick').onclick = function () { setMode('pick'); };
  $('modeRegion').onclick = function () { setMode('region'); };
  $('modeMulti').onclick = function () { setMode('multi'); };
  $('zoomIn').onclick = function () { zoom = Math.min(8, zoom * 1.25); applyTransform(); };
  $('zoomOut').onclick = function () { zoom = Math.max(0.1, zoom / 1.25); applyTransform(); };
  $('zoom100').onclick = function () { zoom = 1; tx = 0; ty = 0; applyTransform(); };
  $('zoomFit').onclick = fit;
  $('refresh').onclick = function () { vscode.postMessage({ type: 'refresh' }); };
  $('saveShot').onclick = function () { vscode.postMessage({ type: 'saveShot' }); };
  $('copyColor').onclick = function () {
    var c = actionColor();
    if (c) vscode.postMessage({ type: 'copy', text: argbOf(c) });
  };
  $('pickClear').onclick = clearPin;
  $('pickCode').onclick = function () {
    var p = actionPoint();
    var c = actionColor();
    if (!p || !c) return;
    vscode.postMessage({ type: 'insertPick', x: p.x, y: p.y, argb: argbOf(c) });
  };
  $('findColor').onclick = function () {
    var p = actionPoint();
    var c = actionColor();
    if (!p || !c) return;
    vscode.postMessage({ type: 'insertFindColor', x: p.x, y: p.y, argb: argbOf(c) });
  };
  $('regionCoords').onclick = function () {
    if (!region) return;
    var text = region.x + ', ' + region.y + ', ' + (region.x + region.w) + ', ' + (region.y + region.h);
    vscode.postMessage({ type: 'copy', text: text });
  };
  $('template').onclick = function () {
    if (!region || region.w < 2 || region.h < 2) return;
    var off = document.createElement('canvas');
    off.width = region.w; off.height = region.h;
    off.getContext('2d').drawImage(base, region.x, region.y, region.w, region.h, 0, 0, region.w, region.h);
    vscode.postMessage({
      type: 'saveTemplate',
      dataUrl: off.toDataURL('image/png'),
      name: 'tpl-' + region.x + '_' + region.y + '_' + region.w + 'x' + region.h,
    });
  };
  $('multiClear').onclick = function () {
    points = [];
    updateMultiButtons();
    draw();
    toast('已清空点');
  };
  $('multiUndo').onclick = function () {
    var removed = points.pop();
    if (removed && pen && pen.x === removed.x && pen.y === removed.y) {
      pen = null;
      $('pinpos').textContent = '-';
      $('pickClear').disabled = true;
    }
    updateMultiButtons();
    draw();
    toast('已撤销一点，剩 ' + points.length + ' 个');
  };
  $('multiCode').onclick = function () {
    if (points.length < 2) { toast('至少需要 2 个点'); return; }
    var first = points[0];
    var others = [];
    for (var i = 1; i < points.length; i++) {
      others.push({ dx: points[i].x - first.x, dy: points[i].y - first.y,
                    argb: argbOf(points[i]) });
    }
    vscode.postMessage({ type: 'insertFindMulti',
      first: { x: first.x, y: first.y, argb: argbOf(first) }, others: others });
  };

  // ---- 测试（在手机上重新截图并真跑一次，回传命中坐标）----
  $('testPick').onclick = function () {
    var p = actionPoint();
    var c = actionColor();
    if (!p || !c) return;
    setTestText('测试中…');
    vscode.postMessage({ type: 'test', kind: 'pick', x: p.x, y: p.y, argb: argbOf(c) });
  };
  $('testFindColor').onclick = function () {
    var c = actionColor();
    if (!c) return;
    setTestText('测试中…');
    vscode.postMessage({ type: 'test', kind: 'findColor', argb: argbOf(c), threshold: 4 });
  };
  $('testFindMulti').onclick = function () {
    if (points.length < 2) { toast('至少需要 2 个点'); return; }
    var first = points[0];
    var others = [];
    for (var i = 1; i < points.length; i++) {
      others.push({ dx: points[i].x - first.x, dy: points[i].y - first.y,
                    argb: argbOf(points[i]) });
    }
    setTestText('测试中…');
    vscode.postMessage({ type: 'test', kind: 'findMulti',
      first: { x: first.x, y: first.y, argb: argbOf(first) }, others: others, threshold: 4 });
  };
  $('testFindImage').onclick = function () {
    if (!region || region.w < 2 || region.h < 2) return;
    var off = document.createElement('canvas');
    off.width = region.w; off.height = region.h;
    off.getContext('2d').drawImage(base, region.x, region.y, region.w, region.h, 0, 0, region.w, region.h);
    setTestText('测试中…');
    vscode.postMessage({ type: 'test', kind: 'findImage',
      dataUrl: off.toDataURL('image/png'), imageThreshold: 0.9 });
  };

  function setTestText(text) { $('test').textContent = text; }

  function updateRegionButtons() {
    var ok = region && region.w >= 2 && region.h >= 2;
    $('regionCoords').disabled = !ok;
    $('template').disabled = !ok;
    $('testFindImage').disabled = !ok;
  }
  function updateMultiButtons() {
    $('multiClear').disabled = points.length === 0;
    $('multiUndo').disabled = points.length === 0;
    $('multiCode').disabled = points.length < 2;
    $('testFindMulti').disabled = points.length < 2;
    $('pointcount').textContent = points.length;
  }

  // ---- 与扩展通信 ----
  window.addEventListener('message', function (event) {
    var msg = event.data || {};
    if (msg.type === 'busy') {
      $('refresh').disabled = !!msg.on;
      if (msg.on) { $('hint').textContent = '正在获取手机截图…'; $('hint').style.display = 'flex'; }
      return;
    }
    if (msg.type === 'error') {
      $('hint').textContent = '截图失败：' + msg.message;
      $('hint').style.display = 'flex';
      toast('截图失败：' + msg.message);
      return;
    }
    if (msg.type === 'saved') {
      toast('模板已保存：' + msg.relPath);
      lastTemplate = msg.relPath;
      return;
    }
    if (msg.type === 'testBusy') {
      $('testPick').disabled = !!msg.on;
      $('testFindColor').disabled = !!msg.on;
      $('testFindMulti').disabled = !!msg.on || points.length < 2;
      $('testFindImage').disabled = !!msg.on || !(region && region.w >= 2 && region.h >= 2);
      return;
    }
    if (msg.type === 'testResult') {
      var r = msg.result || {};
      if (r.error) {
        setTestText((r.kind || '?') + ' 错误：' + r.error);
        testHit = null;
        toast('测试失败：' + r.error);
      } else if (r.hit) {
        setTestText(
          (r.kind || '?') +
            ' 命中 (' + r.x + ', ' + r.y + ')' +
            (r.size && r.size !== '?' ? '  帧 ' + r.size : ''),
        );
        testHit = { x: r.x, y: r.y };
        toast('命中 (' + r.x + ', ' + r.y + ')');
      } else {
        setTestText((r.kind || '?') + ' 未命中（阈值 4）');
        testHit = null;
        toast('未命中');
      }
      draw();
      return;
    }
    if (msg.type === 'shot') {
      var image = new Image();
      image.onload = function () {
        W = image.naturalWidth; H = image.naturalHeight;
        base.width = W; base.height = H;
        bctx.drawImage(image, 0, 0);
        $('hint').style.display = 'none';
        $('saveShot').disabled = false;
        // 新截图 → 旧的固定点/测试标记全部作废
        pen = null;
        testHit = null;
        $('pinpos').textContent = '-';
        $('pickClear').disabled = true;
        setTestText('-');
        setHover(null);
        fit();
        resizeOverlay();
        toast('截图 ' + W + '×' + H + '（' + msg.device + '）');
      };
      image.src = msg.uri;
      return;
    }
  });

  setMode('pick');
  resizeOverlay();
  vscode.postMessage({ type: 'ready' });
</script>
</body>
</html>
`;
}
