package com.jdkshen.aijspro.flutterpoc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * task-96：APK 大小构成统计的 JVM 单测（纯 JDK，不需要 Android）。
 *
 * <p>覆盖：分桶规则（唯一判据 bucketOf）、汇总求和、size 未知(-1) 的处理、空清单，
 * 以及**在真实产物上复算**（若 .artifacts/updates 下有 APK；没有就 skip，不假装跑过）。</p>
 */
public class ApkSizeBreakdownTest {

    private static final long MIB = 1024L * 1024L;

    private static ApkSizeBreakdown.Entry entry(String name, long size) {
        return new ApkSizeBreakdown.Entry(name, size, size / 2);
    }

    @Test
    public void bucketRulesAreTheSingleSourceOfTruth() {
        assertEquals(ApkSizeBreakdown.BUCKET_PROJECT, ApkSizeBreakdown.bucketOf("assets/project/main.js"));
        assertEquals(ApkSizeBreakdown.BUCKET_PROJECT, ApkSizeBreakdown.bucketOf("assets/sjbc/module.sjbc"));
        assertEquals(ApkSizeBreakdown.BUCKET_OPENCV, ApkSizeBreakdown.bucketOf("lib/arm64-v8a/libopencv_java5.so"));
        assertEquals(ApkSizeBreakdown.BUCKET_OPENCV, ApkSizeBreakdown.bucketOf("assets/opencv/features.dat"));
        assertEquals(ApkSizeBreakdown.BUCKET_OCR, ApkSizeBreakdown.bucketOf("lib/arm64-v8a/libmlkit_google_ocr_pipeline.so"));
        assertEquals(ApkSizeBreakdown.BUCKET_OCR, ApkSizeBreakdown.bucketOf("lib/arm64-v8a/libppocr.so"));
        assertEquals(ApkSizeBreakdown.BUCKET_ENGINE, ApkSizeBreakdown.bucketOf("classes.dex"));
        assertEquals(ApkSizeBreakdown.BUCKET_ENGINE, ApkSizeBreakdown.bucketOf("res/layout/a.xml"));
        assertEquals(ApkSizeBreakdown.BUCKET_ENGINE, ApkSizeBreakdown.bucketOf("org/antlr/runtime/Parser.class"));
        assertEquals(ApkSizeBreakdown.BUCKET_ENGINE, ApkSizeBreakdown.bucketOf(null));
    }

    @Test
    public void summarySumsAndOrderFollowTheDeclaredBuckets() {
        List<ApkSizeBreakdown.Entry> entries = Arrays.asList(
                entry("classes.dex", (long) (33.9 * MIB)),
                entry("res/layout/a.xml", 9 * MIB),
                entry("lib/arm64-v8a/libopencv_java5.so", 20 * MIB),
                entry("lib/arm64-v8a/libppocr.so", 8 * MIB),
                entry("assets/project/main.js", MIB));
        ApkSizeBreakdown.Summary summary = ApkSizeBreakdown.summarize(entries);
        assertEquals(5, summary.entries);
        assertEquals(Arrays.asList(ApkSizeBreakdown.BUCKET_ORDER), new ArrayList<>(summary.buckets.keySet()));
        assertEquals(20 * MIB, summary.buckets.get(ApkSizeBreakdown.BUCKET_OPENCV).uncompressedBytes);
        assertEquals(8 * MIB, summary.buckets.get(ApkSizeBreakdown.BUCKET_OCR).uncompressedBytes);
        assertEquals(MIB, summary.buckets.get(ApkSizeBreakdown.BUCKET_PROJECT).uncompressedBytes);
        long engine = summary.buckets.get(ApkSizeBreakdown.BUCKET_ENGINE).uncompressedBytes;
        assertEquals((long) (33.9 * MIB) + 9 * MIB, engine);
        assertEquals(20 * MIB + 8 * MIB + MIB + engine, summary.uncompressedBytes);
        assertEquals(20.0, ApkSizeBreakdown.toMiB(20 * MIB), 0.001);
    }

    @Test
    public void unknownSizesAreCountedNotGuessed() {
        List<ApkSizeBreakdown.Entry> entries = new ArrayList<>();
        entries.add(new ApkSizeBreakdown.Entry("classes.dex", -1L, -1L));
        entries.add(entry("assets/project/main.js", MIB));
        ApkSizeBreakdown.Summary summary = ApkSizeBreakdown.summarize(entries);
        assertEquals(2, summary.entries);
        assertEquals(1, summary.unknownSizes);
        assertEquals(1, summary.buckets.get(ApkSizeBreakdown.BUCKET_ENGINE).unknownSizes);
        assertEquals("未知大小按 0 计，不加猜测", MIB, summary.uncompressedBytes);
    }

    @Test
    public void emptyArtifactIsZeroNotError() {
        ApkSizeBreakdown.Summary summary = ApkSizeBreakdown.summarize(new ArrayList<>());
        assertEquals(0, summary.entries);
        assertEquals(0L, summary.uncompressedBytes);
        for (String key : ApkSizeBreakdown.BUCKET_ORDER) {
            assertEquals(0L, summary.buckets.get(key).uncompressedBytes);
        }
    }

    /** 真实产物复算（有就真跑，没有就 skip —— 不假装跑过）。 */
    @Test
    public void realArtifactAddsUp() throws Exception {
        File dir = new File(".artifacts/updates");
        if (!dir.isDirectory()) dir = new File("../../.artifacts/updates");
        File[] apks = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".apk"));
        assumeTrue("本机没有 .artifacts/updates/*.apk，跳过真实产物复算", apks != null && apks.length > 0);
        File apk = apks[apks.length - 1];
        ApkSizeBreakdown.Summary summary = ApkSizeBreakdown.summarize(ApkSizeBreakdown.read(apk));
        long sum = 0;
        StringBuilder line = new StringBuilder();
        for (String key : ApkSizeBreakdown.BUCKET_ORDER) {
            ApkSizeBreakdown.Bucket bucket = summary.buckets.get(key);
            sum += bucket.uncompressedBytes;
            line.append(key).append('=').append(ApkSizeBreakdown.toMiB(bucket.uncompressedBytes)).append("MB ");
        }
        assertEquals("各桶相加必须等于总量（同一份数据）", summary.uncompressedBytes, sum);
        assertTrue("真实产物必须有条目", summary.entries > 0);
        System.out.println("REAL " + apk.getName() + " apkBytes=" + apk.length()
                + " uncompressedMiB=" + ApkSizeBreakdown.toMiB(summary.uncompressedBytes) + " " + line);
    }
}
