#!/usr/bin/env python3
"""R5.4 Node24 识别结果 vs QuickJS 基线自动对照工具。

约定（与 node-smem 的 Node24 移植输出对齐，单个 input 的结果）：
{
  "input": "f01" | "skin-a" | "<path>",
  "bbox": {"x":..,"y":..,"width":..,"height":..} 或 [x,y,w,h],
  "grid": {"originX":..,"originY":..,"stepX":..,"stepY":..} 或 {x,y,stepX,stepY},
  "pieces": [{"row":0..9,"col":0..8,"side":"red|black|unknown","kind":"R|N|B|A|K|C|P","unknown":false}, ...],
  "placement": "<FEN 布局字段，'?'=未知>",
  "counts": {"pieces":32,"unknown":0},
  "ms": 123.4
}
也接受 {"inputs":[...]} / {"results":[...]} / 直接数组。

用法：
  python compare-recognition.py --baseline quickjs-baseline-recognition.json --node24 r5-node24-recognition.json
  python compare-recognition.py --self-test
"""
import argparse
import datetime
import json
import os
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
BT = chr(96)
COLS, ROWS = 9, 10
EMPTY = "."


def load_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def norm_bbox(value):
    if value is None:
        return None
    if isinstance(value, (list, tuple)) and len(value) >= 4:
        return {"x": float(value[0]), "y": float(value[1]), "width": float(value[2]), "height": float(value[3])}
    if isinstance(value, dict):
        x = value.get("x", value.get("left"))
        y = value.get("y", value.get("top"))
        w = value.get("width", value.get("w"))
        h = value.get("height", value.get("h"))
        if x is not None and y is not None and w is not None and h is not None:
            return {"x": float(x), "y": float(y), "width": float(w), "height": float(h)}
    return None


def norm_grid(value):
    if not isinstance(value, dict):
        return None
    ox = value.get("originX", value.get("x"))
    oy = value.get("originY", value.get("y"))
    sx = value.get("stepX", value.get("step"))
    sy = value.get("stepY", value.get("step"))
    if ox is None or oy is None or sx is None or sy is None:
        return None
    return {"originX": float(ox), "originY": float(oy), "stepX": float(sx), "stepY": float(sy),
            "orientation": value.get("orientation")}


def placement_to_chars(placement):
    """把 FEN 布局字段解析为 90 格（row-major, row0 在上）。'?'=未知，'.'=空。"""
    if not isinstance(placement, str):
        return None
    rows = []
    for part in placement.split("/"):
        cells = []
        for ch in part:
            if ch.isdigit():
                cells.extend([EMPTY] * int(ch))
            elif ch in "?.":
                cells.append("?")
            else:
                cells.append(ch)
        rows.append(cells)
    if len(rows) != ROWS or any(len(r) != COLS for r in rows):
        return None
    chars = []
    for row in rows:
        chars.extend(row)
    return chars


def chars_to_rows(chars):
    rows = []
    for r in range(ROWS):
        rows.append("".join(chars[r * COLS + (c)] for c in range(COLS)))
    return rows


def side_kind_to_char(side, kind, unknown):
    if unknown is True:
        return "?"
    side_text = str(side or "").lower()
    kind_text = str(kind or "").strip()
    if kind_text in ("", "?", "unknown", "None"):
        return "?"
    if len(kind_text) == 1 and kind_text.isalpha() and kind_text not in "RNBAKCP":
        # 已经是 FEN 字符（大小写含阵营）
        return kind_text
    letter = kind_text[0].upper() if kind_text else "?"
    if letter not in "RNBAKCP":
        return "?"
    if side_text in ("black", "b", "hei", "2"):
        return letter.lower()
    if side_text in ("red", "r", "hong", "1"):
        return letter
    # side 未知但 kind 原样大写/小写：沿用原大小写
    if isinstance(kind, str) and kind.islower():
        return letter.lower()
    return "?"


