import * as vscode from 'vscode';

import { DeviceConnection, FileEntry } from './connection';
import { deviceFileKind, isRunnableDeviceFile } from './filePolicy';
import { DeviceManager } from './manager';

export interface DeviceNode {
  kind: 'device';
  conn: DeviceConnection;
}

export interface DirNode {
  kind: 'dir';
  conn: DeviceConnection;
  path: string;
}

export interface FileNode {
  kind: 'file';
  conn: DeviceConnection;
  path: string;
  size: number;
}

export interface InfoNode {
  kind: 'info';
  label: string;
}

export type ExplorerNode = DeviceNode | DirNode | FileNode | InfoNode;

interface DirListing {
  files: FileEntry[];
  dirs: string[];
  truncated: boolean;
}

function baseName(p: string): string {
  const index = p.lastIndexOf('/');
  return index >= 0 ? p.slice(index + 1) : p;
}

/** 「AI.js Pro 设备文件」树：设备 → 目录 → 文件，懒加载（每层一次 list_files）。 */
export class DeviceFileProvider implements vscode.TreeDataProvider<ExplorerNode> {
  private readonly emitter = new vscode.EventEmitter<ExplorerNode | undefined>();
  readonly onDidChangeTreeData = this.emitter.event;

  private readonly cache = new Map<string, DirListing>();

  constructor(private readonly manager: DeviceManager) {
    manager.on('changed', () => this.refresh());
  }

  refresh(): void {
    this.cache.clear();
    this.emitter.fire(undefined);
  }

  getTreeItem(node: ExplorerNode): vscode.TreeItem {
    switch (node.kind) {
      case 'device': {
        const item = new vscode.TreeItem(node.conn.deviceName, vscode.TreeItemCollapsibleState.Expanded);
        item.description = node.conn.label;
        item.iconPath = new vscode.ThemeIcon('device-mobile');
        return item;
      }
      case 'dir': {
        const item = new vscode.TreeItem(baseName(node.path) || node.path, vscode.TreeItemCollapsibleState.Collapsed);
        item.contextValue = 'aijspro.dir';
        item.iconPath = vscode.ThemeIcon.Folder;
        return item;
      }
      case 'file': {
        const item = new vscode.TreeItem(baseName(node.path), vscode.TreeItemCollapsibleState.None);
        item.description = formatSize(node.size);
        item.contextValue = isRunnableDeviceFile(node.path) ? 'aijspro.script' : 'aijspro.file';
        item.iconPath = new vscode.ThemeIcon(
          isRunnableDeviceFile(node.path) ? 'file-code' : deviceFileKind(node.path) === 'image' ? 'file-media' : 'file',
        );
        item.command = {
          command: 'aijspro.openDeviceFile',
          title: '打开',
          arguments: [node],
        };
        return item;
      }
      default: {
        const item = new vscode.TreeItem(node.label, vscode.TreeItemCollapsibleState.None);
        item.iconPath = new vscode.ThemeIcon('info');
        return item;
      }
    }
  }

  async getChildren(node?: ExplorerNode): Promise<ExplorerNode[]> {
    if (!node) {
      const connections = this.manager.all;
      if (connections.length === 0) {
        return [{ kind: 'info', label: '未连接：打开手机侧「Pro 扩展服务器」，或在命令面板执行「AI.js Pro: 连接设备…」' }];
      }
      return connections.map((conn) => ({ kind: 'device', conn }) as DeviceNode);
    }
    if (node.kind === 'device') {
      return this.list(node.conn, '');
    }
    if (node.kind === 'dir') {
      return this.list(node.conn, node.path);
    }
    return [];
  }

  private async list(conn: DeviceConnection, path: string): Promise<ExplorerNode[]> {
    const cacheKey = `${conn.key}|${path}`;
    let listing = this.cache.get(cacheKey);
    if (!listing) {
      try {
        listing = await conn.listFiles(path, false);
      } catch (error) {
        return [{ kind: 'info', label: `读取目录失败：${(error as Error).message}` }];
      }
      this.cache.set(cacheKey, listing);
    }
    const dirs = listing.dirs.map((dir) => ({ kind: 'dir', conn, path: dir }) as DirNode);
    const files = listing.files.map(
      (file) =>
        ({
          kind: 'file',
          conn,
          path: file.path,
          size: file.size,
        }) as FileNode,
    );
    const nodes: ExplorerNode[] = [...dirs, ...files];
    if (listing.truncated) {
      nodes.push({ kind: 'info', label: '（条目过多，已截断）' });
    }
    return nodes;
  }
}

function formatSize(size: number): string {
  if (size < 1024) {
    return `${size} B`;
  }
  if (size < 1024 * 1024) {
    return `${(size / 1024).toFixed(1)} KB`;
  }
  return `${(size / 1024 / 1024).toFixed(1)} MB`;
}
