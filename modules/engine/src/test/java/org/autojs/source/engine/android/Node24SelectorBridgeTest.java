package org.autojs.source.engine.android;

import com.stardust.autojs.runtime.api.remote.RemoteAutomationBridge;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.Assert.*;

/**
 * Pure-JVM tests for the Node24 selector/node actions.
 *
 * <p>{@link Node24AutomationBridge} normally creates its backend reflectively from the app
 * classloader and needs an Android {@code Context}; these tests allocate the bridge without
 * running its constructor (the same fake-backend pattern used for engine-side bridge tests) and
 * inject a recording {@link RemoteAutomationBridge.Backend}. No Android service, node tree or
 * Binder is involved.
 */
public class Node24SelectorBridgeTest {

    @Test public void selectorIsRequiredAndMustBeAJsonObject() throws Exception {
        FakeBackend backend = new FakeBackend();
        Node24AutomationBridge bridge = newBridge(backend);
        try {
            assertError(bridge.handle(new JSONObject().put("action", "findNodes")), "selector");
            assertError(bridge.handle(new JSONObject().put("action", "findNodes")
                    .put("selector", JSONObject.NULL)), "selector");
            assertError(bridge.handle(new JSONObject().put("action", "findNodes")
                    .put("selector", "{}")), "JSON 对象");
            assertError(bridge.handle(new JSONObject().put("action", "findNodes")
                    .put("selector", new JSONArray())), "JSON 对象");
            assertEquals("selector 校验失败时不能碰后端", 0, backend.findNodesCalls);
        } finally {
            bridge.close();
        }
    }

    @Test public void selectorRejectsUnknownKeysAndWrongTypes() throws Exception {
        FakeBackend backend = new FakeBackend();
        Node24AutomationBridge bridge = newBridge(backend);
        try {
            assertSelectorError(bridge, new JSONObject().put("unknown", true), "不支持字段");
            assertSelectorError(bridge, new JSONObject().put("text", 1), "text");
            assertSelectorError(bridge, new JSONObject().put("id", JSONObject.NULL), "id");
            assertSelectorError(bridge, new JSONObject().put("clickable", "true"), "clickable");
            assertSelectorError(bridge, new JSONObject().put("enabled", 1), "enabled");
            assertSelectorError(bridge, new JSONObject().put("scrollable", JSONObject.NULL), "scrollable");
            assertSelectorError(bridge, new JSONObject().put("depth", -1), "depth");
            assertSelectorError(bridge, new JSONObject().put("depth", 64), "depth");
            assertSelectorError(bridge, new JSONObject().put("depth", 1.5), "depth");
            assertSelectorError(bridge, new JSONObject().put("index", 8192), "selector.index");
            assertSelectorError(bridge, new JSONObject().put("index", "0"), "selector.index");
            assertSelectorError(bridge, new JSONObject().put("path", "[]"), "path");
            assertSelectorError(bridge, new JSONObject().put("path", new JSONArray().put(-1)), "path");
            assertSelectorError(bridge, new JSONObject().put("path", new JSONArray().put("0")), "path");
            assertSelectorError(bridge, new JSONObject().put("textMatches", "["), "textMatches");
            assertSelectorError(bridge, new JSONObject().put("textMatches", 12), "textMatches");
            assertEquals("参数拒绝时不能把 selector 交给后端", 0, backend.findNodesCalls);
        } finally {
            bridge.close();
        }
    }

    @Test public void selectorBoundsAndPathAreStrict() throws Exception {
        FakeBackend backend = new FakeBackend();
        Node24AutomationBridge bridge = newBridge(backend);
        try {
            assertSelectorError(bridge, new JSONObject().put("path", pathOfLength(64)), "path");
            assertSelectorError(bridge, new JSONObject().put("bounds", new JSONObject()
                    .put("left", 0).put("top", 0).put("right", 10)), "bounds");
            assertSelectorError(bridge, new JSONObject().put("bounds", new JSONObject()
                    .put("left", 0).put("top", 0).put("right", 10).put("bottom", 10)
                    .put("extra", 1)), "bounds");
            assertSelectorError(bridge, new JSONObject().put("bounds", new JSONObject()
                    .put("left", 20).put("top", 0).put("right", 10).put("bottom", 10)), "right");
            assertSelectorError(bridge, new JSONObject().put("bounds", new JSONObject()
                    .put("left", 0).put("top", 20).put("right", 10).put("bottom", 10)), "bottom");
            assertSelectorError(bridge, new JSONObject().put("bounds", new JSONObject()
                    .put("left", 0).put("top", 0).put("right", 10_000_001).put("bottom", 10)), "bounds");
            assertSelectorError(bridge, new JSONObject().put("bounds", new JSONObject()
                    .put("left", 0).put("top", 0).put("right", 10.5).put("bottom", 10)), "bounds");
            assertSelectorError(bridge, new JSONObject().put("bounds", new JSONArray()
                    .put(0).put(0).put(10).put(10)), "bounds");
            assertSelectorError(bridge, new JSONObject().put("text", repeat("a", 32_768)), "过长");
            assertEquals(0, backend.findNodesCalls);
        } finally {
            bridge.close();
        }
    }