def normalize_baseline(path):
    data = load_json(path)
    items = {}
    for item in data.get("inputs") or []:
        rid = str(item.get("id") or item.get("label") or "").strip()
        if not rid:
            continue
        stats = item.get("stats") or {}
        chars = item.get("chars")
        placement = item.get("layout") or item.get("fenPlacement")
        if not chars and placement:
            chars = placement_to_chars(placement)
        if chars:
            # recognizer 的 chars 用 null 表示空格；对照侧统一为 "."
            chars = ["." if (ch is None or ch == "") else ch for ch in chars]
        bbox = norm_bbox(item.get("boardBbox") or item.get("bbox"))
        grid = norm_grid(item.get("grid"))
        walls = ((item.get("deterministic") or {}).get("runWalls")) or []
        ms = walls[0] if walls else item.get("wallMs")
        counts = {"pieces": stats.get("pieceCells", item.get("pieces")),
                  "unknown": item.get("unknownCells", stats.get("unknownCells"))}
        items[rid] = {
            "id": rid, "label": item.get("label"), "path": item.get("path"),
            "bbox": bbox, "grid": grid, "chars": chars, "placement": placement,
            "counts": counts, "ms": ms,
            "boardRows": chars_to_rows(chars) if chars else None,
        }
    return data, items


def normalize_node24(path):
    data = load_json(path)
    raw_items = []
    if isinstance(data, list):
        raw_items = data
    elif isinstance(data, dict):
        for key in ("inputs", "results", "items", "frames"):
            if isinstance(data.get(key), list):
                raw_items = data[key]
                break
        if not raw_items and ("input" in data or "placement" in data or "pieces" in data):
            raw_items = [data]
    results = {}
    warnings = []
    for index, item in enumerate(raw_items):
        rid = item.get("input")
        if isinstance(rid, dict):
            rid = rid.get("id") or rid.get("name") or rid.get("path")
        rid = str(rid or "").strip()
        if not rid:
            rid = "item-%d" % index
        # 常见 id 归一：路径 -> 文件名去扩展名；"real-frame-f01" -> f01
        rid_key = rid
        if "/" in rid_key or "\\" in rid_key:
            rid_key = re.split(r"[/\\]", rid_key)[-1]
        rid_key = re.sub(r"\.png$", "", rid_key, flags=re.IGNORECASE)
        # 只把「以 known 结尾」的名字归一（如 real-frame-f01、f01），不要吞掉 f01-noicc 这类变体
        lower_key = rid_key.lower()
        for known in ("skin-a", "f01"):
            if lower_key == known or lower_key.endswith("-" + known) or lower_key.endswith("_" + known):
                rid_key = known
                break
        bbox = norm_bbox(item.get("bbox"))
        grid = norm_grid(item.get("grid"))
        pieces = item.get("pieces")
        chars = [EMPTY] * (COLS * ROWS)
        if isinstance(pieces, list) and pieces:
            for piece in pieces:
                try:
                    row = int(piece.get("row"))
                    col = int(piece.get("col"))
                except (TypeError, ValueError):
                    warnings.append("%s: piece 缺少合法 row/col：%s" % (rid_key, piece))
                    continue
                if not (0 <= row < ROWS and 0 <= col < COLS):
                    warnings.append("%s: piece 越界 row=%s col=%s" % (rid_key, row, col))
                    continue
                chars[row * COLS + col] = side_kind_to_char(piece.get("side"), piece.get("kind"), piece.get("unknown"))
        else:
            parsed = placement_to_chars(item.get("placement"))
            if parsed:
                chars = parsed
            else:
                warnings.append("%s: 既没有 pieces 也没有可解析的 placement" % rid_key)
                chars = None
        placement = item.get("placement")
        if not placement and chars:
            placement = placement_from_chars(chars)
        counts = item.get("counts") or {}
        if chars:
            computed_pieces = sum(1 for ch in chars if ch not in (EMPTY, "?", None))
            computed_unknown = sum(1 for ch in chars if ch == "?")
        else:
            computed_pieces = computed_unknown = None
        counts = {"pieces": counts.get("pieces", computed_pieces),
                  "unknown": counts.get("unknown", computed_unknown)}
        ms = item.get("ms")
        if isinstance(ms, dict):
            ms = ms.get("totalMs", ms.get("ms"))
        results[rid_key] = {
            "id": rid_key, "rawInput": rid, "bbox": bbox, "grid": grid,
            "chars": chars, "placement": placement, "counts": counts, "ms": ms,
            "boardRows": chars_to_rows(chars) if chars else None,
            "source": item.get("source"), "bPath": item.get("bPath"), "aPath": item.get("aPath"),
            "error": item.get("error"),
        }
    meta = {
        "capabilities": data.get("capabilities") if isinstance(data, dict) else None,
        "notes": data.get("notes") if isinstance(data, dict) else None,
        "writeError": data.get("writeError") if isinstance(data, dict) else None,
        "probe": data.get("probe") if isinstance(data, dict) else None,
    }
    return data, results, warnings, meta


