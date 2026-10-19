package com.jdkshen.aijspro.model.sample;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * B1 批次3：锁定 {@link SampleRepository} 的枚举/路径/读取语义。
 *
 * <p>遍历、截断（>1000 条）、深层目录不爆栈这三类回归在示例页历史上都出现过，
 * 因此这里用注入的假资产树做纯 JVM 验证（不需要设备）。
 */
public class SampleRepositoryTest {

    private static SampleRepository.AssetLister listerOf(Map<String, String[]> tree, List<String> calls) {
        return path -> {
            calls.add(path);
            return tree.get(path);
        };
    }

    /** 与示例页测试同一棵假树（含目录、大小写混排、深层文件）。 */
    private static Map<String, String[]> sampleTree() {
        Map<String, String[]> tree = new HashMap<>();
        tree.put("sample", new String[]{"foo", "README"});
        tree.put("sample/foo", new String[]{"nested", "run.JS"});
        tree.put("sample/foo/nested", new String[]{"main.js", "image.png", "config.json"});
        return tree;
    }

    @Test
    public void listAllTraversesWholeTreeListingEachNodeOnce() {
        List<String> calls = new ArrayList<>();
        List<SampleRepository.Node> nodes = SampleRepository.listAll(listerOf(sampleTree(), calls));

        assertEquals(7, nodes.size());
        assertEquals(new java.util.HashSet<>(Arrays.asList(
                "sample/foo", "sample/README", "sample/foo/nested", "sample/foo/run.JS",
                "sample/foo/nested/main.js", "sample/foo/nested/image.png", "sample/foo/nested/config.json"
        )), new java.util.HashSet<>(nodes.stream().map(SampleRepository.Node::getPath)
                .collect(Collectors.toList())));

        assertEquals(new java.util.HashSet<>(Arrays.asList("sample/foo", "sample/foo/nested")),
                new java.util.HashSet<>(nodes.stream().filter(SampleRepository.Node::isDirectory)
                        .map(SampleRepository.Node::getPath).collect(Collectors.toList())));

        // 每个节点恰好 list 一次 + 根一次，且互不重复
        assertEquals(nodes.size() + 1, calls.size());
        assertEquals(calls.size(), new java.util.HashSet<>(calls).size());
    }

    @Test
    public void listAllHandlesDeepTreeWithoutStackOverflow() {
        SampleRepository.AssetLister lister = path -> {
            if (path.endsWith("main.js")) {
                return null;
            }
            int depth = 2000;
            long slashes = path.chars().filter(ch -> ch == '/').count();
            return slashes < depth ? new String[]{"d"} : new String[]{"main.js"};
        };
        List<SampleRepository.Node> nodes = SampleRepository.listAll(lister);
        assertEquals(2001, nodes.size());
    }

    @Test
    public void listSortsSingleLevelByNameAndDetectsDirectories() {
        Map<String, String[]> tree = new HashMap<>();
        tree.put("sample", new String[]{"b.txt", "a.js", "dir"});
        tree.put("sample/dir", new String[]{"x.js"});

        List<String> names = new ArrayList<>();
        List<SampleRepository.Node> nodes =
                SampleRepository.list(listerOf(tree, new ArrayList<>()), "sample");
        for (SampleRepository.Node node : nodes) {
            names.add(node.getName());
        }
        assertEquals(Arrays.asList("a.js", "b.txt", "dir"), names);
        assertFalse(nodes.get(0).isDirectory());
        assertTrue(nodes.get(2).isDirectory());
        assertEquals("sample/dir", nodes.get(2).getPath());

        assertTrue(SampleRepository.list(listerOf(tree, new ArrayList<>()), "sample/missing").isEmpty());
    }

    @Test
    public void sanitizePathAcceptsSampleTreeAndRejectsEscapes() {
        assertEquals("sample", SampleRepository.sanitizePath("sample"));
        assertEquals("sample", SampleRepository.sanitizePath("/sample/"));
        assertEquals("sample/a/b.js", SampleRepository.sanitizePath("sample/a/b.js"));
        assertEquals("sample/a/b.js", SampleRepository.sanitizePath("sample\\a\\b.js"));
        assertEquals("sample/a", SampleRepository.sanitizePath("  sample/a  "));

        assertRejected("", "只能访问 sample 目录");
        assertRejected("assets/x", "只能访问 sample 目录");
        assertRejected("samples/x", "只能访问 sample 目录");
        assertRejected("sample/../x", "路径无效");
        assertRejected("sample/a/./b", "路径无效");
        assertRejected("sample//a", "路径无效");
    }

    private static void assertRejected(String path, String expectedMessage) {
        try {
            SampleRepository.sanitizePath(path);
            fail("应拒绝: " + path);
        } catch (IllegalArgumentException error) {
            assertEquals(expectedMessage, error.getMessage());
        }
    }

    @Test
    public void readBytesReadsWholeAssetAndPropagatesOpenFailure() throws Exception {
        byte[] payload = "hello 示例".getBytes(StandardCharsets.UTF_8);
        SampleRepository.AssetOpener opener = new SampleRepository.AssetOpener() {
            @Override
            public InputStream open(String path) throws IOException {
                if ("sample/hello.js".equals(path)) {
                    return new ByteArrayInputStream(payload);
                }
                throw new IOException("not found: " + path);
            }
        };

        assertTrue(Arrays.equals(payload, SampleRepository.readBytes(opener, "sample/hello.js")));
        try {
            SampleRepository.readBytes(opener, "sample/missing.js");
            fail("缺失文件应抛 IOException");
        } catch (IOException expected) {
            // 调用方（MCP）据此翻译成「示例不存在」
        }
    }
}
