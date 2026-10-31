package com.stardust.autojs.runtime.api.remote;

import com.stardust.autojs.runtime.exception.ScriptException;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

/**
 * R05/R06/R07 回归：快照完整性/分页/取消、真正的 BFS、节点 token/代次。
 *
 * <p>用纯 JVM 的 recording backend 模拟宿主节点树；不涉及 AccessibilityService/Binder。
 * 参考顺序用人工写死的期望列表，避免复用实现自身的排序逻辑。
 */
public class RemoteUiSnapshotPagingTest {

    private RemoteAutomationBridge.Backend backend;
    private Object previousBackend;
    private boolean previousIsolation;
    private JSONArray tree = new JSONArray();
    /** >=0 时模拟宿主 findNodes 的单次返回上限。 */
    private int findNodesLimit = -1;
    private final List<String> calls = new ArrayList<>();

    // ---------------------------------------------------------------- fixture

    private static JSONObject node(int[] path, String id, String text, int childCount) throws Exception {
        JSONArray jsonPath = new JSONArray();
        for (int value : path) { jsonPath.put(value); }
        return new JSONObject()
                .put("path", jsonPath)
                .put("id", id)
                .put("text", text)
                .put("desc", "")
                .put("className", "View")
                .put("packageName", "pkg")
                .put("bounds", new JSONArray("[0,0,10,10]"))
                .put("clickable", true)
                .put("enabled", true)
                .put("scrollable", false)
                .put("childCount", childCount)
                .put("indexInParent", path.length == 0 ? -1 : path[path.length - 1])
                .put("depth", path.length);
    }

    /**
     * 参考树（DFS 前序存储）：
     * <pre>
     * root
     *  ├─ a          (x0 子节点)
     *  │   ├─ a0  [text=x0]
     *  │   └─ a1
     *  │       └─ a10 [text=x10]
     *  └─ b
     *      └─ b0  [text=x0]
     * </pre>
     * DFS 顺序：root, a, a0, a1, a10, b, b0
     * BFS 顺序：root, a, b, a0, a1, b0, a10
     */
    private static JSONArray referenceTree() throws Exception {
        return new JSONArray()
                .put(node(new int[]{}, "root", "", 2))
                .put(node(new int[]{0}, "a", "", 2))
                .put(node(new int[]{0, 0}, "a0", "x0", 0))
                .put(node(new int[]{0, 1}, "a1", "", 1))
                .put(node(new int[]{0, 1, 0}, "a10", "x10", 0))
                .put(node(new int[]{1}, "b", "", 1))
                .put(node(new int[]{1, 0}, "b0", "x0", 0));
    }

    private static JSONArray flatTree(int count) throws Exception {
        JSONArray array = new JSONArray().put(node(new int[]{}, "root", "", count));
        for (int i = 0; i < count; i++) {
            array.put(node(new int[]{i}, "n" + i, "item", 0));
        }
        return array;
    }

