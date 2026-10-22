import * as fs from 'fs';
import { createHash } from 'crypto';
import * as path from 'path';
import { TextDecoder } from 'util';
import * as vscode from 'vscode';

import { DeviceConnection } from './connection';
import { ColorAssistant } from './color';
import { createDebugAdapterFactory, createDebugConfigurationProvider } from './debug';
import { isScriptEditor, pickScriptEditor, trackScriptEditors } from './editor-tracker';
import { deviceFileKind, isRunnableDeviceFile } from './filePolicy';
import { DeviceManager, savedDevices } from './manager';
import { UsbDevices } from './adb';
import { selectDevice } from './devicePicker';
import { DeviceFileProvider, ExplorerNode, FileNode } from './tree';
import { createZip, ZipInput } from './zip';
import { createExcludeMatcher, ExcludeMatcher } from './exclude';
import { findProjectRoot } from './projectRoot';

const PUSH_MANIFEST_PREFIX = 'aijspro.pushManifest';

let channel: vscode.OutputChannel;
let manager: DeviceManager;
let usbDevices: UsbDevices;
let treeProvider: DeviceFileProvider;
let statusBar: vscode.StatusBarItem;

export function activate(context: vscode.ExtensionContext): void {
  channel = vscode.window.createOutputChannel('AI.js Pro', { log: true });
  channel.appendLine('AI.js Pro 扩展已启动');
  e2eLog('[ext] activated');

  manager = new DeviceManager((message) => channel.appendLine(message));
  usbDevices = new UsbDevices(() => vscode.workspace.getConfiguration('aijspro').get<string>('adbPath', ''));
  treeProvider = new DeviceFileProvider(manager);
  trackScriptEditors(context);
  const colorAssistant = new ColorAssistant(context, manager, (message) => {
    channel.appendLine(message);
    e2eLog(message);
  });
  statusBar = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 90);
  statusBar.command = 'aijspro.selectDevice';
  statusBar.show();
  let connectionLogShown = false;

  const updateStatus = () => {
    const conn = manager.activeConnection;
    if (conn?.attached) {
      statusBar.text = `$(radio-tower) AI.js Pro ${conn.deviceName}`;
      statusBar.tooltip = `${conn.deviceName} · ${conn.deviceAddress || conn.label}\n点击切换设备`;
      statusBar.backgroundColor = undefined;
    } else {
      statusBar.text = '$(debug-disconnect) AI.js Pro: 未连接';
      statusBar.tooltip = '点击选择设备（支持 USB 和局域网）；手机侧需开启“电脑连接 → 服务器模式”';
      statusBar.backgroundColor = undefined;
    }
  };
  manager.on('changed', () => {
    updateStatus();
    treeProvider.refresh();
    if (!connectionLogShown && manager.activeConnection?.attached &&
        vscode.workspace.getConfiguration('aijspro').get<boolean>('showLogsOnConnect', true)) {
      connectionLogShown = true;
      channel.show(true);
    }
  });
  manager.on('deviceLog', (conn: DeviceConnection, line: string) => {
    // Android log records may contain a record separator; it is not display text.
    const clean = line.replace(/\x1e/g, '').replace(/\r\n/g, '\n');
    for (const entry of clean.split('\n')) {
      if (entry.length > 0) channel.appendLine(`[${conn.deviceName}] ${entry}`);
    }
  });
  updateStatus();

  context.subscriptions.push(
    channel,
    statusBar,
    vscode.window.registerTreeDataProvider('aijsproFiles', treeProvider),
    vscode.commands.registerCommand('aijspro.help', () => helpCommand(context)),
    vscode.commands.registerCommand('aijspro.connect', () => connectCommand()),
    vscode.commands.registerCommand('aijspro.selectDevice', () => selectDeviceCommand()),
    vscode.commands.registerCommand('aijspro.scanDevices', () => scanDevicesCommand()),
    vscode.commands.registerCommand('aijspro.forgetDevices', () => forgetDevicesCommand()),
    vscode.commands.registerCommand('aijspro.run', () => runActiveFileCommand(context)),
    vscode.commands.registerCommand('aijspro.runFile', () => runFilePickerCommand(context)),
    vscode.commands.registerCommand('aijspro.runProject', () => runProjectCommand(context)),
    vscode.commands.registerCommand('aijspro.rerun', () => rerunCommand(context)),
    vscode.commands.registerCommand('aijspro.stop', () => stopCurrentCommand()),
    vscode.commands.registerCommand('aijspro.stopAll', () => stopAllCommand()),
    vscode.commands.registerCommand('aijspro.inspectLayout', () => inspectLayoutCommand()),
    vscode.commands.registerCommand('aijspro.pickColor', () => colorAssistant.open()),
    vscode.commands.registerCommand('aijspro.browseFiles', () =>
      vscode.commands.executeCommand('aijsproFiles.focus')),
    vscode.commands.registerCommand('aijspro.saveFile', () => pushFileCommand()),
    vscode.commands.registerCommand('aijspro.pushFile', () => pushFileCommand()),
    vscode.commands.registerCommand('aijspro.saveProject', () => saveProjectCommand()),
    vscode.commands.registerCommand('aijspro.newRhinoProject', () => createDeviceProjectCommand('rhino')),
    vscode.commands.registerCommand('aijspro.newQuickJsProject', () => createDeviceProjectCommand('quickjs')),
    vscode.commands.registerCommand('aijspro.newRhinoProjectInWorkspace', () =>
      createWorkspaceProjectCommand('rhino')),
    vscode.commands.registerCommand('aijspro.newQuickJsProjectInWorkspace', () =>
      createWorkspaceProjectCommand('quickjs')),
    vscode.commands.registerCommand('aijspro.addDebugConfig', () => addDebugConfigCommand()),
    vscode.commands.registerCommand('aijspro.debug', () => debugFileCommand()),
    vscode.debug.registerDebugAdapterDescriptorFactory(
      'aijspro',
      createDebugAdapterFactory(manager, channel),
    ),
    vscode.debug.registerDebugConfigurationProvider('aijspro', createDebugConfigurationProvider()),
    vscode.commands.registerCommand('aijspro.disconnect', () => manager.disconnectAll()),
    vscode.commands.registerCommand('aijspro.showLogs', () => channel.show(true)),
    vscode.commands.registerCommand('aijspro.refreshFiles', () => treeProvider.refresh()),
    vscode.commands.registerCommand('aijspro.togglePushOnSave', () => togglePushOnSave()),
    vscode.commands.registerCommand('aijspro.menu', () => menuCommand()),
    vscode.commands.registerCommand('aijspro.openDeviceFile', (node: FileNode) => openDeviceFile(node, context)),
    vscode.commands.registerCommand('aijspro.runDeviceFile', (node: FileNode) => runDeviceFile(node)),
    vscode.commands.registerCommand('aijspro.downloadFile', (node: FileNode) => downloadFile(node)),
    vscode.workspace.onDidSaveTextDocument((document) => pushOnSave(document)),
    { dispose: () => manager.dispose() },
  );

  manager.startListening();

  // 开发/自动化用：AIJSPRO_E2E_CONNECT=host[:port] 时激活后直接连该设备
  // （用于隔离实例端到端测试；正常使用不会设置这个变量）。
  const directHost = process.env.AIJSPRO_E2E_CONNECT;
  if (directHost) {
    channel.appendLine(`[e2e] 环境变量直连 ${directHost}`);
    void manager.connectTo(directHost).catch((error) => channel.appendLine(`连接失败：${(error as Error).message}`));
  }

  // 开发/自动化用：AIJSPRO_E2E_DEBUG=<行号> 时，连上设备后自动对工作区里第一个 .js
  // 文件下断点并启动调试（用于隔离实例端到端测试；正常使用不会设置这个变量）。
  const e2eDebug = process.env.AIJSPRO_E2E_DEBUG;
  if (e2eDebug) {
    void autoDebugForE2E(Number(e2eDebug));
  }

  // 开发/自动化用：AIJSPRO_E2E_COLOR=1 时，连上设备后自动打开图色助手（隔离实例测试）。
  if (process.env.AIJSPRO_E2E_COLOR) {
    void autoColorForE2E(colorAssistant);
  }
}