    @Test public void rootPathAndEmptySelectorAreForwarded() throws Exception {
        FakeBackend backend = new FakeBackend();
        Node24AutomationBridge bridge = newBridge(backend);
        try {
            JSONObject emptyResponse = bridge.handle(request("findNodes", new JSONObject()));
            assertTrue(emptyResponse.optBoolean("ok"));
            assertEquals("{}", backend.lastSelectorJson);

            JSONObject rootSelector = new JSONObject().put("path", new JSONArray());
            JSONObject rootResponse = bridge.handle(request("findNodes", rootSelector));
            assertTrue(rootResponse.optBoolean("ok"));
            JSONObject forwarded = new JSONObject(backend.lastSelectorJson);
            assertTrue(forwarded.has("path"));
            assertEquals(0, forwarded.getJSONArray("path").length());

            JSONObject complex = new JSONObject()
                    .put("id", "btn").put("text", "OK").put("textContains", "O")
                    .put("textStartsWith", "O").put("textMatches", "^O.*$")
                    .put("desc", "d").put("descContains", "d")
                    .put("className", "android.widget.Button").put("packageName", "com.example")
                    .put("clickable", true).put("enabled", true).put("scrollable", false)
                    .put("depth", 63).put("index", 8191)
                    .put("path", new JSONArray().put(0).put(2))
                    // 屏幕外负坐标是合法的；边界内应保留给主进程。
                    .put("bounds", new JSONObject().put("left", -10).put("top", -20)
                            .put("right", 30).put("bottom", 40));
            JSONObject complexResponse = bridge.handle(request("findNodes", complex));
            assertTrue(complexResponse.optBoolean("ok"));
            JSONObject recorded = new JSONObject(backend.lastSelectorJson);
            assertEquals("btn", recorded.getString("id"));
            assertEquals(63, recorded.getInt("depth"));
            assertEquals(8191, recorded.getInt("index"));
            assertEquals(2, recorded.getJSONArray("path").length());
            assertEquals(-10, recorded.getJSONObject("bounds").getInt("left"));

            // 边界值本身必须允许：path 63 项、bounds 绝对值 1e7。
            JSONObject boundary = new JSONObject()
                    .put("path", pathOfLength(63))
                    .put("bounds", new JSONObject().put("left", -10_000_000)
                            .put("top", -10_000_000).put("right", 10_000_000)
                            .put("bottom", 10_000_000));
            assertTrue(bridge.handle(request("findNodes", boundary)).optBoolean("ok"));
        } finally {
            bridge.close();
        }
    }

    @Test public void findNodesParsesRealArrayAndRejectsProtocolErrors() throws Exception {
        FakeBackend backend = new FakeBackend();
        Node24AutomationBridge bridge = newBridge(backend);
        try {
            backend.findNodesResponse = "[{\"path\":[],\"text\":\"OK\"}]";
            JSONObject response = bridge.handle(request("findNodes", new JSONObject()));
            assertTrue(response.optBoolean("ok"));
            JSONArray nodes = response.getJSONArray("value");
            assertEquals(1, nodes.length());
            assertEquals("OK", nodes.getJSONObject(0).getString("text"));

            backend.findNodesResponse = "[]";
            JSONObject empty = bridge.handle(request("findNodes", new JSONObject()));
            assertTrue(empty.optBoolean("ok"));
            assertEquals(0, empty.getJSONArray("value").length());

            backend.findNodesResponse = "";
            assertError(bridge.handle(request("findNodes", new JSONObject())), "findNodes");
            backend.findNodesResponse = "not-json";
            assertError(bridge.handle(request("findNodes", new JSONObject())), "findNodes");
            backend.findNodesResponse = "{\"path\":[]}";
            assertError(bridge.handle(request("findNodes", new JSONObject())), "findNodes");
            backend.findNodesResponse = null;
            assertError(bridge.handle(request("findNodes", new JSONObject())), "findNodes");
        } finally {
            bridge.close();
        }
    }