def placement_from_chars(chars):
    parts = []
    for r in range(ROWS):
        part = ""
        empty = 0
        for c in range(COLS):
            ch = chars[r * COLS + c]
            if ch in (EMPTY, None, ""):
                empty += 1
            else:
                if empty:
                    part += str(empty)
                    empty = 0
                part += ch
        if empty:
            part += str(empty)
        parts.append(part)
    return "/".join(parts)


def bbox_iou(a, b):
    if not a or not b:
        return None
    x0 = max(a["x"], b["x"])
    y0 = max(a["y"], b["y"])
    x1 = min(a["x"] + a["width"], b["x"] + b["width"])
    y1 = min(a["y"] + a["height"], b["y"] + b["height"])
    inter = max(0.0, x1 - x0) * max(0.0, y1 - y0)
    union = a["width"] * a["height"] + b["width"] * b["height"] - inter
    return round(inter / union, 6) if union > 0 else 0.0


def compare_items(baseline_items, node_items, tolerances):
    rows = []
    for rid, base in baseline_items.items():
        node = node_items.get(rid)
        row = {"input": rid, "baselineLabel": base.get("label"), "node24Input": node.get("rawInput") if node else None,
               "status": "FAIL", "reasons": [], "metrics": {}, "cellMismatches": []}
        if node is None:
            row["reasons"].append("Node24 输出缺少该 input（可用 inputs: %s）" % ", ".join(sorted(node_items)))
            rows.append(row)
            continue
        row["nodeSource"] = node.get("source")
        row["bPath"] = node.get("bPath")
        row["aPath"] = node.get("aPath")
        if node.get("error"):
            row["reasons"].append("Node24 结果 error：%s" % node["error"])
        source_text = str(node.get("source") or "")
        b_path = node.get("bPath") if isinstance(node.get("bPath"), dict) else None
        row["aFallback"] = (("A" in source_text) or (b_path is not None and b_path.get("ok") is False))
        if row["aFallback"]:
            row["verdictNote"] = "Node24 走 A(glyph) 回退路径，不是 B(原生 recognizeImage) 等价证据；差异属预期"
        reasons = []
        metrics = {}
        # bbox
        iou = bbox_iou(base["bbox"], node["bbox"])
        metrics["bboxIoU"] = iou
        if iou is None:
            reasons.append("bbox 缺失（QuickJS/Node24 至少一方没有合法 bbox）")
        else:
            for key in ("x", "y", "width", "height"):
                metrics["bbox" + key[0].upper() + key[1:]] = round(node["bbox"][key] - base["bbox"][key], 3)
            if iou < tolerances["bbox_iou_min"]:
                reasons.append("bbox IoU %.4f < %.2f" % (iou, tolerances["bbox_iou_min"]))
        # grid
        if base["grid"] and node["grid"]:
            deltas = {key: round(node["grid"][key] - base["grid"][key], 4)
                      for key in ("originX", "originY", "stepX", "stepY")}
            metrics["gridDeltas"] = deltas
            if abs(deltas["originX"]) > tolerances["grid_origin_tol"] or abs(deltas["originY"]) > tolerances["grid_origin_tol"]:
                reasons.append("grid origin 偏差 %.3f/%.3f > %.2f" % (
                    deltas["originX"], deltas["originY"], tolerances["grid_origin_tol"]))
            if abs(deltas["stepX"]) > tolerances["grid_step_tol"] or abs(deltas["stepY"]) > tolerances["grid_step_tol"]:
                reasons.append("grid step 偏差 %.3f/%.3f > %.2f" % (
                    deltas["stepX"], deltas["stepY"], tolerances["grid_step_tol"]))
            if base["grid"].get("orientation") and node["grid"].get("orientation") and \
                    base["grid"]["orientation"] != node["grid"]["orientation"]:
                reasons.append("grid orientation 不同：%s vs %s" % (
                    base["grid"]["orientation"], node["grid"]["orientation"]))
        else:
            reasons.append("grid 缺失（QuickJS/Node24 至少一方没有合法 grid）")
        # counts
        base_counts = base.get("counts") or {}
        node_counts = node.get("counts") or {}
        metrics["counts"] = {"base": base_counts, "node24": node_counts}
        for key in ("pieces", "unknown"):
            if base_counts.get(key) is not None and node_counts.get(key) is not None and \
                    int(base_counts[key]) != int(node_counts[key]):
                reasons.append("%s 计数不同：QuickJS=%s Node24=%s" % (
                    key, base_counts.get(key), node_counts.get(key)))
        # cells
        if base.get("chars") and node.get("chars"):
            agreement = 0
            for idx in range(COLS * ROWS):
                b_ch = base["chars"][idx]
                n_ch = node["chars"][idx]
                if b_ch == n_ch:
                    agreement += 1
                else:
                    row_index, col_index = divmod(idx, COLS)
                    if len(row["cellMismatches"]) < tolerances["max_mismatch_detail"]:
                        row["cellMismatches"].append({
                            "row": row_index, "col": col_index,
                            "quickjs": b_ch, "node24": n_ch,
                        })
            metrics["cellAgreement"] = agreement
            metrics["cellAgreementRatio"] = round(agreement / float(COLS * ROWS), 4)
            if agreement / float(COLS * ROWS) < tolerances["min_cell_agreement"]:
                reasons.append("逐格一致 %d/90（%.2f%% < %.2f%%）" % (
                    agreement, 100.0 * agreement / (COLS * ROWS), 100.0 * tolerances["min_cell_agreement"]))
        else:
            reasons.append("chars 缺失（至少一方无法还原 90 格）")
        # placement
        base_placement = base.get("placement")
        node_placement = node.get("placement")
        metrics["placementEqual"] = bool(base_placement and node_placement and base_placement == node_placement)
        if base_placement and node_placement and base_placement != node_placement:
            diff_index = next((i for i in range(min(len(base_placement), len(node_placement)))
                               if base_placement[i] != node_placement[i]), None)
            reasons.append("placement 字符串不同（首个差异位置 %s：%r vs %r）" % (
                diff_index, base_placement[diff_index] if diff_index is not None else None,
                node_placement[diff_index] if diff_index is not None else None))
        elif not (base_placement and node_placement):
            reasons.append("placement 缺失（至少一方没有）")
        # ms（只记录不判）
        metrics["ms"] = {"quickjsWallMs": base.get("ms"), "node24Ms": node.get("ms")}
        if base.get("ms") and node.get("ms"):
            try:
                metrics["ms"]["nodeVsQuickjs"] = round(float(node["ms"]) / float(base["ms"]), 3)
            except (TypeError, ValueError, ZeroDivisionError):
                pass
        row["metrics"] = metrics
        row["status"] = "PASS" if not reasons else "FAIL"
        row["reasons"] = reasons
        rows.append(row)
    return rows


