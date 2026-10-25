#!/usr/bin/env python3
"""task-245：aijs-ui 无障碍广播桥的真机测试。

该脚本不直接使用 adb shell 冒充应用 UID：
1. 用 adb forward 把手机的 MCP HTTP 服务映射到本机；
2. 通过 MCP run_script 在应用 UID 的 Node/脚本进程里执行 /system/bin/am broadcast；
3. 广播后读取 app-private 的 files/aijs-ui/last-result.json，校验 receiver 是否真的执行。

前提：
- receiver 由 Lead 加入 AndroidManifest（见 docs/任务报告），未加入时脚本会给出
  "Broadcast completed 但无结果文件" 的最小复现；
- 手机需开启 MCP 服务、无障碍服务；本脚本不 root、不安装、不修改产品。

用法：
  python tools/tests/test_aijs_ui.py --command ping
  python tools/tests/test_aijs_ui.py --command click --x 500 --y 900
  python tools/tests/test_aijs_ui.py --command back
  python tools/tests/test_aijs_ui.py --command input --text "hello"
"""

import argparse
import json
import re
import subprocess
import sys
import time
import urllib.request
from http.client import HTTPConnection
from urllib.parse import urlparse
from pathlib import Path

RECEIVER = "com.jdkshen.aijspro/.mcp.AijsUiReceiver"
ACTION = "com.jdkshen.aijspro.AIJS_UI"
RESULT_FILE = "/data/user/0/com.jdkshen.aijspro/files/aijs-ui/last-result.json"


def adb(args, serial):
    cmd = ["adb"]
    if serial:
        cmd += ["-s", serial]
    cmd += args
    return subprocess.run(cmd, text=True, capture_output=True, timeout=30)


def ensure_forward(args):
    if args.no_forward:
        return
    # 默认 MCP URL 是 127.0.0.1:18765；若用户指定其他端口，仍按端口映射。
    from urllib.parse import urlparse
    parsed = urlparse(args.mcp_url)
    if parsed.hostname not in ("127.0.0.1", "localhost"):
        return
    host_port = parsed.port or 18765
    result = adb(["forward", "tcp:%d" % host_port, "tcp:8765"], args.serial)
    if result.returncode != 0:
        raise RuntimeError("adb forward 失败：" + (result.stderr or result.stdout))


def rpc(mcp_url, name, arguments):
    payload = json.dumps({
        "jsonrpc": "2.0", "id": int(time.time() * 1000),
        "method": "tools/call",
        "params": {"name": name, "arguments": arguments},
    }).encode()
    # Python urllib 会发送 Expect: 100-continue；MCP 手机服务对此返回 417。
    # 用 http.client 显式控制请求头，避免该兼容问题。
    parsed = urlparse(mcp_url)
    conn = HTTPConnection(parsed.hostname, parsed.port or 80, timeout=60)
    try:
        conn.putrequest("POST", parsed.path or "/", skip_accept_encoding=True)
        conn.putheader("Content-Type", "application/json")
        conn.putheader("Accept", "application/json")
        conn.putheader("Content-Length", str(len(payload)))
        conn.endheaders(payload)
        response = conn.getresponse()
        body = response.read()
    finally:
        conn.close()
    data = json.loads(body.decode("utf-8"))
    if "error" in data:
        raise RuntimeError("MCP error: " + json.dumps(data["error"], ensure_ascii=False))
    result = data["result"]
    if result.get("isError"):
        raise RuntimeError("MCP tool error: " + str(result.get("content")))
    return result.get("structuredContent") or json.loads(result["content"][0]["text"])