    @Test public void getNodeInfoUsesNullOnlyForNotFound() throws Exception {
        FakeBackend backend = new FakeBackend();
        Node24AutomationBridge bridge = newBridge(backend);
        try {
            backend.nodeInfoResponse = "";
            JSONObject missing = bridge.handle(request("getNodeInfo", new JSONObject()));
            assertTrue(missing.optBoolean("ok"));
            assertTrue(missing.has("value"));
            assertTrue(missing.isNull("value"));
            assertEquals(0, backend.lastIndex);

            backend.nodeInfoResponse = "{\"path\":[],\"text\":\"OK\"}";
            JSONObject found = bridge.handle(requestWithIndex("getNodeInfo", 3));
            assertTrue(found.optBoolean("ok"));
            assertEquals("OK", found.getJSONObject("value").getString("text"));
            assertEquals(3, backend.lastIndex);

            backend.nodeInfoResponse = "not-json";
            JSONObject malformed = assertError(bridge.handle(request("getNodeInfo", new JSONObject())),
                    "getNodeInfo");
            assertFalse("协议错误不能伪装成没找到", malformed.has("value"));
            backend.nodeInfoResponse = "[]";
            assertError(bridge.handle(request("getNodeInfo", new JSONObject())), "getNodeInfo");
            backend.nodeInfoResponse = null;
            assertError(bridge.handle(request("getNodeInfo", new JSONObject())), "getNodeInfo");
        } finally {
            bridge.close();
        }
    }

    @Test public void nodeActionsProxyArgumentsAndActualBooleans() throws Exception {
        FakeBackend backend = new FakeBackend();
        Node24AutomationBridge bridge = newBridge(backend);
        try {
            JSONObject selector = new JSONObject().put("id", "btn");
            backend.clickNodeResult = false;
            JSONObject click = bridge.handle(requestWithIndex("clickNode", 7, selector));
            assertTrue(click.optBoolean("ok"));
            assertFalse("false 必须原样返回", click.optBoolean("value", true));
            assertEquals(1, backend.clickNodeCalls);
            assertEquals(7, backend.lastIndex);
            assertEquals("btn", new JSONObject(backend.lastSelectorJson).getString("id"));

            backend.longClickNodeResult = true;
            JSONObject longClick = bridge.handle(requestWithIndex("longClickNode", 7, selector));
            assertTrue(longClick.optBoolean("ok"));
            assertTrue(longClick.optBoolean("value"));
            assertEquals(1, backend.longClickNodeCalls);

            backend.setTextNodeResult = false;
            JSONObject setText = bridge.handle(requestWithIndex("setTextNode", 7, selector)
                    .put("text", "hello"));
            assertTrue(setText.optBoolean("ok"));
            assertFalse(setText.optBoolean("value", true));
            assertEquals("hello", backend.lastText);
            assertEquals(1, backend.setTextNodeCalls);

            backend.clickNodeResult = true;
            JSONObject defaultIndex = bridge.handle(request("clickNode", selector));
            assertTrue(defaultIndex.optBoolean("ok"));
            assertTrue(defaultIndex.optBoolean("value"));
            assertEquals("省略 index 时默认 0", 0, backend.lastIndex);
        } finally {
            bridge.close();
        }
    }

    @Test public void nodeActionsRejectBadIndexAndText() throws Exception {
        FakeBackend backend = new FakeBackend();
        Node24AutomationBridge bridge = newBridge(backend);
        try {
            JSONObject selector = new JSONObject();
            for (Object invalid : new Object[]{-1, 256, 1.5, "1", JSONObject.NULL}) {
                JSONObject response = bridge.handle(request("clickNode", selector)
                        .put("index", invalid));
                assertError(response, "index");
            }
            assertEquals(0, backend.clickNodeCalls);

            assertError(bridge.handle(request("findNodes", selector).put("index", 300)),
                    "index");
            assertEquals("非法 index 不能查询节点", 0, backend.findNodesCalls);

            assertTrue(bridge.handle(request("clickNode", selector).put("index", 255))
                    .optBoolean("ok"));
            assertEquals(255, backend.lastIndex);

            assertError(bridge.handle(request("longClickNode", selector).put("index", 300)),
                    "index");
            assertError(bridge.handle(request("setTextNode", selector)), "text");
            assertError(bridge.handle(request("setTextNode", selector).put("text", 12)), "text");
            assertError(bridge.handle(request("setTextNode", selector)
                    .put("text", JSONObject.NULL)), "text");
            assertEquals("参数拒绝时不能执行动作", 0, backend.setTextNodeCalls);

            assertTrue(bridge.handle(request("setTextNode", selector).put("text", ""))
                    .optBoolean("ok"));
            assertEquals("", backend.lastText);
        } finally {
            bridge.close();
        }
    }

