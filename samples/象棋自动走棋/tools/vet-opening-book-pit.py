#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""设坑版开局库 vetting（2026-10-06）

在「大师走过 + 引擎深搜认可」的候选中，优先选：
  ① 冷门线 —— 大师对局里少见的着法（绕开双方都能"背"的主航道）；
  ② 浅深反转线 —— 浅层搜索看亏、深层搜索看好（固定时长 5s 的对手最容易把它评估错）。
输出 schema 与原 vet 一致（运行时不改；candidates 就是运行时的抽签池，全部在 30cp 抽签容差内）。

用法：
  python tools/vet-opening-book-pit.py <opening-book.json> <opening-book-pit.json>
        --engine <pikafish.exe 路径> [--limit 200] [--deep-ms 2000] [--shallow-ms 350]
"""
import argparse
import json
import pathlib
import re
import subprocess


def command(proc, line):
    proc.stdin.write(line + "\n")
    proc.stdin.flush()


def until(proc, prefix):
    while True:
        line = proc.stdout.readline()
        if not line:
            raise RuntimeError("Pikafish exited before " + prefix)
        if line.startswith(prefix):
            return line.strip()


def search(proc, fen_key, movetime, multipv):
    """跑一次分析，返回 ({move: (score_cp, depth)}, bestmove)；只用 cp 档。"""
    command(proc, "setoption name MultiPV value " + str(multipv))
    command(proc, "position fen " + fen_key + " - - 0 1")
    command(proc, "go movetime " + str(movetime))
    by_depth = {}
    best = None
    while True:
        line = proc.stdout.readline()
        if not line:
            raise RuntimeError("Pikafish exited while searching")
        if line.startswith("bestmove "):
            parts = line.split()
            best = parts[1] if len(parts) > 1 else None
            break
        if not line.startswith("info ") or " pv " not in line:
            continue
        rank = re.search(r"\bmultipv (\d+)", line)
        score = re.search(r"\bscore (cp|mate) (-?\d+)", line)
        depth = re.search(r"\bdepth (\d+)", line)
        pv = re.search(r"\bpv ([a-i][0-9][a-i][0-9])", line)
        if rank and score and pv and depth:
            d = int(depth.group(1))
            by_depth.setdefault(d, {})[int(rank.group(1))] = {
                "move": pv.group(1), "scoreType": score.group(1),
                "score": int(score.group(2)), "depth": d,
            }
    complete = [d for d, ranks in by_depth.items() if all(k in ranks for k in range(1, multipv + 1))]
    sel = max(complete) if complete else max(by_depth, default=0)
    info = by_depth.get(sel, {})
    scores = {}
    for item in info.values():
        if item["scoreType"] == "cp" and item["move"] not in scores:
            scores[item["move"]] = (item["score"], item["depth"])
    return scores, best


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("index", type=pathlib.Path)
    ap.add_argument("output", type=pathlib.Path)
    ap.add_argument("--engine", type=pathlib.Path, required=True)
    ap.add_argument("--limit", type=int, default=200)
    ap.add_argument("--deep-ms", type=int, default=2000)
    ap.add_argument("--shallow-ms", type=int, default=350)
    ap.add_argument("--multipv", type=int, default=8)
    ap.add_argument("--max-loss-cp", type=int, default=32)
    ap.add_argument("--reversal-cp", type=int, default=60)
    ap.add_argument("--slack", type=int, default=25)
    args = ap.parse_args()

    raw = json.loads(args.index.read_text(encoding="utf-8"))
    positions = sorted(raw["positions"].items(),
                       key=lambda item: sum(n for _, n in item[1]), reverse=True)[:args.limit]
    engine = args.engine.resolve()
    proc = subprocess.Popen([str(engine)], cwd=engine.parent, stdin=subprocess.PIPE,
                            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                            text=True, encoding="utf-8", bufsize=1)
    vetted = {}
    stats = {"traps": 0, "rare": 0, "fallback": 0, "skipped": 0}
    try:
        command(proc, "uci")
        until(proc, "uciok")
        command(proc, "setoption name Threads value 2")
        command(proc, "setoption name Hash value 256")
        command(proc, "isready")
        until(proc, "readyok")
        for number, (key, counts) in enumerate(positions, 1):
            deep, best = search(proc, key, args.deep_ms, args.multipv)
            if not deep:
                stats["skipped"] += 1
                continue
            shallow, _ = search(proc, key, args.shallow_ms, args.multipv)
            deep_best = max(v for v, _ in deep.values())
            lead = counts[0][0] if counts else None
            pool = []
            for idx, (move, count) in enumerate(counts[:10]):
                if move in deep:
                    score, depth = deep[move]
                    pool.append({"move": move, "count": count, "index": idx,
                                 "score": score, "depth": depth})
            sound = [p for p in pool if deep_best - p["score"] <= args.max_loss_cp]
            if not sound:
                top = sorted(deep.items(), key=lambda kv: kv[1][0], reverse=True)[:3]
                cand = [{"move": m, "scoreType": "cp", "score": s, "depth": d}
                        for m, (s, d) in top]
                vetted[key] = {"move": cand[0]["move"], "games": sum(n for _, n in counts),
                               "bookLead": lead or cand[0]["move"], "engineBest": best,
                               "reason": "fallback-engine", "candidates": cand}
                stats["fallback"] += 1
                continue
            shallow_best = max((v[0] for v in shallow.values()), default=None)
            max_count = max(c["count"] for c in pool) if pool else 1
            for p in sound:
                rarity = 1.0 - (p["count"] / max_count)
                trap = 0.0
                if shallow_best is not None and p["move"] in shallow:
                    sh = shallow[p["move"]][0]
                    if (shallow_best - sh) >= args.reversal_cp:
                        trap = 3.0
                lead_pen = -2.0 if p["move"] == lead else 0.0
                p["pit"] = rarity * 2 + trap + lead_pen - (deep_best - p["score"]) / max(1, args.max_loss_cp)
                p["trap"] = trap > 0
            sound.sort(key=lambda p: p["pit"], reverse=True)
            top_pick = sound[0]
            chosen = [top_pick]
            best_score = top_pick["score"]
            for p in sound[1:]:
                if len(chosen) >= 4:
                    break
                if best_score - p["score"] <= args.slack:
                    chosen.append(p)
                    best_score = max(best_score, p["score"])
            best_score = max(c["score"] for c in chosen)
            chosen = [c for c in chosen if best_score - c["score"] <= args.slack]
            cand = [{"move": c["move"], "scoreType": "cp", "score": c["score"], "depth": c["depth"]}
                    for c in chosen]
            vetted[key] = {
                "move": top_pick["move"], "games": sum(n for _, n in counts),
                "bookLead": lead or top_pick["move"], "engineBest": best,
                "reason": "pit-shallow-reversal" if top_pick["trap"] else "pit-rare-sound",
                "candidates": cand,
                "pit": {"masterCounts": [{"move": c["move"], "count": c["count"], "trap": c["trap"],
                                          "shallowScore": (shallow.get(c["move"]) or (None,))[0]}
                                         for c in chosen]},
            }
            stats["traps" if top_pick["trap"] else "rare"] += 1
            if number % 25 == 0 or number == len(positions):
                print("vetted %d/%d; traps %d; rare %d; fallback %d; skipped %d" %
                      (number, len(positions), stats["traps"], stats["rare"],
                       stats["fallback"], stats["skipped"]), flush=True)
    finally:
        if proc.poll() is None:
            command(proc, "quit")
            try:
                proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                proc.kill()

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps({
        "schema": 1, "source": raw.get("source", ""), "maxPlies": raw.get("maxPlies", 12),
        "vet": {"engine": engine.name, "movetimeMs": args.deep_ms, "shallowMs": args.shallow_ms,
                "maxLossCp": args.max_loss_cp, "reversalCp": args.reversal_cp, "pit": True,
                "positions": len(vetted), "traps": stats["traps"], "rare": stats["rare"],
                "fallback": stats["fallback"], "skipped": stats["skipped"]},
        "positions": vetted,
    }, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    print("wrote", args.output, "bytes", args.output.stat().st_size)


if __name__ == "__main__":
    main()
