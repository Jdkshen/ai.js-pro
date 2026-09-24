package com.xq;

/**
 * YOLO 检测解码 + NMS（2026-09-10 重建版）
 *
 * 模型原始输出 [25200, 20] -> 检测框 [x1, y1, x2, y2, score, cls] × N（原图坐标）
 * 行布局: [cx, cy, w, h, obj, 15个类别分数]
 *
 * 注意: 原反编译源码与 dex 字节码不等价（会产生重复框），
 * 本文件按原始 dex 字节码逐条重建，并已与原始 dex 在相同输入下做逐位输出对比验证。
 */
public final class YoloDecode {
    public static final int BOARD_CLS = 14;
    public static final int NUM_CLASSES = 15;
    public static final int STRIDE = 20;

    /**
     * @param fArr  模型输出 [25200*20], 每20个为一组
     * @param x0    letterbox 填充偏移x（原坐标）
     * @param y0    letterbox 填充偏移y
     * @param scale letterbox 缩放比例
     * @param conf  置信度阈值
     * @param iouThresh NMS 的 IoU 阈值
     * @return [x1,y1,x2,y2,score,cls] × N
     */
    public float[] decode(float[] fArr, double x0, double y0, double scale, double conf, double iouThresh) {
        final int length = fArr.length / STRIDE;
        final int CAP = 2048;
        double[] x1s = new double[CAP], y1s = new double[CAP];
        double[] x2s = new double[CAP], y2s = new double[CAP];
        double[] scores = new double[CAP];
        int[] clss = new int[CAP];
        int n = 0;

        // ---------- 1) 置信度过滤 + 类别argmax + 反letterbox坐标 ----------
        for (int i = 0; i < length && n < CAP; i++) {
            final int o = i * STRIDE;
            final float obj = fArr[o + 4];
            if ((double) obj < conf) continue;
            float bestScore = 0.0f;
            int bestCls = -1;
            for (int c = 0; c < NUM_CLASSES; c++) {
                float s = fArr[o + 5 + c] * obj;
                if (s > bestScore) { bestScore = s; bestCls = c; }
            }
            if (bestCls < 0) continue;
            final double score = (double) bestScore;
            if (score < conf) continue;
            final double cx = ((double) fArr[o]     - x0) / scale;
            final double cy = ((double) fArr[o + 1] - y0) / scale;
            final double hw = ((double) fArr[o + 2] / scale) / 2.0;
            final double hh = ((double) fArr[o + 3] / scale) / 2.0;
            x1s[n] = cx - hw;
            y1s[n] = cy - hh;
            x2s[n] = cx + hw;
            y2s[n] = cy + hh;
            scores[n] = score;
            clss[n] = bestCls;
            n++;
        }

        // ---------- 2) 贪心 NMS（按类别, 分数从高到低逐个遴选） ----------
        boolean[] removed = new boolean[n];
        int[] keep = new int[n];
        int m = 0;
        for (; ; ) {
            double bestS = -1.0;
            int best = -1;
            for (int i = 0; i < n; i++) {
                if (!removed[i] && scores[i] > bestS) { best = i; bestS = scores[i]; }
            }
            if (best < 0) break;
            removed[best] = true;
            int out = m + 1;
            keep[m] = best;
            for (int j = 0; j < n; j++) {
                if (removed[j] || clss[j] != clss[best]) continue;
                final double inter = Math.max(0.0, Math.min(x2s[best], x2s[j]) - Math.max(x1s[best], x1s[j]))
                                   * Math.max(0.0, Math.min(y2s[best], y2s[j]) - Math.max(y1s[best], y1s[j]));
                final double union = (x2s[best] - x1s[best]) * (y2s[best] - y1s[best])
                                   + (x2s[j] - x1s[j]) * (y2s[j] - y1s[j]) - inter;
                if (union <= 0.0) continue;
                if (inter / union > iouThresh) removed[j] = true;
            }
            m = out;
        }

        // ---------- 3) 按遴选顺序输出 ----------
        float[] out = new float[m * 6];
        for (int i = 0; i < m; i++) {
            final int k = keep[i];
            out[i * 6]     = (float) x1s[k];
            out[i * 6 + 1] = (float) y1s[k];
            out[i * 6 + 2] = (float) x2s[k];
            out[i * 6 + 3] = (float) y2s[k];
            out[i * 6 + 4] = (float) scores[k];
            out[i * 6 + 5] = (float) clss[k];
        }
        return out;
    }
}