def build_mock_from_baseline(baseline_items):
    results = []
    for rid, base in baseline_items.items():
        pieces = []
        chars = base.get("chars") or []
        for idx, ch in enumerate(chars):
            if ch in (EMPTY, None, ""):
                continue
            row, col = divmod(idx, COLS)
            if ch == "?":
                pieces.append({"row": row, "col": col, "side": "unknown", "kind": "?", "unknown": True})
            else:
                letter = ch.upper()
                pieces.append({
                    "row": row, "col": col,
                    "side": "red" if ch.isupper() else "black",
                    "kind": letter, "unknown": False,
                })
        results.append({
            "input": rid,
            "bbox": base.get("bbox"),
            "grid": base.get("grid"),
            "pieces": pieces,
            "placement": base.get("placement"),
            "counts": base.get("counts"),
            "ms": base.get("ms"),
        })
    return {"probe": "mock-node24-from-baseline", "inputs": results}


def self_test(baseline_path):
    data, items = normalize_baseline(baseline_path)
    if not items:
        print("SELF_TEST_FAIL: baseline has no inputs")
        return 1

    def run_compare(mock):
        _raw, node_items, _warn, _meta = normalize_node24_from_data(mock)
        return compare_items(items, node_items, DEFAULT_TOLERANCES)

    mock = build_mock_from_baseline(items)
    rows = run_compare(mock)
    ok_pass = all(r["status"] == "PASS" for r in rows)
    print("self-test 1: baseline->mock 自对照 => %s" % ("PASS" if ok_pass else "FAIL"))
    if not ok_pass:
        print(json.dumps(rows, ensure_ascii=False, indent=2))
        return 1

    failures = 0
    # 1) bbox 偏移
    bad = json.loads(json.dumps(mock))
    bad["inputs"][0]["bbox"]["x"] += 50
    rows = run_compare(bad)
    expected_fail = rows[0]["status"] == "FAIL" and any("bbox" in r for r in rows[0]["reasons"])
    print("self-test 2: bbox 偏移 => %s" % ("PASS" if expected_fail else "FAIL"))
    failures += 0 if expected_fail else 1
    # 2) grid step 偏移
    bad = json.loads(json.dumps(mock))
    bad["inputs"][0]["grid"]["stepX"] += 2
    rows = run_compare(bad)
    expected_fail = rows[0]["status"] == "FAIL" and any("grid" in r for r in rows[0]["reasons"])
    print("self-test 3: grid 偏差 => %s" % ("PASS" if expected_fail else "FAIL"))
    failures += 0 if expected_fail else 1
    # 3) 少一个棋子
    bad = json.loads(json.dumps(mock))
    bad["inputs"][0]["pieces"] = bad["inputs"][0]["pieces"][:-1]
    rows = run_compare(bad)
    expected_fail = rows[0]["status"] == "FAIL"
    print("self-test 4: 少一子 => %s" % ("PASS" if expected_fail else "FAIL"))
    failures += 0 if expected_fail else 1
    # 4) placement 改一格
    bad = json.loads(json.dumps(mock))
    placement = bad["inputs"][0]["placement"]
    bad["inputs"][0]["placement"] = ("r" if placement[0] != "r" else "n") + placement[1:]
    rows = run_compare(bad)
    expected_fail = rows[0]["status"] == "FAIL"
    print("self-test 5: placement 改一格 => %s" % ("PASS" if expected_fail else "FAIL"))
    failures += 0 if expected_fail else 1
    if failures:
        print("SELF_TEST_FAIL %d" % failures)
        return 1
    print("SELF_TEST_OK")
    return 0


