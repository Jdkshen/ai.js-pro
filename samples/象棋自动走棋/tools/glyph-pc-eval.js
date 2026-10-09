// @engine quickjs
/**
 * 字形识别 · **电脑侧**评测（跑真正的 `src/vision/glyph-recognizer.js`）。
 *
 * 为什么要有它：识别链的迭代一直是「改完 → 打包/同步到手机 → 跑 tools/probe-glyph.js → 看 logcat」，
 * 一轮要动手机。这里用 Node 自带能力（zlib 解 PNG + 手写 `images.*` 语义）在电脑上把同一份
 * 识别代码跑在**同一批评测帧**上，改一行就能立刻看到成绩。
 *
 * 素材（都在仓库里的 `docs/reports/chess-skin-eval/`）：
 *   · `skin-a/b/c.png`、`skin2-01..06.png`：K40 天天象棋「浅木皮肤」9 帧（810 格，含真值）
 *   · `eval-9.json`：每帧的棋盘线位置（`py/export-eval-9.py` 生成）+ 真值 FEN
 * 模板：工程 `assets/glyph-tpl/tpl_<字>.png`（与手机同一份）
 *
 * 跑法：
 *   node tools/glyph-pc-eval.js                 # 9 帧全跑
 *   node tools/glyph-pc-eval.js --frame skin-c  # 只跑一帧（改代码时用这个迭代）
 *   node tools/glyph-pc-eval.js --cells         # 额外打印每格读数（排查用）
 *   node tools/glyph-pc-eval.js --report reports/regress/glyph-pc.txt
 *
 * ⚠️ 与真机的差异（读数不可能逐位相同，看趋势）：
 *   · 几何用 py 定位的格线（真机是 lattice 定位），简化成均匀步长网格
 *   · `images.resize` 用双线性近似（引擎口径未公开），只影响金字塔粗搜与跨分辨率模板
 *   · 不带多帧投票 / 稳定帧闸门（那是驱动层的事）
 */
"use strict";

const fs = require("fs");
const path = require("path");
const zlib = require("zlib");

// ---------------------------------------------------------------- PNG 解码（8bit、非隔行）

function decodePng(buffer) {
    if (buffer.readUInt32BE(0) !== 0x89504e47) {
        throw new Error("不是 PNG");
    }
    let offset = 8;
    let width = 0;
    let height = 0;
    let colorType = 2;
    let bitDepth = 8;
    const idat = [];
    while (offset + 8 <= buffer.length) {
        const length = buffer.readUInt32BE(offset);
        const type = buffer.toString("ascii", offset + 4, offset + 8);
        const dataStart = offset + 8;
        const data = buffer.subarray(dataStart, dataStart + length);
        if (type === "IHDR") {
            width = data.readUInt32BE(0);
            height = data.readUInt32BE(4);
            bitDepth = data[8];
            colorType = data[9];
            if (data[12] !== 0) {
                throw new Error("暂不支持隔行 PNG");
            }
            if (bitDepth !== 8) {
                throw new Error("只支持 8bit PNG");
            }
        } else if (type === "IDAT") {
            idat.push(Buffer.from(data));
        } else if (type === "IEND") {
            break;
        }
        offset = dataStart + length + 4;
    }
    const channels = colorType === 0 ? 1 : (colorType === 2 ? 3 : (colorType === 4 ? 2 :
        (colorType === 6 ? 4 : 0)));
    if (!channels) {
        throw new Error("不支持的 PNG 颜色类型：" + colorType);
    }
    const raw = zlib.inflateSync(Buffer.concat(idat));
    const stride = width * channels;
    const out = Buffer.alloc(stride * height);
    let prev = Buffer.alloc(stride);
    for (let y = 0; y < height; y++) {
        const filter = raw[y * (stride + 1)];
        const lineStart = y * (stride + 1) + 1;
        const line = raw.subarray(lineStart, lineStart + stride);
        const cur = out.subarray(y * stride, (y + 1) * stride);
        const left = (index) => (index >= channels ? cur[index - channels] : 0);
        for (let i = 0; i < stride; i++) {
            const rawByte = line[i];
            const up = prev[i];
            const upLeft = i >= channels ? prev[i - channels] : 0;
            let value;
            switch (filter) {
                case 0: value = rawByte; break;
                case 1: value = rawByte + left(i); break;
                case 2: value = rawByte + up; break;
                case 3: value = rawByte + ((left(i) + up) >> 1); break;
                case 4: {
                    const p = left(i) + up - upLeft;
                    const pa = Math.abs(p - left(i));
                    const pb = Math.abs(p - up);
                    const pc = Math.abs(p - upLeft);
                    value = rawByte + (pa <= pb && pa <= pc ? left(i) : (pb <= pc ? up : upLeft));
                    break;
                }
                default: throw new Error("未知 PNG 过滤器 " + filter);
            }
            cur[i] = value & 0xff;
        }
        prev = cur;
    }
    // 统一成 RGB（模板与帧都当彩色图读；灰度图复制三分量）
    const rgb = Buffer.alloc(width * height * 3);
    for (let p = 0; p < width * height; p++) {
        if (channels === 1) {
            const v = out[p];
            rgb[p * 3] = v; rgb[p * 3 + 1] = v; rgb[p * 3 + 2] = v;
        } else if (channels === 2) {
            const v = out[p * 2];
            rgb[p * 3] = v; rgb[p * 3 + 1] = v; rgb[p * 3 + 2] = v;
        } else if (channels === 3) {
            rgb[p * 3] = out[p * 3]; rgb[p * 3 + 1] = out[p * 3 + 1]; rgb[p * 3 + 2] = out[p * 3 + 2];
        } else {
            rgb[p * 3] = out[p * 4]; rgb[p * 3 + 1] = out[p * 4 + 1]; rgb[p * 3 + 2] = out[p * 4 + 2];
        }
    }
    return { width: width, height: height, channels: 3, kind: "rgb", data: rgb };
}

