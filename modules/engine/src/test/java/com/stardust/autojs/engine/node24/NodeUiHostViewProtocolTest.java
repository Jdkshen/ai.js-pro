package com.stardust.autojs.engine.node24;

import com.stardust.autojs.core.ui.inflater.util.Ids;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * task-254 宿主侧协议 / 句柄 / id 一致性的纯净 JVM 回归。
 *
 * <p>不加载任何 Android View/Looper：只测 {@link NodeUiHost} 里与真实 View 无关的纯逻辑
 * （句柄表身份、uiRequestView 参数归一化、inline 资源 id 回退），以及
 * {@link NodeUiProtocol} 的常量契约。真机 inflate/addView/监听器仍需设备验收。
 */
public class NodeUiHostViewProtocolTest {

    @Test
    public void handleRegistryReturnsStableIdentityHandle() {
        NodeUiHost.ViewHandleRegistry registry = new NodeUiHost.ViewHandleRegistry();
        Object parent = new Object();
        Object child = new Object();

        String parentHandle = registry.handleFor(parent);
        String childHandle = registry.handleFor(child);
        assertEquals("view-1", parentHandle);
        assertEquals("view-2", childHandle);
        assertNotEquals(parentHandle, childHandle);

        // 同一 View 必须复用同一 handleId，addView(child) 后 getParent() 才能命中 JS facade 缓存。
        assertSame(parentHandle, registry.handleFor(parent));
        assertSame(child, registry.get(childHandle));
        assertSame(parentHandle, registry.handleOf(parent));
        assertNull(registry.handleOf(new Object()));
        assertEquals(2, registry.size());

        registry.clear();
        assertEquals(0, registry.size());
        assertEquals("view-1", registry.handleFor(parent));
        assertNull(registry.handleOf(child));
        assertNull(registry.get(null));
    }

    @Test
    public void handleRegistryRejectsNullAndCapsGrowth() {
        NodeUiHost.ViewHandleRegistry registry = new NodeUiHost.ViewHandleRegistry();
        try {
            registry.handleFor(null);
            fail("handleFor(null) 必须被拒绝");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        // 超过上限后仍能继续注册（淘汰最老句柄），不会无限增长。
        for (int i = 0; i < 4200; i++) {
            registry.handleFor(new Object());
        }
        assertTrue("句柄表应有上限", registry.size() <= 4096);
    }

    @Test
    public void resolveViewActionAcceptsWrapperAndDirectForms() throws Exception {
        // 1) 新协议：op=uiRequestView + action
        JSONObject wrapper = new JSONObject().put("op", "uiRequestView").put("action", "addView");
        assertEquals("addView", NodeUiHost.resolveViewAction(wrapper));
        // 2) 旧协议：op=viewCommand + action
        JSONObject command = new JSONObject().put("op", "viewCommand").put("action", "getParent");
        assertEquals("getParent", NodeUiHost.resolveViewAction(command));
        // 3) action 直接作为 op
        JSONObject direct = new JSONObject().put("op", "removeView");
        assertEquals("removeView", NodeUiHost.resolveViewAction(direct));
        // 4) action 放在 params 里
        JSONObject params = new JSONObject()
                .put("op", "uiRequestView")
                .put("params", new JSONObject().put("action", "setOnItemSelectedListener"));
        assertEquals("setOnItemSelectedListener", NodeUiHost.resolveViewAction(params));
        // 5) 缺 action 时不把 uiRequestView 当动作名
        assertEquals("", NodeUiHost.resolveViewAction(new JSONObject().put("op", "uiRequestView")));
        assertEquals("", NodeUiHost.resolveViewAction(null));
    }

    @Test
    public void viewActionParamsMergeParamsOverTopLevel() throws Exception {
        JSONObject request = new JSONObject()
                .put("op", "uiRequestView")
                .put("action", "addView")
                .put("handleId", "view-1")
                .put("params", new JSONObject()
                        .put("handleId", "view-9")
                        .put("childHandle", "view-2")
                        .put("index", 1));

        JSONObject merged = NodeUiHost.viewActionParams(request);
        assertEquals("view-9", merged.getString("handleId"));
        assertEquals("view-2", merged.getString("childHandle"));
        assertEquals(1, merged.getInt("index"));
        assertEquals("addView", merged.getString("action"));
        assertFalse("params 容器本身不应残留", merged.has("params"));
    }

    @Test
    public void isViewActionCoversDocumentedProtocol() {
        String[] actions = {
                "addView", "removeView", "removeAllViews", "getParent", "getChildCount", "getChildAt",
                "setOnClickListener", "setOnCheckedChangeListener", "setOnTextChangedListener",
                "setOnItemSelectedListener", "getSelectedItemId", "setSelectedItemId",
                "getMenuSize", "getMenuItemId", "menuItems", "postDelayed", "removeCallbacks"
        };
        for (String action : actions) {
            assertTrue(action, NodeUiHost.isViewAction(action));
        }
        assertFalse(NodeUiHost.isViewAction("setText"));
        assertFalse(NodeUiHost.isViewAction(null));
    }

    @Test
    public void inlineResourceIdFallbackKeepsMenuAndLayoutIdsOnSameTable() {
        // 2.7：宿主资源表没有工程自建 id 时，ui.R.id.* 必须与布局/菜单项的 Ids.parse 同源。
        int parsed = Ids.parse("navigation_home");
        assertNotEquals(0, parsed);
        assertEquals(parsed, NodeUiHost.inlineResourceIdFallback(0, "id", "navigation_home"));
        assertEquals(parsed, NodeUiHost.inlineResourceIdFallback(0, "id", "navigation_home"));

        // 宿主资源表命中时保持原值（不覆盖真实资源 id）。
        assertEquals(0x7f0f0001, NodeUiHost.inlineResourceIdFallback(0x7f0f0001, "id", "anything"));
        // 非 id 类型仍返回 0，不能伪造资源。
        assertEquals(0, NodeUiHost.inlineResourceIdFallback(0, "drawable", "ic_home"));
        assertEquals(0, NodeUiHost.inlineResourceIdFallback(0, "id", ""));
    }

    @Test
    public void protocolConstantsMatchJsClient() {
        assertEquals("uiRequestView", NodeUiProtocol.OP_UI_REQUEST_VIEW);
        assertEquals("viewCommand", NodeUiProtocol.OP_VIEW_COMMAND);
        assertEquals("handleId", NodeUiProtocol.K_HANDLE);
        assertEquals("childHandle", NodeUiProtocol.K_CHILD_HANDLE);
        assertEquals("parentHandle", NodeUiProtocol.K_PARENT_HANDLE);
        assertEquals("attachToParent", NodeUiProtocol.K_ATTACH_TO_PARENT);
        assertEquals("action", NodeUiProtocol.K_ACTION);
        assertEquals("params", NodeUiProtocol.K_PARAMS);
        assertEquals("channel", NodeUiProtocol.K_CHANNEL);
        assertEquals("check", NodeUiProtocol.EVENT_CHECK);
        assertEquals("text_change", NodeUiProtocol.EVENT_TEXT_CHANGE);
        assertEquals("item_selected", NodeUiProtocol.EVENT_ITEM_SELECTED);
        assertEquals("timer", NodeUiProtocol.EVENT_TIMER);
    }
}
