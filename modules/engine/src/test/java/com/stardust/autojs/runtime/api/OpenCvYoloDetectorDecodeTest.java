package com.stardust.autojs.runtime.api;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * {@link OpenCvYoloDetector} 的端到端输出解码：纯数据逻辑，不需要 OpenCV native（可在 JVM 单测跑）。
 *
 * 覆盖 2026-09-22 新增的 Pose 支持：
 *   · 检测（cols=6）与姿态（cols=6+3K）两种行宽的打包格式 v2；
 *   · letterbox 反映射（去 pad → 除以 scale → clamp）；
 *   · detectRgba 的 region 平移（跨 stride，含关键点；v1 旧数组不误判）。
 */
public class OpenCvYoloDetectorDecodeTest {

    @Test
    public void detectRowsPackWithStrideSix() {
        // rows=2：第一条过阈值、第二条 0.10 < 0.25 被过滤
        float[] raw = {
                100, 50, 200, 150, 0.90f, 0,
                10, 10, 20, 20, 0.10f, 3
        };
        float[] packed = OpenCvYoloDetector.decode(raw, 2, 6, 1000, 1000,
                1f, 0, 0, 0.25f, 1.5f, 2.5f);
        assertEquals(3 + 6, packed.length);
        assertEquals(1.5f, packed[0], 0f);
        assertEquals(2.5f, packed[1], 0f);
        assertEquals(6f, packed[2], 0f);
        assertEquals(100f, packed[3], 0f);
        assertEquals(0.90f, packed[7], 0f);
        assertEquals(0f, packed[8], 0f);
    }

    @Test
    public void poseRowsCarryKeypointsWithStrideFiftySeven() {
        // cols = 6 + 3*17 = 57（YOLO26n-pose @COCO）；只填前两个关键点，其余为零
        float[] row = new float[57];
        row[0] = 10; row[1] = 20; row[2] = 110; row[3] = 220; row[4] = 0.8f; row[5] = 0;
        row[6] = 30; row[7] = 40; row[8] = 0.7f;     // kpt1
        row[9] = 50; row[10] = 60; row[11] = 0.6f;   // kpt2
        float[] packed = OpenCvYoloDetector.decode(row, 1, 57, 1000, 1000,
                1f, 0, 0, 0.25f, 1f, 2f);
        assertEquals(3 + 57, packed.length);
        assertEquals(57f, packed[2], 0f);
        assertEquals(10f, packed[3], 0f);
        assertEquals(30f, packed[3 + 6], 0f);
        assertEquals(40f, packed[3 + 7], 0f);
        assertEquals(0.7f, packed[3 + 8], 0f);
        assertEquals(50f, packed[3 + 9], 0f);
        // 最后一个关键点的槽位（k=17 的第 3 个分量）存在且为零
        assertEquals(0f, packed[3 + 6 + 16 * 3 + 2], 0f);
    }

    @Test
    public void letterboxMappingUnpadsAndRescales() {
        // scale=0.5、pad=(40,30)：模型坐标 100 → (100-40)/0.5 = 120
        float[] raw = {100, 130, 200, 230, 0.9f, 7, 60, 130, 0.5f, 0, 0, 0};   // cols=6+3*2
        float[] packed = OpenCvYoloDetector.decode(raw, 1, 12, 500, 400,
                0.5f, 40, 30, 0.25f, 0f, 0f);
        assertEquals(3 + 12, packed.length);
        assertEquals(120f, packed[3], 0f);
        assertEquals(200f, packed[4], 0f);
        assertEquals(320f, packed[5], 0f);
        assertEquals(400f, packed[6], 0f);
        assertEquals(40f, packed[3 + 6], 0f);     // (60-40)/0.5
        assertEquals(200f, packed[3 + 7], 0f);    // (130-30)/0.5
        assertEquals(0.5f, packed[3 + 8], 0f);    // 关键点置信度原样
    }

    @Test
    public void regionShiftMovesBoxesAndKeypointsV2() {
        float[] row = new float[12];
        row[0] = 1; row[1] = 2; row[2] = 3; row[3] = 4; row[4] = 0.9f; row[5] = 0;
        row[6] = 5; row[7] = 6; row[8] = 0.5f;
        row[9] = 7; row[10] = 8; row[11] = 0.4f;
        float[] packed = OpenCvYoloDetector.decode(row, 1, 12, 100, 100, 1f, 0, 0, 0.25f, 0f, 0f);
        OpenCvYoloDetector.shiftToRegion(packed, 1000, 2000);
        assertEquals(1001f, packed[3], 0f);
        assertEquals(2002f, packed[4], 0f);
        assertEquals(1003f, packed[5], 0f);
        assertEquals(2004f, packed[6], 0f);
        assertEquals(1005f, packed[3 + 6], 0f);    // 关键点 x 也要平移
        assertEquals(2006f, packed[3 + 7], 0f);
        assertEquals(1007f, packed[3 + 9], 0f);
        assertEquals(2008f, packed[3 + 10], 0f);
        assertEquals(0.5f, packed[3 + 8], 0f);     // 置信度不动
    }

    @Test
    public void regionShiftSupportsLegacyStrideSix() {
        // v1：头只有两个字段；packed[2] 是首个 x1（=10，也可能落在 6..306），不能被当成行宽
        float[] packed = {1f, 2f, 10, 20, 30, 40, 0.9f, 0};
        OpenCvYoloDetector.shiftToRegion(packed, 5, 7);
        assertEquals(15f, packed[2], 0f);
        assertEquals(27f, packed[3], 0f);
        assertEquals(35f, packed[4], 0f);
        assertEquals(47f, packed[5], 0f);
    }
}