// ---------------------------------------------------------------- images.* 语义（宿主）

const images = {
    read: function (file) {
        return decodePng(fs.readFileSync(file));
    },
    sizeOf: function (image) {
        return { width: image.width, height: image.height };
    },
    grayscale: function (image) {
        if (image.kind === "gray") {
            return image;
        }
        const out = Buffer.alloc(image.width * image.height);
        const data = image.data;
        for (let p = 0, q = 0; p < out.length; p++, q += 3) {
            out[p] = (data[q] * 299 + data[q + 1] * 587 + data[q + 2] * 114) / 1000 | 0;
        }
        return { width: image.width, height: image.height, channels: 1, kind: "gray", data: out };
    },
    clip: function (image, x, y, w, h) {
        if (x < 0 || y < 0 || x + w > image.width || y + h > image.height) {
            throw new Error("clip 越界：" + [x, y, w, h].join(","));
        }
        const ch = image.channels;
        const out = Buffer.alloc(w * h * ch);
        for (let row = 0; row < h; row++) {
            const src = ((y + row) * image.width + x) * ch;
            out.set(image.data.subarray(src, src + w * ch), row * w * ch);
        }
        return { width: w, height: h, channels: ch, kind: image.kind, data: out };
    },
    threshold: function (image, threshold, maxValue) {
        const out = Buffer.alloc(image.width * image.height);
        const data = image.data;
        const ch = image.channels;
        for (let p = 0, q = 0; p < out.length; p++, q += ch) {
            out[p] = data[q] > threshold ? maxValue : 0;
        }
        return { width: image.width, height: image.height, channels: 1, kind: "bin", data: out };
    },
    pixel: function (image, x, y) {
        const ch = image.channels;
        const q = (y * image.width + x) * ch;
        if (ch === 1) {
            return { r: image.data[q], g: image.data[q], b: image.data[q] };
        }
        return { r: image.data[q], g: image.data[q + 1], b: image.data[q + 2] };
    },
    resize: function (image, size) {
        const [w, h] = size;
        const ch = image.channels;
        const out = Buffer.alloc(w * h * ch);
        const sx = (image.width - 1) / Math.max(1, w - 1);
        const sy = (image.height - 1) / Math.max(1, h - 1);
        for (let y = 0; y < h; y++) {
            const fy = y * sy;
            const y0 = Math.floor(fy);
            const y1 = Math.min(image.height - 1, y0 + 1);
            const wy = fy - y0;
            for (let x = 0; x < w; x++) {
                const fx = x * sx;
                const x0 = Math.floor(fx);
                const x1 = Math.min(image.width - 1, x0 + 1);
                const wx = fx - x0;
                for (let c = 0; c < ch; c++) {
                    const p00 = image.data[(y0 * image.width + x0) * ch + c];
                    const p01 = image.data[(y0 * image.width + x1) * ch + c];
                    const p10 = image.data[(y1 * image.width + x0) * ch + c];
                    const p11 = image.data[(y1 * image.width + x1) * ch + c];
                    const top = p00 + (p01 - p00) * wx;
                    const bottom = p10 + (p11 - p10) * wx;
                    out[(y * w + x) * ch + c] = (top + (bottom - top) * wy + 0.5) | 0;
                }
            }
        }
        return { width: w, height: h, channels: ch, kind: image.kind, data: out };
    }
};

