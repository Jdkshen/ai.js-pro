package com.stardust.autojs.engine.node24;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JVM-only contract for the JSON-over-Binder envelope (no Android framework needed). */
public class NodeEngineBridgeProtocolTest {

    @Test
    public void jsonArgsKeepsNestedObjectsAndArrays() throws Exception {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("serverEngineId", 42);
        value.put("title", "计时器");
        List<Object> list = new ArrayList<>();
        list.add("a");
        list.add(1);
        value.put("items", list);

        JSONArray args = NodeEngineBridgeProtocol.jsonArgs(value);
        assertEquals(1, args.length());
        JSONObject object = args.getJSONObject(0);
        assertEquals(42, object.getInt("serverEngineId"));
        assertEquals("计时器", object.getString("title"));
        assertEquals("a", object.getJSONArray("items").getString(0));
        assertEquals(1, object.getJSONArray("items").getInt(1));
    }

    @Test
    public void javaArgsRoundTripsToMapAndList() throws Exception {
        JSONArray args = new JSONArray();
        args.put(new JSONObject().put("content", "1"));
        Object[] values = NodeEngineBridgeProtocol.javaArgs(args);
        assertEquals(1, values.length);
        assertTrue(values[0] instanceof Map);
        assertEquals("1", ((Map<?, ?>) values[0]).get("content"));

        JSONArray nested = new JSONArray();
        nested.put(args);
        Object[] wrapper = NodeEngineBridgeProtocol.javaArgs(nested);
        assertTrue(wrapper[0] instanceof List);
        assertTrue(((List<?>) wrapper[0]).get(0) instanceof Map);
    }

    @Test
    public void eventEnvelopesUseRealEngineIdAndToken() throws Exception {
        JSONObject engineEvent = NodeEngineBridgeProtocol.engineEvent(42, "command",
                NodeEngineBridgeProtocol.jsonArgs("httpGet"));
        assertEquals(42, engineEvent.getInt("engineId"));
        assertEquals("command", engineEvent.getString("event"));
        assertEquals("httpGet", engineEvent.getJSONArray("args").getString(0));

        JSONObject executionEvent = NodeEngineBridgeProtocol.executionEvent("token-1", "start",
                7, new JSONArray());
        assertEquals("token-1", executionEvent.getString("token"));
        assertEquals("start", executionEvent.getString("event"));
        assertEquals(7, executionEvent.getInt("engineId"));
    }

    @Test
    public void unknownJavaObjectFallsBackToStringInsteadOfInventingApi() {
        Object value = new Object() {
            @Override
            public String toString() {
                return "opaque";
            }
        };
        assertEquals("opaque", NodeEngineBridgeProtocol.jsonValue(value));
    }
}
