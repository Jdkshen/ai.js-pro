// @engine quickjs
/**
 * YOLO 识别源（TflRunner 版）——「YOLO 主识别」的引擎封装（2026-09-24）。
 *
 * 来源：第三方「象棋自动走棋」包（YOLOv5n 15 类：红黑各 7 子 + 棋盘框；TFLite）。
 * 但**不在脚本里跑 TFLite**：QuickJS 桥拿不到可写的 float[]、也构造不出 Interpreter
 * （`Array.newInstance(Float.TYPE,n)` / `Class.getConstructor(Class...)` 都不支持）。
 * 所以整条流水线（letterbox 预处理 → 推理 → 解码 → FEN）收在 Java 侧
 * `com.xq.TflRunner`（本目录 `yolo/dex-src/TflRunner.java`，d8 重编进 `yolo/dex/`），
 * 这里只做「拿 Mat → 调 TflRunner → JSON 解析 + FEN→90格」。
 *
 * 资产（与本模块同工程的 `yolo/` 目录）：
 *   model/yolov5n_xq_fp16.tflite        （640×640 fp16，3.8MB）
 *   lib/arm64-v8a/libtensorflowlite_jni.so（TFLite JNI，2.9MB）
 *   dex/tflite_classes_fen_v2.dex       （org.tensorflow.lite + com.xq.{YoloDecode,ChessFen,TflRunner}）
 *
 * 三个真机踩过的点（别改回去）：
 *   · DexClassLoader 的 parent 必须用 `context.getClassLoader()`（app 的 PathClassLoader）——
 *     用 getSystemClassLoader() 解析不到 org.opencv.core.Mat（NoClassDefFoundError）；
 *   · .so 要复制到私有目录的**独立子目录**再用绝对路径 System.load（同路径不能被两个 CL 加载）；
 *   · 本仓库帧→Mat（images.toMat）的字节序实测是 **RGBA**（TflRunner 默认即 RGBA）。
 */
"use strict";

function create(deps) {
    const d = deps || {};
    const baseDir = String(d.baseDir == null ? "." : d.baseDir);
    const log = typeof d.log === "function" ? d.log : function () { };
    /** 可用显式覆盖（测试/迁移用），默认 <工程>/yolo。 */
    const assetRoot = String(d.assetRoot == null ? (baseDir + "/yolo") : d.assetRoot);
    const threads = d.threads == null ? 4 : Number(d.threads);

    let runner = null;
    let failed = false;

    function assetPath(relative) {
        return assetRoot + "/" + relative;
    }

    /** 懒加载：.so → 私有目录独立子目录 → DexClassLoader(TflRunner) → init 模型。失败返回 null（只记一次日志）。 */
    function ensureServer() {
        if (runner) {
            return runner;
        }
        if (failed) {
            return null;
        }
        try {
            const jniSrc = assetPath("lib/arm64-v8a/libtensorflowlite_jni.so");
            const modelPath = assetPath("model/yolov5n_xq_fp16.tflite");
            const dexPath = assetPath("dex/tflite_classes_fen_v2.dex");
            const ctx = context;
            const optDir = ctx.getDir("tfl_opt", android.app.Activity.MODE_PRIVATE).getAbsolutePath();
            const libDir = ctx.getDir("tfl_libs", android.app.Activity.MODE_PRIVATE);
            const jniDir = libDir.getAbsolutePath() + "/" + (new Date() - 0);
            new java.io.File(jniDir).mkdirs();
            files.copy(jniSrc, jniDir + "/libtensorflowlite_jni.so");
            const dcl = new Packages.dalvik.system.DexClassLoader(
                dexPath, optDir, jniDir, ctx.getClassLoader());
            runner = dcl.loadClass("com.xq.TflRunner").newInstance();
            runner.init(modelPath, jniDir, threads);
            log("YOLO 识别源就绪（TflRunner，" + threads + " 线程）");
            return runner;
        } catch (error) {
            failed = true;
            log("YOLO 识别源不可用：" + error);
            return null;
        }
    }

    /** FEN（行0=屏幕顶行）→ 90 格数组；索引 = row*9+col；空 = null。 */
    function fenToChars(fen) {
        const out = new Array(90);
        for (let i = 0; i < 90; i++) {
            out[i] = null;
        }
        const rows = String(fen).split("/");
        for (let r = 0; r < 10 && r < rows.length; r++) {
            const s = rows[r];
            let col = 0;
            for (let k = 0; k < s.length; k++) {
                const ch = s.charAt(k);
                if (ch >= "1" && ch <= "9") {
                    col += parseInt(ch, 10);
                    continue;
                }
                if (ch === ".") {
                    col++;
                    continue;
                }
                if (col < 9) {
                    out[r * 9 + col] = ch;
                }
                col++;
            }
        }
        return out;
    }

    /**
     * 读一帧（image = 宿主帧对象；QuickJS 走 images.toMat，Rhino 帧自带 .mat）。
     * 返回 `{ok, fen, chars[90], flipped, n, box{x,y,x2,y2}, geo{x0,y0,sx,sy}, ms, reason?}`。
     */
    function read(image, conf, iou) {
        const r = ensureServer();
        if (!r) {
            return { ok: false, reason: "YOLO 引擎未就绪" };
        }
        if (!image) {
            return { ok: false, reason: "YOLO 读帧：没有图像" };
        }
        let mat = null;
        let borrowed = false;
        try {
            if (image.mat) {
                mat = image.mat;
                borrowed = true;
            } else {
                mat = images.toMat(image);
            }
            const started = Date.now();
            const json = r.recognizeFenJson(mat, conf == null ? 0.45 : Number(conf),
                iou == null ? 0.45 : Number(iou));
            const out = JSON.parse(json);
            out.ms = Date.now() - started;
            out.chars = (out.ok && out.fen) ? fenToChars(out.fen) : null;
            out.box = { x: out.bx, y: out.by, x2: out.bx2, y2: out.by2 };
            out.geo = { x0: out.gx0, y0: out.gy0, sx: out.gsx, sy: out.gsy };
            return out;
        } catch (error) {
            return { ok: false, reason: "YOLO 读帧异常：" + error };
        } finally {
            if (mat && !borrowed) {
                try {
                    mat.release();
                } catch (error) {
                    // Mat 释放失败不影响主流程
                }
            }
        }
    }

    return {
        ensureServer: ensureServer,
        fenToChars: fenToChars,
        read: read,
        assetPath: assetPath,
        /** 供日志/探针：资产根目录。 */
        assetRoot: assetRoot
    };
}

module.exports = { create: create };