/** 单通道像素视图（gray/bin 图直接用；灰度模板由调用方保证）。 */
function grayData(image) {
    if (image.channels !== 1) {
        throw new Error("需要一个通道的图");
    }
    return image.data;
}

/** TM_CCOEFF_NORMED：与 OpenCV 同公式；返回 {sim, x, y}（x,y = 模板左上角，相对搜索窗）。 */
function matchTemplateNormed(winImage, tplImage, winOx, winOy, threshold, maxCount, absolute) {
    const W = winImage.width;
    const H = winImage.height;
    const TW = tplImage.width;
    const TH = tplImage.height;
    if (TW > W || TH > H) {
        return { matches: [] };
    }
    const win = grayData(winImage);
    const tplRaw = grayData(tplImage);
    // 模板去均值
    const tpl = new Float64Array(TW * TH);
    let tplMean = 0;
    for (let i = 0; i < tpl.length; i++) {
        tplMean += tplRaw[i];
    }
    tplMean /= tpl.length;
    let tplSq = 0;
    for (let i = 0; i < tpl.length; i++) {
        tpl[i] = tplRaw[i] - tplMean;
        tplSq += tpl[i] * tpl[i];
    }
    const tplNorm = Math.sqrt(tplSq);
    // 窗口积分图（和 / 平方和），用来 O(1) 拿每个位置的均值与方差
    const sat = new Float64Array((W + 1) * (H + 1));
    const satSq = new Float64Array((W + 1) * (H + 1));
    for (let y = 0; y < H; y++) {
        let rowSum = 0;
        let rowSq = 0;
        for (let x = 0; x < W; x++) {
            const v = win[y * W + x];
            rowSum += v;
            rowSq += v * v;
            sat[(y + 1) * (W + 1) + (x + 1)] = sat[y * (W + 1) + (x + 1)] + rowSum;
            satSq[(y + 1) * (W + 1) + (x + 1)] = satSq[y * (W + 1) + (x + 1)] + rowSq;
        }
    }
    const sumAt = function (table, x, y) {
        return table[(y + TH) * (W + 1) + (x + TW)] - table[y * (W + 1) + (x + TW)] -
            table[(y + TH) * (W + 1) + x] + table[y * (W + 1) + x];
    };
    const matches = [];
    const limit = maxCount == null ? 1 : maxCount;
    const n = TW * TH;
    for (let y = 0; y + TH <= H; y++) {
        for (let x = 0; x + TW <= W; x++) {
            let dot = 0;
            for (let ty = 0; ty < TH; ty++) {
                const winRow = (y + ty) * W + x;
                const tplRow = ty * TW;
                for (let tx = 0; tx < TW; tx++) {
                    dot += tpl[tplRow + tx] * win[winRow + tx];
                }
            }
            const sum = sumAt(sat, x, y);
            const sumSq = sumAt(satSq, x, y);
            const mean = sum / n;
            const varWin = sumSq - sum * mean;
            if (varWin <= 1e-6 || tplNorm <= 1e-6) {
                continue;
            }
            const sim = dot / (tplNorm * Math.sqrt(varWin));
            if (threshold != null && sim < threshold) {
                continue;
            }
            matches.push({ similarity: sim, x: x, y: y, _unused: 0 });
        }
    }
    matches.sort(function (a, b) { return b.similarity - a.similarity; });
    const top = matches.slice(0, limit);
    return {
        matches: top.map(function (m) {
            return {
                similarity: m.similarity,
                point: { x: m.x + (absolute ? winOx : 0), y: m.y + (absolute ? winOy : 0) }
            };
        })
    };
}

