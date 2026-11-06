# -*- coding: utf-8 -*-
"""发布更新 GUI（由桌面快捷方式以 pythonw 启动，无控制台窗口）

干什么：
  1. 自动检测在线的安卓设备（发布要用设备版本号做基准、发布后在手机上验证）
  2. 填写「本版改了什么」→ 点「发布」→ 确认
  3. 后台隐藏运行 tools/publish.ps1（构建 + 发布到本地更新源，约 3~10 分钟）
  4. 实时显示进度行；完成后给出结果（版本号 / 失败原因），可一键打开日志

⚠️ 点「发布」= 真的发布（会写更新源、更新历史）。自检/演练用环境变量：
     set PUBLISH_GUI_DRYRUN=1     （等价 publish.ps1 -DryRun：只检查，不发布）

命令行自检（不开窗口，跑 DryRun 全链路）：
    C:\\Python314\\python.exe "tools\\发布更新.pyw" --selftest
"""
import glob
import os
import queue
import re
import socket
import subprocess
import sys
import threading

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ADB = r'C:\Android\platform-tools\adb.exe'
PUBLISH = os.path.join(ROOT, 'tools', 'publish.ps1')
CREATE_NO_WINDOW = 0x08000000


def _run_hidden(args):
    return subprocess.run(args, capture_output=True, creationflags=CREATE_NO_WINDOW)


def list_devices():
    """返回 [(serial, model), ...]（当前在线的设备）"""
    r = _run_hidden([ADB, 'devices'])
    out = r.stdout.decode('utf-8', errors='replace')
    serials = []
    for line in out.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2 and parts[1] == 'device':
            serials.append(parts[0])
    result = []
    for s in serials:
        m = _run_hidden([ADB, '-s', s, 'shell', 'getprop', 'ro.product.model'])
        model = m.stdout.decode('utf-8', errors='replace').strip()
        result.append((s, model))
    return result


def build_args(device, notes, dryrun):
    args = ['powershell.exe', '-NoProfile', '-ExecutionPolicy', 'Bypass',
            '-File', PUBLISH, '-ReleaseNotes', notes,
            '-DeviceId', device, '-AllowMultiDevice']
    if dryrun:
        args.append('-DryRun')
    return args


class Publisher(threading.Thread):
    """后台线程：隐藏运行 publish.ps1，输出行推进 queue，结束推 ('done', rc)"""

    def __init__(self, device, notes, dryrun, q):
        super().__init__(daemon=True)
        self.device = device
        self.notes = notes
        self.dryrun = dryrun
        self.q = q

    def run(self):
        try:
            proc = subprocess.Popen(
                build_args(self.device, self.notes, self.dryrun), cwd=ROOT,
                stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                creationflags=CREATE_NO_WINDOW)
        except Exception as e:
            self.q.put(('line', '[启动失败] %s' % e))
            self.q.put(('done', -1))
            return
        for raw in proc.stdout:
            self.q.put(('line', raw.decode('gbk', errors='replace').rstrip()))
        self.q.put(('done', proc.wait()))


# ---------------- GUI ----------------

