#!/usr/bin/env python3
"""Use desktop Pikafish to vet the most common corpus opening positions.

The output is a local trial artifact, not a bundled third-party game archive.
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


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("index", type=pathlib.Path)
    parser.add_argument("output", type=pathlib.Path)
    parser.add_argument("--engine", type=pathlib.Path, required=True)
    parser.add_argument("--limit", type=int, default=200)
    parser.add_argument("--movetime", type=int, default=700)
    parser.add_argument("--max-loss-cp", type=int, default=20)
    args = parser.parse_args()

    raw = json.loads(args.index.read_text(encoding="utf-8"))
    positions = sorted(raw["positions"].items(),
                       key=lambda item: sum(n for _, n in item[1]), reverse=True)[:args.limit]
    engine = args.engine.resolve()
    proc = subprocess.Popen([str(engine)], cwd=engine.parent, stdin=subprocess.PIPE,
                            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                            text=True, encoding="utf-8", bufsize=1)
    vetted = {}
    agreements = 0
    try:
        command(proc, "uci")
        until(proc, "uciok")
        command(proc, "setoption name Threads value 2")
        command(proc, "setoption name Hash value 128")
        command(proc, "setoption name MultiPV value 3")
        command(proc, "isready")
        until(proc, "readyok")
        for number, (key, counts) in enumerate(positions, 1):
            command(proc, "position fen " + key + " - - 0 1")
            command(proc, "go movetime " + str(args.movetime))
            by_depth = {}
            while True:
                line = proc.stdout.readline()
                if not line:
                    raise RuntimeError("Pikafish exited while searching")
                if line.startswith("bestmove "):
                    best = line.split()[1]
                    break
                if not line.startswith("info ") or " pv " not in line:
                    continue
                rank = re.search(r"\bmultipv (\d+)", line)
                score = re.search(r"\bscore (cp|mate) (-?\d+)", line)
                depth = re.search(r"\bdepth (\d+)", line)
                pv = re.search(r"\bpv ([a-i][0-9][a-i][0-9])", line)
                if rank and score and pv and depth:
                    current_depth = int(depth.group(1))
                    by_depth.setdefault(current_depth, {})[int(rank.group(1))] = {
                        "move": pv.group(1), "scoreType": score.group(1),
                        "score": int(score.group(2)),
                        "depth": current_depth,
                    }
            # Compare all candidate scores from the same *completed* MultiPV depth.
            complete = [d for d, ranks in by_depth.items() if all(k in ranks for k in (1, 2, 3))]
            selected_depth = max(complete) if complete else max(by_depth, default=0)
            info = by_depth.get(selected_depth, {})
            if not re.fullmatch(r"[a-i][0-9][a-i][0-9]", best):
                continue
            lead = counts[0][0]
            choice = best
            reason = "engine-best"
            if 1 in info and info[1]["scoreType"] == "cp":
                for item in counts:
                    candidate = next((value for value in info.values()
                                      if value["move"] == item[0]), None)
                    if (candidate and candidate["scoreType"] == "cp" and
                            info[1]["score"] - candidate["score"] <= args.max_loss_cp):
                        choice = item[0]
                        reason = "master-frequency-within-engine-margin"
                        break
            if choice == lead:
                agreements += 1
            vetted[key] = {
                "move": choice, "games": sum(n for _, n in counts),
                "bookLead": lead, "engineBest": best, "reason": reason,
                "candidates": list(info.values()),
            }
            if number % 25 == 0 or number == len(positions):
                print(f"vetted {number}/{len(positions)}; lead agreement {agreements}", flush=True)
    finally:
        if proc.poll() is None:
            command(proc, "quit")
            proc.wait(timeout=5)

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps({
        "schema": 1, "source": raw["source"], "maxPlies": raw["maxPlies"],
        "vet": {"engine": engine.name, "movetimeMs": args.movetime,
                "maxLossCp": args.max_loss_cp, "positions": len(vetted),
                "bookLeadAgreements": agreements},
        "positions": vetted,
    }, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    print("wrote", args.output, "bytes", args.output.stat().st_size)


if __name__ == "__main__":
    main()