// ---------------------------------------------------------------- 宿主（喂给 glyph-recognizer）

function makeHost() {
    let clock = 0;
    return {
        sizeOf: function (image) { return images.sizeOf(image); },
        grayscale: function (image) { return images.grayscale(image); },
        clip: function (image, x, y, w, h) { return images.clip(image, x, y, w, h); },
        threshold: function (image, t, max) { return images.threshold(image, t, max); },
        countColor: function (image, color, options) {
            const region = options.region;
            const data = grayData(image);
            let count = 0;
            for (let y = region.y; y < region.y + region.height; y++) {
                const row = y * image.width;
                for (let x = region.x; x < region.x + region.width; x++) {
                    if (data[row + x] === 0) {
                        count += 1;
                    }
                }
            }
            return count;
        },
        matchTemplate: function (image, template, options) {
            const opts = options || {};
            const threshold = opts.threshold == null ? 0 : opts.threshold;
            const max = opts.max == null ? 1 : opts.max;
            if (opts.region) {
                const region = opts.region;
                const win = images.clip(image, region[0], region[1], region[2], region[3]);
                return matchTemplateNormed(win, template, region[0], region[1], threshold, max, true);
            }
            return matchTemplateNormed(image, template, 0, 0, threshold, max, false);
        },
        pixel: function (image, x, y) { return images.pixel(image, x, y); },
        red: function (pixel) { return pixel.r; },
        green: function (pixel) { return pixel.g; },
        blue: function (pixel) { return pixel.b; },
        resize: function (image, size) { return images.resize(image, size); },
        read: function (file) { return images.read(file); },
        nowMono: function () { clock += 1; return Date.now(); }
    };
}

// ---------------------------------------------------------------- 真值/口径

const CHAR = { "帥": "K", "仕": "A", "相": "B", "兵": "P", "將": "k", "士": "a", "象": "b", "卒": "p" };

function truthChars(fen) {
    const out = [];
    fen.split(" ")[0].split("/").forEach(function (row) {
        for (let i = 0; i < row.length; i++) {
            const ch = row[i];
            if (ch >= "0" && ch <= "9") {
                for (let k = 0; k < Number(ch); k++) {
                    out.push(".");
                }
            } else {
                out.push(ch);
            }
        }
    });
    return out;
}

function boardOf(chars) {
    return chars.map(function (ch) { return ch === "." ? null : ch; });
}

function findRepoRoot(start) {
    let dir = start;
    for (let i = 0; i < 12; i++) {
        if (fs.existsSync(path.join(dir, ".git"))) {
            return dir;
        }
        const up = path.dirname(dir);
        if (up === dir) {
            break;
        }
        dir = up;
    }
    return null;
}

// ---------------------------------------------------------------- 主流程

const BASE_DIR = path.resolve(__dirname, "..");
const args = process.argv.slice(2);
function argOf(name) {
    const i = args.indexOf(name);
    return i >= 0 ? args[i + 1] : null;
}
const onlyFrame = argOf("--frame");
const showCells = args.indexOf("--cells") >= 0;
const reportPath = argOf("--report");
const glyph = require(path.join(BASE_DIR, "src/vision/glyph-recognizer.js"));
const glyphResolve = require(path.join(BASE_DIR, "src/vision/glyph-resolve.js"));

const repoRoot = findRepoRoot(BASE_DIR);
if (!repoRoot) {
    console.error("找不到仓库根（没有 .git）——评测帧在 docs/reports/chess-skin-eval 下");
    process.exit(2);
}
const EVAL_DIR = path.join(repoRoot, "docs/reports/chess-skin-eval");
const evalPath = path.join(EVAL_DIR, "eval-9.json");
if (!fs.existsSync(evalPath)) {
    console.error("缺少 " + evalPath + "（先跑：python -X utf8 docs/reports/chess-skin-eval/py/export-eval-9.py）");
    process.exit(2);
}
const data = JSON.parse(fs.readFileSync(evalPath, "utf8"));
const tplDir = path.join(BASE_DIR, "assets/glyph-tpl");