    @Test public void nodeActionRejectsUnknownActionAndBadArgumentsBeforeBackend() throws Exception {
        FakeBackend backend = new FakeBackend();
        Node24AutomationBridge bridge = newBridge(backend);
        try {
            JSONObject selector = new JSONObject();
            assertError(bridge.handle(request("nodeAction", selector)
                    .put("actionName", "scrollDown")), "arguments");
            assertError(bridge.handle(request("nodeAction", selector)
                    .put("actionName", "scrollDown").put("arguments", JSONObject.NULL)), "arguments");
            assertError(bridge.handle(request("nodeAction", selector)
                    .put("actionName", "scrollDown").put("arguments", new JSONObject())), "arguments");
            assertError(bridge.handle(request("nodeAction", selector)
                    .put("actionName", "scrollDown").put("arguments", "[]")), "arguments");
            assertError(bridge.handle(request("nodeAction", selector)
                    .put("actionName", 3).put("arguments", new JSONArray())), "actionName");
            assertError(bridge.handle(request("nodeAction", selector)
                    .put("actionName", "bogus").put("arguments", new JSONArray())), "不支持");
            assertNodeActionArgsError(bridge, "scrollDown",
                    new JSONArray().put(1), "不接受参数");

            assertNodeActionArgsError(bridge, "setSelection", new JSONArray(), "参数");
            assertNodeActionArgsError(bridge, "setSelection",
                    new JSONArray().put(0), "参数");
            assertNodeActionArgsError(bridge, "setSelection",
                    new JSONArray().put(0).put(1).put(2), "参数");
            assertNodeActionArgsError(bridge, "setSelection",
                    new JSONArray().put(-1).put(0), "32 位整数");
            assertNodeActionArgsError(bridge, "setSelection",
                    new JSONArray().put(0).put(2_147_483_648L), "32 位整数");
            assertNodeActionArgsError(bridge, "setSelection",
                    new JSONArray().put(1.5).put(2), "整数");
            assertNodeActionArgsError(bridge, "setSelection",
                    new JSONArray().put("0").put(1), "整数");
            assertNodeActionArgsError(bridge, "setSelection",
                    new JSONArray().put(5).put(4), "start <= end");

            assertNodeActionArgsError(bridge, "scrollTo",
                    new JSONArray().put(-1).put(0), "32 位整数");
            assertNodeActionArgsError(bridge, "scrollTo",
                    new JSONArray().put(0).put(2_147_483_648L), "32 位整数");
            assertNodeActionArgsError(bridge, "scrollTo",
                    new JSONArray().put(0.5).put(1), "整数");
            assertNodeActionArgsError(bridge, "scrollTo",
                    new JSONArray().put(1), "参数");

            assertNodeActionArgsError(bridge, "setProgress", new JSONArray(), "参数");
            assertNodeActionArgsError(bridge, "setProgress",
                    new JSONArray().put(0.5).put(1), "参数");
            assertNodeActionArgsError(bridge, "setProgress",
                    new JSONArray().put("0.5"), "必须是数字");
            assertNodeActionArgsError(bridge, "setProgress",
                    new JSONArray().put(1e39), "float 有限范围");

            assertEquals("非法参数不能调用 backend.nodeAction", 0, backend.nodeActionCalls);
        } finally {
            bridge.close();
        }
    }