export async function deactivate(): Promise<void> {
  manager?.dispose();
  await usbDevices?.dispose();
}

// ---- 命令实现 ----

async function connectCommand(): Promise<void> {
  const remembered = savedDevices();
  const picked = await vscode.window.showQuickPick(
    [...remembered.map((host) => ({ label: host })), { label: '$(edit) 输入地址…' },
      { label: '$(trash) 删除已记住的设备…' }],
    { placeHolder: '选择要连接的设备（手机侧需打开「电脑连接 → 服务器模式」）' },
  );
  if (!picked) {
    return;
  }
  let host = picked.label;
  if (host.startsWith('$(trash)')) {
    await forgetDevicesCommand();
    return;
  }
  if (host.startsWith('$(edit)')) {
    const input = await vscode.window.showInputBox({
      prompt: '输入手机地址（如 192.168.31.59 或 192.168.31.59:9317）',
    });
    if (!input) {
      return;
    }
    host = input.trim();
  }
  try {
    await manager.connectTo(host);
  } catch (error) {
    vscode.window.showErrorMessage(`AI.js Pro: 连接失败：${(error as Error).message}`);
    return;
  }
  vscode.window.setStatusBarMessage(`AI.js Pro: 正在连接 ${host}…`, 4000);
}

async function forgetDevicesCommand(): Promise<void> {
  const remembered = savedDevices();
  if (remembered.length === 0) {
    vscode.window.showInformationMessage('AI.js Pro: 没有已记住的设备');
    return;
  }
  const selected = await vscode.window.showQuickPick(
    remembered.map((host) => ({ label: host })),
    { canPickMany: true, title: '删除已记住的设备',
      placeHolder: '勾选要删除的地址并按回车；会断开连接并停止自动重连，手动连接可恢复' },
  );
  if (!selected?.length) return;
  try {
    await manager.forgetDevices(selected.map((item) => item.label));
    vscode.window.showInformationMessage(`AI.js Pro: 已删除 ${selected.length} 个设备地址，已停止自动重连`);
  } catch (error) {
    vscode.window.showErrorMessage(`AI.js Pro: 当前连接已取消，但删除设备设置失败：${(error as Error).message}`);
  }
}

async function withConnection<T>(action: (conn: DeviceConnection) => Promise<T>): Promise<T | undefined> {
  const conn = await manager.pickConnection();
  if (!conn) {
    await promptConnect();
    return undefined;
  }
  try {
    return await action(conn);
  } catch (error) {
    vscode.window.showErrorMessage(`AI.js Pro: ${(error as Error).message}`);
    return undefined;
  }
}

async function stopCurrentCommand(): Promise<void> {
  await withConnection(async (conn) => {
    const result = await conn.stop();
    const message = result.fallbackStopAll
      ? '无法可靠定位当前脚本，已发送“停止所有脚本”'
      : '已发送停止当前脚本';
    channel.appendLine(`[${conn.deviceName}] ${message}`);
    vscode.window.setStatusBarMessage(`AI.js Pro: ${message}`, 5000);
  });
}

