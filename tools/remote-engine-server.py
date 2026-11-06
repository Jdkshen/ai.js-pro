#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
远程算棋服务（LAN 助攻）— 2026-09-26
=====================================

    手机（curl）──HTTP──> 本服务 ──UCI──> pikafish.exe（PC：大线程 / 大 Hash / 长思考）
                              <──「native 同构」JSON──┘

为什么要它：手机算力（2 线程/7s ≈ depth 26）与对手「特级大师」同档（depth 25 封顶）⇒ 互和。
把着法交给 PC（8~16 线程 + 1GB Hash + 15~30s ≈ depth 30+）才是「算力碾压」那条路。

引擎与权重（相对本仓库；msys2 mingw 编译的产物）：
    third-party/Pikafish/src/pikafish.exe
    third-party/Pikafish/src/pikafish.nnue

接口
----
GET  /ping      → {"ok":true,"name":"...","threads":8,"hashMb":1024}
POST /analyze   → body（JSON）：
                    {"fen":"<六段 FEN>",                                # 二选一
                     "positionCommand":"position fen ... moves ...",    # 优先（带着法历史）
                     "movetime":15000,                                  # go movetime（ms）
                     "depth":0,                                         # >0 时改 go depth N
                     "multiPv":3}
                  resp（JSON；字段与手机 native 契约同构 —— score 是**走棋方视角**，
                        手机侧会自己换算成红方视角，这里不做换算）：
                    {"ok":true,"move":"h2e2","scoreCp":12,"mate":null,"depth":30,
                     "nodes":123456,"timeMs":14800,"pv":"h2e2 h2e5 ...",
                     "candidates":[{"move":"h2e2","scoreCp":12,"mate":null,"depth":30,
                                    "pv":"...","nodes":123,"timeMs":14800}, ...]}
                    {"ok":false,"reason":"..."}

启动（PC）
----------
    python tools/remote-engine-server.py                  # 127.0.0.1:8787，8 线程，1GB Hash
    python tools/remote-engine-server.py --threads 16 --hash 2048 --contempt 40
    python tools/remote-engine-server.py --host 0.0.0.0   # WiFi 直连（手机访问 http://<PC-IP>:8787）
    （或者直接双击 tools/远程算棋服务.cmd —— 等价于上面第三条，固定 8 线程 / 1GB）

手机侧（二选一）
----------------
1) USB（需保持连线）：先 `adb reverse tcp:8787 tcp:8787`，手机端 url 用 http://127.0.0.1:8787
2) WiFi（不依赖 USB）：autorun 写
   "remoteEngine": {"url": "http://<PC-IP>:8787", "timeoutMs": 120000, "movetimeMul": 2}
   · movetimeMul >1 = 远程算更久（基础 movetime × 倍率，上限 45s；手机上也会等更久）

自测
----
    python tools/remote-engine-server.py --selftest       # 开局算一步（约 2s）
