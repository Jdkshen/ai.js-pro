package com.xq;

import org.tensorflow.lite.Interpreter;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * YOLOv5 象棋检测的 TFLite 推理封装（整条流水线收进 Java）。
 *
 * <p>为什么要有它（而不是像 AutoX 版那样全在脚本里做）：QuickJS 桥不支持
 * {@code Array.newInstance(Float.TYPE, n)}（拿不到可写的 float[]）、
 * {@code Class.getConstructor(Class...)}（构造不出 Interpreter）这类用法。
 * 把 预处理 / 推理 / 解码 全部收进 Java 后，脚本侧只需要：
 * <pre>
 *   var R = dcl.loadClass("com.xq.TflRunner").newInstance();
 *   R.init(modelPath, jniDir, 4);
 *   var mat = images.toMat(frame);            // QuickJS；Rhino 用 img.mat
 *   var json = R.recognizeFenJson(mat, 0.45, 0.45);
 * </pre>
 * 出入参全用基本类型 / String，跨 Rhino、QuickJS 都没有桥的坑。
 *
 * <p>预处理与 AutoX 版脚本逐项对齐：letterbox 到 640×640（灰 114 填充、居中、
 * 双线性），BGRA/RGBA 字节序可通过 {@link #setBgra(boolean)} 切换（默认 BGRA，
 * 与本仓库 QuickJS 帧→Mat 桥的输出一致）。
 */
public final class TflRunner {

    private static final int IN_W = 640;
    private static final int IN_H = 640;
    private static final int IN_LEN = IN_W * IN_H * 3;
    private static final int OUT_ROWS = 25200;
    private static final int OUT_STRIDE = 20;

    private Interpreter mInterpreter;
    private ByteBuffer mIn;
    private ByteBuffer mOut;
    private float[] mInArr;
    private float[] mOutArr;
    private int mX0 = 0;
    private int mY0 = 0;
    private double mScale = 1.0;
    /** 帧 Mat 的字节序：false = RGBA（默认，与本仓库 QuickJS 帧→Mat 桥实测一致）；true = BGRA。 */
    private boolean mBgra = false;
    private long mPreMs = 0;
    private long mInferMs = 0;

    public TflRunner() {
    }

    /** 帧 Mat 的字节序：true = BGRA，false（默认）= RGBA。 */
    public void setBgra(boolean bgra) {
        mBgra = bgra;
    }

    /** 首次 init 会加载 JNI 与模型（约 1~2 秒），重复调用直接忽略。 */
    public void init(String modelPath, String jniDir, int threads) throws Exception {
        if (mInterpreter != null) {
            return;
        }
        System.load(new File(jniDir, "libtensorflowlite_jni.so").getAbsolutePath());
        Interpreter.Options options = new Interpreter.Options();
        options.setNumThreads(threads <= 0 ? 4 : threads);
        mInterpreter = new Interpreter(new File(modelPath), options);
        mInArr = new float[IN_LEN];
        mOutArr = new float[OUT_ROWS * OUT_STRIDE];
        mIn = ByteBuffer.allocateDirect(mInArr.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        mOut = ByteBuffer.allocateDirect(mOutArr.length * 4).order(ByteOrder.LITTLE_ENDIAN);
    }

    /**
     * 一步到位：Mat → FEN（JSON 字符串）。
     * 返回字段：ok / fen / reason / flipped / n / gx0,gy0,gsx,gsy（格心网格）/
     * bx,by,bx2,by2（棋盘框）/ preMs / inferMs。
     */
    public String recognizeFenJson(org.opencv.core.Mat rgba, double conf, double iou) {
        if (mInterpreter == null) {
            throw new IllegalStateException("TflRunner 未初始化：先调 init(modelPath, jniDir, threads)");
        }
        float[] raw = run(rgba);
        ChessFen.Result r = new ChessFen().decodeAndCompute(raw, mX0, mY0, mScale, conf, iou);
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"ok\":").append(r.ok)
                .append(",\"fen\":\"").append(esc(r.fen)).append('"')
                .append(",\"reason\":\"").append(esc(r.reason)).append('"')
                .append(",\"flipped\":").append(r.flipped)
                .append(",\"n\":").append(r.n)
                .append(",\"gx0\":").append(r.gx0).append(",\"gy0\":").append(r.gy0)
                .append(",\"gsx\":").append(r.gsx).append(",\"gsy\":").append(r.gsy)
                .append(",\"bx\":").append(r.bx).append(",\"by\":").append(r.by)
                .append(",\"bx2\":").append(r.bx2).append(",\"by2\":").append(r.by2)
                .append(",\"preMs\":").append(mPreMs)
                .append(",\"inferMs\":").append(mInferMs)
                .append('}');
        return sb.toString();
    }

    /** 只要检测框：JSON {"inferMs":..,"dets":[[x1,y1,x2,y2,score,cls],...]}。 */
    public String detectJson(org.opencv.core.Mat rgba, double conf, double iou) {
        if (mInterpreter == null) {
            throw new IllegalStateException("TflRunner 未初始化：先调 init(modelPath, jniDir, threads)");
        }
        float[] raw = run(rgba);
        float[] dets = new YoloDecode().decode(raw, mX0, mY0, mScale, conf, iou);
        StringBuilder sb = new StringBuilder(64 + dets.length * 14);
        sb.append("{\"inferMs\":").append(mInferMs).append(",\"dets\":[");
        for (int i = 0; i + 5 < dets.length; i += 6) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('[').append(Math.round(dets[i])).append(',').append(Math.round(dets[i + 1]))
                    .append(',').append(Math.round(dets[i + 2])).append(',').append(Math.round(dets[i + 3]))
                    .append(',').append(dets[i + 4]).append(',').append((int) dets[i + 5]).append(']');
        }
        sb.append("]}");
        return sb.toString();
    }

    private float[] run(org.opencv.core.Mat rgba) {
        long t0 = System.currentTimeMillis();
        prepare(rgba);
        long t1 = System.currentTimeMillis();
        mIn.rewind();
        mIn.asFloatBuffer().put(mInArr);
        mIn.rewind();
        mOut.rewind();
        mInterpreter.run(mIn, mOut);
        mOut.rewind();
        mOut.asFloatBuffer().get(mOutArr);
        long t2 = System.currentTimeMillis();
        mPreMs = t1 - t0;
        mInferMs = t2 - t1;
        return mOutArr;
    }

    /** letterbox 到 640×640（灰 114 填充、居中、双线性），并记录反变换几何。 */
    private void prepare(org.opencv.core.Mat rgba) {
        int w = rgba.cols();
        int h = rgba.rows();
        if (w <= 0 || h <= 0) {
            throw new IllegalArgumentException("空图像");
        }
        byte[] src = new byte[w * h * 4];
        rgba.get(0, 0, src);

        double scale = Math.min((double) IN_W / w, (double) IN_H / h);
        int nw = (int) Math.round(w * scale);
        int nh = (int) Math.round(h * scale);
        int x0 = (int) Math.round((IN_W - nw) / 2.0);
        int y0 = (int) Math.round((IN_H - nh) / 2.0);
        mX0 = x0;
        mY0 = y0;
        mScale = scale;

        float bg = 114f / 255f;
        for (int i = 0; i < IN_LEN; i++) {
            mInArr[i] = bg;
        }
        int redAt = mBgra ? 2 : 0;
        int blueAt = mBgra ? 0 : 2;
        for (int py = 0; py < nh; py++) {
            double fy = (py + 0.5) * h / nh - 0.5;
            if (fy < 0) {
                fy = 0;
            }
            if (fy > h - 1) {
                fy = h - 1;
            }
            int y1 = (int) fy;
            int y2 = y1 + 1 < h ? y1 + 1 : y1;
            double wy = fy - y1;
            int rowBase = (py + y0) * IN_W;
            for (int px = 0; px < nw; px++) {
                double fx = (px + 0.5) * w / nw - 0.5;
                if (fx < 0) {
                    fx = 0;
                }
                if (fx > w - 1) {
                    fx = w - 1;
                }
                int x1 = (int) fx;
                int x2 = x1 + 1 < w ? x1 + 1 : x1;
                double wx = fx - x1;
                int i11 = (y1 * w + x1) * 4;
                int i12 = (y1 * w + x2) * 4;
                int i21 = (y2 * w + x1) * 4;
                int i22 = (y2 * w + x2) * 4;
                double w11 = (1 - wx) * (1 - wy);
                double w12 = wx * (1 - wy);
                double w21 = (1 - wx) * wy;
                double w22 = wx * wy;
                int out = (rowBase + x0 + px) * 3;
                mInArr[out] = (float) (((src[i11 + redAt] & 255) * w11 + (src[i12 + redAt] & 255) * w12
                        + (src[i21 + redAt] & 255) * w21 + (src[i22 + redAt] & 255) * w22) / 255.0);
                mInArr[out + 1] = (float) (((src[i11 + 1] & 255) * w11 + (src[i12 + 1] & 255) * w12
                        + (src[i21 + 1] & 255) * w21 + (src[i22 + 1] & 255) * w22) / 255.0);
                mInArr[out + 2] = (float) (((src[i11 + blueAt] & 255) * w11 + (src[i12 + blueAt] & 255) * w12
                        + (src[i21 + blueAt] & 255) * w21 + (src[i22 + blueAt] & 255) * w22) / 255.0);
            }
        }
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\').append(c);
            } else if (c < 0x20) {
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
