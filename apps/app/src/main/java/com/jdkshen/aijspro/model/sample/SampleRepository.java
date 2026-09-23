package com.jdkshen.aijspro.model.sample;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.AbstractMap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 内置示例的**读取事实**门面（B1 批次3）：枚举示例树、规范化访问路径、读取正文。
 *
 * <p>此前「示例页」与「MCP list_samples/read_sample」各写一套 assets 枚举，
 * 「打开/读取」又有第三处（{@code SampleFile} / {@code ViewSampleActivity}）。
 * 本类收口枚举与读取；**落盘/导入**（materialize / importForEdit / copyAssetTree）仍由
 * {@link SampleFiles} 负责——它的职责是「把示例写到磁盘」，与读取事实是两件事。
 *
 * <p>依赖注入（{@link AssetLister} / {@link AssetOpener}）而非直接吃 {@code Context}：
 * 纯逻辑可以在 JVM 单测里用假树验证（历史上遍历/截断类回归就是这么抓到的）。
 */
public final class SampleRepository {

    /** assets 下的示例根目录（与 {@link SampleFiles#ASSET_ROOT} 一致）。 */
    public static final String ASSET_ROOT = "sample";

    /** 只列目录（返回 null 视作空/不存在）。 */
    public interface AssetLister {
        String[] list(String path);
    }

    /** 只打开文件（单一抽象方法，Kotlin 侧可直接用 lambda 适配）。 */
    public interface AssetOpener {
        InputStream open(String path) throws IOException;
    }

    /** 示例树中的一个节点。 */
    public static final class Node {
        private final String name;
        private final String path;
        private final boolean directory;

        Node(String name, String path, boolean directory) {
            this.name = name;
            this.path = path;
            this.directory = directory;
        }

        public String getName() {
            return name;
        }

        /** assets 全路径，如 {@code sample/foo/main.js}。 */
        public String getPath() {
            return path;
        }

        public boolean isDirectory() {
            return directory;
        }

        @Override
        public String toString() {
            return path + (directory ? "/" : "");
        }
    }

    private SampleRepository() {
    }

    /**
     * 遍历整棵示例树（迭代式，无递归深度限制；每个节点恰好 list 一次，根一次）。
     * 顺序与历史实现一致：栈式深度优先、同级从后往前。
     */
    public static List<Node> listAll(AssetLister lister) {
        List<Node> result = new ArrayList<>();
        if (lister == null) {
            return result;
        }
        ArrayDeque<Map.Entry<String, String[]>> pending = new ArrayDeque<>();
        pending.addLast(new AbstractMap.SimpleEntry<>(ASSET_ROOT, lister.list(ASSET_ROOT)));
        while (!pending.isEmpty()) {
            Map.Entry<String, String[]> current = pending.removeLast();
            String parent = current.getKey();
            String[] names = current.getValue();
            if (names == null) {
                continue;
            }
            for (String name : names) {
                String path = parent + "/" + name;
                String[] children = lister.list(path);
                // AssetManager 把文件也列成空数组；只有能列出内容的才是目录。
                boolean directory = children != null && children.length > 0;
                result.add(new Node(name, path, directory));
                if (directory) {
                    pending.addLast(new AbstractMap.SimpleEntry<>(path, children));
                }
            }
        }
        return result;
    }

    /** 列单层（按名字自然序，与 MCP list_samples 的历史输出一致）。 */
    public static List<Node> list(AssetLister lister, String assetPath) {
        List<Node> result = new ArrayList<>();
        if (lister == null) {
            return result;
        }
        String[] names = lister.list(assetPath);
        if (names == null || names.length == 0) {
            return result;
        }
        List<String> sorted = new ArrayList<>(Arrays.asList(names));
        Collections.sort(sorted);
        for (String name : sorted) {
            String child = assetPath + "/" + name;
            String[] children = lister.list(child);
            result.add(new Node(name, child, children != null && children.length > 0));
        }
        return result;
    }

    /**
     * 规范化并校验示例访问路径（MCP 的历史语义）：反斜杠归一、去首尾斜杠；
     * 必须就是 {@code sample} 或以 {@code sample/} 开头；拒绝空段与 {@code .}/{@code ..}。
     *
     * @throws IllegalArgumentException 路径越界或非法（消息可直接展示给调用方）
     */
    public static String sanitizePath(String path) {
        String clean = path == null ? "" : path.replace('\\', '/').trim();
        clean = trimSlashes(clean);
        if (!clean.equals(ASSET_ROOT) && !clean.startsWith(ASSET_ROOT + "/")) {
            throw new IllegalArgumentException("只能访问 sample 目录");
        }
        for (String part : clean.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                throw new IllegalArgumentException("路径无效");
            }
        }
        return clean;
    }

    /** 读取整个示例文件（调用方负责把 IOException 翻译成自己的错误语义）。 */
    public static byte[] readBytes(AssetOpener opener, String assetPath) throws IOException {
        try (InputStream input = opener.open(assetPath)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    /** 磁盘上的示例根目录（{@code files/sample}）——与 {@link SampleFiles#sampleRoot} 同一事实。 */
    public static File sampleRoot(File filesDir) {
        return new File(filesDir, ASSET_ROOT);
    }

    private static String trimSlashes(String path) {
        int start = 0;
        int end = path.length();
        while (start < end && path.charAt(start) == '/') {
            start++;
        }
        while (end > start && path.charAt(end - 1) == '/') {
            end--;
        }
        return path.substring(start, end);
    }
}