def main_gui():
    import tkinter as tk
    from tkinter import messagebox, ttk

    FONT = ('Microsoft YaHei UI', 10)
    q = queue.Queue()

    root = tk.Tk()
    root.title('发布更新（构建 + 发布到本地更新源）')
    root.geometry('560x420')
    root.minsize(520, 400)

    tk.Label(root, text='目标设备（读版本号做基准、发布后在手机上验证）：', font=FONT).pack(
        anchor='w', padx=14, pady=(12, 2))
    dev_row = tk.Frame(root)
    dev_row.pack(fill='x', padx=14)
    device_combo = ttk.Combobox(dev_row, state='readonly', font=FONT)
    device_combo.pack(side='left', fill='x', expand=True)
    dev_status = tk.Label(root, text='正在检测设备…', font=('Microsoft YaHei UI', 8),
                          fg='#888', anchor='w')
    dev_status.pack(fill='x', padx=14)

    def refresh_devices(*_):
        dev_status.config(text='正在检测设备…')

        def work():
            try:
                q.put(('devices', list_devices()))
            except Exception as e:
                q.put(('devices', []))
                q.put(('line', '[设备检测失败] %s' % e))
        threading.Thread(target=work, daemon=True).start()

    tk.Button(dev_row, text='刷新', font=FONT, command=refresh_devices,
              padx=10).pack(side='left', padx=(6, 0))

    tk.Label(root, text='本版改了什么（会显示在手机的更新提示里）：', font=FONT).pack(
        anchor='w', padx=14, pady=(10, 2))
    notes = tk.Text(root, height=5, font=FONT, wrap='word')
    notes.pack(fill='x', padx=14)
    notes.insert('1.0', '')

    act = tk.Frame(root)
    act.pack(fill='x', padx=14, pady=10)
    publish_btn = tk.Button(act, text='发布', font=('Microsoft YaHei UI', 11, 'bold'),
                            bg='#16a34a', fg='white', activebackground='#15803d',
                            activeforeground='white', width=10)
    publish_btn.pack(side='left')
    status = tk.Label(act, text='就绪', font=FONT, anchor='w')
    status.pack(side='left', padx=12)

    lastline = tk.Label(root, text='', font=('Consolas', 9), fg='#666',
                        anchor='w', justify='left', wraplength=520)
    lastline.pack(fill='x', padx=14)

    log_row = tk.Frame(root)
    log_row.pack(fill='x', padx=14, pady=(8, 12), side='bottom')
    open_log_btn = tk.Button(log_row, text='打开最近日志', font=FONT, state='disabled',
                             command=lambda: open_latest_log())
    open_log_btn.pack(side='right')

    state = {'running': False, 'buffer': [], 'devices': [], 'latest_log': None}

    def open_latest_log():
        p = state['latest_log']
        if p and os.path.exists(p):
            os.startfile(p)

    def on_devices(devs):
        state['devices'] = devs
        if not devs:
            device_combo['values'] = []
            device_combo.set('')
            dev_status.config(text='⚠ 没有检测到在线设备（发布需要设备在线）', fg='#b45309')
            return
        device_combo['values'] = ['%s · %s' % (s, m or '?') for s, m in devs]
        prefer = None
        for i, (s, _m) in enumerate(devs):
            if s == 'cccc62c7':
                prefer = i
        device_combo.current(prefer if prefer is not None else 0)
        dev_status.config(text='已检测到 %d 台设备（下拉可切换）' % len(devs), fg='#888')

    def start_publish():
        if state['running']:
            return
        if not state['devices']:
            messagebox.showwarning('发布更新', '没有检测到在线的安卓设备。\n'
                                   '发布要用设备的版本号做基准，且发布后要在手机上验证。')
            return
        idx = device_combo.current()
        if idx < 0:
            messagebox.showwarning('发布更新', '请先选择目标设备')
            return
        serial = state['devices'][idx][0]
        text = notes.get('1.0', 'end').strip()
        if not text:
            messagebox.showwarning('发布更新', '请填写「本版改了什么」')
            return
        dry = os.environ.get('PUBLISH_GUI_DRYRUN') == '1'
        try:
            with socket.create_connection(('127.0.0.1', 8080), timeout=0.6):
                port_ok = True
        except OSError:
            port_ok = False
        if not port_ok:
            if not messagebox.askyesno('发布更新', '本机 8080（更新源服务）现在没有响应。\n\n'
                                       '手机将拉不到更新清单，发布最后一步「手机可达性」验证预计失败。\n'
                                       '（可先在本仓库目录起服务：python -m http.server 8080 --directory .artifacts\\updates）\n\n'
                                       '仍要继续吗？'):
                return
        tip = '\n\n[DRY-RUN 测试模式：只检查，不会真的发布]' if dry else ''
        if not messagebox.askyesno('发布更新',
                                   '即将构建并发布一版。\n\n目标设备：%s\n预计 3~10 分钟，期间不要关闭本窗口。\n继续？%s'
                                   % (serial, tip)):
            return
        state['running'] = True
        state['buffer'] = []
        publish_btn.config(state='disabled', text='发布中…')
        status.config(text='正在构建发布…（可最小化，别关窗口）', fg='#2563eb')
        lastline.config(text='')
        open_log_btn.config(state='disabled')
        Publisher(serial, text, dry, q).start()

    def on_line(line):
        state['buffer'].append(line)
        s = line.strip()
        if s and len(s) > 4:
            lastline.config(text=s[:110])

    def on_done(rc):
        state['running'] = False
        publish_btn.config(state='normal', text='发布')
        txt = '\n'.join(state['buffer'])
        logs = glob.glob(os.path.join(ROOT, '.artifacts', 'publish-*.log'))
        latest = max(logs, key=os.path.getmtime) if logs else None
        state['latest_log'] = latest
        if latest:
            open_log_btn.config(state='normal')
        if rc == 0:
            m = re.search(r'发布完成：dev-(\d+)', txt)
            m2 = re.search(r'\[DryRun\].*dev-(\d+)', txt)
            if m:
                status.config(text='✔ 发布成功：dev-%s' % m.group(1), fg='#16a34a')
                messagebox.showinfo('发布更新', '发布成功：dev-%s 已就绪，手机可以检查更新。' % m.group(1))
            elif 'DryRun 完成' in txt:
                v = re.search(r'dev-(\d+)', txt)
                status.config(text='✔ DryRun 完成（未发布）', fg='#16a34a')
                messagebox.showinfo('发布更新', 'DryRun 完成：将要发布的版本是 %s（未发布）。' % (v.group(1) if v else 'dev-?'))
            else:
                status.config(text='✔ 完成（退出码 0）', fg='#16a34a')
                messagebox.showinfo('发布更新', '脚本正常结束。')
        else:
            fails = [l for l in state['buffer'] if 'FAILED:' in l]
            reason = fails[-1] if fails else ('退出码 %s' % rc)
            status.config(text='✘ 发布失败', fg='#dc2626')
            messagebox.showerror('发布更新', '发布失败：\n%s\n\n%s'
                                 % (reason, ('日志：' + latest) if latest else ''))

    def poll():
        try:
            while True:
                kind, val = q.get_nowait()
                if kind == 'devices':
                    on_devices(val)
                elif kind == 'line':
                    on_line(val)
                elif kind == 'done':
                    on_done(val)
        except queue.Empty:
            pass
        root.after(150, poll)

    publish_btn.config(command=start_publish)
    refresh_devices()
    poll()
    root.mainloop()


# ---------------- 命令行自检 ----------------

def selftest():
    devs = list_devices()
    print('在线设备: %s' % devs)
    if not devs:
        print('没有在线设备，无法自检')
        return 2
    serial = 'cccc62c7' if any(s == 'cccc62c7' for s, _ in devs) else devs[0][0]
    print('使用设备: %s（DryRun，不会发布）' % serial)
    q = queue.Queue()
    Publisher(serial, 'GUI 自检（DryRun，不会发布）', True, q).start()
    while True:
        kind, val = q.get()
        if kind == 'line':
            if val.strip():
                print(val)
        elif kind == 'done':
            print('RC = %s' % val)
            return 0 if val == 0 else 1


if __name__ == '__main__':
    if '--selftest' in sys.argv:
        sys.exit(selftest())
    try:
        main_gui()
    except Exception:
        import traceback
        try:
            import tkinter.messagebox as mb
            mb.showerror('发布更新', traceback.format_exc()[-1500:])
        except Exception:
            pass
        raise