async function stopAllCommand(): Promise<void> {
  await withConnection(async (conn) => {
    const count = await conn.stopAll();
    const message = count > 0 ? `已停止 ${count} 个脚本` : '已发送停止所有脚本';
    channel.appendLine(`[${conn.deviceName}] ${message}`);
    vscode.window.setStatusBarMessage(`AI.js Pro: ${message}`, 5000);
  });
}

/** A file-tree action belongs to the device that supplied that row, not the currently selected device. */
async function withNodeConnection<T>(node: FileNode, action: (conn: DeviceConnection) => Promise<T>): Promise<T | undefined> {
  const conn = node?.conn;
  if (!conn?.attached || conn.isClosed()) {
    vscode.window.showWarningMessage('AI.js Pro: 此文件所属设备已断开，请刷新设备文件树');
    return undefined;
  }
  try {
    return await action(conn);
  } catch (error) {
    vscode.window.showErrorMessage(`AI.js Pro: ${(error as Error).message}`);
    return undefined;
  }
}

async function promptConnect(): Promise<void> {
  const choice = await vscode.window.showInformationMessage(
    'AI.js Pro: 请先选择并连接要使用的设备。',
    '选择设备…',
    '连接设备…',
    '显示日志',
  );
  if (choice === '选择设备…') {
    await vscode.commands.executeCommand('aijspro.selectDevice');
  } else if (choice === '连接设备…') {
    await vscode.commands.executeCommand('aijspro.connect');
  } else if (choice === '显示日志') {
    channel.show(true);
  }
}

async function runActiveFileCommand(context: vscode.ExtensionContext): Promise<void> {
  const editor = pickScriptEditor();
  if (!editor) {
    vscode.window.showWarningMessage('AI.js Pro: 没有可运行的脚本文件（先打开或点一下 .js 文件）');
    return;
  }
  const active = vscode.window.activeTextEditor;
  if (active && !isScriptEditor(active)) {
    // 焦点在输出/调试面板等非脚本编辑器上：用最近编辑的脚本，并说清楚
    vscode.window.setStatusBarMessage('AI.js Pro: 焦点不在编辑器上，已改用最近编辑的脚本', 4000);
  }
  const document = editor.document;
  const name = path.basename(document.isUntitled ? 'untitled.js' : document.fileName);
  const id = relativeToWorkspace(document) ?? name;
  const script = document.getText();
  await withConnection(async (conn) => {
    await conn.run(name, id, script);
    await context.workspaceState.update(rerunKey(conn), { kind: 'script', name, id, script });
    channel.appendLine(`[${conn.deviceName}] ▶ ${name}`);
    vscode.window.setStatusBarMessage(`AI.js Pro: 已发送 ${name}`, 3000);
  });
}

async function runProjectCommand(context: vscode.ExtensionContext): Promise<void> {
  const folder = await pickWorkspaceFolder();
  if (!folder) {
    return;
  }
  const options = vscode.workspace.getConfiguration('aijspro');
  // aijspro.exclude 支持 glob：*.apk / assets/** / `**/node_modules/**`（按相对路径匹配）。
  const exclude = createExcludeMatcher(options.get<string[]>('exclude', []));
  // 工作区可能是「父目录 + 单个工程子目录」；工程根目录必须包含 project.json，否则手机会报
  // 「工程无法运行：Attempt to invoke ... on a null object reference」。
  const projectRoot = findProjectRoot(folder.uri.fsPath);
  if (!projectRoot) {
    vscode.window.showErrorMessage(
      'AI.js Pro: 工作区里没有找到 project.json。请打开 AI.js Pro 工程目录，或只包含一个工程的父目录。',
    );
    return;
  }
  const entries = await collectProjectFiles(projectRoot, exclude);
  if (entries.length === 0) {
    vscode.window.showWarningMessage('AI.js Pro: 工程目录里没有可推送的文件');
    return;
  }
  const zipInputs: ZipInput[] = [];
  for (const entry of entries) {
    zipInputs.push({
      path: entry.relative,
      data: await fs.promises.readFile(entry.absolute),
    });
  }
  const archive = createZip(zipInputs);
  if (archive.length > 200 * 1024 * 1024) {
    vscode.window.showErrorMessage(
      `AI.js Pro: 工程打包后 ${(archive.length / 1024 / 1024).toFixed(0)} MB，超过协议上限（256MB），请用 aijspro.exclude 排除大文件（现在支持 *.apk、assets/**、**/node_modules/** 等 glob）`,
    );
    return;
  }
  const projectName = path.basename(projectRoot);
  if (projectRoot !== folder.uri.fsPath) {
    channel.appendLine(`工作区根目录没有 project.json，使用子工程：${projectName}`);
  }

  await withConnection(async (conn) => {
    const manifestKey = `${PUSH_MANIFEST_PREFIX}.${conn.key}.${projectRoot}`;
    const previous = context.globalState.get<string[]>(manifestKey, []);
    const current = new Set(entries.map((entry) => entry.relative));
    const deletedFiles = previous.filter((relative) => !current.has(relative));
    await conn.sendBytesCommand('run_project', projectName, archive, deletedFiles);
    await context.globalState.update(
      manifestKey,
      entries.map((entry) => entry.relative),
    );
    await context.workspaceState.update(rerunKey(conn), {
      kind: 'project',
      name: projectName,
      folder: projectRoot,
    });
    channel.appendLine(
      `[${conn.deviceName}] ▶ 工程 ${projectName}（${entries.length} 个文件，` +
        `${(archive.length / 1024).toFixed(0)} KB，删除 ${deletedFiles.length}）`,
    );
    vscode.window.setStatusBarMessage(`AI.js Pro: 已推送并运行 ${projectName}`, 4000);
  });
}

