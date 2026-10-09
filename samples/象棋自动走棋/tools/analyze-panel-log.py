# -*- coding: utf-8 -*-
"""面板日志汇总器（K40 真机 A/B 用）。

把 `adb logcat -s GlobalConsole:V` 拉回来的日志（含 `[floaty]` 行）汇总成一张表：

  · 出招 / 确认 / 未看清 / 恢复 的**次数**（误判率的分母分子）
  · **确认耗时**：每次「点击 → SENT」到紧随其后的「确认：XXX」之间的毫秒（中位/最大）
  · **本轮账**：tick／识别／重定位／全盘 OCR 的中位数（性能）
  · 每分钟速率（不同长度的对比跑才有可比性）

用法：
  python tools/analyze-panel-log.py .artifacts/ab-frames2.txt [.artifacts/ab-frames1.txt ...]
（日志文件先用 `adb shell "logcat -d -f /sdcard/x.txt -s GlobalConsole:V"` + `adb pull` 取回。）
"""
import io
import re
import sys


def ms_of(ts):
    """'12:45:09.970' → 毫秒数（同一天内足够用）"""
    h, m, s = ts.split(":")
    return int(h) * 3600000 + int(m) * 60000 + int(round(float(s) * 1000))


def parse(path):
    text = io.open(path, encoding="utf-8", errors="replace").read()
    out = {
        "path": path, "sent": 0, "confirm": {}, "confirm_ms": [], "no_see": 0,
        "unstable": 0, "gate_reject": 0, "recover": 0, "turn_unknown": 0,
        "tick_ms": [], "obs_ms": [], "locate_ms": 0, "ocrboard_ms": 0, "compare_ms": [],
        "first": None, "last": None, "moves": [], "frames_note": None, "anim_frames": 0,
    }
    pending_click = None
    for raw in text.splitlines():
        i = raw.find("GlobalConsole: ")
        line = raw[i + 15:] if i >= 0 else raw
        if "[floaty]" not in line and "[driver]" not in line:
            continue
        m = re.match(r"^(\d\d:\d\d:\d\d\.\d\d\d)", line)
        t = ms_of(m.group(1)) if m else None
        if t is not None:
            if out["first"] is None:
                out["first"] = t
            out["last"] = t
        if "观察帧数：" in line and out["frames_note"] is None:
            out["frames_note"] = line.strip()
        if "点击：" in line and "SENT" in line:
            out["sent"] += 1
            pending_click = t
        if "确认：" in line:
            kind = "其它"
            for k in ("CONFIRMED", "UNCHANGED", "UNEXPECTED", "OPPONENT_REPLY", "UNRELIABLE"):
                if k in line:
                    kind = k
                    break
            out["confirm"][kind] = out["confirm"].get(kind, 0) + 1
            if "动画" in line:
                out["anim_frames"] = out.get("anim_frames", 0) + 1
            if pending_click is not None and t is not None:
                out["confirm_ms"].append(t - pending_click)
                pending_click = None
        if "本轮没看清" in line:
            out["no_see"] += 1
        if "帧不稳定" in line:
            out["unstable"] += 1
        if "规则门 否决" in line:
            out["gate_reject"] += 1
        if "恢复完成" in line:
            out["recover"] += 1
        if "轮次推不出来" in line:
            out["turn_unknown"] += 1
        m = re.search(r"本轮账：tick (\d+)ms", line)
        if m:
            out["tick_ms"].append(int(m.group(1)))
        m = re.search(r"驱动自计观察 (\d+)ms", line)
        if m:
            out["obs_ms"].append(int(m.group(1)))
        m = re.search(r"重定位 \d+ 次共 (\d+)ms", line)
        if m:
            out["locate_ms"] += int(m.group(1))
        m = re.search(r"\bocrBoard (\d+)ms", line)
        if m:
            out["ocrboard_ms"] += int(m.group(1))
        m = re.search(r"全盘 OCR (\d+)ms", line)
        if m:
            out["compare_ms"].append(int(m.group(1)))
    return out


def median(xs):
    if not xs:
        return None
    ys = sorted(xs)
    return ys[len(ys) // 2]


def report(d):
    span = (d["last"] - d["first"]) / 1000.0 if (d["first"] and d["last"]) else 0
    per_min = (60.0 / span) if span > 0 else 0
    print("=" * 78)
    print("%s" % d["path"])
    print("  时长 %.0fs（%.1f 分钟）｜%s" % (span, span / 60.0, d["frames_note"] or "观察帧数：默认"))
    print("  出招 %d 次（%.1f/分）｜确认 %s" % (d["sent"], d["sent"] * per_min,
                                              d["confirm"] or "{}"))
    cm = d["confirm_ms"]
    if cm:
        print("  确认耗时：中位 %dms｜最大 %dms｜样本 %d" % (median(cm), max(cm), len(cm)))
    print("  未看清 %d（%.1f/分）｜帧不稳定 %d｜规则门否决 %d｜恢复 %d｜轮次推不出 %d｜应手动画帧 %d"
          % (d["no_see"], d["no_see"] * per_min, d["unstable"], d["gate_reject"],
             d["recover"], d["turn_unknown"], d.get("anim_frames", 0)))
    tk = d["tick_ms"]
    print("  tick 中位 %sms（%d 个）｜观察中位 %sms｜重定位合计 %dms｜驱动询问 ocrBoard 合计 %dms｜面板对照合计 %dms"
          % (median(tk), len(tk), median(d["obs_ms"]), d["locate_ms"], d["ocrboard_ms"],
             sum(d["compare_ms"])))


def main():
    paths = sys.argv[1:]
    if not paths:
        print(__doc__)
        return
    for p in paths:
        report(parse(p))


main()
