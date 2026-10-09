"""Prepare real chess screenshots for test-sift-chess-device.js (Pillow/OpenCV).

Run from any directory: python tools/prepare-sift-chess-fixtures.py.
Output is .artifacts/sift-chess-fixture. No Android/product assets are changed.
"""
import importlib.util
import json
import shutil
import re
import hashlib
from pathlib import Path

import cv2
from PIL import Image


def main():
    root = Path(__file__).resolve().parents[1]
    dataset = root / "docs/reports/chess-skin-eval"
    output = root / ".artifacts/sift-chess-fixture"
    (output / "templates").mkdir(parents=True, exist_ok=True)
    spec = importlib.util.spec_from_file_location(
        "chess_grid", dataset / "py/cv-chess-recognize-2.py"
    )
    grid_module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(grid_module)
    truth = json.loads((dataset / "skin-truth.json").read_text("utf-8"))
    truth.update(json.loads((dataset / "skin2-truth.json").read_text("utf-8")))
    # The source JSON still has c at r2c3. Visual evidence and the independent
    # run-py-eval-9.py agree it is r2c1 in skin-b.png.
    truth["skin-b.png"] = "rnbakab1r/9/1c4nc1/p1p1p1p1p/9/9/P1P1P1P1P/4C2C1/9/RNBAKABNR"
    fixture = {
        "sceneSize": [1080, 2400],
        "tolerancePx": 20,
        "notes": [
            "skin-b cannon annotation corrected to row2 col1 after visual inspection; agrees with run-py-eval-9.py",
            "skin2-04 has one unknown animated piece; excluded if returned there",
        ],
        "frames": [],
        "targets": [],
    }
    for name, fen in truth.items():
        image = grid_module.imread_u(str(dataset / name))
        xs, ys, _ = grid_module.locate_board(cv2.cvtColor(image, cv2.COLOR_BGR2GRAY))
        if image.shape[:2] != (2400, 1080) or len(xs) != 9 or len(ys) != 10:
            raise ValueError("Unexpected fixture geometry: " + name)
        pieces = []
        for row, line in enumerate(fen.split("/")):
            col = 0
            for code in line:
                if code.isdigit():
                    col += int(code)
                else:
                    pieces.append({"code": code, "row": row, "col": col,
                                   "x": round(float(xs[col])), "y": round(float(ys[row])) - 5})
                    col += 1
        fixture["frames"].append({"file": name, "fen": fen, "pieces": pieces})
    image = Image.open(dataset / "skin-a.png")
    image.crop((12, 600, 1068, 1776)).save(output / "templates/board.png")
    fixture["targets"].append({"code": "board", "file": "templates/board.png",
                               "crop": [12, 600, 1056, 1176]})
    for code in "rnbakcpRNBAKCP":
        piece = next(p for p in fixture["frames"][0]["pieces"] if p["code"] == code)
        x, y = piece["x"], piece["y"]
        name = "templates/" + ("red-" if code.isupper() else "black-") + code.lower() + ".png"
        image.crop((x - 52, y - 52, x + 52, y + 52)).save(output / name)
        fixture["targets"].append({"code": code, "file": name, "crop": [x - 52, y - 52, 104, 104]})
    (output / "fixture.json").write_text(json.dumps(fixture, ensure_ascii=False, indent=2), "utf-8")
    # The pipeline sees source-template calibration only, never per-frame labels.
    first = grid_module.imread_u(str(dataset / "skin-a.png"))
    xs, ys, _ = grid_module.locate_board(cv2.cvtColor(first, cv2.COLOR_BGR2GRAY))
    pipeline_fixture = {
        "calibration": {"width": 1056, "height": 1176,
                        "originX": float(xs[0]) - 12, "originY": float(ys[0]) - 600,
                        "stepX": float((xs[-1] - xs[0]) / 8),
                        "stepY": float((ys[-1] - ys[0]) / 9),
                        "anchors": [[248, 571, 160, 25], [633, 571, 160, 25]]},
        "frames": [{"file": f["file"], "fen": f["fen"]} for f in fixture["frames"]],
    }
    shifted = Image.new("RGB", (1200, 2600), "#121212")
    shifted.paste(Image.open(dataset / "skin2-03.png"), (60, 80))
    shifted.save(output / "shifted.png")
    pipeline_fixture["frames"].append({"file": "shifted.png", "group": "transformed",
                                       "fen": truth["skin2-03.png"]})
    Image.open(dataset / "skin2-02.png").resize((810, 1800), Image.Resampling.BILINEAR).save(output / "scaled.png")
    pipeline_fixture["frames"].append({"file": "scaled.png", "group": "transformed",
                                       "fen": truth["skin2-02.png"]})
    Image.new("RGB", (1080, 2400), "#171b23").save(output / "no-board.png")
    pipeline_fixture["frames"].append({"file": "no-board.png", "negative": True,
                                       "group": "negative"})
    rgb_check = Image.new("RGB", (4, 1))
    rgb_check.putdata([(255, 0, 0), (0, 255, 0), (0, 0, 255), (255, 255, 255)])
    rgb_check.save(output / "grayscale-check.png")
    pipeline_fixture["rhinoImagesAssetSha256"] = hashlib.sha256(
        (root / "modules/engine/src/main/assets/modules/__images__.js").read_bytes()).hexdigest()
    (output / "fixture-pipeline.json").write_text(
        json.dumps(pipeline_fixture, ensure_ascii=False, indent=2), "utf-8")
    sample = root / "samples/象棋自动走棋"
    shutil.copyfile(sample / "src/vision/glyph-recognizer.js", output / "glyph-recognizer.js")
    # This synchronous QuickJS module does not capture loop bindings in closures.
    # Old Rhino treats const inside loops as an immutable function binding; use
    # its var dialect for this staged compatibility copy (not the sample source).
    glyph_source = (output / "glyph-recognizer.js").read_text("utf-8")
    (output / "glyph-recognizer-rhino.js").write_text(
        re.sub(r"\b(?:const|let)(?=\s+[A-Za-z_$])", "var", glyph_source), "utf-8")
    shutil.copytree(sample / "assets/glyph-tpl", output / "glyph-tpl", dirs_exist_ok=True)
    shutil.copyfile(root / "tools/sift-chess-pipeline.js", output / "sift-chess-pipeline.js")
    shutil.copyfile(root / "modules/engine/src/main/assets/modules/__images__.js",
                    output / "images-patched.js")
    pipeline_source = (root / "tools/test-sift-chess-pipeline-device.js").read_text("utf-8")
    for engine in ("quickjs", "rhino"):
        (output / ("test-pipeline-" + engine + ".js")).write_text(
            pipeline_source.replace("// @engine quickjs", "// @engine " + engine, 1), "utf-8")
    source = (root / "tools/test-sift-chess-device.js").read_text("utf-8")
    for engine in ("quickjs", "rhino"):
        script = source.replace("// @engine quickjs", "// @engine " + engine, 1)
        (output / ("test-" + engine + ".js")).write_text(script, "utf-8")
        script = script.replace("files.read('./fixture.json')", "files.read('./fixture-accurate.json')", 1)
        (output / ("test-accurate-" + engine + ".js")).write_text(script, "utf-8")
    fixture["profiles"] = [{"name": "board-fullres", "options": {
        "scale": 1, "region": [12, 600, 1056, 1176]}}]
    fixture["outputPrefix"] = "chess-accurate-"
    fixture["targets"] = [t for t in fixture["targets"] if t["code"] != "board"]
    (output / "fixture-accurate.json").write_text(
        json.dumps(fixture, ensure_ascii=False, indent=2), "utf-8")
    print(output)


if __name__ == "__main__":
    main()
