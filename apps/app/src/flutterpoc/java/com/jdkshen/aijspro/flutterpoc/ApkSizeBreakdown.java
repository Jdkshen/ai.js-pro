package com.jdkshen.aijspro.flutterpoc;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * APK「大小构成」的**只读**统计（task-96）。
 *
 * <p>纯 JDK（{@link ZipFile}），不依赖 Android / 不打日志 / 不写任何文件 —— 所以它可以被 JVM 单测直接跑，
 * 也可以在一台机器的真实产物上复算。数据源就是产物本身（等价于 unzip -l 聚合），**没有任何写死的估算**。</p>
 *
 * <p>口径（重要）：这里统计的是 **未压缩大小**（{@code ZipEntry.getSize()}，即 unzip -l 那一列），
 * 各桶相加 **不等于 APK 文件大小**（后者是压缩后的）；两者都返回，避免读者误判。</p>
 *
 * <p>分桶规则（可复核、可测试）：</p>
 * <ul>
 *   <li>{@link #BUCKET_PROJECT} 你的工程：{@code assets/project/} 与 {@code assets/sjbc/}（打包时从工程拷进去的那些）；</li>
 *   <li>{@link #BUCKET_OPENCV} OpenCV：条目名含 {@code opencv}；</li>
 *   <li>{@link #BUCKET_OCR} 内建 OCR：条目名含 {@code mlkit} 或 {@code ppocr}；</li>
 *   <li>{@link #BUCKET_ENGINE} 引擎与模板：其余全部（dex、res、其余 lib、META-INF…）。</li>
 * </ul>
 */
public final class ApkSizeBreakdown {

    public static final String BUCKET_ENGINE = "engine";
    public static final String BUCKET_OCR = "ocr";
    public static final String BUCKET_OPENCV = "opencv";
    public static final String BUCKET_PROJECT = "project";

    /** 展示顺序（也是 JSON 里的顺序）。 */
    public static final String[] BUCKET_ORDER = {BUCKET_ENGINE, BUCKET_OCR, BUCKET_OPENCV, BUCKET_PROJECT};

    private ApkSizeBreakdown() {
    }

    /** 一条 zip 条目（只留统计需要的字段）。 */
    public static final class Entry {
        public final String name;
        public final long uncompressedBytes;
        public final long compressedBytes;

        public Entry(String name, long uncompressedBytes, long compressedBytes) {
            this.name = name;
            this.uncompressedBytes = uncompressedBytes;
            this.compressedBytes = compressedBytes;
        }
    }

    /** 一个桶的汇总。 */
    public static final class Bucket {
        public final String key;
        public long uncompressedBytes;
        public long compressedBytes;
        public int entries;
        /** size 未知（-1，例如流式写入的条目）的条目数；这些条目按 0 计。 */
        public int unknownSizes;

        Bucket(String key) {
            this.key = key;
        }
    }

    public static final class Summary {
        public final Map<String, Bucket> buckets = new LinkedHashMap<>();
        public int entries;
        public long uncompressedBytes;
        public long compressedBytes;
        public int unknownSizes;
    }

    /** 条目名 → 桶名（唯一判据来源，UI/测试共用；不要在多处复制这套规则）。 */
    public static String bucketOf(String entryName) {
        String name = entryName == null ? "" : entryName.replace('\\', '/');
        if (name.startsWith("assets/project/") || name.startsWith("assets/sjbc/")) return BUCKET_PROJECT;
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("opencv")) return BUCKET_OPENCV;
        if (lower.contains("mlkit") || lower.contains("ppocr")) return BUCKET_OCR;
        return BUCKET_ENGINE;
    }

    /** 读 APK 的条目清单（只读打开；不存在的文件抛 IOException 由调用方处理）。 */
    public static List<Entry> read(File apk) throws IOException {
        List<Entry> entries = new ArrayList<>();
        try (ZipFile zip = new ZipFile(apk)) {
            java.util.Enumeration<? extends ZipEntry> iterator = zip.entries();
            while (iterator.hasMoreElements()) {
                ZipEntry entry = iterator.nextElement();
                if (entry.isDirectory()) continue;
                entries.add(new Entry(entry.getName(), entry.getSize(), entry.getCompressedSize()));
            }
        }
        return entries;
    }

    /** 按桶汇总（纯函数：给定条目清单 → 汇总，便于单测）。 */
    public static Summary summarize(List<Entry> entries) {
        Summary summary = new Summary();
        for (String key : BUCKET_ORDER) {
            Bucket bucket = new Bucket(key);
            summary.buckets.put(key, bucket);
        }
        for (Entry entry : entries) {
            Bucket bucket = summary.buckets.get(bucketOf(entry.name));
            long size = entry.uncompressedBytes;
            long compressed = entry.compressedBytes;
            if (size < 0) {
                bucket.unknownSizes += 1;
                summary.unknownSizes += 1;
                size = 0;
            }
            if (compressed < 0) compressed = 0;
            bucket.uncompressedBytes += size;
            bucket.compressedBytes += compressed;
            bucket.entries += 1;
            summary.entries += 1;
            summary.uncompressedBytes += size;
            summary.compressedBytes += compressed;
        }
        return summary;
    }

    /** MiB（1 位小数），只用于展示；不做四舍五入以外的加工。 */
    public static double toMiB(long bytes) {
        return Math.round(bytes / (1024.0 * 1024.0) * 10.0) / 10.0;
    }
}
