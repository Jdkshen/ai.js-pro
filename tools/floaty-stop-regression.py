"""Run isolated UI callback/stop probes on an explicitly selected idle test phone."""
import argparse
import datetime
import json
import pathlib
import re
import subprocess
import time
import urllib.request

ap = argparse.ArgumentParser()
ap.add_argument("--serial", required=True)
ap.add_argument("--port", type=int, default=18766)
ap.add_argument("--engine", choices=["rhino", "quickjs", "node"], default="rhino")
ap.add_argument("--output", type=pathlib.Path, required=True)
args = ap.parse_args()
if args.serial == "cccc62c7":
    raise SystemExit("K40 is running the user's chess task; select an idle test phone")
args.output.mkdir(parents=True, exist_ok=True)
ADB = "C:/Android/platform-tools/adb.exe"
def adb(*cmd):
    return subprocess.check_output([ADB, "-s", args.serial, *cmd], text=True, encoding="utf-8", errors="replace").strip()
adb("forward", f"tcp:{args.port}", "tcp:8765")
seq = 0
def rpc(method, params=None):
    global seq
    seq += 1
    data = json.dumps({"jsonrpc": "2.0", "id": seq, "method": method, "params": params or {}}).encode()
    req = urllib.request.Request(f"http://127.0.0.1:{args.port}/mcp", data=data,
        headers={"Content-Type": "application/json", "Accept": "application/json, text/event-stream"})
    with urllib.request.urlopen(req, timeout=35) as response:
        text = response.read().decode()
    if text.lstrip().startswith("{"):
        return json.loads(text)
    return json.loads([line[5:] for line in text.splitlines() if line.startswith("data:")][-1])
def call(name, values=None):
    obj = rpc("tools/call", {"name": name, "arguments": values or {}})
    if obj.get("error"):
        raise RuntimeError(obj["error"])
    result = obj.get("result", {})
    text = "\n".join(c.get("text", "") for c in result.get("content", []) if c.get("type") == "text")
    if result.get("isError"):
        raise RuntimeError(text)
    return json.loads(text)