    @Before public void setup() throws Exception {
        backend = (RemoteAutomationBridge.Backend) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{RemoteAutomationBridge.Backend.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    calls.add(name);
                    switch (name) {
                        case "isReady":
                        case "isAccessibilityServiceEnabled":
                            return true;
                        case "getLatestNodeDump":
                            return tree.toString();
                        case "findNodes": {
                            JSONObject selector = new JSONObject((String) args[0]);
                            List<JSONObject> matches = matches(selector);
                            JSONArray out = new JSONArray();
                            for (int i = 0; i < matches.size(); i++) {
                                if (findNodesLimit >= 0 && i >= findNodesLimit) { break; }
                                out.put(matches.get(i));
                            }
                            return out.toString();
                        }
                        case "getNodeInfo": {
                            JSONObject selector = new JSONObject((String) args[0]);
                            int index = (Integer) args[1];
                            List<JSONObject> matches = matches(selector);
                            return index >= 0 && index < matches.size() ? matches.get(index).toString() : "";
                        }
                        case "clickNode":
                        case "longClickNode": {
                            JSONObject selector = new JSONObject((String) args[0]);
                            return !matches(selector).isEmpty();
                        }
                        case "setTextNode":
                        case "nodeAction": {
                            JSONObject selector = new JSONObject((String) args[0]);
                            return !matches(selector).isEmpty();
                        }
                        default:
                            throw new AssertionError("unexpected backend call " + name);
                    }
                });
        Field field = RemoteAutomationBridge.class.getDeclaredField("sBackend");
        field.setAccessible(true);
        previousBackend = field.get(null);
        field.set(null, backend);
        field = RemoteAutomationBridge.class.getDeclaredField("sIsolationProcess");
        field.setAccessible(true);
        previousIsolation = field.getBoolean(null);
        field.setBoolean(null, true);
    }

    @After public void cleanup() throws Exception {
        Field field = RemoteAutomationBridge.class.getDeclaredField("sBackend");
        field.setAccessible(true);
        field.set(null, previousBackend);
        field = RemoteAutomationBridge.class.getDeclaredField("sIsolationProcess");
        field.setAccessible(true);
        field.setBoolean(null, previousIsolation);
    }

    // --------------------------------------------------------- fake host match

    /** 与宿主 SelectorSpec 对齐的最小子集：path / id / text 系列 / desc 系列 / class / pkg / boolean / depth / index / bounds。 */
    private List<JSONObject> matches(JSONObject selector) throws Exception {
        List<JSONObject> found = new ArrayList<>();
        for (int i = 0; i < tree.length(); i++) {
            JSONObject raw = tree.getJSONObject(i);
            if (matches(raw, selector)) { found.add(raw); }
        }
        if ("BFS".equalsIgnoreCase(selector.optString("algorithm", "DFS"))) {
            List<JSONObject> ordered = new ArrayList<>(found);
            ordered.sort((left, right) -> {
                int byDepth = Integer.compare(left.optInt("depth", 0), right.optInt("depth", 0));
                if (byDepth != 0) { return byDepth; }
                return Integer.compare(treeIndexOf(left), treeIndexOf(right));
            });
            return ordered;
        }
        return found;
    }

    private int treeIndexOf(JSONObject target) {
        for (int i = 0; i < tree.length(); i++) {
            if (tree.optJSONObject(i) == target) { return i; }
        }
        return -1;
    }

    private static boolean matches(JSONObject raw, JSONObject selector) throws Exception {
        if (selector.has("path") && !raw.getJSONArray("path").toString()
                .equals(selector.getJSONArray("path").toString())) { return false; }
        if (selector.has("id")) {
            String expected = selector.getString("id");
            String actual = raw.optString("id", "");
            int slash = actual.indexOf('/');
            if (!actual.equals(expected) && !(slash >= 0 && actual.substring(slash + 1).equals(expected))) {
                return false;
            }
        }
        if (selector.has("text") && !raw.optString("text", "").equals(selector.getString("text"))) { return false; }
        if (selector.has("textContains")
                && !raw.optString("text", "").contains(selector.getString("textContains"))) { return false; }
        if (selector.has("textStartsWith")
                && !raw.optString("text", "").startsWith(selector.getString("textStartsWith"))) { return false; }
        if (selector.has("textMatches") && !Pattern.compile(selector.getString("textMatches"))
                .matcher(raw.optString("text", "")).matches()) { return false; }
        if (selector.has("desc") && !raw.optString("desc", "").equals(selector.getString("desc"))) { return false; }
        if (selector.has("className")
                && !raw.optString("className", "").equals(selector.getString("className"))) { return false; }
        if (selector.has("packageName")
                && !raw.optString("packageName", "").equals(selector.getString("packageName"))) { return false; }
        for (String key : new String[]{"clickable", "enabled", "scrollable"}) {
            if (selector.has(key) && raw.optBoolean(key, false) != selector.getBoolean(key)) { return false; }
        }
        if (selector.has("depth") && raw.optInt("depth", -1) != selector.getInt("depth")) { return false; }
        if (selector.has("index") && raw.optInt("indexInParent", -1) != selector.getInt("index")) { return false; }
        if (selector.has("bounds")) {
            JSONObject bounds = selector.getJSONObject("bounds");
            JSONArray actual = raw.optJSONArray("bounds");
            if (actual == null || actual.length() != 4
                    || actual.getInt(0) != bounds.getInt("left")
                    || actual.getInt(1) != bounds.getInt("top")
                    || actual.getInt(2) != bounds.getInt("right")
                    || actual.getInt(3) != bounds.getInt("bottom")) { return false; }
        }
        return true;
    }

    private static List<String> ids(RemoteUiObjectCollection nodes) {
        List<String> values = new ArrayList<>();
        for (RemoteUiObject node : nodes) { values.add(node.id()); }
        return values;
    }

    // ------------------------------------------------------------------ R05

    @Test public void completeSnapshotIsReportedAsCompleteWithoutCursor() throws Exception {
        tree = referenceTree();
        RemoteUiObjectCollection nodes = new RemoteUiSelector().className("View")
                .filter(value -> true).find();
        assertEquals(7, nodes.size());
        assertTrue("完整快照必须判为完整", nodes.snapshotComplete());
        assertTrue(nodes.complete());
        assertFalse(nodes.truncated());
        assertNull(nodes.firstMissingPath());
        assertNull("完整结果没有下一页游标", nodes.nextCursor());
        assertTrue(nodes.generation() > 0);
        assertEquals("检查过全部 7 个快照节点", 7, nodes.seen());
    }

    @Test public void missingChildIsReportedWithFirstMissingPathAndNotComplete() throws Exception {
        tree = new JSONArray()
                .put(node(new int[]{}, "root", "", 3))
                .put(node(new int[]{0}, "c0", "", 0))
                .put(node(new int[]{1}, "c1", "", 0));
        RemoteUiObjectCollection nodes = new RemoteUiSelector().className("View")
                .filter(value -> true).find();
        assertEquals("截断前的节点仍然全部返回，不丢", 3, nodes.size());
        assertFalse(nodes.snapshotComplete());
        assertTrue(nodes.truncated());
        assertFalse(nodes.complete());
        assertEquals("第一个缺失子节点是根的第 2 个孩子", "[2]", nodes.firstMissingPath());
        assertNotNull("截断结果必须有可续的游标", nodes.nextCursor());
    }

    @Test public void truncationInsideSubtreeReportsNestedMissingPath() throws Exception {
        tree = new JSONArray()
                .put(node(new int[]{}, "root", "", 1))
                .put(node(new int[]{0}, "a", "", 2))
                .put(node(new int[]{0, 0}, "a0", "", 0));
        RemoteUiObjectCollection nodes = new RemoteUiSelector().className("View")
                .filter(value -> true).find();
        assertEquals(3, nodes.size());
        assertFalse(nodes.snapshotComplete());
        assertEquals("[0,1]", nodes.firstMissingPath());
    }

    @Test public void hostFindNodesCapIsNotClaimedComplete() throws Exception {
        tree = flatTree(600);
        findNodesLimit = 500;
        RemoteUiObjectCollection nodes = new RemoteUiSelector().className("View").find();
        assertEquals(500, nodes.size());
        assertFalse("触达宿主单次上限时不能声称完整", nodes.snapshotComplete());
        assertTrue(nodes.truncated());
        assertNotNull(nodes.nextCursor());
    }

    @Test public void pagingCoversAllMatchesExactlyOnceInOrder() throws Exception {
        tree = flatTree(20);
        RemoteUiSelector selector = new RemoteUiSelector().depth(1).filter(value -> true);
        RemoteUiSelector.Query query = selector.query(7);
        List<String> seen = new ArrayList<>();
        int[] pageSizes = new int[3];
        int pages = 0;
        while (query.hasNext()) {
            RemoteUiObjectCollection page = query.next();
            pageSizes[pages] = page.size();
            seen.addAll(ids(page));
            if (pages < 2) {
                assertFalse("中间页不能声称完整", page.complete());
                assertTrue(page.truncated());
                assertNotNull(page.nextCursor());
            }
            pages++;
        }
        assertEquals(3, pages);
        assertArrayEquals(new int[]{7, 7, 6}, pageSizes);
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 20; i++) { expected.add("n" + i); }
        assertEquals("分页不丢不重且保持顺序", expected, seen);
    }

    @Test public void lastPageIsCompleteAndCursorNull() throws Exception {
        tree = flatTree(5);
        RemoteUiSelector.Query query = new RemoteUiSelector().depth(1)
                .filter(value -> true).query(2);
        query.next();
        query.next();
        RemoteUiObjectCollection last = query.next();
        assertEquals(1, last.size());
        assertTrue(last.complete());
        assertNull(last.nextCursor());
        assertFalse(query.hasNext());
    }

    @Test public void cursorResumesTheSameSnapshotAndRejectsStaleOnes() throws Exception {
        tree = flatTree(20);
        RemoteUiSelector selector = new RemoteUiSelector().depth(1).filter(value -> true);
        RemoteUiObjectCollection first = selector.query(7).next();
        String cursor = first.nextCursor();
        assertNotNull(cursor);
        RemoteUiObjectCollection resumed = selector.query(7, cursor).next();
        assertEquals("游标从第 7 项继续", Arrays.asList("n7", "n8", "n9", "n10", "n11", "n12", "n13"),
                ids(resumed));

        // 刷新快照（宿主树变化后重新取树）会让旧游标失效。
        selector.query(7).next();
        try {
            selector.query(7, cursor).next();
            fail("旧代次游标必须被拒绝");
        } catch (ScriptException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("过期"));
        }
        try {
            selector.query(7, "bogus").next();
            fail("畸形游标必须被拒绝");
        } catch (ScriptException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("游标"));
        }
    }

    @Test public void cancelStopsPagingWithoutReturningMoreNodes() throws Exception {
        tree = flatTree(20);
        RemoteUiSelector.Query query = new RemoteUiSelector().depth(1)
                .filter(value -> true).query(5);
        RemoteUiObjectCollection first = query.next();
        assertEquals(5, first.size());
        query.cancel();
        assertTrue(query.cancelled());
        assertFalse(query.hasNext());
        RemoteUiObjectCollection after = query.next();
        assertTrue("取消后不再返回节点", after.isEmpty());
        assertNull(query.cursor());
    }

    // ------------------------------------------------------------------ R06

    @Test public void bfsWithLocalPredicateUsesRealLevelOrder() throws Exception {
        tree = referenceTree();
        // 参考 BFS（人工从上面的树推出）：root, a, b, a0, a1, b0, a10
        List<String> expectedBfs = Arrays.asList("root", "a", "b", "a0", "a1", "b0", "a10");
        RemoteUiObjectCollection bfs = new RemoteUiSelector().algorithm("BFS")
                .filter(value -> true).find();
        assertEquals("BFS 数量必须等于完整树节点数", expectedBfs.size(), bfs.size());
        assertEquals("BFS 必须真正按层遍历，不能对 DFS 排序冒充", expectedBfs, ids(bfs));
        assertTrue(bfs.snapshotComplete());
    }

    @Test public void dfsOrderIsUnchangedForLocalPredicates() throws Exception {
        tree = referenceTree();
        List<String> expectedDfs = Arrays.asList("root", "a", "a0", "a1", "a10", "b", "b0");
        RemoteUiObjectCollection dfs = new RemoteUiSelector()
                .filter(value -> true).find();
        assertEquals(expectedDfs, ids(dfs));
    }

    @Test public void bfsAppliesWireAndLocalFiltersInLevelOrder() throws Exception {
        tree = referenceTree();
        // textStartsWith 是下发条件，filter 是本地谓词：两者都要在同一快照上生效。
        List<String> bfs = ids(new RemoteUiSelector().algorithm("BFS").textStartsWith("x")
                .filter(value -> true).find());
        assertEquals(Arrays.asList("a0", "b0", "a10"), bfs);
        List<String> dfs = ids(new RemoteUiSelector().textStartsWith("x")
                .filter(value -> true).find());
        assertEquals("同一组过滤条件在 DFS 下顺序不同，说明顺序真的来自遍历算法",
                Arrays.asList("a0", "a10", "b0"), dfs);
    }

    @Test public void bfsWithLocalPredicateRefusesTruncatedSnapshot() throws Exception {
        tree = new JSONArray()
                .put(node(new int[]{}, "root", "", 2))
                .put(node(new int[]{0}, "a", "", 0));
        try {
            new RemoteUiSelector().algorithm("BFS").filter(value -> true).find();
            fail("截断 DFS 排序不能冒充完整 BFS");
        } catch (ScriptException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("截断"));
        }
    }

    @Test public void bfsWithoutLocalConditionsStillUsesHostFastPath() throws Exception {
        tree = referenceTree();
        calls.clear();
        RemoteUiObjectCollection nodes = new RemoteUiSelector().algorithm("BFS")
                .className("View").find();
        assertTrue("纯下发条件必须走宿主 findNodes", calls.contains("findNodes"));
        assertFalse("纯下发条件不应拉整棵 dump", calls.contains("getLatestNodeDump"));
        assertEquals(Arrays.asList("root", "a", "b", "a0", "a1", "b0", "a10"), ids(nodes));
    }

    // ------------------------------------------------------------------ R07

    @Test public void tokenIsStableAcrossSnapshotsAndGenerationIsExposed() throws Exception {
        tree = referenceTree();
        RemoteUiObject first = new RemoteUiSelector().id("a0").findOnce();
        RemoteUiObject second = new RemoteUiSelector().id("a0").findOnce();
        assertNotNull(first);
        assertNotNull(second);
        assertEquals("同一节点跨快照 token 相同", first.token(), second.token());
        assertTrue(first.generation() > 0);
        assertFalse(first.isStale());
    }

    @Test public void contentChangeAtSamePathIsDetectedAsStale() throws Exception {
        tree = referenceTree();
        RemoteUiObject node = new RemoteUiSelector().id("a0").findOnce();
        assertFalse(node.isStale());
        tree.getJSONObject(2).put("text", "changed");
        assertTrue("同一 path 被新内容复用必须判过期", node.isStale());
        assertFalse("动作不会误点被复用的节点", node.click());
    }

    @Test public void listReuseWithDifferentIdIsDetectedAndActionRefused() throws Exception {
        tree = flatTree(3);
        RemoteUiObject node = new RemoteUiSelector().id("n1").findOnce();
        assertNotNull(node);
        assertFalse(node.isStale());
        // 列表复用：同一个 path 现在换成另一个节点（不同 id）。
        tree.getJSONObject(2).put("id", "reused").put("text", "reused");
        assertTrue(node.isStale());
        assertFalse("列表复用后点击必须失败，不做坐标兜底", node.click());
        assertFalse("删除后报告不存在", new RemoteUiSelector().id("n1").exists());
    }

    @Test public void removedNodeIsStale() throws Exception {
        tree = flatTree(3);
        RemoteUiObject node = new RemoteUiSelector().id("n2").findOnce();
        assertNotNull(node);
        JSONArray remaining = new JSONArray();
        for (int i = 0; i < tree.length(); i++) {
            if (i != 3) { remaining.put(tree.getJSONObject(i)); }
        }
        tree = remaining;
        assertTrue(node.isStale());
        assertFalse(node.exists());
    }
}