    @Test public void nodeActionForwardsKnownActionsWithExactArguments() throws Exception {
        FakeBackend backend = new FakeBackend();
        Node24AutomationBridge bridge = newBridge(backend);
        try {
            JSONObject selector = new JSONObject().put("id", "btn");
            backend.nodeActionResult = false;
            JSONObject noArgs = bridge.handle(requestWithIndex("nodeAction", 4, selector)
                    .put("actionName", "scrollDown").put("arguments", new JSONArray()));
            assertTrue(noArgs.optBoolean("ok"));
            assertFalse("performAction false 必须原样返回", noArgs.optBoolean("value", true));
            assertEquals("scrollDown", backend.lastNodeAction);
            assertEquals("[]", backend.lastNodeActionArguments);
            assertEquals(4, backend.lastIndex);

            backend.nodeActionResult = true;
            JSONObject selection = bridge.handle(request("nodeAction", selector)
                    .put("actionName", "setSelection")
                    .put("arguments", new JSONArray().put(0).put(4)));
            assertTrue(selection.optBoolean("ok"));
            assertTrue(selection.optBoolean("value"));
            assertEquals("setSelection", backend.lastNodeAction);
            assertEquals("[0,4]", backend.lastNodeActionArguments);

            JSONObject scrollTo = bridge.handle(request("nodeAction", selector)
                    .put("actionName", "scrollTo")
                    .put("arguments", new JSONArray().put(0).put(Integer.MAX_VALUE)));
            assertTrue(scrollTo.optBoolean("ok"));
            assertEquals("scrollTo", backend.lastNodeAction);
            assertEquals("[0,2147483647]", backend.lastNodeActionArguments);

            JSONObject progress = bridge.handle(request("nodeAction", selector)
                    .put("actionName", "setProgress")
                    .put("arguments", new JSONArray().put(0.5)));
            assertTrue(progress.optBoolean("ok"));
            assertEquals("setProgress", backend.lastNodeAction);
            assertEquals("[0.5]", backend.lastNodeActionArguments);

            assertEquals("btn", new JSONObject(backend.lastSelectorJson).getString("id"));
            assertEquals("所有合法动作都必须转发到 backend", 4, backend.nodeActionCalls);
        } finally {
            bridge.close();
        }
    }

    @Test public void nodeActionBackendFailureReturnsErrorEnvelopeNotFalse() throws Exception {
        FakeBackend backend = new FakeBackend();
        backend.nodeActionFailure = new IllegalStateException(
                "主进程自动化服务调用失败（nodeAction）：Binder 已断开");
        Node24AutomationBridge bridge = newBridge(backend);
        try {
            JSONObject response = bridge.handle(request("nodeAction", new JSONObject())
                    .put("actionName", "scrollDown").put("arguments", new JSONArray()));
            assertFalse(response.optBoolean("ok", true));
            assertFalse("异常不能折叠成 value=false", response.has("value"));
            assertTrue(response.optString("error", "").contains("Binder 已断开"));
            assertEquals(1, backend.nodeActionCalls);
        } finally {
            bridge.close();
        }
    }

    @Test public void setTextNodeRejectsOversizeTextBeforeBackend() throws Exception {
        FakeBackend backend = new FakeBackend();
        Node24AutomationBridge bridge = newBridge(backend);
        try {
            JSONObject selector = new JSONObject();
            assertError(bridge.handle(request("setTextNode", selector)
                    .put("text", repeat("a", 32_769))), "过长");
            assertError(bridge.handle(request("setTextNode", selector)
                    .put("text", repeat("中", 21_846))), "UTF-8");
            assertEquals("超限文本不能调用 backend.setTextNode", 0, backend.setTextNodeCalls);

            JSONObject boundary = bridge.handle(request("setTextNode", selector)
                    .put("text", repeat("a", 32_768)));
            assertTrue(boundary.optBoolean("ok"));
            assertEquals(1, backend.setTextNodeCalls);
            assertEquals(32_768, backend.lastText.length());
        } finally {
            bridge.close();
        }
    }

    @Test public void notReadyAndClosedActionsReturnHonestErrors() throws Exception {
        FakeBackend backend = new FakeBackend();
        backend.ready = false;
        backend.enabled = false;
        Node24AutomationBridge bridge = newBridge(backend);
        try {
            JSONObject disabled = assertError(bridge.handle(request("findNodes", new JSONObject())),
                    "未开启");
            assertFalse(disabled.optBoolean("ok", true));
            assertEquals("未就绪时不能访问节点", 0, backend.findNodesCalls);

            // 已开启但服务未连接：应报「未运行」，不能误报「未开启」。
            backend.enabled = true;
            JSONObject notReady = assertError(bridge.handle(request("findNodes", new JSONObject())),
                    "未运行");
            assertFalse(notReady.optBoolean("ok", true));
            assertEquals("未就绪时不能访问节点", 0, backend.findNodesCalls);

            backend.ready = true;
            assertTrue(bridge.handle(request("findNodes", new JSONObject())).optBoolean("ok"));
            bridge.close();
            assertError(bridge.handle(request("findNodes", new JSONObject())), "已释放");
            bridge.close();
        } finally {
            bridge.close();
        }
    }

