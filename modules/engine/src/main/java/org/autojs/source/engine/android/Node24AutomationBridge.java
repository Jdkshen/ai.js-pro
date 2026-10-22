// SPDX-License-Identifier: MIT
package org.autojs.source.engine.android;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.provider.Settings;
import android.util.Log;

import com.stardust.autojs.runtime.api.remote.RemoteAutomationBridge;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Iterator;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * {@code kind:"automation"} 的 Java 宿主实现。
 *
 * <p>引擎模块不能反向依赖 app 模块，所以这里用反射拿 app 侧的
 * {@code com.jdkshen.aijspro.flutterpoc.scriptproc.AutomationHostClient}，它实现
 * {@link RemoteAutomationBridge.Backend} 并绑定主进程常驻的
 * {@code AutomationHostService}。本类<b>不会</b>调用 {@code RemoteAutomationBridge.install}：
 * 该全局安装只属于 {@code :script} 进程，在 :node24 安装会污染现有脚本进程的自动化路由。
 *
 * <p>每个 Node 任务一个实例，任务结束由 {@link #close()} 解绑；不缓存主进程授权，不伪造成功。
 * 无后台服务/无障碍未就绪时，除 {@code status}/{@code openSettings} 外一律返回中文错误。
 */
public final class Node24AutomationBridge implements AutoCloseable {

    private static final String TAG = "Node24Automation";
    private static final String CLIENT_CLASS =
            "com.jdkshen.aijspro.flutterpoc.scriptproc.AutomationHostClient";
    private static final String SERVICE_NOT_ENABLED_MESSAGE =
            "无障碍服务未开启，请在系统设置中开启 AI.js Pro 的无障碍服务";
    private static final String SERVICE_NOT_RUNNING_MESSAGE =
            "无障碍服务已开启但未运行，请回到 AI.js Pro 重新打开后再试";
    private static final int MAX_GESTURE_STROKES = 10;
    private static final int MAX_GESTURE_POINTS = 1000;
    private static final long MAX_GESTURE_OFFSET_MS = 60_000L;
    /** 单个 selector JSON 的字符上限（与 Binder/主进程节点协议保持余量）。 */
    private static final int MAX_SELECTOR_CHARS = 32_768;
    /** getNodeInfo/动作接口的匹配序号上限。 */
    private static final int MAX_NODE_INDEX = 255;
    /** selector.index 过滤的是 indexInParent，范围与主进程遍历上限一致。 */
    private static final int MAX_SELECTOR_INDEX = 8191;
    /** selector.depth / selector.path 长度上限。 */
    private static final int MAX_SELECTOR_DEPTH = 63;
    /** bounds 坐标允许屏幕外负值，但绝对值必须有界，避免意外超大坐标。 */
    private static final long MAX_BOUNDS_ABS = 10_000_000L;
    private static final String SUPPORTED_SELECTOR_KEYS =
            "id/text/textContains/textStartsWith/textMatches/desc/descContains/className/"
                    + "packageName/clickable/enabled/scrollable/depth/index/path/bounds";

    private final Context mContext;
    private final Node24Images mImages;
    private RemoteAutomationBridge.Backend mBackend;
    private Object mClient;
    private boolean mClosed;
    private boolean mBackendFailureLogged;

    public Node24AutomationBridge(Context context, Node24Images images) {
        mContext = context.getApplicationContext();
        mImages = images;
    }

    // ------------------------------------------------------------------
    // kind:"automation" 入口
    // ------------------------------------------------------------------

    /** 返回宿主统一 envelope。 */
    public synchronized JSONObject handle(JSONObject request) {
        try {
            String action = request.optString("action", "");
            switch (action) {
                case "status":
                    return status();
                case "openSettings":
                    openAccessibilitySettings();
                    return ok(Boolean.TRUE);
                case "click":
                    return ok(click(request));
                case "longClick":
                    return ok(longClick(request));
                case "press":
                    return ok(press(request));
                case "swipe":
                    return ok(swipe(request));
                case "gesture":
                    return ok(gesture(request));
                case "back":
                    return ok(requireReady().back());
                case "home":
                    return ok(requireReady().home());
                case "recents":
                    return ok(requireReady().recents());
                case "notifications":
                    return ok(requireReady().notifications());
                case "takeScreenshot":
                    return ok(takeScreenshot(request.optInt("maxWidth", 0),
                            request.optInt("maxHeight", 0)));
                case "findNodes":
                case "getLatestNodeDump":
                case "getNodeInfo":
                case "clickNode":
                case "longClickNode":
                case "setTextNode":
                    return handleNodeAction(action, request);
                default:
                    return error("automation 不支持的操作：" + action
                            + "（支持 status/openSettings/click/longClick/press/swipe/gesture/"
                            + "back/home/recents/notifications/takeScreenshot/findNodes/"
                            + "getNodeInfo/getLatestNodeDump/clickNode/longClickNode/setTextNode）");
            }
        } catch (Throwable failure) {
            Log.w(TAG, "automation action failed: " + request, failure);
            return error(describe(failure));
        }
    }

    private JSONObject status() {
        boolean backendReady = false;
        boolean enabled = false;
        try {
            RemoteAutomationBridge.Backend backend = backend();
            backendReady = safeReady(backend);
            enabled = safeEnabled(backend);
        } catch (Throwable error) {
            // 主进程后端缺失/未连接：status 仍如实反映系统设置里的 enabled，ready 固定 false；
            // 具体动作入口会返回明确错误。轮询时只记一次日志，避免刷屏。
            if (!mBackendFailureLogged) {
                mBackendFailureLogged = true;
                Log.w(TAG, "automation status unavailable", error);
            }
        }
        if (!enabled) {
            // 即使后端不存在，:node24 也能读系统设置，不能把「已开启」误报成未开启。
            enabled = readAccessibilityEnabledFromSettings();
        }
        // task-139 follow-up：ready 表示「动作现在能成功」，必须与 enabled 绑定；
        // 系统无障碍已关闭但后端仍连接时不能报 ready=true（否则脚本会误判）。
        boolean ready = enabled && backendReady;
        try {
            return ok(new JSONObject().put("enabled", enabled).put("ready", ready));
        } catch (JSONException impossible) {
            return error("automation.status 响应编码失败");
        }
    }

    private boolean readAccessibilityEnabledFromSettings() {
        if (mContext == null) {
            // 正常构造器保证非 null；纯 JVM 测试注入 fake backend 时按未开启处理。
            return false;
        }
        try {
            if (android.provider.Settings.Secure.getInt(mContext.getContentResolver(),
                    android.provider.Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) {
                return false;
            }
            String services = android.provider.Settings.Secure.getString(mContext.getContentResolver(),
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (services == null) {
                return false;
            }
            String prefix = mContext.getPackageName() + "/";
            for (String name : services.split(":")) {
                if (name != null && name.startsWith(prefix)) {
                    return true;
                }
            }
        } catch (Throwable error) {
            Log.w(TAG, "read accessibility settings failed", error);
        }
        return false;
    }

    private boolean click(JSONObject request) {
        int x = requireCoordinate(request, "x");
        int y = requireCoordinate(request, "y");
        return requireReady().click(x, y);
    }

    private boolean longClick(JSONObject request) {
        int x = requireCoordinate(request, "x");
        int y = requireCoordinate(request, "y");
        return requireReady().longClick(x, y);
    }

    /** 一次同点按压：用一条 stroke 表达，保留 duration。 */
    private boolean press(JSONObject request) {
        int x = requireCoordinate(request, "x");
        int y = requireCoordinate(request, "y");
        long duration = request.optLong("duration", 0L);
        if (duration < 1L || duration > 60_000L) {
            throw new IllegalArgumentException("press.duration 必须在 1..60000 毫秒之间，收到：" + duration);
        }
        try {
            return requireReady().gesture(pressGestureJson(x, y, duration));
        } catch (JSONException error) {
            throw new IllegalArgumentException("press 参数编码失败：" + error.getMessage(), error);
        }
    }

    private boolean swipe(JSONObject request) {
        int x1 = requireCoordinate(request, "x1");
        int y1 = requireCoordinate(request, "y1");
        int x2 = requireCoordinate(request, "x2");
        int y2 = requireCoordinate(request, "y2");
        long duration = request.optLong("duration", 300L);
        if (duration < 1L || duration > 60_000L) {
            throw new IllegalArgumentException("swipe.duration 必须在 1..60000 毫秒之间，收到：" + duration);
        }
        return requireReady().swipe(x1, y1, x2, y2, duration);
    }

    private boolean gesture(JSONObject request) {
        JSONArray strokes = request.optJSONArray("strokes");
        if (strokes == null) {
            throw new IllegalArgumentException("gesture 需要 strokes 数组");
        }
        try {
            return requireReady().gesture(normalizeStrokes(strokes));
        } catch (JSONException error) {
            throw new IllegalArgumentException("gesture 参数编码失败：" + error.getMessage(), error);
        }
    }

    /**
     * 节点查询/动作统一入口。
     *
     * <p>参数错误、未就绪和主进程返回的协议错误都转换成现有 error envelope；不会把解析失败
     * 伪装成「没找到节点」。真正的后端运行时异常仍交给 {@link #handle(JSONObject)} 统一记录。
     */
    private JSONObject handleNodeAction(String action, JSONObject request) {
        try {
            switch (action) {
                case "findNodes":
                    return findNodes(request);
                case "getLatestNodeDump":
                    return nodeArray(requireReady().getLatestNodeDump(), "getLatestNodeDump");
                case "getNodeInfo":
                    return getNodeInfo(request);
                case "clickNode":
                    return ok(clickNode(request));
                case "longClickNode":
                    return ok(longClickNode(request));
                case "setTextNode":
                    return ok(setTextNode(request));
                default:
                    return error("automation 不支持的操作：" + action);
            }
        } catch (IllegalArgumentException | IllegalStateException | JSONException failure) {
            return error(describe(failure));
        }
    }

    private JSONObject findNodes(JSONObject request) throws JSONException {
        String selectorJson = requireSelectorJson(request);
        // findNodes 返回全部匹配项、本身不按 index 取结果；但统一校验请求字段，
        // 避免非法 index 被静默忽略后与其它动作行为不一致。
        requireNodeIndex(request);
        return nodeArray(requireReady().findNodes(selectorJson), "findNodes");
    }

    private static JSONObject nodeArray(String raw, String action) throws JSONException {
        if (raw == null) {
            throw new JSONException(action + " 响应为 null，主进程节点协议错误");
        }
        try {
            return ok(new JSONArray(raw));
        } catch (JSONException error) {
            throw new JSONException(action + " 响应不是合法的 JSON 数组：" + describe(error));
        }
    }

    private JSONObject getNodeInfo(JSONObject request) throws JSONException {
        String selectorJson = requireSelectorJson(request);
        int index = requireNodeIndex(request);
        String raw = requireReady().getNodeInfo(selectorJson, index);
        if (raw == null) {
            throw new JSONException("getNodeInfo 响应为 null，主进程节点协议错误");
        }
        // 契约里空串才表示「没有匹配到节点」；其它非法 JSON 必须报协议错误，不能返回 null。
        if (raw.isEmpty()) {
            return ok(JSONObject.NULL);
        }
        try {
            return ok(new JSONObject(raw));
        } catch (JSONException error) {
            throw new JSONException("getNodeInfo 响应不是合法的 JSON 对象：" + describe(error));
        }
    }

    private boolean clickNode(JSONObject request) {
        String selectorJson = requireSelectorJson(request);
        int index = requireNodeIndex(request);
        return requireReady().clickNode(selectorJson, index);
    }

    private boolean longClickNode(JSONObject request) {
        String selectorJson = requireSelectorJson(request);
        int index = requireNodeIndex(request);
        return requireReady().longClickNode(selectorJson, index);
    }

    private boolean setTextNode(JSONObject request) {
        String selectorJson = requireSelectorJson(request);
        int index = requireNodeIndex(request);
        String text = requireNodeText(request);
        return requireReady().setTextNode(selectorJson, index, text);
    }

    /**
     * 校验 selector 并返回真正传给主进程的 JSON。
     *
     * <p>主进程的 {@code SelectorSpec.parse} 会忽略未知字段、并把部分类型宽松转换（例如
     * {@code optString} 会把数字变字符串）；这里必须先严格拒绝，否则条件会被静默忽略后
     * 仍按剩余条件执行动作，可能误点其它节点。
     */
    private static String requireSelectorJson(JSONObject request) {
        Object raw = request == null ? null : request.opt("selector");
        if (!(raw instanceof JSONObject)) {
            if (raw == null || raw == JSONObject.NULL) {
                throw new IllegalArgumentException(
                        "automation 节点操作需要 selector（JSON 对象；空对象 {} 表示无过滤条件）");
            }
            throw new IllegalArgumentException("selector 必须是 JSON 对象，收到：" + valueType(raw));
        }
        return validateSelector((JSONObject) raw);
    }

    private static String validateSelector(JSONObject selector) {
        if (selector == null) {
            throw new IllegalArgumentException("selector 不能为空");
        }
        String selectorJson = selector.toString();
        if (selectorJson.length() > MAX_SELECTOR_CHARS) {
            throw new IllegalArgumentException("selector JSON 过长（" + selectorJson.length()
                    + " 字符，上限 " + MAX_SELECTOR_CHARS + "）");
        }
        for (Iterator<String> keys = selector.keys(); keys.hasNext(); ) {
            String key = keys.next();
            Object value = selector.opt(key);
            switch (key) {
                case "id":
                case "text":
                case "textContains":
                case "textStartsWith":
                case "desc":
                case "descContains":
                case "className":
                case "packageName":
                    requireString(value, "selector." + key);
                    break;
                case "textMatches":
                    validateTextMatches(requireString(value, "selector.textMatches"));
                    break;
                case "clickable":
                case "enabled":
                case "scrollable":
                    requireBoolean(value, "selector." + key);
                    break;
                case "depth":
                    requireRange(value, 0L, MAX_SELECTOR_DEPTH, "selector.depth");
                    break;
                case "index":
                    requireRange(value, 0L, MAX_SELECTOR_INDEX, "selector.index");
                    break;
                case "path":
                    validatePath(value);
                    break;
                case "bounds":
                    validateBounds(value);
                    break;
                default:
                    throw new IllegalArgumentException("selector 不支持字段：" + key
                            + "（支持 " + SUPPORTED_SELECTOR_KEYS + "）");
            }
        }
        return selectorJson;
    }

    private static void validateTextMatches(String regex) {
        try {
            Pattern.compile(regex);
        } catch (PatternSyntaxException error) {
            throw new IllegalArgumentException("selector.textMatches 不是合法的 Java 正则表达式："
                    + describe(error), error);
        }
    }

    private static void validatePath(Object value) {
        if (!(value instanceof JSONArray)) {
            throw new IllegalArgumentException("selector.path 必须是非负整数数组，收到："
                    + valueType(value));
        }
        JSONArray path = (JSONArray) value;
        if (path.length() > MAX_SELECTOR_DEPTH) {
            throw new IllegalArgumentException("selector.path 过长（" + path.length()
                    + " 项，上限 " + MAX_SELECTOR_DEPTH + "）");
        }
        for (int i = 0; i < path.length(); i++) {
            requireRange(path.opt(i), 0L, Integer.MAX_VALUE, "selector.path[" + i + "]");
        }
    }

    private static void validateBounds(Object value) {
        if (!(value instanceof JSONObject)) {
            throw new IllegalArgumentException("selector.bounds 必须是对象，收到：" + valueType(value));
        }
        JSONObject bounds = (JSONObject) value;
        if (bounds.length() != 4) {
            throw new IllegalArgumentException(
                    "selector.bounds 必须且只能包含 left/top/right/bottom 四个整数");
        }
        for (Iterator<String> keys = bounds.keys(); keys.hasNext(); ) {
            String key = keys.next();
            if (!isBoundsKey(key)) {
                throw new IllegalArgumentException("selector.bounds 不支持字段：" + key);
            }
        }
        long left = requireBoundsValue(bounds, "left");
        long top = requireBoundsValue(bounds, "top");
        long right = requireBoundsValue(bounds, "right");
        long bottom = requireBoundsValue(bounds, "bottom");
        if (right < left) {
            throw new IllegalArgumentException("selector.bounds.right 必须 >= left");
        }
        if (bottom < top) {
            throw new IllegalArgumentException("selector.bounds.bottom 必须 >= top");
        }
    }

    private static boolean isBoundsKey(String key) {
        return "left".equals(key) || "top".equals(key) || "right".equals(key)
                || "bottom".equals(key);
    }

    private static long requireBoundsValue(JSONObject bounds, String name) {
        if (!bounds.has(name)) {
            throw new IllegalArgumentException("selector.bounds 缺少 " + name);
        }
        long value = requireIntegral(bounds.opt(name), "selector.bounds." + name);
        if (value < -MAX_BOUNDS_ABS || value > MAX_BOUNDS_ABS) {
            throw new IllegalArgumentException("selector.bounds." + name + " 绝对值不能超过 "
                    + MAX_BOUNDS_ABS + "，收到：" + value);
        }
        return value;
    }

    /** 请求级 index：省略时默认第 0 个；显式 null/非整数/越界都拒绝。 */
    private static int requireNodeIndex(JSONObject request) {
        Object raw = request == null ? null : request.opt("index");
        if (raw == null) {
            return 0;
        }
        return (int) requireRange(raw, 0L, MAX_NODE_INDEX, "index");
    }

    private static String requireNodeText(JSONObject request) {
        Object raw = request == null ? null : request.opt("text");
        if (!(raw instanceof String)) {
            throw new IllegalArgumentException("setTextNode.text 必须是字符串，收到：" + valueType(raw));
        }
        return (String) raw;
    }

    private static String requireString(Object value, String label) {
        if (!(value instanceof String)) {
            throw new IllegalArgumentException(label + " 必须是字符串，收到：" + valueType(value));
        }
        return (String) value;
    }

    private static void requireBoolean(Object value, String label) {
        if (!(value instanceof Boolean)) {
            throw new IllegalArgumentException(label + " 必须是布尔值，收到：" + valueType(value));
        }
    }

    private static long requireRange(Object value, long min, long max, String label) {
        long parsed = requireIntegral(value, label);
        if (parsed < min || parsed > max) {
            throw new IllegalArgumentException(label + " 必须在 " + min + ".." + max
                    + " 之间，收到：" + parsed);
        }
        return parsed;
    }

    /**
     * 只接受 JSON 中真正的数字节点，且数值必须是整数。字符串 "1"、布尔 true、1.5 都拒绝；
     * 用 BigDecimal 正确拒绝超出 long 范围的整数，避免 longValue() 静默回绕。
     */
    private static long requireIntegral(Object value, String label) {
        if (!(value instanceof Number)) {
            throw new IllegalArgumentException(label + " 必须是整数，收到：" + valueType(value));
        }
        try {
            return new BigDecimal(value.toString()).longValueExact();
        } catch (NumberFormatException | ArithmeticException error) {
            throw new IllegalArgumentException(label + " 必须是整数，收到：" + value);
        }
    }

    private static String valueType(Object value) {
        if (value == null || value == JSONObject.NULL) {
            return "null";
        }
        if (value instanceof String) {
            return "字符串";
        }
        if (value instanceof Boolean) {
            return "布尔值";
        }
        if (value instanceof Number) {
            return "数字";
        }
        if (value instanceof JSONObject) {
            return "JSON 对象";
        }
        if (value instanceof JSONArray) {
            return "JSON 数组";
        }
        return value.getClass().getSimpleName();
    }

    /**
     * 无障碍截图：PFD 像素管道优先 → Phase A Raw 文件 → PNG 回退；解码成图片句柄后立即清理。
     * 不把临时文件路径暴露给 JS，也不复用上一次截图授权/文件/管道。
     *
     * <p>回退链（规格 2026-10-22 Phase A/B）：
     * <ol>
     *   <li>Phase B：主进程 {@code screenshotPixels} 通过 {@link ParcelFileDescriptor}
     *       管道直传 32B 头 + ARGB_8888 原始像素，省掉 14MB 临时文件中转；</li>
     *   <li>Phase A：主进程按 {@code .n24r} 后缀直写 Raw 临时文件；</li>
     *   <li>原 PNG 临时文件（历史兼容路径）。</li>
     * </ol>
     * 任何一级失败都静默落到下一级，对外错误提示保持 PNG 历史文案。
     */
    private JSONObject takeScreenshot(int maxWidth, int maxHeight) throws Exception {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            throw new IllegalStateException("无障碍截图需要 Android 11（API 30）及以上");
        }
        // task-226：Node 侧可传 maxWidth/maxHeight；0=全分辨率，负数/超大值先归一化。
        final int widthLimit = normalizeScreenshotLimit(maxWidth);
        final int heightLimit = normalizeScreenshotLimit(maxHeight);
        RemoteAutomationBridge.Backend backend = requireReady();
        File directory = mContext.getCacheDir();
        if (directory == null) {
            directory = mContext.getFilesDir();
        }
        return takeScreenshotWithFallback(directory,
                () -> readScreenshotPixels(backend, widthLimit, heightLimit),
                backend::screenshot, mImages::readRawFile, mImages::readFile);
    }

    /** 无障碍截图尺寸上限：0=全分辨率；负数/0 归 0，超大值 clamp，避免异常尺寸/内存请求。 */
    static final int MAX_SCREENSHOT_LIMIT = 16384;

    static int normalizeScreenshotLimit(int value) {
        if (value <= 0) {
            return 0;
        }
        return Math.min(value, MAX_SCREENSHOT_LIMIT);
    }

    /**
     * Phase B：主进程读写端分离的像素管道。
     *
     * <p>{@code screenshotPixels} 立即返回 readEnd，writeEnd 由主进程后台线程写 header + 像素。
     * 这里用 AutoCloseInputStream 读满整帧（{@code readRawStream} 负责 header 校验/预算/
     * Bitmap 解码/注册），读完关闭 readEnd；外层 try-with-resources 再兜底关闭 pfd。
     * 管道为 null、写端提前关闭、截断等都会抛异常，交给回退链。
     */
    private JSONObject readScreenshotPixels(RemoteAutomationBridge.Backend backend,
            int maxWidth, int maxHeight) throws Exception {
        // maxWidth/maxHeight = 0 表示全分辨率（坐标与 PNG/Phase A 路径一致）；format 复用 Phase A 常量。
        try (ParcelFileDescriptor pfd = backend.screenshotPixels(
                maxWidth, maxHeight, RawScreenshotFormat.FORMAT_ARGB_8888)) {
            if (pfd == null) {
                throw new IllegalStateException(SCREENSHOT_PIXELS_NULL_MESSAGE);
            }
            try (ParcelFileDescriptor.AutoCloseInputStream pixels =
                         new ParcelFileDescriptor.AutoCloseInputStream(pfd)) {
                return mImages.readRawStream(pixels);
            }
        }
    }

    // ------------------------------------------------------------------
    // 截图临时文件 / Raw 优先回退（纯 JVM 单测覆盖）
    // ------------------------------------------------------------------

    /** 主进程截图调用（{@code backend.screenshot} 的最小抽象，便于纯 JVM 单测）。 */
    interface ScreenshotCapture {
        String screenshot(String targetPath) throws Exception;
    }

    /** 图片解码（{@code Node24Images.readRawFile/readFile} 的最小抽象，便于纯 JVM 单测）。 */
    interface ScreenshotDecoder {
        JSONObject decode(String path) throws Exception;
    }

    /** Phase B 像素管道读取（{@code screenshotPixels + readRawStream} 的最小抽象，便于纯 JVM 单测）。 */
    interface ScreenshotPixelsReader {
        JSONObject read() throws Exception;
    }

    /** 历史 PNG 路径的两句错误提示：回退后的对外错误必须保持原样。 */
    static final String SCREENSHOT_MISSING_FILE_MESSAGE =
            "无障碍截图失败：主进程未返回截图文件（请确认无障碍服务已开启且系统支持截图）";
    static final String SCREENSHOT_EMPTY_FILE_MESSAGE =
            "无障碍截图失败：主进程截图文件不存在或为空";
    /** Phase B 管道为空时的内部错误：只在 suppressed 里出现，对外仍是 PNG 历史文案。 */
    static final String SCREENSHOT_PIXELS_NULL_MESSAGE =
            "无障碍截图失败：主进程未返回像素管道";

    private static final String SCREENSHOT_FILE_PREFIX = "node24-shot-";
    private static final String RAW_SCREENSHOT_SUFFIX = ".n24r";
    private static final String PNG_SCREENSHOT_SUFFIX = ".png";

    /** 每次尝试都用新 UUID 的临时文件名，避免复用上一次的半写文件。 */
    static File screenshotTemporaryFile(File directory, UUID id, boolean raw) {
        return new File(directory, SCREENSHOT_FILE_PREFIX + id
                + (raw ? RAW_SCREENSHOT_SUFFIX : PNG_SCREENSHOT_SUFFIX));
    }

    /** 主进程返回的截图文件必须存在且非空（缺失 / 0 字节 / 半写都算失败）。 */
    static boolean isUsableScreenshotFile(File file) {
        return file != null && file.isFile() && file.length() > 0L;
    }

    /**
     * 单次截图尝试（Raw 或 PNG）：新 UUID 临时文件 → 主进程写文件 → 校验 → 解码。
     * 无论成功失败，finally 都删除临时文件与主进程返回的 saved 文件（防半写/泄漏）。
     */
    static JSONObject captureScreenshotOnce(File directory, boolean raw,
            ScreenshotCapture capture, ScreenshotDecoder decoder) throws Exception {
        File temporary = screenshotTemporaryFile(directory, UUID.randomUUID(), raw);
        File saved = null;
        try {
            String path = capture.screenshot(temporary.getAbsolutePath());
            if (path == null || path.trim().isEmpty()) {
                throw new IllegalStateException(SCREENSHOT_MISSING_FILE_MESSAGE);
            }
            saved = new File(path);
            if (!isUsableScreenshotFile(saved)) {
                throw new IllegalStateException(SCREENSHOT_EMPTY_FILE_MESSAGE);
            }
            return decoder.decode(saved.getAbsolutePath());
        } finally {
            // 真实临时资源不能留着；无论解码成功与否都删（主进程失败时的半写文件也在这里清）。
            deleteQuietly(temporary);
            if (saved != null && !saved.equals(temporary)) {
                deleteQuietly(saved);
            }
        }
    }

    /**
     * 截图三级回退链，优先级从高到低：
     * <ol>
     *   <li>Phase B：ParcelFileDescriptor 像素管道（{@code screenshotPixels + readRawStream}）；</li>
     *   <li>Phase A：{@code .n24r} Raw 临时文件（主进程直写像素）；</li>
     *   <li>原 PNG 临时文件（历史兼容路径）。</li>
     * </ol>
     * 每次尝试使用独立资源、失败各自清理；只回退 {@link Exception}（含 RuntimeException/
     * IOException/JSONException），OOM 等 Error 直接上抛。三级都失败时抛出 PNG 路径的异常
     * （对外错误提示保持历史文案），PFD/Raw 的失败原因依次挂到 {@code suppressed} 便于排查。
     */
    static JSONObject takeScreenshotWithFallback(File directory,
            ScreenshotPixelsReader pixelsReader,
            ScreenshotCapture capture, ScreenshotDecoder rawDecoder, ScreenshotDecoder pngDecoder)
            throws Exception {
        Exception pixelsFailure = null;
        try {
            return pixelsReader.read();
        } catch (Exception failure) {
            pixelsFailure = failure;
        }
        Exception rawFailure = null;
        try {
            return captureScreenshotOnce(directory, true, capture, rawDecoder);
        } catch (Exception failure) {
            rawFailure = failure;
        }
        try {
            return captureScreenshotOnce(directory, false, capture, pngDecoder);
        } catch (Exception pngFailure) {
            if (pixelsFailure != null) {
                pngFailure.addSuppressed(pixelsFailure);
            }
            if (rawFailure != null) {
                pngFailure.addSuppressed(rawFailure);
            }
            throw pngFailure;
        }
    }

    private void openAccessibilitySettings() {
        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        mContext.startActivity(intent);
    }

    // ------------------------------------------------------------------
    // 主进程后端（反射，无编译依赖）
    // ------------------------------------------------------------------

    private RemoteAutomationBridge.Backend backend() {
        if (mClosed) {
            throw new IllegalStateException("本次 Node 任务的自动化资源已释放");
        }
        if (mBackend != null) {
            return mBackend;
        }
        try {
            Class<?> type = Class.forName(CLIENT_CLASS, true, mContext.getClassLoader());
            Object client;
            try {
                Method getInstance = type.getMethod("getInstance", Context.class);
                client = getInstance.invoke(null, mContext);
            } catch (NoSuchMethodException noSingleton) {
                Constructor<?> constructor = type.getDeclaredConstructor(Context.class);
                constructor.setAccessible(true);
                client = constructor.newInstance(mContext);
            }
            if (!(client instanceof RemoteAutomationBridge.Backend)) {
                throw new IllegalStateException(CLIENT_CLASS
                        + " 未实现 RemoteAutomationBridge.Backend，无法复用主进程无障碍");
            }
            Method connect = type.getMethod("connect");
            Object connected = connect.invoke(client);
            if (!(connected instanceof Boolean) || !((Boolean) connected)) {
                throw new IllegalStateException(
                        "无法绑定主进程 AutomationHostService（未注册/主进程不可用/绑定超时）；"
                                + "无障碍动作未执行");
            }
            mClient = client;
            mBackend = (RemoteAutomationBridge.Backend) client;
            return mBackend;
        } catch (ClassNotFoundException missing) {
            throw new IllegalStateException("Node24 自动化宿主不可用：APK 中缺少 " + CLIENT_CLASS
                    + "（:node24 与主进程必须来自同一个 APK）", missing);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            throw new IllegalStateException("连接主进程自动化服务失败：" + describe(cause), cause);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Node24 自动化宿主反射失败：" + describe(error), error);
        }
    }

    private RemoteAutomationBridge.Backend requireReady() {
        RemoteAutomationBridge.Backend backend = backend();
        boolean enabled = safeEnabled(backend) || readAccessibilityEnabledFromSettings();
        if (enabled && safeReady(backend)) {
            return backend;
        }
        throw new IllegalStateException(accessibilityRequirementMessage(enabled));
    }

    private static boolean safeReady(RemoteAutomationBridge.Backend backend) {
        try {
            return backend.isReady();
        } catch (Throwable error) {
            Log.w(TAG, "isReady failed", error);
            return false;
        }
    }

    private static boolean safeEnabled(RemoteAutomationBridge.Backend backend) {
        try {
            return backend.isAccessibilityServiceEnabled();
        } catch (Throwable error) {
            Log.w(TAG, "isAccessibilityServiceEnabled failed", error);
            return false;
        }
    }

    static String accessibilityRequirementMessage(boolean enabled) {
        return enabled ? SERVICE_NOT_RUNNING_MESSAGE : SERVICE_NOT_ENABLED_MESSAGE;
    }

    private static int requireCoordinate(JSONObject request, String name) {
        long value = request.optLong(name, Long.MIN_VALUE);
        if (value == Long.MIN_VALUE || value < 0L || value > 100_000L) {
            throw new IllegalArgumentException(name + " 必须是 0..100000 的整数坐标");
        }
        return (int) value;
    }

    // ------------------------------------------------------------------
    // 纯逻辑（单测覆盖）
    // ------------------------------------------------------------------

    /** 构造同点按压手势 JSON；由主进程 GestureDescription 执行。 */
    static String pressGestureJson(int x, int y, long duration) throws JSONException {
        JSONObject start = new JSONObject().put("x", x).put("y", y).put("t", 0L);
        JSONObject end = new JSONObject().put("x", x).put("y", y).put("t", duration);
        JSONArray stroke = new JSONArray().put(start).put(end);
        JSONArray strokes = new JSONArray().put(stroke);
        return strokes.toString();
    }

    /**
     * 校验并规范化 strokes。输入 t 是相对整个 gesture 起点的绝对毫秒数（契约），但主进程
     * AutomationHostService 的 StrokeDescription 需要「每条 stroke 的 startTime + stroke 内相对 t」：
     * 因此把首点 t 提为 startTime，并从该 stroke 所有 t 中减去它。只保留 x/y/t（首点另有 startTime），
     * 要求每点 t 在 0..60000、stroke 内单调不减、坐标非负且有限。
     * 上限与主进程 AutomationHostService 的解析器一致（10 条 stroke）。
     */
    static String normalizeStrokes(JSONArray strokes) throws JSONException {
        if (strokes == null || strokes.length() == 0) {
            throw new IllegalArgumentException("gesture.strokes 不能为空");
        }
        if (strokes.length() > MAX_GESTURE_STROKES) {
            throw new IllegalArgumentException("gesture 最多支持 " + MAX_GESTURE_STROKES + " 条 stroke");
        }
        JSONArray normalized = new JSONArray();
        for (int i = 0; i < strokes.length(); i++) {
            JSONArray points = strokes.optJSONArray(i);
            if (points == null || points.length() == 0) {
                throw new IllegalArgumentException("gesture.strokes[" + i + "] 必须是非空点数组");
            }
            if (points.length() > MAX_GESTURE_POINTS) {
                throw new IllegalArgumentException("gesture.strokes[" + i + "] 点数过多（上限 "
                        + MAX_GESTURE_POINTS + "）");
            }
            JSONArray normalizedPoints = new JSONArray();
            long startTime = 0L;
            long previousT = 0L;
            for (int j = 0; j < points.length(); j++) {
                JSONObject point = points.optJSONObject(j);
                if (point == null) {
                    throw new IllegalArgumentException("gesture.strokes[" + i + "][" + j + "] 必须是对象");
                }
                if (!point.has("x") || !point.has("y")) {
                    throw new IllegalArgumentException("gesture.strokes[" + i + "][" + j + "] 缺少 x/y");
                }
                double x = point.optDouble("x", Double.NaN);
                double y = point.optDouble("y", Double.NaN);
                long t = point.optLong("t", 0L);
                if (Double.isNaN(x) || Double.isNaN(y) || Double.isInfinite(x) || Double.isInfinite(y)) {
                    throw new IllegalArgumentException("gesture.strokes[" + i + "][" + j + "] 坐标不是有限数字");
                }
                if (x < 0 || y < 0) {
                    throw new IllegalArgumentException("gesture.strokes[" + i + "][" + j
                            + "] 坐标必须是非负数，收到：" + x + "," + y);
                }
                if (t < 0L || t > MAX_GESTURE_OFFSET_MS) {
                    throw new IllegalArgumentException("gesture.strokes[" + i + "][" + j
                            + "].t 必须在 0.." + MAX_GESTURE_OFFSET_MS + " 毫秒之间");
                }
                if (j == 0) {
                    startTime = t;
                } else if (t < previousT) {
                    throw new IllegalArgumentException("gesture.strokes[" + i + "][" + j
                            + "].t 必须单调不减（" + previousT + " -> " + t + "）");
                }
                JSONObject normalizedPoint = new JSONObject()
                        .put("x", x).put("y", y).put("t", t - startTime);
                if (j == 0) {
                    // AutomationHostService 从每条 stroke 的首点读取延迟；旧客户端缺省为 0。
                    normalizedPoint.put("startTime", startTime);
                }
                normalizedPoints.put(normalizedPoint);
                previousT = t;
            }
            normalized.put(normalizedPoints);
        }
        return normalized.toString();
    }

    private static void deleteQuietly(File file) {
        if (file == null) {
            return;
        }
        try {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        } catch (Throwable error) {
            Log.w(TAG, "delete temporary screenshot failed: " + file, error);
        }
    }

    private static JSONObject ok(Object value) {
        try {
            return new JSONObject().put("ok", true)
                    .put("value", value == null ? JSONObject.NULL : value);
        } catch (JSONException error) {
            return error("automation 响应编码失败：" + error.getMessage());
        }
    }

    private static JSONObject error(String message) {
        try {
            return new JSONObject().put("ok", false)
                    .put("error", message == null ? "未知自动化错误" : message);
        } catch (JSONException ignored) {
            return new JSONObject();
        }
    }

    private static String describe(Throwable failure) {
        if (failure == null) {
            return "未知错误";
        }
        String message = failure.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = failure.getClass().getSimpleName();
        }
        return message;
    }

    /** 任务结束/取消/异常统一解绑；不触碰进程级 RemoteAutomationBridge。 */
    @Override
    public synchronized void close() {
        if (mClosed) {
            return;
        }
        mClosed = true;
        Object client = mClient;
        mClient = null;
        mBackend = null;
        if (client == null) {
            return;
        }
        try {
            Method disconnect = client.getClass().getMethod("disconnect");
            disconnect.invoke(client);
        } catch (Throwable error) {
            Log.w(TAG, "disconnect AutomationHostClient failed", error);
        }
    }
}
