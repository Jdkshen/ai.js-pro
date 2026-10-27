package com.jdkshen.aijspro.external.foreground;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/**
 * Builds the {@code miui.focus.param} JSON for a running-script notification on
 * Xiaomi HyperOS. This class deliberately has no Android dependency: it only
 * produces the string that a notification builder places in its extras.
 *
 * <p>The native side must still provide the referenced resources/actions:
 * an icon under {@link #PICTURE_KEY} in the {@code miui.focus.pics} bundle and
 * {@code Notification.Action} entries under the three action keys in the
 * {@code miui.focus.actions} bundle. The JSON contains references only.</p>
 *
 * <p>The result is always valid UTF-8 JSON and strictly smaller than 3072
 * bytes. User text is bounded by Unicode code points; an unpaired surrogate is
 * replaced rather than emitted, and a valid surrogate pair is never split.
 * Line breaks, quotes and emoji are preserved as JSON string values.</p>
 */
public final class XiaomiFocusPayload {
    public static final String PICTURE_KEY = "miui.focus.pic_app";
    public static final String ACTION_STOP_CURRENT = "miui.focus.action_stop_current";
    public static final String ACTION_STOP_ALL = "miui.focus.action_stop_all";
    public static final String ACTION_DETAILS = "miui.focus.action_details";

    private static final int MAX_PAYLOAD_BYTES = 3072;
    private static final int MAX_NAME_CODE_POINTS = 48;
    private static final int MAX_ENGINES_CODE_POINTS = 48;
    private static final int MAX_ELAPSED_CODE_POINTS = 24;
    private static final int MAX_DETAILS_CODE_POINTS = 160;

    private static final String DEFAULT_NAME = "当前任务";
    private static final String DEFAULT_ENGINES = "AI.js Pro";
    private static final String DEFAULT_ELAPSED = "刚刚开始";

    private XiaomiFocusPayload() {
    }

    /**
     * Creates the island payload, or returns {@code null} when there is no task
     * left (count &lt;= 0), which lets the caller cancel/remove the notification.
     *
     * @param count       number of running tasks, at least one
     * @param currentName name of the current task
     * @param engines     engine description shown in the focused notification
     * @param elapsed     already formatted running time
     * @param details     optional extra detail shown in the focused notification
     */
    public static String build(int count, String currentName, String engines,
                               String elapsed, String details) {
        if (count <= 0) {
            return null;
        }

        String[] values = new String[]{
                bounded(currentName, MAX_NAME_CODE_POINTS, DEFAULT_NAME),
                bounded(engines, MAX_ENGINES_CODE_POINTS, DEFAULT_ENGINES),
                bounded(elapsed, MAX_ELAPSED_CODE_POINTS, DEFAULT_ELAPSED),
                bounded(details, MAX_DETAILS_CODE_POINTS, "")
        };

        while (true) {
            String payload;
            try {
                payload = createPayload(count, values).toString();
            } catch (JSONException error) {
                throw new IllegalStateException("Unable to build Xiaomi focus payload", error);
            }

            if (payload.getBytes(StandardCharsets.UTF_8).length < MAX_PAYLOAD_BYTES) {
                return payload;
            }
            if (!dropOneCodePoint(values)) {
                throw new IllegalStateException(
                        "Xiaomi focus payload cannot fit below " + MAX_PAYLOAD_BYTES + " bytes");
            }
        }
    }

    private static JSONObject createPayload(int count, String[] values) throws JSONException {
        String name = values[0];
        String engineText = values[1];
        String runtime = values[2];
        String detailText = values[3];
        String total = count + " 个任务";

        JSONObject paramV2 = new JSONObject();
        paramV2.put("protocol", 1);
        paramV2.put("business", "script_runtime");
        paramV2.put("updatable", true);
        paramV2.put("reopen", "reopen");
        paramV2.put("islandFirstFloat", false);
        paramV2.put("enableFloat", false);
        paramV2.put("filterWhenNoPermission", false);
        paramV2.put("ticker", "脚本运行中 · " + total);
        paramV2.put("aodTitle", name + " · " + total);
        paramV2.put("param_island", createIsland(total, runtime));
        paramV2.put("baseInfo", createBaseInfo(name, engineText, runtime, detailText, total));
        paramV2.put("actions", createActions());

        JSONObject root = new JSONObject();
        root.put("param_v2", paramV2);
        return root;
    }