    @Test public void staleReadyBackendCannotActAfterServiceDisabled() throws Exception {
        FakeBackend backend = new FakeBackend();
        backend.enabled = false;
        Node24AutomationBridge bridge = newBridge(backend);
        try {
            assertError(bridge.handle(request("clickNode", new JSONObject())), "未开启");
            assertEquals(0, backend.clickNodeCalls);
        } finally { bridge.close(); }
    }

    @Test public void dumpPreservesArrayAndRejectsBrokenProtocol() throws Exception {
        FakeBackend backend = new FakeBackend();
        Node24AutomationBridge bridge = newBridge(backend);
        JSONObject request = new JSONObject().put("action", "getLatestNodeDump");
        try {
            backend.dumpResponse = "[{\"path\":[],\"childCount\":2}]";
            JSONArray value = bridge.handle(request).getJSONArray("value");
            assertEquals(2, value.getJSONObject(0).getInt("childCount"));
            backend.dumpResponse = "{}";
            assertError(bridge.handle(request), "JSON 数组");
            backend.dumpResponse = null;
            assertError(bridge.handle(request), "协议错误");
        } finally { bridge.close(); }
    }

    @Test public void matchTextPatternBatchUsesJavaPatternSemanticsWithoutBackend()
            throws Exception {
        // 不注入 backend：成功返回即证明该 action 完全本地计算，不依赖 A11y/主进程。
        Node24AutomationBridge bridge = newBridge(null);
        try {
            JSONObject flags = bridge.handle(batchRequest("(?i)^ok$",
                    new JSONArray().put("OK").put("ok").put("ok!").put("no")));
            assertTrue(flags.optBoolean("ok"));
            JSONArray flagged = flags.getJSONArray("value");
            assertEquals(4, flagged.length());
            assertTrue(flagged.getBoolean(0));
            assertTrue(flagged.getBoolean(1));
            assertFalse("matches() 必须完整匹配，不能 contains", flagged.getBoolean(2));
            assertFalse(flagged.getBoolean(3));

            JSONObject quoted = bridge.handle(batchRequest("\\Q[a-z]+\\E",
                    new JSONArray().put("[a-z]+").put("abc").put("x[a-z]+")));
            assertTrue(quoted.optBoolean("ok"));
            JSONArray literals = quoted.getJSONArray("value");
            assertTrue(literals.getBoolean(0));
            assertFalse("\\Q..\\E 必须按 Java 字面量解释，不能当 JS 正则", literals.getBoolean(1));
            assertFalse(literals.getBoolean(2));

            JSONObject full = bridge.handle(batchRequest("abc",
                    new JSONArray().put("abc").put("xabc").put("abcx").put("ab")));
            JSONArray fullMatches = full.getJSONArray("value");
            assertTrue(fullMatches.getBoolean(0));
            assertFalse(fullMatches.getBoolean(1));
            assertFalse(fullMatches.getBoolean(2));
            assertFalse(fullMatches.getBoolean(3));

            JSONObject emptyPattern = bridge.handle(batchRequest("",
                    new JSONArray().put("").put("x")));
            assertTrue(emptyPattern.optBoolean("ok"));
            assertTrue(emptyPattern.getJSONArray("value").getBoolean(0));
            assertFalse(emptyPattern.getJSONArray("value").getBoolean(1));

            JSONObject emptyBatch = bridge.handle(batchRequest("a+", new JSONArray()));
            assertTrue(emptyBatch.optBoolean("ok"));
            assertEquals(0, emptyBatch.getJSONArray("value").length());
        } finally {
            bridge.close();
        }
    }