async function pushFileCommand(): Promise<void> {
  const editor = vscode.window.activeTextEditor;
  if (!editor) {
    vscode.window.showWarningMessage('AI.js Pro: 没有打开的文件');
    return;
  }
  const devicePath = devicePathForDocument(editor.document);
  if (!devicePath) {
    vscode.window.showWarningMessage('AI.js Pro: 该文件不在工作区内，无法确定推送路径');
    return;
  }
  await withConnection(async (conn) => {
    await conn.writeFile(devicePath, editor.document.getText());
    vscode.window.setStatusBarMessage(`AI.js Pro: 已推送 ${devicePath}`, 4000);
  });
}

async function openDeviceFile(node: FileNode, context: vscode.ExtensionContext): Promise<void> {
  const kind = deviceFileKind(node.path);
  if (kind === 'download') {
    const choice = await vscode.window.showInformationMessage(
      `AI.js Pro: ${path.posix.basename(node.path)} 不能直接预览，可下载到工作区后用合适的应用打开。`,
      '下载到工作区',
    );
    if (choice === '下载到工作区') await downloadFile(node);
    return;
  }
  await withNodeConnection(node, async (conn) => {
    const data = await conn.readFile(node.path);
    if (kind === 'image') {
      const previewDir = vscode.Uri.joinPath(context.globalStorageUri, 'device-previews');
      await vscode.workspace.fs.createDirectory(previewDir);
      const hash = createHash('sha256').update(`${conn.key}|${node.path}`).digest('hex').slice(0, 16);
      const preview = vscode.Uri.joinPath(previewDir, `${hash}-${sanitize(path.posix.basename(node.path))}`);
      await vscode.workspace.fs.writeFile(preview, data);
      await vscode.commands.executeCommand('vscode.open', preview, { preview: true });
      return;
    }
    let content: string;
    try {
      if (data.includes(0)) throw new Error('binary');
      content = new TextDecoder('utf-8', { fatal: true }).decode(data);
    } catch {
      vscode.window.showWarningMessage(`AI.js Pro: ${node.path} 不是有效 UTF-8 文本，请用“下载到工作区”保存原文件`);
      return;
    }
    const document = await vscode.workspace.openTextDocument({
      content,
      language: languageOf(node.path),
    });
    await vscode.window.showTextDocument(document, { preview: true });
  });
}

async function runDeviceFile(node: FileNode): Promise<void> {
  if (!isRunnableDeviceFile(node.path)) {
    vscode.window.showWarningMessage('AI.js Pro: 只有 .js 和 .auto 脚本可以直接运行');
    return;
  }
  await withNodeConnection(node, async (conn) => {
    const data = await conn.readFile(node.path);
    const name = path.basename(node.path);
    const script = new TextDecoder('utf-8', { fatal: true }).decode(data);
    await conn.run(name, node.path, script);
    channel.appendLine(`[${conn.deviceName}] ▶ ${node.path}`);
    vscode.window.setStatusBarMessage(`AI.js Pro: 已在手机上运行 ${name}`, 4000);
  });
}

async function downloadFile(node: FileNode): Promise<void> {
  await withNodeConnection(node, async (conn) => {
    const data = await conn.readFile(node.path);
    const folder = (await pickWorkspaceFolder()) ?? undefined;
    let target: string;
    if (folder) {
      target = path.join(
        folder.uri.fsPath,
        'device-export',
        sanitize(conn.deviceName),
        ...node.path.split('/'),
      );
    } else {
      const picked = await vscode.window.showSaveDialog({
        defaultUri: vscode.Uri.file(data ? path.basename(node.path) : node.path),
      });
      if (!picked) {
        return;
      }
      target = picked.fsPath;
    }
    await fs.promises.mkdir(path.dirname(target), { recursive: true });
    await fs.promises.writeFile(target, data);
    const choice = await vscode.window.showInformationMessage(
      `AI.js Pro: 已下载到 ${target}`,
      '打开文件',
    );
    if (choice === '打开文件') {
      await vscode.commands.executeCommand('vscode.open', vscode.Uri.file(target));
    }
  });
}