    private static JSONObject createIsland(String total, String runtime) throws JSONException {
        JSONObject leftPic = createPicture();
        JSONObject leftText = new JSONObject();
        leftText.put("title", "运行");
        leftText.put("content", total.replace(" 个任务", "项"));
        leftText.put("showHighlightColor", false);

        JSONObject left = new JSONObject();
        left.put("type", 1);
        left.put("picInfo", leftPic);
        left.put("textInfo", leftText);

        JSONObject rightPic = createPicture();
        JSONObject rightText = new JSONObject();
        rightText.put("frontTitle", "");
        rightText.put("title", runtime);
        rightText.put("showHighlightColor", false);

        JSONObject right = new JSONObject();
        right.put("type", 2);
        right.put("picInfo", rightPic);
        right.put("textInfo", rightText);

        JSONObject bigIslandArea = new JSONObject();
        bigIslandArea.put("imageTextInfoLeft", left);
        bigIslandArea.put("imageTextInfoRight", right);

        JSONObject smallIslandArea = new JSONObject();
        smallIslandArea.put("picInfo", createPicture());

        JSONObject island = new JSONObject();
        island.put("bigIslandArea", bigIslandArea);
        island.put("smallIslandArea", smallIslandArea);
        return island;
    }

    private static JSONObject createBaseInfo(String name, String engineText, String runtime,
                                             String detailText, String total) throws JSONException {
        JSONObject baseInfo = new JSONObject();
        baseInfo.put("type", 2);
        baseInfo.put("title", name);
        baseInfo.put("subTitle", total);
        baseInfo.put("content", "运行中 · " + runtime);
        baseInfo.put("extraTitle", engineText);
        baseInfo.put("subContent", detailText);
        return baseInfo;
    }

    private static JSONObject createPicture() throws JSONException {
        JSONObject picture = new JSONObject();
        picture.put("type", 1);
        picture.put("pic", PICTURE_KEY);
        return picture;
    }

    private static JSONArray createActions() throws JSONException {
        JSONArray actions = new JSONArray();
        actions.put(actionReference(ACTION_STOP_CURRENT));
        actions.put(actionReference(ACTION_STOP_ALL));
        actions.put(actionReference(ACTION_DETAILS));
        return actions;
    }

    private static JSONObject actionReference(String action) throws JSONException {
        JSONObject reference = new JSONObject();
        reference.put("type", 0);
        reference.put("action", action);
        return reference;
    }

    /**
     * Produces valid text with normalized line endings, no unpaired surrogates
     * and no ISO control characters other than line feed/tab.
     */
    private static String sanitize(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String normalized = raw.indexOf('\r') < 0
                ? raw
                : raw.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder result = new StringBuilder(normalized.length());
        for (int index = 0; index < normalized.length(); ) {
            int codePoint = normalized.codePointAt(index);
            index += Character.charCount(codePoint);
            if (codePoint >= Character.MIN_SURROGATE
                    && codePoint <= Character.MAX_SURROGATE) {
                result.append('\uFFFD');
            } else if (codePoint == '\n' || codePoint == '\t'
                    || !Character.isISOControl(codePoint)) {
                result.appendCodePoint(codePoint);
            }
        }
        return result.toString();
    }

    private static String bounded(String raw, int maxCodePoints, String fallback) {
        String text = sanitize(raw);
        if (text.trim().isEmpty()) {
            return fallback;
        }
        if (text.codePointCount(0, text.length()) > maxCodePoints) {
            int end = text.offsetByCodePoints(0, maxCodePoints);
            return text.substring(0, end);
        }
        return text;
    }

    /**
     * Shrinks optional text in a stable order until the UTF-8 JSON fits. Details
     * are sacrificed first, then the task name, engine description and runtime.
     */
    private static boolean dropOneCodePoint(String[] values) {
        for (int index : new int[]{3, 0, 1, 2}) {
            String value = values[index];
            if (value.codePointCount(0, value.length()) > 1) {
                int end = value.offsetByCodePoints(value.length(), -1);
                values[index] = value.substring(0, end);
                return true;
            }
        }
        return false;
    }
}