    @Test public void matchTextPatternBatchValidatesTypesAndLimits() throws Exception {
        Node24AutomationBridge bridge = newBridge(null);
        try {
            assertError(bridge.handle(new JSONObject()
                    .put("action", "matchTextPatternBatch")
                    .put("texts", new JSONArray())), "pattern");
            for (Object invalid : new Object[]{JSONObject.NULL, 7, true,
                    new JSONObject().put("regex", "a"), new JSONArray().put("a")}) {
                assertError(bridge.handle(batchRequest(invalid, new JSONArray())), "pattern");
            }
            assertError(bridge.handle(batchRequest(repeat("a", 32_769), new JSONArray())), "过长");
            JSONObject patternBoundary = bridge.handle(
                    batchRequest(repeat("a", 32_768), new JSONArray()));
            assertTrue(patternBoundary.optBoolean("ok"));
            assertEquals("边界 pattern 应被接受并返回空结果",
                    0, patternBoundary.getJSONArray("value").length());

            assertError(bridge.handle(new JSONObject()
                    .put("action", "matchTextPatternBatch")
                    .put("pattern", "a")), "texts");
            for (Object invalid : new Object[]{JSONObject.NULL, "a", 3, true,
                    new JSONObject().put("text", "a")}) {
                assertError(bridge.handle(batchRequest("a", invalid)), "texts");
            }
            for (Object invalid : new Object[]{JSONObject.NULL, 3, true,
                    new JSONObject().put("text", "a"), new JSONArray().put("a")}) {
                JSONArray texts = new JSONArray().put("ok").put(invalid);
                assertError(bridge.handle(batchRequest("a", texts)), "texts[1]");
            }

            JSONArray tooMany = new JSONArray();
            for (int i = 0; i < 257; i++) {
                tooMany.put("");
            }
            assertError(bridge.handle(batchRequest("", tooMany)), "上限 256");

            JSONArray maxTexts = new JSONArray();
            for (int i = 0; i < 256; i++) {
                maxTexts.put("a");
            }
            JSONObject countBoundary = bridge.handle(batchRequest("a", maxTexts));
            assertTrue(countBoundary.optBoolean("ok"));
            JSONArray countValues = countBoundary.getJSONArray("value");
            assertEquals(256, countValues.length());
            assertTrue(countValues.getBoolean(255));

            assertError(bridge.handle(batchRequest("a", new JSONArray().put(repeat("a", 65_537)))),
                    "总长度");
            JSONArray maxTotal = new JSONArray();
            for (int i = 0; i < 256; i++) {
                maxTotal.put(repeat("a", 256));
            }
            JSONObject totalBoundary = bridge.handle(batchRequest("a+", maxTotal));
            assertTrue(totalBoundary.optBoolean("ok"));
            JSONArray totalValues = totalBoundary.getJSONArray("value");
            assertEquals(256, totalValues.length());
            for (int i = 0; i < totalValues.length(); i++) {
                assertTrue(totalValues.getBoolean(i));
            }
        } finally {
            bridge.close();
        }
    }

    @Test public void matchTextPatternBatchReportsInvalidJavaRegex() throws Exception {
        Node24AutomationBridge bridge = newBridge(null);
        try {
            JSONObject response = assertError(bridge.handle(batchRequest("(",
                    new JSONArray().put("x"))), "不是合法的 Java 正则表达式");
            assertTrue(response.optString("error", "")
                    .contains("matchTextPatternBatch.pattern"));
            assertFalse("非法正则不能返回 value", response.has("value"));

            assertError(bridge.handle(batchRequest("[z-a]", new JSONArray())),
                    "不是合法的 Java 正则表达式");
        } finally {
            bridge.close();
        }
    }

    // ------------------------------------------------------------------
    // Test helpers
    // ------------------------------------------------------------------

    private static Node24AutomationBridge newBridge(RemoteAutomationBridge.Backend backend)
            throws Exception {
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeType.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object unsafe = theUnsafe.get(null);
        Method allocateInstance = unsafeType.getMethod("allocateInstance", Class.class);
        Node24AutomationBridge bridge = (Node24AutomationBridge) allocateInstance.invoke(
                unsafe, Node24AutomationBridge.class);
        Field backendField = Node24AutomationBridge.class.getDeclaredField("mBackend");
        backendField.setAccessible(true);
        backendField.set(bridge, backend);
        return bridge;
    }

    private static JSONObject request(String action, JSONObject selector) throws Exception {
        return new JSONObject().put("action", action).put("selector", selector);
    }

    private static JSONObject batchRequest(Object pattern, Object texts) throws Exception {
        return new JSONObject().put("action", "matchTextPatternBatch")
                .put("pattern", pattern).put("texts", texts);
    }

    private static JSONObject requestWithIndex(String action, int index) throws Exception {
        return requestWithIndex(action, index, new JSONObject());
    }

    private static JSONObject requestWithIndex(String action, int index, JSONObject selector)
            throws Exception {
        return request(action, selector).put("index", index);
    }