async function menuCommand(): Promise<void> {
  const actions: { label: string; command: string }[] = [
    { label: '使用教程 (Help)', command: 'aijspro.help' },
    { label: '连接到新设备 (Connect)', command: 'aijspro.connect' },
    { label: '选择设备 (Select Device)', command: 'aijspro.selectDevice' },
    { label: '重新扫描设备 (Scan Devices)', command: 'aijspro.scanDevices' },
    { label: '删除已记住的设备 (Delete Devices)', command: 'aijspro.forgetDevices' },
    { label: '运行 (Run)', command: 'aijspro.run' },
    { label: '运行项目 (Run Project)', command: 'aijspro.runProject' },
    { label: '运行单文件 (Run File)', command: 'aijspro.runFile' },
    { label: '停止所有脚本 (Stop All)', command: 'aijspro.stopAll' },
    { label: '停止当前脚本 (Stop)', command: 'aijspro.stop' },
    { label: '布局分析 (Inspect Layout)', command: 'aijspro.inspectLayout' },
    { label: '图色助手 (Screenshot & Pick Color)', command: 'aijspro.pickColor' },
    { label: '浏览设备文件 (Browse Device Files)', command: 'aijspro.browseFiles' },
    { label: '保存当前文件 (Save)', command: 'aijspro.saveFile' },
    { label: '新建第一代 API 项目 (Rhino)', command: 'aijspro.newRhinoProject' },
    { label: '新建第二代 API 项目 (QuickJS)', command: 'aijspro.newQuickJsProject' },
    { label: '在工作区建立第一代 API 项目 (Rhino)', command: 'aijspro.newRhinoProjectInWorkspace' },
    { label: '在工作区建立第二代 API 项目 (QuickJS)', command: 'aijspro.newQuickJsProjectInWorkspace' },
    { label: '保存项目 (Save Project)', command: 'aijspro.saveProject' },
    { label: '重新运行 (Rerun)', command: 'aijspro.rerun' },
    { label: '增加调试设备配置 (Add Debug Config)', command: 'aijspro.addDebugConfig' },
    { label: '调试当前文件 (Debug File)', command: 'aijspro.debug' },
    { label: '断开所有连接 (Disconnect)', command: 'aijspro.disconnect' },
    { label: '刷新文件 (Refresh Directory)', command: 'aijspro.refreshFiles' },
    { label: '显示日志 (Show Logs)', command: 'aijspro.showLogs' },
    { label: '开关保存时自动推送', command: 'aijspro.togglePushOnSave' },
  ];
  const picked = await vscode.window.showQuickPick(actions, { placeHolder: 'AI.js Pro 功能菜单' });
  if (picked) await vscode.commands.executeCommand(picked.command);
}

async function togglePushOnSave(): Promise<void> {
  const config = vscode.workspace.getConfiguration('aijspro');
  const next = !config.get<boolean>('pushOnSave', false);
  await config.update('pushOnSave', next, vscode.ConfigurationTarget.Global);
  vscode.window.setStatusBarMessage(
    next ? 'AI.js Pro: 保存时自动推送已开启' : 'AI.js Pro: 保存时自动推送已关闭',
    3000,
  );
}

// ---- 推送与工具 ----

async function pushOnSave(document: vscode.TextDocument): Promise<void> {
  if (!vscode.workspace.getConfiguration('aijspro').get<boolean>('pushOnSave', false)) {
    return;
  }
  const conn = manager.activeConnection;
  if (!conn?.attached || document.uri.scheme !== 'file') {
    return;
  }
  const devicePath = devicePathForDocument(document);
  if (!devicePath) {
    return;
  }
  try {
    await conn.writeFile(devicePath, document.getText());
    channel.appendLine(`[${conn.deviceName}] ⇧ 已推送 ${devicePath}`);
  } catch (error) {
    channel.appendLine(`推送失败：${(error as Error).message}`);
  }
}

function devicePathForDocument(document: vscode.TextDocument): string | undefined {
  const folder = vscode.workspace.getWorkspaceFolder(document.uri);
  if (!folder) {
    return undefined;
  }
  const relative = path.relative(folder.uri.fsPath, document.uri.fsPath);
  if (relative.startsWith('..') || path.isAbsolute(relative)) {
    return undefined;
  }
  return `${path.basename(folder.uri.fsPath)}/${relative.split(path.sep).join('/')}`;
}

function relativeToWorkspace(document: vscode.TextDocument): string | undefined {
  const folder = vscode.workspace.getWorkspaceFolder(document.uri);
  if (!folder) {
    return undefined;
  }
  return path.relative(folder.uri.fsPath, document.uri.fsPath).split(path.sep).join('/');
}

async function pickWorkspaceFolder(): Promise<vscode.WorkspaceFolder | undefined> {
  const folders = vscode.workspace.workspaceFolders ?? [];
  if (folders.length === 0) {
    // 单文件（无工作区）时用文件所在目录当工程。
    const editor = vscode.window.activeTextEditor;
    if (editor && editor.document.uri.scheme === 'file') {
      return {
        uri: vscode.Uri.file(path.dirname(editor.document.fileName)),
        name: path.basename(path.dirname(editor.document.fileName)),
        index: 0,
      };
    }
    vscode.window.showWarningMessage('AI.js Pro: 需要打开一个文件夹工作区');
    return undefined;
  }
  if (folders.length === 1) {
    return folders[0];
  }
  const picked = await vscode.window.showQuickPick(
    folders.map((folder) => ({ label: folder.name, folder })),
    { placeHolder: '选择要推送的工程' },
  );
  return picked?.folder;
}

async function collectProjectFiles(
  root: string,
  exclude: ExcludeMatcher,
): Promise<{ relative: string; absolute: string }[]> {
  const result: { relative: string; absolute: string }[] = [];
  const relativeOf = (absolute: string): string => path.relative(root, absolute).split(path.sep).join('/');
  const walk = async (dir: string): Promise<void> => {
    const children = await fs.promises.readdir(dir, { withFileTypes: true });
    for (const child of children) {
      const absolute = path.join(dir, child.name);
      const relative = relativeOf(absolute);
      if (child.isDirectory()) {
        if (exclude.ignoresDirectory(relative)) {
          continue;
        }
        await walk(absolute);
      } else if (child.isFile()) {
        if (exclude.ignoresFile(relative)) {
          continue;
        }
        result.push({ relative, absolute });
      }
    }
  };
  await walk(root);
  return result;
}

function languageOf(devicePath: string): string {
  const extension = path.extname(devicePath).toLowerCase();
  switch (extension) {
    case '.js':
      return 'javascript';
    case '.json':
      return 'json';
    case '.ts':
      return 'typescript';
    case '.md':
      return 'markdown';
    default:
      return 'plaintext';
  }
}

