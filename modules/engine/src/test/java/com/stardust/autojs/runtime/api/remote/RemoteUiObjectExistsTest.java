package com.stardust.autojs.runtime.api.remote;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.lang.reflect.Proxy;
import static org.junit.Assert.*;

public class RemoteUiObjectExistsTest {
    @Test public void snapshotMustBeRevalidatedAndDisappearedNodeIsFalse() throws Exception {
        JSONObject snapshot = new JSONObject().put("path", new JSONArray("[0,2]"))
                .put("id", "app:id/button").put("className", "android.widget.Button")
                .put("packageName", "app").put("text", "old");
        final String[] live = { snapshot.toString() };
        final JSONObject[] queried = { null };
        RemoteAutomationBridge.Backend backend = (RemoteAutomationBridge.Backend) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class[]{RemoteAutomationBridge.Backend.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getNodeInfo")) {
                        queried[0] = new JSONObject((String) args[0]);
                        return live[0];
                    }
                    throw new AssertionError("Unexpected call " + method.getName());
                });
        RemoteUiObject object = new RemoteUiObject(backend, "{}", 0, snapshot, true);
        assertTrue(object.exists());
        assertEquals("[0,2]", queried[0].getJSONArray("path").toString());
        assertEquals("app:id/button", queried[0].getString("id"));
        assertFalse(queried[0].has("text")); // stable resource id survives a text update
        live[0] = "";
        assertFalse(object.exists());
        live[0] = "{}";
        assertFalse(object.exists());
    }

    @Test public void anonymousNodeKeepsEmptyTextAndRootPathIdentity() throws Exception {
        JSONObject snapshot = new JSONObject().put("path", new JSONArray())
                .put("className", "android.view.View").put("text", "").put("desc", "before");
        RemoteAutomationBridge.Backend backend = (RemoteAutomationBridge.Backend) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class[]{RemoteAutomationBridge.Backend.class},
                (proxy, method, args) -> {
                    JSONObject query = new JSONObject((String) args[0]);
                    assertEquals("[]", query.getJSONArray("path").toString());
                    assertEquals("", query.getString("text"));
                    assertEquals("before", query.getString("desc"));
                    return "";
                });
        assertFalse(new RemoteUiObject(backend, "{}", 0, snapshot, false).exists());
    }
}