    private static JSONArray pathOfLength(int length) throws Exception {
        JSONArray path = new JSONArray();
        for (int i = 0; i < length; i++) {
            path.put(i);
        }
        return path;
    }

    private static String repeat(String value, int count) {
        StringBuilder text = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) {
            text.append(value);
        }
        return text.toString();
    }

    private static void assertSelectorError(Node24AutomationBridge bridge, JSONObject selector,
                                            String fragment) throws Exception {
        assertError(bridge.handle(request("findNodes", selector)), fragment);
    }

    private static void assertNodeActionArgsError(Node24AutomationBridge bridge, String action,
                                                  JSONArray arguments, String fragment)
            throws Exception {
        JSONObject request = request("nodeAction", new JSONObject())
                .put("actionName", action).put("arguments", arguments);
        assertError(bridge.handle(request), fragment);
    }

    private static JSONObject assertError(JSONObject response, String fragment) {
        assertFalse("expected error response: " + response, response.optBoolean("ok", true));
        String message = response.optString("error", "");
        assertTrue("expected error containing '" + fragment + "' but was: " + message,
                message.contains(fragment));
        return response;
    }

    /** Recording backend; all methods not under test return safe defaults. */
    private static final class FakeBackend implements RemoteAutomationBridge.Backend {

        boolean ready = true;
        boolean enabled = true;
        String findNodesResponse = "[]";
        String dumpResponse = "[]";
        String nodeInfoResponse = "";
        boolean clickNodeResult = true;
        boolean longClickNodeResult = true;
        boolean setTextNodeResult = true;
        boolean nodeActionResult = true;
        RuntimeException nodeActionFailure;
        String lastSelectorJson;
        int lastIndex = -1;
        String lastText;
        String lastNodeAction;
        String lastNodeActionArguments;
        int findNodesCalls;
        int getNodeInfoCalls;
        int clickNodeCalls;
        int longClickNodeCalls;
        int setTextNodeCalls;
        int nodeActionCalls;

        @Override public boolean isReady() {
            return ready;
        }

        @Override public boolean isAccessibilityServiceEnabled() {
            return enabled;
        }

        @Override public String getLatestNodeDump() {
            return dumpResponse;
        }

        @Override public String findNodes(String selectorJson) {
            findNodesCalls++;
            lastSelectorJson = selectorJson;
            return findNodesResponse;
        }

        @Override public String getNodeInfo(String selectorJson, int index) {
            getNodeInfoCalls++;
            lastSelectorJson = selectorJson;
            lastIndex = index;
            return nodeInfoResponse;
        }

        @Override public boolean clickNode(String selectorJson, int index) {
            clickNodeCalls++;
            lastSelectorJson = selectorJson;
            lastIndex = index;
            return clickNodeResult;
        }

        @Override public boolean longClickNode(String selectorJson, int index) {
            longClickNodeCalls++;
            lastSelectorJson = selectorJson;
            lastIndex = index;
            return longClickNodeResult;
        }

        @Override public boolean setTextNode(String selectorJson, int index, String text) {
            setTextNodeCalls++;
            lastSelectorJson = selectorJson;
            lastIndex = index;
            lastText = text;
            return setTextNodeResult;
        }

        @Override public boolean nodeAction(String selectorJson, int index, String action,
                                            String argumentsJson) {
            nodeActionCalls++;
            lastSelectorJson = selectorJson;
            lastIndex = index;
            lastNodeAction = action;
            lastNodeActionArguments = argumentsJson;
            if (nodeActionFailure != null) {
                throw nodeActionFailure;
            }
            return nodeActionResult;
        }

        @Override public boolean click(int x, int y) {
            return false;
        }

        @Override public boolean longClick(int x, int y) {
            return false;
        }

        @Override public boolean swipe(int x1, int y1, int x2, int y2, long duration) {
            return false;
        }

        @Override public boolean gesture(String strokesJson) {
            return false;
        }

        @Override public boolean back() {
            return false;
        }

        @Override public boolean home() {
            return false;
        }

        @Override public boolean recents() {
            return false;
        }

        @Override public boolean notifications() {
            return false;
        }

        @Override public String screenshot(String targetPath) {
            return "";
        }

        @Override public boolean subscribeAccessibilityEvents(
                RemoteAutomationBridge.AccessibilityEventListener listener) {
            return false;
        }

        @Override public void unsubscribeAccessibilityEvents(
                RemoteAutomationBridge.AccessibilityEventListener listener) {
        }
    }
}