function sanitize(name: string): string {
  return name.replace(/[\\/:*?"<>|]/g, '_');
}

// ---- AI.js Pro 项目与调试命令 ----

function rerunKey(conn: DeviceConnection): string {
  return `aijspro.lastRun.${conn.key}`;
}

function hostOf(conn: DeviceConnection): string {
  return conn.deviceAddress ?? conn.label;
}

async function helpCommand(context: vscode.ExtensionContext): Promise<void> {
  await vscode.commands.executeCommand(
    'markdown.showPreview',
    vscode.Uri.joinPath(context.extensionUri, 'README.md'),
  );
}

async function selectDeviceCommand(): Promise<void> {
  await selectDevice(manager, usbDevices, connectCommand);
}

async function scanDevicesCommand(): Promise<void> {
  const tried = manager.scanNow();
  channel.appendLine(`扫描设备：已发起 ${tried} 个已记住地址的连接，正在读取 USB 手机列表`);
  await selectDevice(manager, usbDevices, connectCommand);
}

async function runFilePickerCommand(context: vscode.ExtensionContext): Promise<void> {
  const folder = await pickWorkspaceFolder();
  if (!folder) {
    return;
  }
  const exclude = createExcludeMatcher(vscode.workspace.getConfiguration('aijspro').get<string[]>('exclude', []));
  const entries = (await collectProjectFiles(folder.uri.fsPath, exclude)).filter((entry) =>
    entry.relative.endsWith('.js'),
  );
  if (entries.length === 0) {
    vscode.window.showWarningMessage('AI.js Pro: 工作区里没有 .js 文件');
    return;
  }
  const picked = await vscode.window.showQuickPick(
    entries.map((entry) => entry.relative),
    { placeHolder: '选择要运行的脚本' },
  );
  if (!picked) {
    return;
  }
  const absolute = path.join(folder.uri.fsPath, ...picked.split('/'));
  let script: string;
  try {
    script = await fs.promises.readFile(absolute, 'utf8');
  } catch (error) {
    vscode.window.showErrorMessage(`AI.js Pro: 读取 ${picked} 失败：${(error as Error).message}`);
    return;
  }
  const name = path.basename(picked);
  await withConnection(async (conn) => {
    await conn.run(name, picked, script);
    await context.workspaceState.update(rerunKey(conn), { kind: 'script', name, id: picked, script });
    channel.appendLine(`[${conn.deviceName}] ▶ ${picked}`);
    vscode.window.setStatusBarMessage(`AI.js Pro: 已发送 ${name}`, 3000);
  });
}

async function inspectLayoutCommand(): Promise<void> {
  await withConnection(async (conn) => {
    const xml = await conn.inspectLayout();
    const document = await vscode.workspace.openTextDocument({ content: xml, language: 'xml' });
    await vscode.window.showTextDocument(document, { preview: true });
    channel.appendLine(`[${conn.deviceName}] 布局分析：${xml.length} 字符`);
  });
}

async function saveProjectCommand(): Promise<void> {
  const folder = await pickWorkspaceFolder();
  if (!folder) {
    return;
  }
  const exclude = createExcludeMatcher(vscode.workspace.getConfiguration('aijspro').get<string[]>('exclude', []));
  const projectRoot = findProjectRoot(folder.uri.fsPath);
  if (!projectRoot) {
    vscode.window.showErrorMessage(
      'AI.js Pro: 工作区里没有找到 project.json。请打开 AI.js Pro 工程目录，或只包含一个工程的父目录。',
    );
    return;
  }
  const entries = await collectProjectFiles(projectRoot, exclude);
  if (entries.length === 0) {
    vscode.window.showWarningMessage('AI.js Pro: 工程目录里没有可保存的文件');
    return;
  }
  const zipInputs: ZipInput[] = [];
  for (const entry of entries) {
    zipInputs.push({ path: entry.relative, data: await fs.promises.readFile(entry.absolute) });
  }
  const archive = createZip(zipInputs);
  const projectName = path.basename(projectRoot);
  await withConnection(async (conn) => {
    await conn.sendBytesCommand('save_project', projectName, archive, []);
    channel.appendLine(`[${conn.deviceName}] ⇧ 工程 ${projectName} 已保存到手机（${entries.length} 个文件）`);
    vscode.window.setStatusBarMessage(`AI.js Pro: 已保存工程 ${projectName} 到手机`, 4000);
  });
}

async function rerunCommand(context: vscode.ExtensionContext): Promise<void> {
  await withConnection(async (conn) => {
    const last = context.workspaceState.get<any>(rerunKey(conn));
    if (!last) {
      vscode.window.showInformationMessage('AI.js Pro: 还没有可重新运行的任务');
      return;
    }
    if (last.kind === 'script') {
      await conn.run(last.name, last.id, last.script);
      channel.appendLine(`[${conn.deviceName}] ↻ ${last.name}`);
      return;
    }
    // kind === 'project'：按当前工作区内容重新打包推送。
    const exclude = createExcludeMatcher(vscode.workspace.getConfiguration('aijspro').get<string[]>('exclude', []));
    const entries = await collectProjectFiles(last.folder, exclude);
    const zipInputs: ZipInput[] = [];
    for (const entry of entries) {
      zipInputs.push({ path: entry.relative, data: await fs.promises.readFile(entry.absolute) });
    }
    await conn.sendBytesCommand('run_project', last.name, createZip(zipInputs), []);
    channel.appendLine(`[${conn.deviceName}] ↻ 工程 ${last.name}`);
  });
}

const RHINO_TEMPLATE = '"ui";\n// 第一代 API（Rhino 引擎）\nconsole.log("Hello AI.js Pro");\n';
const QUICKJS_TEMPLATE = '// @engine quickjs\n// 第二代 API（QuickJS 引擎）\nconsole.log("Hello AI.js Pro");\n';

function projectJson(name: string, engine: 'rhino' | 'quickjs'): string {
  return `${JSON.stringify(
    {
      name,
      versionName: '1.0.0',
      versionCode: 1,
      main: 'main.js',
      engine,
    },
    null,
    2,
  )}\n`;
}

function engineOf(kind: 'rhino' | 'quickjs'): { template: string; engine: 'rhino' | 'quickjs' } {
  return { template: kind === 'rhino' ? RHINO_TEMPLATE : QUICKJS_TEMPLATE, engine: kind };
}

async function createDeviceProjectCommand(kind: 'rhino' | 'quickjs'): Promise<void> {
  const title = kind === 'rhino' ? '第一代API项目(Rhino)' : '第二代API项目(QuickJS)';
  const name = await vscode.window.showInputBox({
    prompt: `新建${title}：项目名（创建在手机脚本目录下）`,
    value: kind === 'rhino' ? 'rhino-project' : 'quickjs-project',
  });
  if (!name) {
    return;
  }
  const safe = sanitize(name.trim());
  if (!safe) {
    vscode.window.showWarningMessage('AI.js Pro: 项目名不合法');
    return;
  }
  await withConnection(async (conn) => {
    await conn.writeFile(`${safe}/project.json`, projectJson(safe, kind));
    await conn.writeFile(`${safe}/main.js`, engineOf(kind).template);
    channel.appendLine(`[${conn.deviceName}] ＋ 已创建项目 ${safe}（${title}）`);
    const choice = await vscode.window.showInformationMessage(
      `AI.js Pro: 已在手机创建项目 ${safe}`,
      '刷新设备文件',
    );
    if (choice === '刷新设备文件') {
      await vscode.commands.executeCommand('aijspro.refreshFiles');
    }
  });
}

async function createWorkspaceProjectCommand(kind: 'rhino' | 'quickjs'): Promise<void> {
  const title = kind === 'rhino' ? '第一代API项目(Rhino)' : '第二代API项目(QuickJS)';
  const folder = await pickWorkspaceFolder();
  if (!folder) {
    return;
  }
  const name = await vscode.window.showInputBox({
    prompt: `在工作区建立${title}：目录名`,
    value: kind === 'rhino' ? 'rhino-project' : 'quickjs-project',
  });
  if (!name) {
    return;
  }
  const safe = sanitize(name.trim());
  if (!safe) {
    vscode.window.showWarningMessage('AI.js Pro: 目录名不合法');
    return;
  }
  const dir = path.join(folder.uri.fsPath, safe);
  if (fs.existsSync(dir)) {
    vscode.window.showWarningMessage(`AI.js Pro: ${safe} 已存在`);
    return;
  }
  await fs.promises.mkdir(dir, { recursive: true });
  await fs.promises.writeFile(path.join(dir, 'project.json'), projectJson(safe, kind), 'utf8');
  const mainPath = path.join(dir, 'main.js');
  await fs.promises.writeFile(mainPath, engineOf(kind).template, 'utf8');
  await vscode.window.showTextDocument(vscode.Uri.file(mainPath));
  channel.appendLine(`＋ 已建立 ${title}：${mainPath}`);
}

async function addDebugConfigCommand(): Promise<void> {
  const conn = await manager.pickConnection();
  let host = conn ? hostOf(conn) : undefined;
  if (!host) {
    host = await vscode.window.showInputBox({
      prompt: '设备地址（写入工作区 .vscode/settings.json 的 aijspro.devices）',
    });
    host = host?.trim();
  }
  if (!host) {
    return;
  }
  const folder = vscode.workspace.workspaceFolders?.[0];
  if (!folder) {
    vscode.window.showWarningMessage('AI.js Pro: 需要打开一个文件夹工作区');
    return;
  }
  const vscodeDir = path.join(folder.uri.fsPath, '.vscode');
  const settingsPath = path.join(vscodeDir, 'settings.json');
  let json: Record<string, unknown> = {};
  try {
    json = JSON.parse(await fs.promises.readFile(settingsPath, 'utf8'));
  } catch {
    json = {};
  }
  const devices = Array.isArray(json['aijspro.devices'])
    ? (json['aijspro.devices'] as string[])
    : [];
  if (!devices.includes(host)) {
    devices.push(host);
  }
  json['aijspro.devices'] = devices;
  await fs.promises.mkdir(vscodeDir, { recursive: true });
  await fs.promises.writeFile(settingsPath, `${JSON.stringify(json, null, 2)}\n`, 'utf8');

  // 同时补一份 launch.json（没有配置时才写），F5 可直接调试。
  const launchPath = path.join(vscodeDir, 'launch.json');
  let launch: any = { version: '0.2.0', configurations: [] };
  try {
    launch = JSON.parse(await fs.promises.readFile(launchPath, 'utf8'));
  } catch {
    launch = { version: '0.2.0', configurations: [] };
  }
  if (!Array.isArray(launch.configurations)) {
    launch.configurations = [];
  }
  const hasAijspro = launch.configurations.some((item: any) => item?.type === 'aijspro');
  if (!hasAijspro) {
    launch.configurations.push({
      type: 'aijspro',
      request: 'launch',
      name: 'AI.js Pro: 调试当前文件（Rhino）',
      program: '${file}',
      host,
    });
    await fs.promises.writeFile(launchPath, `${JSON.stringify(launch, null, 2)}\n`, 'utf8');
  }
  vscode.window.setStatusBarMessage(`AI.js Pro: 已写入 ${host} 与调试配置`, 4000);
}

/** 直接调试当前文件（等价于官方扩展的「调试运行」）。 */
async function debugFileCommand(): Promise<void> {
  const editor = vscode.window.activeTextEditor;
  if (!editor || editor.document.uri.scheme !== 'file') {
    vscode.window.showWarningMessage('AI.js Pro: 请先打开要调试的 .js 文件');
    return;
  }
  const folder = vscode.workspace.getWorkspaceFolder(editor.document.uri);
  const started = await vscode.debug.startDebugging(folder, {
    type: 'aijspro',
    request: 'launch',
    name: 'AI.js Pro: 调试当前文件（Rhino）',
    program: editor.document.fileName,
  });
  if (!started) {
    vscode.window.showWarningMessage('AI.js Pro: 调试会话未能启动（详见「AI.js Pro」输出）');
  }
}

/** 隔离实例端到端测试用：把消息同时写进 AIJSPRO_E2E_DEBUG_LOG 文件。 */
function e2eLog(message: string): void {
  const sink = process.env.AIJSPRO_E2E_DEBUG_LOG;
  if (!sink) {
    return;
  }
  try {
    fs.appendFileSync(sink, `${message}\n`, 'utf8');
  } catch {
    // 仅测试用。
  }
}

/** 隔离实例端到端测试用：等设备连上 → 下断点 → 启动调试。 */
async function autoDebugForE2E(line: number): Promise<void> {
  e2eLog('[e2e] auto debug start');
  const deadline = Date.now() + 15000;
  while (Date.now() < deadline && !manager.activeConnection?.attached) {
    await new Promise((resolve) => setTimeout(resolve, 250));
  }
  const conn = manager.activeConnection;
  if (!conn?.attached) {
    channel.appendLine('[e2e] 未能连上设备，自动调试放弃');
    e2eLog('[e2e] no device connection');
    return;
  }
  const folder = vscode.workspace.workspaceFolders?.[0];
  if (!folder) {
    channel.appendLine('[e2e] 没有工作区，自动调试放弃');
    e2eLog('[e2e] no workspace');
    return;
  }
  const target = await findFirstScript(folder.uri.fsPath, 4);
  if (!target) {
    channel.appendLine('[e2e] 工作区里没有 .js 文件，自动调试放弃');
    e2eLog('[e2e] no js file');
    return;
  }
  const document = await vscode.workspace.openTextDocument(target);
  const position = new vscode.Position(Math.max(0, (Number.isFinite(line) ? line : 1) - 1), 0);
  vscode.debug.addBreakpoints([
    new vscode.SourceBreakpoint(new vscode.Location(document.uri, position)),
  ]);
  channel.appendLine(`[e2e] 已下断点 ${target}:${line}，启动调试`);
  e2eLog(`[e2e] breakpoint set ${target}:${line}`);
  const started = await vscode.debug.startDebugging(folder, {
    type: 'aijspro',
    request: 'launch',
    name: 'AI.js Pro E2E',
    program: target,
  });
  e2eLog(`[e2e] startDebugging=${started}`);
}

/** 隔离实例端到端测试用：等设备连上 → 自动打开图色助手（可选：自动跑一次找色测试）。 */
async function autoColorForE2E(assistant: ColorAssistant): Promise<void> {
  e2eLog('[e2e] auto color start');
  const deadline = Date.now() + 15000;
  while (Date.now() < deadline && !manager.activeConnection?.attached) {
    await new Promise((resolve) => setTimeout(resolve, 250));
  }
  const conn = manager.activeConnection;
  if (!conn?.attached) {
    e2eLog('[e2e] no device connection');
    return;
  }
  e2eLog('[e2e] opening color assistant');
  await assistant.open();
  e2eLog('[e2e] color assistant opened');
  const argb = process.env.AIJSPRO_E2E_COLOR_TEST;
  if (argb) {
    e2eLog('[e2e] running device test ' + argb);
    await assistant.runDeviceTest({ kind: 'findColor', argb, threshold: 4 });
    e2eLog('[e2e] device test done');
  }
  const multiArgb = process.env.AIJSPRO_E2E_COLOR_TEST_MULTI;
  if (multiArgb) {
    e2eLog('[e2e] running multi test ' + multiArgb);
    await assistant.runDeviceTest({
      kind: 'findMulti',
      first: { x: 0, y: 0, argb: multiArgb },
      others: [
        { dx: 1, dy: 0, argb: multiArgb },
        { dx: 0, dy: 1, argb: multiArgb },
      ],
      threshold: 4,
    });
    e2eLog('[e2e] multi test done');
  }
}

async function findFirstScript(dir: string, depth: number): Promise<string | undefined> {
  if (depth < 0) {
    return undefined;
  }
  let children: fs.Dirent[];
  try {
    children = await fs.promises.readdir(dir, { withFileTypes: true });
  } catch {
    return undefined;
  }
  for (const child of children) {
    if (child.isFile() && child.name.endsWith('.js')) {
      return path.join(dir, child.name);
    }
  }
  for (const child of children) {
    if (child.isDirectory() && child.name !== 'node_modules' && !child.name.startsWith('.')) {
      const found = await findFirstScript(path.join(dir, child.name), depth - 1);
      if (found) {
        return found;
      }
    }
  }
  return undefined;
}