rpc("initialize", {"protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "floaty-stop-regression", "version": "1"}})
status, overlays = call("get_status"), call("list_overlays")
if status.get("activeMcpRuns") or overlays.get("count"):
    raise SystemExit("Selected phone has active scripts/windows; nothing was stopped")
pid = adb("shell", "pidof", "com.jdkshen.aijspro")
if not pid:
    raise SystemExit("Host process is not running")
def installed_version():
    package = adb("shell", "dumpsys", "package", "com.jdkshen.aijspro")
    return int(re.search(r"versionCode=(\d+)", package).group(1))
version = installed_version()
root = "/sdcard/脚本/_floaty_stop_regression/" + args.engine + "-" + datetime.datetime.now().strftime("%Y%m%d-%H%M%S") + "/"
def marker(rel):
    value = call("read_script", {"path": root + rel, "maxBytes": 4096})
    return json.loads(value["content"])
def await_marker(rel):
    end = time.monotonic() + 10
    while time.monotonic() < end:
        try:
            return marker(rel)
        except (KeyError, ValueError, RuntimeError):
            time.sleep(.15)
    raise RuntimeError("marker not created: " + rel)
def wait_execution(execution):
    end = time.monotonic() + 20
    while time.monotonic() < end:
        if adb("shell", "pidof", "com.jdkshen.aijspro") != pid:
            raise RuntimeError("host PID changed during " + execution)
        item = call("get_execution", {"executionId": execution})
        if item.get("status") not in ["RUNNING", "QUEUED"]:
            return item
        time.sleep(.25)
    raise RuntimeError("execution did not finish: " + execution)
cases = [
    ("headless-exit", "action();"),
    ("timeout-exit", "setTimeout(action,100);"),
    ("immediate-exit", "setImmediate(action);"),
    ("interval-exit", "setInterval(action,100);"),
    ("ui-post-exit", "ui.post(action,100);"),
    ("java-runnable-exit", "new android.os.Handler(android.os.Looper.getMainLooper()).post(new java.lang.Runnable({run:action}));"),
    ("timer-error", "setTimeout(function(){mark('entered');throw Error('FLOATY_EXPECTED_CALLBACK_ERROR');},100);"),
    ("menu-error", 'ui.emitter.on("create_options_menu",function(menu){mark("entered");throw Error("FLOATY_EXPECTED_CALLBACK_ERROR");});ui.layout(<vertical><toolbar id="bar"/></vertical>);activity.setSupportActionBar(ui.bar);'),
    ("list-error", 'ui.layout(<vertical><list id="rows"><text text="{{this.name}}"/></list></vertical>);ui.rows.on("item_bind",function(){mark("entered");throw Error("FLOATY_EXPECTED_CALLBACK_ERROR");});ui.rows.setDataSource([{name:"probe"}]);'),
    ("native-click-exit", 'ui.layout(<vertical><button id="quit" text="Exit probe"/></vertical>);ui.quit.on("click",action);setTimeout(function(){var p=java.lang.reflect.Array.newInstance(java.lang.Integer.TYPE,2);ui.quit.getLocationOnScreen(p);files.write(base+"native-click-exit.ready.json",JSON.stringify({x:p[0]+ui.quit.getWidth()/2,y:p[1]+ui.quit.getHeight()/2}));},250);'),
    ("programmatic-click-exit", 'ui.layout(<vertical><button id="quit" text="Exit probe"/></vertical>);ui.quit.on("click",action);setTimeout(function(){ui.quit.performClick();mark("after");},250);'),
    ("dialog-exit", 'var d=dialogs.build({title:"Exit probe",positive:"Stop"}).on("positive",action).show();setTimeout(function(){d.getActionButton(com.afollestad.materialdialogs.DialogAction.POSITIVE).performClick();mark("after");},250);'),
    ("exit-on-close", 'var w=floaty.rawWindow(<frame w="200" h="100" bg="#4477AA"/>);w.setSize(200,100);w.setPosition(100,500);w.exitOnClose();setTimeout(function(){mark("entered");w.close();mark("after");},200);'),
    ("touch-exit", 'var w=floaty.rawWindow(<frame id="r" w="300" h="160" bg="#4477AA"/>);w.setSize(300,160);w.setPosition(100,500);w.findView("r").setOnTouchListener(function(v,e){if(e.getAction()===1)action();return true;});setTimeout(function(){files.write(base+"touch-exit.ready.json",JSON.stringify({x:w.getX()+150,y:w.getY()+80}));},200);'),
    ("external-stop", "setInterval(function(){},100);mark('ready');"),
    ("stop-in-timer", "setTimeout(function(){mark('ready');var end=Date.now()+5000;while(Date.now()<end){}},100);"),
    ("wait-timeout", "setInterval(function(){},100);mark('ready');")
]
if args.engine != "rhino":
    xml = '<frame id="r" w="300px" h="260px" bg="#4477AA"><button id="quit" w="300px" h="260px" text="Exit probe"/></frame>'
    if args.engine == "quickjs":
        window = "var w=floaty.rawWindow(" + json.dumps(xml) + ",{x:100,y:500});w.setSize(300,260);w.setPosition(100,500);"
        click = "w.quit.on('click',action);"
        touch = "w.quit.on('touch',function(e){if(e.getAction()===1)action();});"
        # Raw Android View methods must run on Android's UI thread. Calling
        # performClick directly from the QuickJS script thread tests Android's
        # wrong-thread rejection instead of the script callback exit boundary.
        trigger = "runOnMainThread(function(){w.quit.javaView.performClick();});"
        close = "w.exitOnClose();setTimeout(function(){mark('entered');w.close();mark('after');},200);"
    else:
        window = "var w=require('floating_window').createWindow({});w.setViewFromXml(" + json.dumps(xml) + ");w.setPosition(100,500);w.setSize(300,260);w.show();"
        click = "w.view.binding.quit.on('click',action);"
        touch = "w.on('touch',function(e){if(e.getAction()===1)action();});"
        trigger = "w.view.binding.quit.click();"
        close = "w.on('close',action);setTimeout(function(){w.close();mark('after');},200);"
    ready = "setTimeout(function(){files.write(base+NAME+'.ready.json',JSON.stringify({x:250,y:650}));},250);"
    cases = [("headless-exit", "action();")]
    for name, body in [
        ("timeout-exit", "setTimeout(action,100);"),
        ("immediate-exit", "setImmediate(action);"),
        ("interval-exit", "setInterval(action,100);"),
        ("ui-post-exit", "ui.post(action,100);"),
        ("timer-error", "setTimeout(function(){mark('entered');throw Error('FLOATY_EXPECTED_CALLBACK_ERROR');},100);"),
        ("native-click-exit", click + ready),
        ("touch-exit", touch + ready),
        ("programmatic-click-exit", click + "setTimeout(function(){" + trigger + "},250);"),
        ("exit-on-close", close),
        ("external-stop", "setInterval(function(){},100);mark('ready');"),
        ("stop-in-timer", "setTimeout(function(){mark('ready');var end=Date.now()+5000;while(Date.now()<end){}},100);"),
        ("wait-timeout", "setInterval(function(){},100);mark('ready');")
    ]:
        # Keep an event pump alive while waiting for queued UI callbacks. These
        # engines do not use Rhino's Activity lifetime for a headless script.
        cases.append((name, window + "setInterval(function(){},1000);" + body.replace("NAME", json.dumps(name))))
    if args.engine == "quickjs":
        queued = "__aiNativePostJsCallbackAsync(__aiRegisterCallback(action),100);"
        cases.extend([
            ("queued-java-exit", window + "setInterval(function(){},1000);" + queued),
            ("sleep-java-exit", window + queued + "sleep(3000);mark('after');"),
            ("ui-post-only-exit", "ui.post(action,100);"),
            ("promise-exit", "Promise.resolve().then(action);")
        ])
out = {"serial": args.serial, "engine": args.engine, "versionCode": version,
       "pid": pid, "root": root, "ok": False, "cases": []}
current = None
try:
    for name, body in cases:
        prefix = ('' if name == "headless-exit" else '"ui";')
        mkdir = "files.ensureDir(base);"
        if args.engine == "quickjs":
            prefix = '"quickjs";'
        elif args.engine == "node":
            prefix = '"nodejs";var files=require("files"),ui=require("ui");'
            mkdir = 'files.mkdir(base);'
        source = (prefix + 'var base=' + json.dumps(root) + ';' + mkdir +
            'function mark(stage){files.write(base+' + json.dumps(name + ".json") + ',JSON.stringify({stage:stage}));}'
            'function action(){mark("entered");exit();mark("after");}' + body)
        (args.output / (name + ".js")).write_text(source, encoding="utf-8")
        run = call("run_script", {"engine": args.engine, "source": source, "name": args.engine + "-floaty-" + name,
            "wait": name == "wait-timeout", **({"timeoutSeconds": 1} if name == "wait-timeout" else {})})
        current = run["executionId"]
        if name == "wait-timeout":
            # MCP's timeout bounds the wait only; it deliberately leaves the
            # script running. Reproduce a caller stopping after that timeout.
            if not run.get("timedOut") or run.get("status") not in ["RUNNING", "QUEUED"]:
                raise RuntimeError("expected wait timeout: " + json.dumps(run))
            await_marker(name + ".json")
            call("stop_script", {"executionId": current})
        if name in ["touch-exit", "native-click-exit"]:
            point = await_marker(name + ".ready.json")
            owned = call("list_overlays")
            if owned.get("count") != (1 if name == "touch-exit" or args.engine != "rhino" else 0):
                raise RuntimeError("touch probe did not create exactly one isolated window")
            adb("shell", "input", "tap", str(int(point["x"])), str(int(point["y"])))
        if name in ["external-stop", "stop-in-timer"]:
            await_marker(name + ".json")
            call("stop_script", {"executionId": current})
        item = wait_execution(current)
        if name in ["timer-error", "menu-error", "list-error"]:
            if item.get("status") != "FAILED" or "FLOATY_EXPECTED_CALLBACK_ERROR" not in json.dumps(item):
                raise RuntimeError("ordinary script error was lost: " + json.dumps(item))
        elif item.get("status") not in (["STOPPED"] if args.engine == "rhino" or name in ["external-stop", "stop-in-timer", "wait-timeout"] else ["STOPPED", "SUCCEEDED"]):
            raise RuntimeError("normal stop did not finish normally: " + json.dumps(item))
        observed = marker(name + ".json")
        if observed.get("stage") == "after":
            raise RuntimeError("script continued after exit: " + name)
        end = time.monotonic() + 8
        while call("list_overlays").get("count") and time.monotonic() < end:
            time.sleep(.2)
        if call("list_overlays").get("count"):
            raise RuntimeError("orphan overlay after " + name)
        out["cases"].append({"name": name, "executionId": current, "status": item.get("status"), "marker": observed,
            "hostPidUnchanged": True, "overlayCount": 0, "execution": item})
        print(json.dumps({"case": name, "status": item.get("status"), "pid": pid, "overlays": 0}), flush=True)
        current = None
        (args.output / "result.json").write_text(json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8")
    out["versionCodeAfter"] = installed_version()
    if out["versionCodeAfter"] != version:
        raise RuntimeError("Installed APK changed while the regression was running")
    out["ok"] = True
except Exception as error:
    out["error"] = str(error)
    if current:
        try:
            call("stop_script", {"executionId": current})
        except Exception:
            pass
    raise
finally:
    (args.output / "result.json").write_text(json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8")