const host = makeHost();
const templates = glyph.loadTemplates(host, tplDir);
const lines = [];
function log(text) {
    console.log(text);
    lines.push(text);
}

log("字形识别 · 电脑侧评测（跑的是工程里的 glyph-recognizer.js）");
log("帧目录：" + EVAL_DIR);
log("模板：" + tplDir + "（" + templates.length + " 个）");
log("");

let totalOk = 0;
let totalBad = 0;
let totalUnknown = 0;
let totalRescued = 0;
Object.keys(data.frames).forEach(function (name) {
    if (onlyFrame && name.indexOf(onlyFrame) < 0) {
        return;
    }
    const meta = data.frames[name];
    const started = Date.now();
    const image = images.read(path.join(EVAL_DIR, name));
    const grid = {
        originX: meta.v9[0],
        originY: meta.h10[0],
        stepX: (meta.v9[8] - meta.v9[0]) / 8,
        stepY: (meta.h10[9] - meta.h10[0]) / 9
    };
    const scale = glyph.scaleFor(grid);
    const prepared = glyph.prepareFor(host, templates, scale);
    const rec = glyph.recognize(host, image, grid, {
        templates: prepared.templates,
        TW: prepared.TW,
        ANCHOR_DY: prepared.ANCHOR_DY,
        coarseTemplates: prepared.coarse,
        collect: true
    });
    // 与生产链路一致：再过一遍「子力上限 + 棋规」的盘面级判决（守恒补全 / 违规降级）
    const scores = {};
    (rec.cells || []).forEach(function (item) {
        if (item && item.idx != null && item.sim != null) {
            scores[item.idx] = item.sim;
        }
    });
    const resolved = glyphResolve.resolve(glyph.normalizeBoard(rec.chars), {
        scores: scores,
        notes: rec.notes || []
    });
    const board = resolved.chars;
    const truth = truthChars(meta.truth);
    let ok = 0;
    let bad = 0;
    const badCells = [];
    for (let k = 0; k < 90; k++) {
        if (truth[k] === "?") {
            continue;
        }
        const got = board[k] == null ? "." : board[k];
        if (got === truth[k]) {
            ok += 1;
        } else {
            bad += 1;
            badCells.push("r" + Math.floor(k / 9) + "c" + (k % 9) + ":" + truth[k] + "→" + got);
        }
    }
    totalOk += ok;
    totalBad += bad;
    totalUnknown += rec.unknown;
    totalRescued += (rec.rescued || 0);
    log(name + "  " + ok + "/" + (ok + bad) + " = " + (100 * ok / Math.max(1, ok + bad)).toFixed(1) + "%" +
        "  未知 " + rec.unknown + "  抢救 " + (rec.rescued || 0) +
        "  判决(补" + resolved.filled.length + "/降" + resolved.demoted.length + "/翻" + resolved.flipped.length + ")" +
        "  耗时 " + (Date.now() - started) + "ms" +
        (badCells.length ? "\n    " + badCells.join(" ") : ""));
    if (showCells) {
        for (let k = 0; k < 90; k++) {
            const got = board[k] == null ? "." : board[k];
            if (got === "?" || got !== truth[k]) {
                log("    r" + Math.floor(k / 9) + "c" + (k % 9) + " 真值 " + truth[k] + " 读数 " + got);
            }
        }
    }
});
log("");
log("合计 " + totalOk + "/" + (totalOk + totalBad) + " = " +
    (100 * totalOk / Math.max(1, totalOk + totalBad)).toFixed(1) + "%（目标：与真机 probe-glyph 同档）" +
    "｜未知 " + totalUnknown + "｜抢救 " + totalRescued);
log("口径：严格（含阵营）；真值 `?` 的格子跳过（与 py 评测同一套跳过集合）");
if (reportPath) {
    const full = path.join(BASE_DIR, reportPath);
    fs.mkdirSync(path.dirname(full), { recursive: true });
    fs.writeFileSync(full, lines.join("\n") + "\n", "utf8");
    console.log("已写：" + full);
}
