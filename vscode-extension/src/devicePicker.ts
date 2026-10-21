import * as vscode from 'vscode';
import { DeviceManager, savedDevices } from './manager';
import { DeviceConnection } from './connection';
import { UsbDevices, UsbDevice } from './adb';

type DeviceItem = vscode.QuickPickItem & {
  conn?: DeviceConnection; host?: string; usb?: UsbDevice;
  action?: 'connect' | 'refresh' | 'adb';
};

/** Explicit selection always shows a list, including when zero/one devices are online. */
export async function selectDevice(manager: DeviceManager, usb: UsbDevices, connectNew: () => Promise<void>): Promise<void> {
  for (;;) {
    const rows: DeviceItem[] = [];
    const online = manager.all.filter((conn) => conn.attached && !conn.isClosed());
    for (const conn of online) rows.push({
      label: `$(device-mobile) ${conn.deviceName}`,
      description: manager.activeConnection === conn ? '已连接 · 当前设备' : '已连接',
      detail: conn.deviceAddress || conn.label, conn,
    });
    const known = savedDevices().filter((host) => !manager.connectionAt(host));
    for (const host of known) rows.push({ label: `$(history) ${host}`, description: '已记住 · 点击连接', host });
    const items = usb.list().then((devices) => {
      for (const device of devices) {
        const address = usb.addressFor(device.serial);
        if (address && manager.connectionAt(address)) continue;
        rows.push({
        label: `$(plug) ${device.name}`, description: device.state === 'device' ? 'USB 手机' : device.state === 'unauthorized' ? 'USB · 等待手机授权' : 'USB · 离线',
        detail: device.serial, usb: device,
        });
      }
      return rows;
    }, (error: Error) => {
      rows.push({ label: '$(info) USB 扫描不可用', description: '配置 adb 路径', detail: error.message, action: 'adb' });
      return rows;
    }).then((list) => [...list,
      { label: '$(add) 输入手机地址连接…', action: 'connect' as const },
      { label: '$(refresh) 刷新设备列表', action: 'refresh' as const },
    ]);
    const picked = await vscode.window.showQuickPick<DeviceItem>(items, {
      title: '选择 AI.js Pro 设备',
      placeHolder: '选择已连接、USB 或已记住的手机；Esc 取消', matchOnDescription: true, matchOnDetail: true,
    });
    if (!picked) return;
    if (picked.action === 'refresh') continue;
    if (picked.action === 'adb') { await vscode.commands.executeCommand('workbench.action.openSettings', 'aijspro.adbPath'); return; }
    if (picked.action === 'connect') { await connectNew(); return; }
    if (picked.usb && picked.usb.state !== 'device') {
      await vscode.window.showWarningMessage(picked.usb.state === 'unauthorized'
        ? '请在手机上允许 USB 调试授权，再刷新设备列表。' : 'USB 设备离线，请重新连接数据线后刷新。');
      continue;
    }
    try {
      let conn = picked.conn;
      if (!conn?.attached || conn.isClosed()) {
        conn = await vscode.window.withProgress({ location: vscode.ProgressLocation.Notification,
          title: `AI.js Pro: 正在连接 ${picked.usb?.name || picked.host || picked.label}…` }, async () => {
          const address = picked.usb ? await usb.connectAddress(picked.usb.serial) : picked.host || picked.conn?.deviceAddress;
          if (!address) throw new Error('设备已断开，请刷新列表后重选');
          await manager.connectTo(address);
          return waitForConnection(manager, address);
        });
      }
      manager.setActive(conn);
      vscode.window.setStatusBarMessage(`AI.js Pro: 当前设备 ${conn.deviceName}`, 4000);
      return;
    } catch (error) {
      await vscode.window.showErrorMessage(`AI.js Pro: ${(error as Error).message}。请确认手机已开启“电脑连接 → 服务器模式”。`);
    }
  }
}

function waitForConnection(manager: DeviceManager, address: string): Promise<DeviceConnection> {
  const ready = manager.connectionAt(address);
  if (ready) return Promise.resolve(ready);
  return new Promise((resolve, reject) => {
    const cleanup = () => { clearTimeout(timer); manager.off('changed', check); };
    const check = () => {
      const conn = manager.connectionAt(address);
      if (conn) { cleanup(); resolve(conn); }
    };
    const timer = setTimeout(() => { cleanup(); reject(new Error(`连接 ${address} 超时`)); }, 12000);
    manager.on('changed', check);
    check();
  });
}