def normalize_node24_from_data(data):
    # 复用 normalize_node24 的解析逻辑（输入是已加载对象）
    import tempfile
    with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False, encoding="utf-8") as handle:
        json.dump(data, handle, ensure_ascii=False)
        temp_path = handle.name
    try:
        return normalize_node24(temp_path)
    finally:
        try:
            os.unlink(temp_path)
        except OSError:
            pass


DEFAULT_TOLERANCES = {
    "bbox_iou_min": 0.98,
    "grid_origin_tol": 2.0,
    "grid_step_tol": 0.5,
    "min_cell_agreement": 1.0,
    "max_mismatch_detail": 90,
}


def render_markdown(payload):
    lines = []
    add = lines.append
    overall = payload["overallStatus"]
    add("# R5.4 Node24 识别结果 vs QuickJS 基线的自动对照")
    add("")
    add("> 生成：%s" % payload["generatedAt"])
    add("> QuickJS 基线：`%s` ｜ Node24 输出：`%s`" % (payload["baselinePath"], payload["node24Path"]))
    add("> 判定阈值：bbox IoU≥%.2f；grid 原点≤%.1fpx/步距≤%.2fpx；逐格一致≥%.0f%%；counts/placement 要求严格相等。ms 只记录不判。" % (
        payload["tolerances"]["bbox_iou_min"], payload["tolerances"]["grid_origin_tol"],
        payload["tolerances"]["grid_step_tol"], 100.0 * payload["tolerances"]["min_cell_agreement"]))
    add("")
    add("## 总判定：**%s**" % overall)
    add("")
    node_meta = payload.get("node24Meta") or {}
    if node_meta.get("capabilities"):
        add("> Node24 capabilities：%s" % json.dumps(node_meta.get("capabilities"), ensure_ascii=False))
        add("")
    if node_meta.get("notes"):
        add("> Node24 notes：%s" % "；".join(str(n) for n in node_meta.get("notes")[:6]))
        add("")
    add("| 输入 | 判定 | Node24 路径 | bbox IoU | grid 偏差 (origin/step) | 逐格一致 | counts(QuickJS→Node24) | placement | QuickJS ms → Node24 ms |")
    add("|---|---|---|---|---|---|---|---|---|")
    for row in payload["inputs"]:
        metrics = row["metrics"]
        deltas = metrics.get("gridDeltas") or {}
        counts = metrics.get("counts") or {}
        base_counts = counts.get("base") or {}
        node_counts = counts.get("node24") or {}
        ms = metrics.get("ms") or {}
        grid_text = "(%.2f,%.2f)/(%.2f,%.2f)" % (
            deltas.get("originX", float("nan")), deltas.get("originY", float("nan")),
            deltas.get("stepX", float("nan")), deltas.get("stepY", float("nan"))) if deltas else "-"
        source_text = str(row.get("nodeSource") or "-")
        if row.get("aFallback"):
            source_text += "（A 回退）"
        add("| %s | **%s** | %s | %s | %s | %s/90 | %s/%s → %s/%s | %s | %s → %s |" % (
            row["input"], row["status"], source_text,
            "-" if metrics.get("bboxIoU") is None else "%.4f" % metrics["bboxIoU"],
            grid_text,
            metrics.get("cellAgreement", "-"),
            base_counts.get("pieces", "-"), base_counts.get("unknown", "-"),
            node_counts.get("pieces", "-"), node_counts.get("unknown", "-"),
            "相等" if metrics.get("placementEqual") else "不等",
            "-" if ms.get("quickjsWallMs") is None else "%.0f" % ms["quickjsWallMs"],
            "-" if ms.get("node24Ms") is None else "%.1f" % ms["node24Ms"]))
    add("")
    for row in payload["inputs"]:
        add("## %s" % row["input"])
        add("")
        if row.get("verdictNote"):
            add("> ⚠️ %s（bPath=%s）" % (row["verdictNote"], json.dumps(row.get("bPath"), ensure_ascii=False)))
            add("")
        if row["reasons"]:
            add("差异原因：")
            for reason in row["reasons"]:
                add("- %s" % reason)
        else:
            add("- 全部检查项一致（bbox/grid/逐格/counts/placement）。")
        if row["cellMismatches"]:
            add("")
            add("逐格差异（最多显示 %d 格）：" % len(row["cellMismatches"]))
            add("")
            add("| row | col | QuickJS | Node24 |")
            add("|---|---|---|---|")
            for mismatch in row["cellMismatches"]:
                add("| %s | %s | `%s` | `%s` |" % (
                    mismatch["row"], mismatch["col"], mismatch["quickjs"], mismatch["node24"]))
        add("")
    add("---")
    add("")
    add("说明：Node24 结果 JSON 约定为 `{input,bbox,grid,pieces[{row,col,side,kind,unknown}],placement,counts{pieces,unknown},ms}`；")
    add("对照取自 `quickjs-baseline-recognition.json`（skin-a 32 子/0 未知、f01 29 子/2 未知）。")
    add("")
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description="Node24 recognition vs QuickJS baseline comparator")
    parser.add_argument("--baseline", default=str(HERE / "quickjs-baseline-recognition.json"))
    parser.add_argument("--node24", default=None)
    parser.add_argument("--out-json", default=str(HERE / "r5-recognition-parity.json"))
    parser.add_argument("--out-md", default=str(HERE / "r5-recognition-parity.md"))
    parser.add_argument("--bbox-iou-min", type=float, default=DEFAULT_TOLERANCES["bbox_iou_min"])
    parser.add_argument("--grid-origin-tol", type=float, default=DEFAULT_TOLERANCES["grid_origin_tol"])
    parser.add_argument("--grid-step-tol", type=float, default=DEFAULT_TOLERANCES["grid_step_tol"])
    parser.add_argument("--min-cell-agreement", type=float, default=DEFAULT_TOLERANCES["min_cell_agreement"],
                        help="0..1，默认 1.0（90/90 严格一致）")
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--emit-mock", default=None, help="把 QuickJS 基线转换成 Node24 约定格式，写出 mock JSON")
    args = parser.parse_args()

    if args.self_test:
        sys.exit(self_test(args.baseline))

    if args.emit_mock:
        _data, items = normalize_baseline(args.baseline)
        mock = build_mock_from_baseline(items)
        Path(args.emit_mock).write_text(json.dumps(mock, ensure_ascii=False, indent=2), encoding="utf-8")
        print("mock written:", args.emit_mock)
        return

    if not args.node24:
        parser.error("--node24 is required unless --self-test/--emit-mock")

    tolerances = {
        "bbox_iou_min": args.bbox_iou_min,
        "grid_origin_tol": args.grid_origin_tol,
        "grid_step_tol": args.grid_step_tol,
        "min_cell_agreement": args.min_cell_agreement,
        "max_mismatch_detail": DEFAULT_TOLERANCES["max_mismatch_detail"],
    }
    _baseline_raw, baseline_items = normalize_baseline(args.baseline)
    _node_raw, node_items, warnings, node_meta = normalize_node24(args.node24)
    rows = compare_items(baseline_items, node_items, tolerances)
    overall = "PASS" if rows and all(row["status"] == "PASS" for row in rows) else "FAIL"
    payload = {
        "probe": "r5-recognition-parity",
        "generatedAt": datetime.datetime.now().isoformat(timespec="seconds"),
        "baselinePath": str(Path(args.baseline)),
        "node24Path": str(Path(args.node24)),
        "tolerances": tolerances,
        "overallStatus": overall,
        "inputs": rows,
        "warnings": warnings,
        "node24Meta": node_meta,
    }
    Path(args.out_json).write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    Path(args.out_md).write_text(render_markdown(payload), encoding="utf-8")
    print("overall: %s" % overall)
    for row in rows:
        print("  %-10s %s  %s" % (row["input"], row["status"], "; ".join(row["reasons"]) if row["reasons"] else ""))
    print("written:", args.out_json, args.out_md)
    sys.exit(0 if overall == "PASS" else 1)


if __name__ == "__main__":
    main()