"""
import argparse
import json
import os
import re
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

REPO = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
EXE = os.path.join(REPO, "third-party", "Pikafish", "src", "pikafish.exe")
NNUE = os.path.join(REPO, "third-party", "Pikafish", "src", "pikafish.nnue")

START_FEN = "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1"


class PikafishEngine:
    """UCI 引擎封装：常驻进程 + 后台读线程；一次搜索 = start/wait（串行，锁保护）。"""

    def __init__(self, exe=EXE, nnue=NNUE, threads=8, hash_mb=1024, contempt=40):
        if not os.path.exists(exe):
            raise RuntimeError("找不到引擎：%s" % exe)
        self.lock = threading.Lock()
        self.st = {"bestmove": None, "gen": 0, "lines": {}, "depth": None,
                   "uciok": False, "ready": False, "name": "",
                   "t_start": 0.0, "t_done": None}
        self.p = subprocess.Popen([exe], cwd=os.path.dirname(exe),
                                  stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                  stderr=subprocess.STDOUT, text=True, encoding="utf-8",
                                  errors="replace", bufsize=1)
        self.threads = threads
        self.hash_mb = hash_mb
        self._multipv = 1
        self._serial = threading.Lock()   # 整个搜索串行（同一引擎一次只能跑一个 go）
        threading.Thread(target=self._pump, daemon=True).start()
        self._send("uci")
        if not self._await_flag("uciok", 20):
            raise RuntimeError("引擎 uci 握手失败（检查 exe：%s）" % exe)
        if nnue and os.path.exists(nnue):
            self._send("setoption name EvalFile value %s" % nnue)
        self._send("setoption name Threads value %d" % threads)
        self._send("setoption name Hash value %d" % hash_mb)
        self._send("setoption name Contempt value %d" % contempt)
        self._send("setoption name UCI_ShowWDL value true")
        self.isready()

    # ---- 后台读取 ----
    def _pump(self):
        try:
            for raw in self.p.stdout:
                line = raw.strip()
                if not line:
                    continue
                if line.startswith("id name"):
                    with self.lock:
                        self.st["name"] = line[8:].strip()
                elif line == "uciok":
                    with self.lock:
                        self.st["uciok"] = True
                elif line == "readyok":
                    with self.lock:
                        self.st["ready"] = True
                elif line.startswith("info"):
                    self._on_info(line)
                elif line.startswith("bestmove"):
                    print("[dbg] << bestmove: %s" % line, flush=True)
                    with self.lock:
                        parts = line.split()
                        self.st["bestmove"] = parts[1] if len(parts) > 1 else None
                        self.st["t_done"] = time.time()
                        self.st["gen"] += 1
        except Exception:
            return

    def _on_info(self, line):
        m = re.search(r" multipv (\d+)", line)
        idx = int(m.group(1)) if m else 1
        m = re.search(r" depth (\d+)", line)
        depth = int(m.group(1)) if m else None
        m = re.search(r" score cp (-?\d+)", line)
        cp = int(m.group(1)) if m else None
        m = re.search(r" score mate (-?\d+)", line)
        mate = int(m.group(1)) if m else None
        m = re.search(r" nodes (\d+)", line)
        nodes = int(m.group(1)) if m else None
        m = re.search(r" time (\d+)", line)
        tms = int(m.group(1)) if m else None
        m = re.search(r" pv (.+)$", line)
        if not m:
            return
        pv = m.group(1)
        with self.lock:
            self.st["lines"][idx] = {"move": pv.split()[0], "scoreCp": cp, "mate": mate,
                                     "pv": pv, "depth": depth, "nodes": nodes, "timeMs": tms}
            self.st["depth"] = depth

    def _await_flag(self, key, timeout):
        t0 = time.time()
        while time.time() - t0 < timeout:
            with self.lock:
                if self.st.get(key):
                    return True
            time.sleep(0.01)
        return False

    def _send(self, text):
        print("[dbg] >> %s" % text, flush=True)
        self.p.stdin.write(text + "\n")
        self.p.stdin.flush()

    def isready(self, timeout=20):
        with self.lock:
            self.st["ready"] = False
        self._send("isready")
        return self._await_flag("ready", timeout)

    # ---- 搜索（串行：多请求排队）----
    def analyze(self, position_cmd, movetime_ms, multipv=1, depth=0):
        with self._search_lock():
            if multipv != self._multipv:
                self._send("setoption name MultiPV value %d" % multipv)
                self._multipv = multipv
            self._send(position_cmd)
            if depth and depth > 0:
                go = "go depth %d" % min(int(depth), 60)
            else:
                go = "go movetime %d" % max(50, int(movetime_ms))
            with self.lock:
                gen0 = self.st["gen"]
                self.st["lines"] = {}
                self.st["bestmove"] = None
                self.st["t_done"] = None
                self.st["t_start"] = time.time()
            self._send(go)
            if depth and depth > 0:
                budget_s = 300.0        # depth 模式：兜底上限 5 分钟（正常远早于此完成）
            else:
                budget_s = int(movetime_ms) / 1000.0 + 30.0
            t0 = time.time()
            last_dbg = t0
            deadline = t0 + budget_s
            stop_sent_at = None
            while True:
                with self.lock:
                    done = self.st["gen"] > gen0
                if done:
                    # ⚠ 必须在**锁外**调用 _snapshot()：它内部会再取同一把锁，
                    # 持锁调用 = 不可重入死锁（2026-09-26 /analyze 卡死的根因）
                    return self._snapshot()
                now = time.time()
                if now - last_dbg > 5.0:
                    last_dbg = now
                    with self.lock:
                        print("[dbg] wait: gen=%s lines=%d busy=%.1fs" %
                              (self.st["gen"], len(self.st["lines"]), now - t0), flush=True)
                if stop_sent_at is None and now > deadline:
                    print("[dbg] 超过预算 %.0fs，发送 stop" % budget_s, flush=True)
                    self._send("stop")
                    stop_sent_at = now
                if stop_sent_at is not None and now - stop_sent_at > 5.0:
                    raise RuntimeError("引擎无响应（stop 后 5s 内 bestmove 未返回）")
                time.sleep(0.01)

    class _SearchLock:
        """串行化整个搜索（同一引擎一次只能跑一个 go）。"""

        def __init__(self, engine):
            self.engine = engine

        def __enter__(self):
            self.engine._serial.acquire()
            return self

        def __exit__(self, *args):
            self.engine._serial.release()
            return False

    def _search_lock(self):
        return self._SearchLock(self)

    def _snapshot(self):
        with self.lock:
            lines = [self.st["lines"][k] for k in sorted(self.st["lines"])]
            ms = int(((self.st["t_done"] or time.time()) - self.st["t_start"]) * 1000)
            best = self.st["bestmove"]
        cands = []
        for item in lines:
            cands.append({"move": item["move"], "scoreCp": item["scoreCp"], "mate": item["mate"],
                          "depth": item["depth"], "pv": item["pv"],
                          "nodes": item["nodes"], "timeMs": item["timeMs"]})
        head = cands[0] if cands else {}
        return {"ok": True,
                "move": best or head.get("move"),
                "scoreCp": head.get("scoreCp"), "mate": head.get("mate"),
                "depth": head.get("depth"), "nodes": head.get("nodes"),
                "timeMs": head.get("timeMs") or ms, "pv": head.get("pv", ""),
                "candidates": cands}

    def quit(self):
        try:
            self._send("quit")
            self.p.wait(timeout=5)
        except Exception:
            try:
                self.p.kill()
            except Exception:
                pass


class EngineHTTPServer(ThreadingHTTPServer):
    """
    Windows 上 SO_REUSEADDR 允许**两个活着的进程**绑同一端口（经典坑：2026-09-26 实测，
    重复启动时第二个实例也打印「监听」，连接会被抢 ⇒ 一会儿通一会儿不通）。
    非 Windows 平台保留它（方便 TIME_WAIT 后立刻重启）。
    """
    allow_reuse_address = (os.name != "nt")


class Handler(BaseHTTPRequestHandler):
    engine = None
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        sys.stderr.write("[remote-engine] %s - %s\n" % (self.address_string(), fmt % args))

    def _json(self, obj, code=200):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        print("[dbg] 发响应 code=%d bytes=%d" % (code, len(body)), flush=True)
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
        print("[dbg] 响应已写", flush=True)

    def do_GET(self):
        if self.path.startswith("/ping"):
            eng = self.engine
            self._json({"ok": True, "name": eng.st.get("name", "pikafish"),
                        "threads": eng.threads, "hashMb": eng.hash_mb})
        else:
            self._json({"ok": False, "reason": "unknown path"}, 404)

    def do_POST(self):
        print("[dbg] 收到 POST %s" % self.path, flush=True)
        if not self.path.startswith("/analyze"):
            self._json({"ok": False, "reason": "unknown path"}, 404)
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            payload = json.loads(self.rfile.read(length).decode("utf-8") or "{}")
        except Exception as exc:
            self._json({"ok": False, "reason": "bad request: %s" % exc}, 400)
            return
        position_cmd = payload.get("positionCommand") or (
            "position fen %s" % payload.get("fen", START_FEN))
        try:
            result = self.engine.analyze(position_cmd,
                                         movetime_ms=int(payload.get("movetime", 5000)),
                                         multipv=int(payload.get("multiPv", 1)),
                                         depth=int(payload.get("depth", 0)))
            self._json(result)
        except Exception as exc:
            self._json({"ok": False, "reason": str(exc)}, 500)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8787)
    parser.add_argument("--threads", type=int, default=8)
    parser.add_argument("--hash", type=int, default=1024)
    parser.add_argument("--contempt", type=int, default=40)
    parser.add_argument("--selftest", action="store_true")
    args = parser.parse_args()

    print("[remote-engine] 引擎：%s" % EXE)
    engine = PikafishEngine(threads=args.threads, hash_mb=args.hash, contempt=args.contempt)
    print("[remote-engine] 已就绪：%s（Threads=%d Hash=%dMB）" % (engine.st.get("name"), args.threads, args.hash))

    if args.selftest:
        t0 = time.time()
        result = engine.analyze("position fen %s" % START_FEN, movetime_ms=2000, multipv=1)
        print("[selftest] %s → %s（depth %s, %sms）" % (time.strftime("%H:%M:%S"),
                                                        result.get("move"), result.get("depth"),
                                                        int((time.time() - t0) * 1000)))
        engine.quit()
        return 0

    Handler.engine = engine
    try:
        server = EngineHTTPServer((args.host, args.port), Handler)
    except OSError as exc:
        print("[remote-engine] 起不来：%s" % exc)
        print("  8787 上可能已有实例在跑 —— 先关掉旧窗口，或换端口 --port 8788")
        engine.quit()
        return 1
    print("[remote-engine] 监听 %s:%d  （手机：curl http://%s:%d/ping）" %
          (args.host, args.port, "127.0.0.1" if args.host == "127.0.0.1" else args.host, args.port))
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n[remote-engine] 退出")
    finally:
        engine.quit()
    return 0


if __name__ == "__main__":
    sys.exit(main())
