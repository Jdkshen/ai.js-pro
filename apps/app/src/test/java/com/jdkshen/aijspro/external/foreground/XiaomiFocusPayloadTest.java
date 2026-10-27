package com.jdkshen.aijspro.external.foreground;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class XiaomiFocusPayloadTest {

    @Test public void buildsTheDocumentedOs2Os3Schema() throws Exception {
        String payload = XiaomiFocusPayload.build(2,
                "主任务 \"A\" 😀", "Rhino + QuickJS", "00:42",
                "第 1 行\r\n第 2 行 \"quoted\" 😀");

        assertNotNull(payload);
        assertTrue("payload must be strict UTF-8 below 3072 bytes",
                payload.getBytes(StandardCharsets.UTF_8).length < 3072);
        assertFalse(payload.contains("paramtextInfo"));
        assertFalse("JSON encoding must escape line breaks", payload.contains("\n"));
        assertTrue(payload.contains("\\n"));
        assertTrue(payload.contains("\\\""));

        JSONObject root = new JSONObject(payload);
        assertEquals(1, root.length());
        assertTrue(root.has("param_v2"));

        JSONObject paramV2 = root.getJSONObject("param_v2");
        assertEquals(keys("protocol", "business", "updatable", "reopen",
                "islandFirstFloat", "enableFloat", "filterWhenNoPermission",
                "ticker", "aodTitle", "param_island", "baseInfo", "actions"),
                jsonKeys(paramV2));
        assertEquals(1, paramV2.getInt("protocol"));
        assertEquals("script_runtime", paramV2.getString("business"));
        assertTrue(paramV2.getBoolean("updatable"));
        assertEquals("reopen", paramV2.getString("reopen"));
        assertFalse(paramV2.getBoolean("islandFirstFloat"));
        assertFalse(paramV2.getBoolean("enableFloat"));
        assertFalse(paramV2.getBoolean("filterWhenNoPermission"));
        assertEquals("脚本运行中 · 2 个任务", paramV2.getString("ticker"));
        assertEquals("主任务 \"A\" 😀 · 2 个任务", paramV2.getString("aodTitle"));

        JSONObject island = paramV2.getJSONObject("param_island");
        assertEquals(keys("bigIslandArea", "smallIslandArea"), jsonKeys(island));
        JSONObject bigIsland = island.getJSONObject("bigIslandArea");
        assertEquals(keys("imageTextInfoLeft", "imageTextInfoRight"),
                jsonKeys(bigIsland));

        JSONObject left = bigIsland.getJSONObject("imageTextInfoLeft");
        assertEquals(keys("type", "picInfo", "textInfo"), jsonKeys(left));
        assertEquals(1, left.getInt("type"));
        assertPicture(left.getJSONObject("picInfo"));
        JSONObject leftText = left.getJSONObject("textInfo");
        assertEquals(keys("title", "content", "showHighlightColor"), jsonKeys(leftText));
        assertEquals("运行", leftText.getString("title"));
        assertEquals("2项", leftText.getString("content"));
        assertFalse(leftText.getBoolean("showHighlightColor"));

        JSONObject right = bigIsland.getJSONObject("imageTextInfoRight");
        assertEquals(keys("type", "picInfo", "textInfo"), jsonKeys(right));
        assertEquals(2, right.getInt("type"));
        assertPicture(right.getJSONObject("picInfo"));
        JSONObject rightText = right.getJSONObject("textInfo");
        assertEquals(keys("frontTitle", "title", "showHighlightColor"),
                jsonKeys(rightText));
        assertEquals("", rightText.getString("frontTitle"));
        assertEquals("00:42", rightText.getString("title"));
        assertFalse(rightText.getBoolean("showHighlightColor"));

        JSONObject smallIsland = island.getJSONObject("smallIslandArea");
        assertEquals(keys("picInfo"), jsonKeys(smallIsland));
        assertPicture(smallIsland.getJSONObject("picInfo"));

        JSONObject baseInfo = paramV2.getJSONObject("baseInfo");
        assertEquals(keys("type", "title", "subTitle", "content",
                "extraTitle", "subContent"), jsonKeys(baseInfo));
        assertEquals(2, baseInfo.getInt("type"));
        assertEquals("主任务 \"A\" 😀", baseInfo.getString("title"));
        assertEquals("2 个任务", baseInfo.getString("subTitle"));
        assertEquals("运行中 · 00:42", baseInfo.getString("content"));
        assertEquals("Rhino + QuickJS", baseInfo.getString("extraTitle"));
        assertEquals("第 1 行\n第 2 行 \"quoted\" 😀",
                baseInfo.getString("subContent"));
    }

    @Test public void actionsAreReferencesAndNeverAutoExpand() throws Exception {
        assertEquals("miui.focus.pic_app", XiaomiFocusPayload.PICTURE_KEY);
        assertEquals("miui.focus.action_stop_current",
                XiaomiFocusPayload.ACTION_STOP_CURRENT);
        assertEquals("miui.focus.action_stop_all",
                XiaomiFocusPayload.ACTION_STOP_ALL);
        assertEquals("miui.focus.action_details",
                XiaomiFocusPayload.ACTION_DETAILS);

        JSONObject paramV2 = new JSONObject(XiaomiFocusPayload.build(
                1, "任务", "Rhino", "00:01", "详情"))
                .getJSONObject("param_v2");

        JSONArray actions = paramV2.getJSONArray("actions");
        assertEquals(3, actions.length());
        String[] expected = new String[]{
                XiaomiFocusPayload.ACTION_STOP_CURRENT,
                XiaomiFocusPayload.ACTION_STOP_ALL,
                XiaomiFocusPayload.ACTION_DETAILS
        };
        for (int index = 0; index < expected.length; index++) {
            JSONObject action = actions.getJSONObject(index);
            assertEquals(keys("type", "action"), jsonKeys(action));
            assertEquals(expected[index], action.getString("action"));
            assertFalse(action.has("actionIntent"));
            assertFalse(action.has("actionIcon"));
            assertFalse(action.has("actionTitle"));
        }

        assertFalse(paramV2.getBoolean("islandFirstFloat"));
        assertFalse(paramV2.getBoolean("enableFloat"));
    }

    @Test public void emptyTaskCountRemovesTheNotification() {
        assertNull(XiaomiFocusPayload.build(0, "任务", "Rhino", "00:01", "详情"));
        assertNull(XiaomiFocusPayload.build(-3, "任务", "Rhino", "00:01", "详情"));
        assertNotNull(XiaomiFocusPayload.build(1, "任务", "Rhino", "00:01", "详情"));
    }

    @Test public void extremeUnicodeStaysBelowTheUtf8BudgetWithoutSplittingPairs()
            throws Exception {
        StringBuilder huge = new StringBuilder("\"\\\n中");
        for (int index = 0; index < 12000; index++) {
            huge.append("x😀");
        }
        String input = huge.toString();

        String payload = XiaomiFocusPayload.build(
                Integer.MAX_VALUE, input, input, input, input);
        int bytes = payload.getBytes(StandardCharsets.UTF_8).length;
        assertTrue("payload was " + bytes + " bytes", bytes < 3072);
        assertFalse("payload contains an unpaired surrogate",
                hasUnpairedSurrogate(payload));

        JSONObject root = new JSONObject(payload);
        JSONObject baseInfo = root.getJSONObject("param_v2").getJSONObject("baseInfo");
        assertTrue(baseInfo.getString("title").codePointCount(0,
                baseInfo.getString("title").length()) <= 64);
        assertTrue("truncation must end on a whole surrogate pair",
                baseInfo.getString("title").endsWith("😀"));
        assertTrue(baseInfo.getString("subContent").codePointCount(0,
                baseInfo.getString("subContent").length()) <= 200);
        assertEquals(3, root.getJSONObject("param_v2").getJSONArray("actions").length());
    }

    private static Set<String> jsonKeys(JSONObject object) {
        Set<String> result=new java.util.HashSet<>();java.util.Iterator<String> it=object.keys();while(it.hasNext())result.add(it.next());return result;
    }

    private static Set<String> keys(String... keys) {
        return new HashSet<>(Arrays.asList(keys));
    }

    private static void assertPicture(JSONObject picture) throws Exception {
        assertEquals(keys("type", "pic"), jsonKeys(picture));
        assertEquals(1, picture.getInt("type"));
        assertEquals(XiaomiFocusPayload.PICTURE_KEY, picture.getString("pic"));
    }

    private static boolean hasUnpairedSurrogate(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    return true;
                }
                index++;
            } else if (Character.isLowSurrogate(current)) {
                return true;
            }
        }
        return false;
    }
}