def node_source(args, include_default_am):
    """生成在应用 UID 的 Node 里运行的探测器源码。"""
    am_args = [
        "broadcast", "--user", "0", "-n", RECEIVER, "-a", ACTION,
        "--es", "command", args.command,
    ]
    if args.command in ("click", "tap", "longclick", "long_click"):
        am_args += ["--ei", "x", str(args.x), "--ei", "y", str(args.y)]
    if args.command == "swipe":
        am_args += ["--ei", "x1", str(args.x1), "--ei", "y1", str(args.y1),
                    "--ei", "x2", str(args.x2), "--ei", "y2", str(args.y2),
                    "--ei", "duration", str(args.duration)]
    if args.command in ("input", "settext"):
        am_args += ["--es", "text", args.text]
        if args.resource_id:
            am_args += ["--es", "resourceId", args.resource_id]
    default_args = [a for a in am_args if a not in ("--user", "0")]
    return r'''
const cp = require('child_process');
const fs = require('fs');
const am = '/system/bin/am';
const receiverArgs = %s;
const defaultArgs = %s;
const resultPath = %s;
function run(label, argv) {
  try {
    const out = cp.execFileSync(am, argv, {encoding: 'utf8', timeout: 8000,
      stdio: ['ignore', 'pipe', 'pipe']});
    console.log(label + '_EXIT=0 OUT=' + JSON.stringify(out));
  } catch (e) {
    console.log(label + '_EXIT=' + (e.status == null ? 'ERR' : e.status) +
      ' OUT=' + JSON.stringify(String(e.stdout || '')) +
      ' ERR=' + JSON.stringify(String(e.stderr || '').slice(0, 1200)));
  }
}
function sleep(ms) { Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms); }
run('AM_APPUID', receiverArgs);
%s
const started = Date.now();
let result = null;
for (let i = 0; i < 25; i++) {
  try {
    if (fs.existsSync(resultPath)) {
      result = JSON.parse(fs.readFileSync(resultPath, 'utf8'));
      if (Number(result.at || 0) >= started - 1500) break;
    }
  } catch (e) {}
  sleep(100);
}
console.log('RESULT=' + JSON.stringify({
  result: result,
  resultPath: resultPath,
  waitedMs: Date.now() - started,
  appUid: process.env.AIJS_APP_UID || '',
  processUid: (typeof process.getuid === 'function' ? process.getuid() : null)
}));
''' % (json.dumps(am_args), json.dumps(default_args), json.dumps(RESULT_FILE),
       ("run('AM_DEFAULT_NO_USER', defaultArgs);" if include_default_am else ""))


def main():
    parser = argparse.ArgumentParser(description="aijs-ui receiver 真机测试（应用 UID，不 root）")
    parser.add_argument("--serial", default="ce4d2bdb")
    parser.add_argument("--mcp-url", default="http://127.0.0.1:18765/mcp")
    parser.add_argument("--no-forward", action="store_true")
    parser.add_argument("--command", default="ping",
                        choices=["ping", "status", "click", "tap", "longclick", "long_click",
                                 "swipe", "back", "home", "recents", "notifications",
                                 "input", "settext"])
    parser.add_argument("--x", type=int, default=500)
    parser.add_argument("--y", type=int, default=900)
    parser.add_argument("--x1", type=int, default=500)
    parser.add_argument("--y1", type=int, default=900)
    parser.add_argument("--x2", type=int, default=500)
    parser.add_argument("--y2", type=int, default=300)
    parser.add_argument("--duration", type=int, default=300)
    parser.add_argument("--text", default="")
    parser.add_argument("--resource-id", default="")
    parser.add_argument("--verbose", action="store_true")
    args = parser.parse_args()

    ensure_forward(args)
    source = node_source(args, include_default_am=True)
    try:
        response = rpc(args.mcp_url, "run_script", {
            "engine": "nodejs", "source": source, "wait": True,
            "timeoutSeconds": 30, "name": "aijs-ui-probe",
        })
    except Exception as error:
        print("FAIL: MCP 调用失败：" + str(error))
        return 1

    if args.verbose:
        print(json.dumps(response, ensure_ascii=False, indent=2))
    logs = response.get("logs") or []
    if not logs and isinstance(response.get("result"), str):
        try:
            logs = json.loads(response["result"]).get("logs", [])
        except Exception:
            pass
    text = "\n".join(str(item.get("content", item) if isinstance(item, dict) else item) for item in logs)
    print(text)
    match = re.search(r"RESULT=(\{.*\})", text)
    if not match:
        print("FAIL: 没有找到 RESULT 行；检查 receiver/manifest 是否已由 Lead 添加")
        return 1
    payload = json.loads(match.group(1))
    result = payload.get("result")
    if not result:
        print("FAIL: receiver 未被调用（am 返回成功不等于 receiver 可达）。")
        print("最小复现：app UID 的 am 默认 --user -2 会报 INTERACT_ACROSS_USERS；")
        print("加 --user 0 命令可 exit 0，但若 receiver 未导出/未安装/UID 不同，"
              "只会得到 Broadcast completed，结果文件不会出现。")
        return 1
    print("RESULT_JSON=" + json.dumps(result, ensure_ascii=False))
    if not result.get("ok"):
        print("FAIL: receiver 执行返回 ok=false")
        return 2
    print("PASS: " + str(result.get("command")) + " ok")
    return 0


if __name__ == "__main__":
    sys.exit(main())
