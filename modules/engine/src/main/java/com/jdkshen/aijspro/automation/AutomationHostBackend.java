package com.jdkshen.aijspro.automation;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ContentResolver;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.view.accessibility.AccessibilityEvent;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import com.stardust.autojs.core.image.capture.AccessibilityScreenCapturer;
import com.stardust.autojs.host.IAutomationHost;
import com.stardust.autojs.host.IAutomationHostCallback;
import org.autojs.source.engine.android.RawScreenshotFormat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Phase1 P1 主进程自动化代理：{@link IAutomationHost} 的 Binder 实现。
 *
 * <p>角色：{@code :script} 进程里的脚本引擎把 auto/click/text/id/desc/selector/gesture/
 * back/home/recents/notifications/screenshot 等自动化 API 代理到主进程执行 —— 只有主进程
 * 持有已连接的 AccessibilityService / 截屏能力。契约见
 * {@code apps/app/src/main/aidl/com/stardust/autojs/host/IAutomationHost.aidl} 与
 * {@code docs/reports/2026-10-19_IAutomationHost契约与JSON协议.md}。
 *
 * <p><b>线程模型与超时</b>（契约 §5）：
 * <ul>
 *   <li>节点树查询、节点动作、全局动作：post 到主进程主线程执行，调用线程（binder 线程）最多等
 *       {@link #TIMEOUT_MS}，超时返回 false/空串；主线程已被占用时不会把 binder 线程拖死；</li>
 *   <li>手势（click/longClick/swipe/gesture）：{@code dispatchGesture} 本身线程无关，由 binder 线程
 *       直接派发，并用 {@code GestureResultCallback} 等 onCompleted/onCancelled（最多 TIMEOUT_MS）；</li>
 *   <li>截图：系统 API 明确禁止在主线程等待（否则直接抛异常），所以在 binder 线程执行
 *       {@link AccessibilityScreenCapturer#capture(long)}；</li>
 *   <li>所有方法同步返回、异常一律兜底 false/空串，不向 :script 抛异常。</li>
 * </ul>
 *
 * <p><b>selectorJson</b>（字段与 UiSelector 对齐，AND 组合；未知字段忽略）：
 * {@code id / text / textContains / textStartsWith / textMatches(正则整串匹配) /
 * desc / descContains / className / packageName / clickable / enabled / scrollable /
 * depth / index / bounds{left,top,right,bottom} / algorithm("BFS"|"DFS"，默认 DFS，大小写不敏感)}。
 *
 * <p><b>实现扩展（不改 AIDL/契约，供 :script 侧按 path 二次定位）</b>：selectorJson 额外支持
 * {@code path} 字段（int 数组，如 {@code {"path":[1,0]}}），语义是「节点在根窗口树里的子下标路径
 * 必须完全等于该数组」（根节点为 {@code []}）。这样 :script 侧用 getLatestNodeDump / findNodes
 * 拿到 path 后，可以直接 {@code clickNode("{"path":[...]}", 0)} 精确点回同一节点，不用再拼
 * id/text 选择器。
 * 语义与引擎侧 UiSelector 对齐的具体点：
 * <ul>
 *   <li>{@code id} 优先按 {@code viewIdResourceName} 全名精确匹配，同时容忍只写 resource 名
 *       （{@code pkg:id/btn} 与 {@code btn} 都算命中）；</li>
 *   <li>{@code index} 按 {@code indexInParent}（子节点下标，和 UiSelector.indexInParent 一致）；</li>
 *   <li>{@code bounds} 按屏幕坐标精确相等（和 UiSelector.bounds(l,t,r,b) 一致）；</li>
 *   <li>{@code depth} 为从根窗口节点算起的深度（根为 0，和 UiSelector.depth 一致）。</li>
 * </ul>
 *
 * <p><b>节点 JSON</b>（findNodes 数组元素 / getNodeInfo 返回值 / getLatestNodeDump 元素）：
 * {@code {"path":[0,2,1],"text":"","desc":"","id":"","className":"","packageName":"",
 * "bounds":[l,t,r,b],"clickable":bool,"enabled":bool,"depth":int}}；
 * {@code path} 是从根窗口到该节点的子下标路径（根为空数组），可用来二次定位。
 *
 * <p><b>getLatestNodeDump</b> 返回**拍平的 JSON 数组**（DFS 前序，元素格式同上）：
 * 根窗口节点是 {@code path=[]} 的那个元素；{@code :script} 侧可直接在本地做节点过滤/属性读取。
 * {@code algorithm} 只影响 findNodes/getNodeInfo/节点动作的遍历顺序，dump 始终为 DFS 前序。
 * 结果按 {@value #NODE_DUMP_TTL_MS}ms 短 TTL 缓存（spec §5），避免一次选择器操作连续拉整棵树。
 *
 * <p><b>坐标约定</b>（spec 附录 C）：AIDL 的 x/y 一律是设备像素；:script 侧负责把脚本逻辑坐标
 * 按 ScreenMetrics 换算后再调用，host 不再二次缩放。
 *
 * <p><b>strokesJson</b>（gesture）：{@code [[{"x":100,"y":200,"t":0},{"x":100,"y":500,"t":300}],...]}，
 * 外层多条 stroke，内层点序列，{@code t} 为 stroke 内相对起点的毫秒偏移；主进程用
 * {@link GestureDescription} 构造，最多 10 条 stroke。
 *
 * <p><b>安全</b>：服务在 AndroidManifest 里 {@code exported=false}（主进程，无 process 属性），
 * 另外每个方法都校验调用方 uid == 本应用 uid，跨应用即使误配也拿不到能力。
 *
 * <p><b>限制</b>：findNodes 最多返回 {@value #MAX_FIND_RESULTS} 个节点；节点遍历最多访问
 * {@value #MAX_TRAVERSE_NODES} 个节点/深度 {@value #MAX_DEPTH}；返回 JSON 截断在
 * {@value #MAX_JSON_CHARS} 字符内，避免 Binder 事务超限（getLatestNodeDump 可能因此不完整）。
 * 超时后的延迟动作会被 {@code cancelled} 标记跳过，避免「调用方已收到 false 但动作仍执行」。
 */
final class AutomationHostBackend extends android.content.ContextWrapper {
    private static volatile AutomationHostBackend shared;

    static AutomationHostBackend get(android.content.Context context) {
        AutomationHostBackend current = shared;
        if (current != null) return current;
        synchronized (AutomationHostBackend.class) {
            if (shared == null) shared = new AutomationHostBackend(context);
            return shared;
        }
    }

    private AutomationHostBackend(android.content.Context context) { super(context.getApplicationContext()); }
    IAutomationHost endpoint() { return mBinder; }

    private static final String TAG = "AutomationHost";

    /** 单个 AIDL 调用的总预算（契约 §5）。 */
    private static final long TIMEOUT_MS = 5000L;
    /** 截图等待预算（留一点保存 PNG 的时间）。 */
    private static final long SCREENSHOT_TIMEOUT_MS = 4200L;
    /** 节点树遍历上限：防止超大 WebView 树把主线程拖住。 */
    private static final int MAX_TRAVERSE_NODES = 5000;
    /** 最大深度：与 path 数组一致。 */
    private static final int MAX_DEPTH = 64;
    /** findNodes 最多返回的节点数。 */
    private static final int MAX_FIND_RESULTS = 500;
    /** 返回 JSON 的字符上限（Binder 事务约 1MB，留足余量）。 */
    private static final int MAX_JSON_CHARS = 200 * 1024;
    /** GestureDescription 最多支持的 stroke 数。 */
    private static final int MAX_GESTURE_STROKES = 10;
    /**
     * getLatestNodeDump 的结果缓存时长（spec §5 建议 150-300ms）。
     * :script 侧一次选择器操作可能连续取多份 dump，短 TTL 既省主线程遍历，又不会明显过期。
     */
    private static final long NODE_DUMP_TTL_MS = 250L;

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final AutomationHostBinder mBinder = new AutomationHostBinder();
    /** 节点 dump 短 TTL 缓存（只在主线程写；读路径也在 onMain 里，volatile 仅防御）。 */
    private volatile String mDumpCache;
    private volatile long mDumpCacheAt;

    // ---- task-163：主进程 -> :script 无障碍事件反向通道 ----
    /** AccessibilityDelegate 注册优先级（与其它模块的 delegate 不冲突即可）。 */
    private static final int EVENT_DELEGATE_PRIORITY = 9200;
    private final RemoteCallbackList<IAutomationHostCallback> mEventCallbacks =
            new RemoteCallbackList<>();
    private final Object mEventBroadcastLock = new Object();
    private boolean mEventDelegateRegistered;

    // ------------------------------------------------------------------
    // Service 生命周期
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // IAutomationHost 实现
    // ------------------------------------------------------------------

    private final class AutomationHostBinder extends IAutomationHost.Stub {

        @Override
        public boolean isReady() {
            if (!isTrustedCaller()) return false;
            try {
                return accessibilityService() != null;
            } catch (Throwable error) {
                Log.w(TAG, "isReady 失败", error);
                return false;
            }
        }

        @Override
        public boolean isAccessibilityServiceEnabled() {
            if (!isTrustedCaller()) return false;
            try {
                return isServiceEnabledInSettings();
            } catch (Throwable error) {
                Log.w(TAG, "isAccessibilityServiceEnabled 失败", error);
                return false;
            }
        }

        @Override
        public String getLatestNodeDump() {
            if (!isTrustedCaller()) return "";
            return onAutomationMain("nodeDump", deadline -> {
                long now = SystemClock.uptimeMillis();
                String cached = mDumpCache;
                if (cached != null && now - mDumpCacheAt <= NODE_DUMP_TTL_MS) {
                    return cached;
                }
                String dump = dumpAllNodes(deadline);
                if (dump != null && !dump.isEmpty()) {
                    mDumpCache = dump;
                    mDumpCacheAt = SystemClock.uptimeMillis();
                }
                return dump;
            });
        }

        @Override
        public String findNodes(String selectorJson) {
            if (!isTrustedCaller()) return "";
            final SelectorSpec spec;
            try {
                spec = SelectorSpec.parse(selectorJson);
            } catch (Throwable error) {
                Log.w(TAG, "findNodes: selectorJson 解析失败", error);
                return "";
            }
            return onAutomationMain("nodeQuery", deadline -> findNodesJson(spec, deadline));
        }

        @Override
        public String getNodeInfo(String selectorJson, int index) {
            if (!isTrustedCaller()) return "";
            final SelectorSpec spec;
            try {
                spec = SelectorSpec.parse(selectorJson);
            } catch (Throwable error) {
                Log.w(TAG, "getNodeInfo: selectorJson 解析失败", error);
                return "";
            }
            final int position = normalizeIndex(index);
            return onAutomationMain("nodeQuery", deadline -> {
                AccessibilityService service = accessibilityService();
                if (service == null) return "";
                List<NodeMatch> matches = findMatches(service, spec, position + 1, deadline);
                if (position >= matches.size()) return "";
                NodeMatch match = matches.get(position);
                return nodeJson(match.node, match.path, match.depth).toString();
            });
        }

        @Override
        public boolean clickNode(String selectorJson, int index) {
            if (!isTrustedCaller()) return false;
            final SelectorSpec spec;
            try {
                spec = SelectorSpec.parse(selectorJson);
            } catch (Throwable error) {
                Log.w(TAG, "clickNode: selectorJson 解析失败", error);
                return false;
            }
            final int position = normalizeIndex(index);
            return onAutomationMain("nodeAction", deadline ->
                    performNodeAction(spec, position, AccessibilityNodeInfo.ACTION_CLICK, null, deadline));
        }

        @Override
        public boolean longClickNode(String selectorJson, int index) {
            if (!isTrustedCaller()) return false;
            final SelectorSpec spec;
            try {
                spec = SelectorSpec.parse(selectorJson);
            } catch (Throwable error) {
                Log.w(TAG, "longClickNode: selectorJson 解析失败", error);
                return false;
            }
            final int position = normalizeIndex(index);
            return onAutomationMain("nodeAction", deadline ->
                    performNodeAction(spec, position, AccessibilityNodeInfo.ACTION_LONG_CLICK, null, deadline));
        }

        @Override
        public boolean setTextNode(String selectorJson, int index, String text) {
            if (!isTrustedCaller()) return false;
            final SelectorSpec spec;
            try {
                spec = SelectorSpec.parse(selectorJson);
            } catch (Throwable error) {
                Log.w(TAG, "setTextNode: selectorJson 解析失败", error);
                return false;
            }
            final int position = normalizeIndex(index);
            final String value = text == null ? "" : text;
            return onAutomationMain("nodeAction", deadline -> {
                Bundle arguments = new Bundle();
                arguments.putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
                return performNodeAction(spec, position, AccessibilityNodeInfo.ACTION_SET_TEXT,
                        arguments, deadline);
            });
        }

        @Override
        public boolean nodeAction(String selectorJson, int index, String action, String argumentsJson) {
            if (!isTrustedCaller()) return false;
            final SelectorSpec spec;
            try {
                spec = SelectorSpec.parse(selectorJson);
            } catch (Throwable error) {
                Log.w(TAG, "nodeAction: selectorJson 解析失败", error);
                return false;
            }
            final int position = normalizeIndex(index);
            final String name = action == null ? "" : action;
            final JSONArray args;
            try {
                args = new JSONArray(argumentsJson == null || argumentsJson.isEmpty() ? "[]" : argumentsJson);
            } catch (Throwable error) {
                Log.w(TAG, "nodeAction: arguments 解析失败", error);
                return false;
            }
            com.stardust.automator.NodeActionArguments.validate(name, args);
            int actionId;
            Bundle arguments = null;
            try {
                switch (name) {
                    case "click": actionId = AccessibilityNodeInfo.ACTION_CLICK; break;
                    case "longClick": actionId = AccessibilityNodeInfo.ACTION_LONG_CLICK; break;
                    case "copy": actionId = AccessibilityNodeInfo.ACTION_COPY; break;
                    case "cut": actionId = AccessibilityNodeInfo.ACTION_CUT; break;
                    case "paste": actionId = AccessibilityNodeInfo.ACTION_PASTE; break;
                    case "select": actionId = AccessibilityNodeInfo.ACTION_SELECT; break;
                    case "collapse": actionId = AccessibilityNodeInfo.ACTION_COLLAPSE; break;
                    case "expand": actionId = AccessibilityNodeInfo.ACTION_EXPAND; break;
                    case "dismiss": actionId = AccessibilityNodeInfo.ACTION_DISMISS; break;
                    case "show": actionId = AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId(); break;
                    case "scrollForward": actionId = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD; break;
                    case "scrollBackward": actionId = AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD; break;
                    case "scrollUp": actionId = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.getId(); break;
                    case "scrollDown": actionId = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.getId(); break;
                    case "scrollLeft": actionId = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.getId(); break;
                    case "scrollRight": actionId = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.getId(); break;
                    case "contextClick": actionId = AccessibilityNodeInfo.AccessibilityAction.ACTION_CONTEXT_CLICK.getId(); break;
                    case "focus": actionId = AccessibilityNodeInfo.ACTION_FOCUS; break;
                    case "clearFocus": actionId = AccessibilityNodeInfo.ACTION_CLEAR_FOCUS; break;
                    case "accessibilityFocus": actionId = AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS; break;
                    case "clearAccessibilityFocus": actionId = AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS; break;
                    case "setSelection":
                        actionId = AccessibilityNodeInfo.ACTION_SET_SELECTION;
                        arguments = new Bundle();
                        arguments.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, args.optInt(0, 0));
                        arguments.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, args.optInt(1, 0));
                        break;
                    case "scrollTo":
                        actionId = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_TO_POSITION.getId();
                        arguments = new Bundle();
                        arguments.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_ROW_INT, args.optInt(0, 0));
                        arguments.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_COLUMN_INT, args.optInt(1, 0));
                        break;
                    case "setProgress":
                        actionId = AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId();
                        arguments = new Bundle();
                        arguments.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,
                                (float) args.optDouble(0, 0));
                        break;
                    default:
                        Log.w(TAG, "nodeAction: unsupported action " + name);
                        return false;
                }
            } catch (Throwable error) {
                Log.w(TAG, "nodeAction: action arguments invalid " + name, error);
                return false;
            }
            final int resolvedAction = actionId;
            final Bundle resolvedArguments = arguments;
            return onAutomationMain("nodeAction", deadline -> performNodeAction(
                    spec, position, resolvedAction, resolvedArguments, deadline));
        }

        @Override
        public boolean click(int x, int y) {
            if (!isTrustedCaller()) return false;
            try {
                long duration = ViewConfiguration.getTapTimeout() + 50L;
                return dispatchPointGesture(x, y, duration);
            } catch (Throwable error) {
                Log.w(TAG, "click 失败", error);
                return false;
            }
        }

        @Override
        public boolean longClick(int x, int y) {
            if (!isTrustedCaller()) return false;
            try {
                long duration = ViewConfiguration.getLongPressTimeout() + 200L;
                return dispatchPointGesture(x, y, duration);
            } catch (Throwable error) {
                Log.w(TAG, "longClick 失败", error);
                return false;
            }
        }

        @Override
        public boolean swipe(int x1, int y1, int x2, int y2, long duration) {
            if (!isTrustedCaller()) return false;
            try {
                long clamped = duration > 0 ? duration : 300L;
                Path path = new Path();
                path.moveTo(x1, y1);
                path.lineTo(x2, y2);
                return dispatchStrokes(new GestureDescription.StrokeDescription[]{
                        new GestureDescription.StrokeDescription(path, 0, clamped)});
            } catch (Throwable error) {
                Log.w(TAG, "swipe 失败", error);
                return false;
            }
        }

        @Override
        public boolean gesture(String strokesJson) {
            if (!isTrustedCaller()) return false;
            try {
                GestureDescription.StrokeDescription[] strokes = parseStrokes(strokesJson);
                if (strokes == null || strokes.length == 0) return false;
                return dispatchStrokes(strokes);
            } catch (Throwable error) {
                Log.w(TAG, "gesture 失败", error);
                return false;
            }
        }

        @Override
        public boolean back() {
            return globalAction(AccessibilityService.GLOBAL_ACTION_BACK);
        }

        @Override
        public boolean home() {
            return globalAction(AccessibilityService.GLOBAL_ACTION_HOME);
        }

        @Override
        public boolean recents() {
            return globalAction(AccessibilityService.GLOBAL_ACTION_RECENTS);
        }

        @Override
        public boolean notifications() {
            return globalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS);
        }

        @Override
        public String screenshot(String targetPath) {
            if (!isTrustedCaller()) return "";
            if (targetPath == null || targetPath.trim().isEmpty()) return "";
            try {
                AccessibilityService service = accessibilityService();
                if (service == null) {
                    Log.w(TAG, "screenshot: 无障碍服务未连接");
                    return "";
                }
                if (!AccessibilityScreenCapturer.isAvailable()) {
                    Log.w(TAG, "screenshot: 当前设备/系统不支持无障碍截图");
                    return "";
                }
                // 系统限制：截图等待不能在主线程（AccessibilityScreenCapturer 会直接抛异常），
                // 这里在 binder 线程执行 + 自带超时；:script 侧调用方本身也是异步等待。
                Bitmap bitmap;
                try {
                    bitmap = new AccessibilityScreenCapturer().capture(SCREENSHOT_TIMEOUT_MS);
                } catch (Throwable error) {
                    Log.w(TAG, "screenshot: 系统截图失败", error);
                    return "";
                }
                if (bitmap == null) return "";
                File target = new File(targetPath.trim());
                File parent = target.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
                    Log.w(TAG, "screenshot: 无法创建目标目录 " + parent);
                    bitmap.recycle();
                    return "";
                }
                // task-220：.n24r 走 Raw 像素临时文件（32B 小端头 + ARGB_8888 连续像素），其它路径保持 PNG。
                if (target.getName().toLowerCase(Locale.ROOT).endsWith(".n24r")) {
                    boolean rawWritten = false;
                    try {
                        int width = bitmap.getWidth();
                        int height = bitmap.getHeight();
                        if (width <= 0 || height <= 0) {
                            Log.w(TAG, "screenshot: Raw 截图尺寸无效 " + width + "x" + height);
                            return "";
                        }
                        // copyPixelsToBuffer 要求 ARGB_8888 且紧凑 stride；无障碍截图通常是软件 ARGB_8888，
                        // 非该配置时只在这里做一次必要的转换（不复制整帧到额外数组）。
                        Bitmap rawBitmap = bitmap;
                        if (rawBitmap.getConfig() != Bitmap.Config.ARGB_8888
                                || rawBitmap.getRowBytes() != width * 4) {
                            Bitmap converted = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                            try {
                                converted.setDensity(rawBitmap.getDensity());
                                new Canvas(converted).drawBitmap(rawBitmap, 0f, 0f, null);
                            } catch (Throwable convertError) {
                                converted.recycle();
                                throw convertError;
                            }
                            bitmap.recycle();
                            bitmap = converted;
                            rawBitmap = converted;
                        }
                        ByteBuffer header = RawScreenshotFormat.newHeader(width, height);
                        RawScreenshotFormat.Header format = RawScreenshotFormat.parseHeader(header.array());
                        long pixelBytes = RawScreenshotFormat.pixelBytes(format);
                        RawScreenshotFormat.validateLength(format, RawScreenshotFormat.HEADER_BYTES + pixelBytes);
                        if (pixelBytes <= 0L || pixelBytes > Integer.MAX_VALUE) {
                            Log.w(TAG, "screenshot: Raw 像素过大 " + pixelBytes);
                            return "";
                        }
                        ByteBuffer pixels = ByteBuffer.allocateDirect((int) pixelBytes);
                        rawBitmap.copyPixelsToBuffer(pixels);
                        pixels.flip();
                        try (FileOutputStream rawOutput = new FileOutputStream(target)) {
                            FileChannel channel = rawOutput.getChannel();
                            while (header.hasRemaining()) channel.write(header);
                            while (pixels.hasRemaining()) channel.write(pixels);
                        }
                        rawWritten = true;
                        return target.getAbsolutePath();
                    } finally {
                        bitmap.recycle();
                        if (!rawWritten) target.delete();
                    }
                }
                FileOutputStream output = null;
                try {
                    output = new FileOutputStream(target);
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                        Log.w(TAG, "screenshot: PNG 编码失败");
                        return "";
                    }
                } finally {
                    if (output != null) {
                        try {
                            output.close();
                        } catch (Throwable ignored) {
                        }
                    }
                    bitmap.recycle();
                }
                return target.getAbsolutePath();
            } catch (Throwable error) {
                Log.w(TAG, "screenshot 失败", error);
                return "";
            }
        }

        @Override
        public ParcelFileDescriptor screenshotPixels(int maxWidth, int maxHeight, int format) {
            if (!isTrustedCaller()) return null;
            final long splitStart = SystemClock.elapsedRealtime();
            if (format != RawScreenshotFormat.FORMAT_ARGB_8888) {
                Log.w(TAG, "screenshotPixels: 不支持的 format=" + format);
                return null;
            }
            Bitmap bitmap = null;
            try {
                AccessibilityService service = accessibilityService();
                if (service == null) {
                    Log.w(TAG, "screenshotPixels: 无障碍服务未连接");
                    return null;
                }
                if (!AccessibilityScreenCapturer.isAvailable()) {
                    Log.w(TAG, "screenshotPixels: 当前设备/系统不支持无障碍截图");
                    return null;
                }
                long captureStart = SystemClock.elapsedRealtime();
                try {
                    // A：缩略回读（max<=0 时等价旧全分辨率路径）；正常路径不再做二次缩放。
                    bitmap = new AccessibilityScreenCapturer().capture(
                            SCREENSHOT_TIMEOUT_MS, maxWidth, maxHeight);
                } catch (Throwable error) {
                    Log.w(TAG, "screenshotPixels: 系统截图失败", error);
                    return null;
                }
                long captureMs = SystemClock.elapsedRealtime() - captureStart;
                if (bitmap == null) return null;
                int width = bitmap.getWidth();
                int height = bitmap.getHeight();
                if (width <= 0 || height <= 0) {
                    Log.w(TAG, "screenshotPixels: 截图尺寸无效 " + width + "x" + height);
                    bitmap.recycle();
                    return null;
                }
                // A：缩略已在 AccessibilityScreenCapturer 内完成；正常路径不再 createScaledBitmap。
                // 这里只保留尺寸安全判断（capturer 回退/异常时仍可能超限）。
                int limitWidth = maxWidth > 0 ? maxWidth : width;
                int limitHeight = maxHeight > 0 ? maxHeight : height;
                if (width > limitWidth || height > limitHeight) {
                    Log.w(TAG, "screenshotPixels: 缩略回读尺寸仍超限 " + width + "x" + height
                            + "，执行安全缩放");
                    float scale = Math.min(limitWidth / (float) width, limitHeight / (float) height);
                    int scaledWidth = Math.max(1, Math.round(width * scale));
                    int scaledHeight = Math.max(1, Math.round(height * scale));
                    Bitmap scaled = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true);
                    if (scaled != bitmap) {
                        bitmap.recycle();
                        bitmap = scaled;
                    }
                    width = bitmap.getWidth();
                    height = bitmap.getHeight();
                }
                // copyPixelsToBuffer 要求 ARGB_8888 且紧凑 stride；无障碍截图通常已是软件 ARGB_8888。
                if (bitmap.getConfig() != Bitmap.Config.ARGB_8888
                        || bitmap.getRowBytes() != bitmap.getWidth() * 4) {
                    Bitmap converted = Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(),
                            Bitmap.Config.ARGB_8888);
                    try {
                        converted.setDensity(bitmap.getDensity());
                        new Canvas(converted).drawBitmap(bitmap, 0f, 0f, null);
                    } catch (Throwable convertError) {
                        converted.recycle();
                        throw convertError;
                    }
                    bitmap.recycle();
                    bitmap = converted;
                }
                Log.i("A11Y_SPLIT", "host capture=" + captureMs + "ms total="
                        + (SystemClock.elapsedRealtime() - splitStart) + "ms size="
                        + bitmap.getWidth() + "x" + bitmap.getHeight()
                        + " max=" + maxWidth + "x" + maxHeight);
                ParcelFileDescriptor[] pipe;
                try {
                    pipe = ParcelFileDescriptor.createPipe();
                } catch (Throwable error) {
                    Log.w(TAG, "screenshotPixels: createPipe 失败", error);
                    bitmap.recycle();
                    return null;
                }
                final ParcelFileDescriptor read = pipe[0];
                final ParcelFileDescriptor write = pipe[1];
                final Bitmap writerBitmap = bitmap;
                bitmap = null; // 所有权移交给写线程
                try {
                    Thread writer = new Thread(() -> {
                        try (ParcelFileDescriptor.AutoCloseOutputStream out =
                                     new ParcelFileDescriptor.AutoCloseOutputStream(write)) {
                            writeRawHeaderAndPixels(out.getChannel(), writerBitmap);
                        } catch (Throwable error) {
                            Log.w(TAG, "screenshotPixels write failed", error);
                        } finally {
                            writerBitmap.recycle();
                            try {
                                write.close();
                            } catch (Throwable ignored) {
                            }
                        }
                    }, "n24-screenshot-writer");
                    writer.setDaemon(true);
                    writer.start();
                    return read;
                } catch (Throwable startError) {
                    Log.w(TAG, "screenshotPixels: 启动写线程失败", startError);
                    writerBitmap.recycle();
                    try {
                        write.close();
                    } catch (Throwable ignored) {
                    }
                    try {
                        read.close();
                    } catch (Throwable ignored) {
                    }
                    return null;
                }
            } catch (Throwable error) {
                Log.w(TAG, "screenshotPixels 失败", error);
                if (bitmap != null) bitmap.recycle();
                return null;
            }
        }

        // ---- task-163：主进程 -> :script 无障碍事件回调 ----------------

        @Override
        public void registerEventListener(IAutomationHostCallback callback) {
            if (!isTrustedCaller() || callback == null) return;
            ensureEventDelegate();
            mEventCallbacks.register(callback);
            final boolean ready = accessibilityService() != null;
            mMainHandler.post(() -> {
                try {
                    callback.onServiceStateChanged(ready);
                } catch (Throwable ignored) {
                }
            });
            Log.i(TAG, "event listener registered count=" + mEventCallbacks.getRegisteredCallbackCount()
                    + " ready=" + ready);
        }

        @Override
        public void unregisterEventListener(IAutomationHostCallback callback) {
            if (callback == null) return;
            mEventCallbacks.unregister(callback);
            Log.i(TAG, "event listener unregistered count="
                    + mEventCallbacks.getRegisteredCallbackCount());
        }

        // ---- 全局动作 -----------------------------------------------------

        private boolean globalAction(final int action) {
            if (!isTrustedCaller()) return false;
            return onMain(TIMEOUT_MS, false, deadline -> {
                AccessibilityService service = accessibilityService();
                if (service == null) return false;
                return service.performGlobalAction(action);
            });
        }
    }

    /**
     * Phase B：把 32B 小端头 + ARGB_8888 连续像素写入管道通道。
     * 调用方（独立写线程）负责关闭写端与回收 bitmap；这里只写一次 direct 像素缓冲。
     */
    private static void writeRawHeaderAndPixels(FileChannel channel, Bitmap bitmap) throws IOException {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        ByteBuffer header = RawScreenshotFormat.newHeader(width, height);
        RawScreenshotFormat.Header parsed = RawScreenshotFormat.parseHeader(header.array());
        long pixelBytes = RawScreenshotFormat.pixelBytes(parsed);
        RawScreenshotFormat.validateLength(parsed, RawScreenshotFormat.HEADER_BYTES + pixelBytes);
        if (pixelBytes <= 0L || pixelBytes > Integer.MAX_VALUE) {
            throw new IOException("Raw 截图像素过大：" + pixelBytes);
        }
        ByteBuffer pixels = ByteBuffer.allocateDirect((int) pixelBytes);
        bitmap.copyPixelsToBuffer(pixels);
        pixels.flip();
        while (header.hasRemaining()) channel.write(header);
        while (pixels.hasRemaining()) channel.write(pixels);
    }

    // ------------------------------------------------------------------
    // task-163：无障碍事件转发到 :script
    // ------------------------------------------------------------------

    /** 只注册一次 AccessibilityDelegate；没有订阅者时事件直接丢弃，不产生 Binder 流量。 */
    private void ensureEventDelegate() {
        if (mEventDelegateRegistered) return;
        mEventDelegateRegistered = true;
        try {
            com.stardust.view.accessibility.AccessibilityService.Companion.addDelegate(
                    EVENT_DELEGATE_PRIORITY, new com.stardust.view.accessibility.AccessibilityDelegate() {
                        @Override
                        public java.util.Set<Integer> getEventTypes() {
                            // null = 所有事件类型都回调，由 dispatch 里按 kind 过滤/转发
                            return null;
                        }

                        @Override
                        public boolean onAccessibilityEvent(
                                android.accessibilityservice.AccessibilityService service,
                                AccessibilityEvent event) {
                            dispatchAccessibilityEvent(event);
                            return false;
                        }
                    });
            Log.i(TAG, "accessibility event delegate registered priority=" + EVENT_DELEGATE_PRIORITY);
        } catch (Throwable error) {
            mEventDelegateRegistered = false;
            Log.w(TAG, "register accessibility event delegate failed", error);
        }
    }

    private void dispatchAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || mEventCallbacks.getRegisteredCallbackCount() == 0) return;
        final String json;
        try {
            json = accessibilityEventJson(event);
        } catch (Throwable error) {
            Log.w(TAG, "build accessibility event json failed", error);
            return;
        }
        // RemoteCallbackList.beginBroadcast/finishBroadcast 必须串行；统一 post 到主线程，
        // 且用锁兜住「注册/注销与广播并发」的窗口。
        mMainHandler.post(() -> {
            synchronized (mEventBroadcastLock) {
                int count = mEventCallbacks.beginBroadcast();
                try {
                    for (int i = 0; i < count; i++) {
                        try {
                            mEventCallbacks.getBroadcastItem(i).onAccessibilityEvent(json);
                        } catch (Throwable ignored) {
                        }
                    }
                } finally {
                    mEventCallbacks.finishBroadcast();
                }
            }
        });
    }

    /** 与 Node24 无障碍通路的 kind 命名保持一致：view_clicked/window_state_changed/toast/... */
    private static String accessibilityEventJson(AccessibilityEvent event) throws JSONException {
        JSONObject json = new JSONObject();
        int type = event.getEventType();
        String kind;
        switch (type) {
            case AccessibilityEvent.TYPE_VIEW_CLICKED: kind = "view_clicked"; break;
            case AccessibilityEvent.TYPE_VIEW_LONG_CLICKED: kind = "view_long_clicked"; break;
            case AccessibilityEvent.TYPE_WINDOWS_CHANGED: kind = "windows_changed"; break;
            case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED: kind = "window_state_changed"; break;
            case AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED: kind = "window_content_changed"; break;
            case AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED: {
                String className = charSequenceToString(event.getClassName());
                kind = className.toLowerCase(java.util.Locale.ROOT).contains("toast")
                        ? "toast" : "notification";
                break;
            }
            default: kind = "accessibility_event";
        }
        json.put("kind", kind);
        json.put("eventType", type);
        json.put("packageName", charSequenceToString(event.getPackageName()));
        json.put("className", charSequenceToString(event.getClassName()));
        json.put("windowId", event.getWindowId());
        json.put("eventTime", event.getEventTime());
        StringBuilder text = new StringBuilder();
        java.util.List<CharSequence> texts = event.getText();
        if (texts != null) {
            for (CharSequence item : texts) {
                if (item == null) continue;
                if (text.length() > 0) text.append(' ');
                text.append(item);
            }
        }
        json.put("text", text.toString());
        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            json.put("contentChangeTypes", event.getContentChangeTypes());
        }
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            try {
                json.put("windowChangeTypes", event.getWindowChanges());
            } catch (Throwable ignored) {
            }
        }
        return json.toString();
    }

    // ------------------------------------------------------------------
    // 无障碍服务 / 节点树
    // ------------------------------------------------------------------

    /** 主进程唯一的无障碍服务实例；未连接（或已被系统断开）时为 null。 */
    private static AccessibilityService accessibilityService() {
        try {
            // 具体类是 automator 模块的 com.stardust.view.accessibility.AccessibilityService
            // （继承 android.accessibilityservice.AccessibilityService），这里用父类型持有。
            return com.stardust.view.accessibility.AccessibilityService.Companion.getInstance();
        } catch (Throwable error) {
            Log.w(TAG, "读取无障碍服务失败", error);
            return null;
        }
    }

    /** 读取当前活动窗口的根节点；活动窗口拿不到时退回任意一个窗口的根。 */
    private static AccessibilityNodeInfo activeRoot(AccessibilityService service) {
        try {
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root != null) return root;
        } catch (Throwable ignored) {
        }
        try {
            List<AccessibilityWindowInfo> windows = service.getWindows();
            if (windows != null) {
                for (AccessibilityWindowInfo window : windows) {
                    if (window == null) continue;
                    AccessibilityNodeInfo root = window.getRoot();
                    if (root != null) return root;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** BFS 纯遍历辅助的节点访问器；异常语义与下面 DFS 的 try/catch 保持一致。 */
    private static final SelectorTraversal.Tree<AccessibilityNodeInfo> NODE_TREE =
            new SelectorTraversal.Tree<AccessibilityNodeInfo>() {
                @Override
                public int childCount(AccessibilityNodeInfo node) {
                    try {
                        return node.getChildCount();
                    } catch (Throwable error) {
                        return 0;
                    }
                }

                @Override
                public AccessibilityNodeInfo child(AccessibilityNodeInfo node, int index) {
                    try {
                        return node.getChild(index);
                    } catch (Throwable error) {
                        return null;
                    }
                }
            };

    /**
     * 按 spec 选择的算法遍历匹配节点：默认 DFS 前序（与 UiSelector 默认顺序一致），
     * algorithm=BFS 时逐层队列遍历；最多收集 max 个。
     * 遍历有节点数/深度/截止时间三重上限，超限返回已收集的部分结果。
     */
    private static List<NodeMatch> findMatches(AccessibilityService service, SelectorSpec spec,
                                               int max, long deadline) {
        List<NodeMatch> matches = new ArrayList<>();
        if (max <= 0) return matches;
        AccessibilityNodeInfo root = activeRoot(service);
        if (root == null) return matches;
        if (spec.bfs) {
            List<SelectorTraversal.Match<AccessibilityNodeInfo>> found =
                    SelectorTraversal.findMatches(root, NODE_TREE, spec::matches, max,
                            MAX_DEPTH, MAX_TRAVERSE_NODES,
                            () -> SystemClock.uptimeMillis() > deadline);
            for (SelectorTraversal.Match<AccessibilityNodeInfo> match : found) {
                matches.add(new NodeMatch(match.node, match.path, match.depth));
            }
            return matches;
        }
        int[] path = new int[MAX_DEPTH];
        traverse(root, 0, -1, path, spec, matches, max, deadline, new int[]{0});
        return matches;
    }

    private static void traverse(AccessibilityNodeInfo node, int depth, int indexInParent,
                                 int[] path, SelectorSpec spec, List<NodeMatch> matches,
                                 int max, long deadline, int[] visited) {
        if (node == null || matches.size() >= max || visited[0] >= MAX_TRAVERSE_NODES
                || SystemClock.uptimeMillis() > deadline) {
            return;
        }
        visited[0]++;
        if (spec.matches(node, depth, indexInParent, path)) {
            matches.add(new NodeMatch(node, path, depth));
        }
        if (depth >= MAX_DEPTH - 1 || matches.size() >= max) return;
        int childCount;
        try {
            childCount = node.getChildCount();
        } catch (Throwable error) {
            return;
        }
        for (int i = 0; i < childCount; i++) {
            if (matches.size() >= max || visited[0] >= MAX_TRAVERSE_NODES
                    || SystemClock.uptimeMillis() > deadline) {
                return;
            }
            AccessibilityNodeInfo child;
            try {
                child = node.getChild(i);
            } catch (Throwable error) {
                continue;
            }
            if (child == null) continue;
            path[depth] = i;
            traverse(child, depth + 1, i, path, spec, matches, max, deadline, visited);
        }
    }

    /** findNodes 的 JSON 数组（超过 MAX_JSON_CHARS 会截断）。 */
    private static String findNodesJson(SelectorSpec spec, long deadline) {
        AccessibilityService service = accessibilityService();
        if (service == null) return "[]";
        List<NodeMatch> matches = findMatches(service, spec, MAX_FIND_RESULTS, deadline);
        JSONArray array = new JSONArray();
        int chars = 2;
        for (NodeMatch match : matches) {
            JSONObject json;
            try {
                json = nodeJson(match.node, match.path, match.depth);
            } catch (Throwable error) {
                continue;
            }
            String text = json.toString();
            if (chars + text.length() > MAX_JSON_CHARS) break;
            chars += text.length() + 1;
            array.put(json);
        }
        return array.toString();
    }

    /** getLatestNodeDump：把当前根窗口树按 findNodes 元素格式拍平成 JSON 数组。 */
    private static String dumpAllNodes(long deadline) {
        AccessibilityService service = accessibilityService();
        if (service == null) return "[]";
        AccessibilityNodeInfo root = activeRoot(service);
        if (root == null) return "[]";
        JSONArray array = new JSONArray();
        int[] chars = {2};
        int[] path = new int[MAX_DEPTH];
        dumpTraverse(root, 0, path, array, chars, deadline, new int[]{0});
        return array.toString();
    }

    private static void dumpTraverse(AccessibilityNodeInfo node, int depth, int[] path,
                                     JSONArray array, int[] chars, long deadline, int[] visited) {
        if (node == null || visited[0] >= MAX_TRAVERSE_NODES || SystemClock.uptimeMillis() > deadline) {
            return;
        }
        visited[0]++;
        try {
            JSONObject json = nodeJson(node, path, depth);
            String text = json.toString();
            if (chars[0] + text.length() > MAX_JSON_CHARS) return;
            chars[0] += text.length() + 1;
            array.put(json);
        } catch (Throwable ignored) {
        }
        if (depth >= MAX_DEPTH - 1) return;
        int childCount;
        try {
            childCount = node.getChildCount();
        } catch (Throwable error) {
            return;
        }
        for (int i = 0; i < childCount; i++) {
            if (visited[0] >= MAX_TRAVERSE_NODES || SystemClock.uptimeMillis() > deadline
                    || chars[0] >= MAX_JSON_CHARS) {
                return;
            }
            AccessibilityNodeInfo child;
            try {
                child = node.getChild(i);
            } catch (Throwable error) {
                continue;
            }
            if (child == null) continue;
            path[depth] = i;
            dumpTraverse(child, depth + 1, path, array, chars, deadline, visited);
        }
    }

    /** 节点 -> 契约 JSON。path 取前 depth 个元素（根节点为空数组）。 */
    private static JSONObject nodeJson(AccessibilityNodeInfo node, int[] path, int depth)
            throws JSONException {
        JSONObject json = new JSONObject();
        JSONArray pathJson = new JSONArray();
        for (int i = 0; i < depth; i++) {
            pathJson.put(path[i]);
        }
        json.put("path", pathJson);
        json.put("text", node.isPassword() ? "" : charSequenceToString(node.getText()));
        json.put("desc", charSequenceToString(node.getContentDescription()));
        String fullId = node.getViewIdResourceName();
        json.put("id", fullId == null ? "" : fullId);
        json.put("className", charSequenceToString(node.getClassName()));
        json.put("packageName", charSequenceToString(node.getPackageName()));
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        JSONArray boundsJson = new JSONArray();
        boundsJson.put(bounds.left);
        boundsJson.put(bounds.top);
        boundsJson.put(bounds.right);
        boundsJson.put(bounds.bottom);
        json.put("bounds", boundsJson);
        json.put("clickable", node.isClickable());
        json.put("enabled", node.isEnabled());
        json.put("scrollable", node.isScrollable());
        json.put("editable", node.isEditable());
        json.put("checkable", node.isCheckable());
        json.put("checked", node.isChecked());
        json.put("focusable", node.isFocusable());
        json.put("focused", node.isFocused());
        json.put("selected", node.isSelected());
        json.put("longClickable", node.isLongClickable());
        json.put("visibleToUser", node.isVisibleToUser());
        json.put("password", node.isPassword());
        json.put("accessibilityFocused", node.isAccessibilityFocused());
        json.put("contextClickable", node.isContextClickable());
        json.put("dismissable", node.isDismissable());
        json.put("contentInvalid", node.isContentInvalid());
        json.put("multiLine", node.isMultiLine());
        json.put("isHierarchically", node.getCollectionInfo() != null && node.getCollectionInfo().isHierarchical());
        AccessibilityNodeInfo.RangeInfo range = node.getRangeInfo();
        if (range != null && Float.isFinite(range.getMin()) && Float.isFinite(range.getMax()) && Float.isFinite(range.getCurrent())) {
            json.put("rangeInfo", new JSONObject().put("type", range.getType()).put("min", range.getMin())
                    .put("max", range.getMax()).put("current", range.getCurrent()));
        } else json.put("rangeInfo", JSONObject.NULL);
        AccessibilityNodeInfo.CollectionItemInfo item = node.getCollectionItemInfo();
        AccessibilityNodeInfo.CollectionInfo collection = node.getCollectionInfo();
        json.put("row", item == null ? -1 : item.getRowIndex());
        json.put("column", item == null ? -1 : item.getColumnIndex());
        json.put("rowSpan", item == null ? 0 : item.getRowSpan());
        json.put("columnSpan", item == null ? 0 : item.getColumnSpan());
        json.put("rowCount", collection == null ? 0 : collection.getRowCount());
        json.put("columnCount", collection == null ? 0 : collection.getColumnCount());

        try { json.put("drawingOrder", node.getDrawingOrder()); } catch (Throwable ignored) { }
        Rect boundsInParent = new Rect();
        try { node.getBoundsInParent(boundsInParent); } catch (Throwable ignored) { }
        JSONArray parentJson = new JSONArray();
        parentJson.put(boundsInParent.left);
        parentJson.put(boundsInParent.top);
        parentJson.put(boundsInParent.right);
        parentJson.put(boundsInParent.bottom);
        json.put("boundsInParent", parentJson);
        json.put("childCount", node.getChildCount());
        json.put("indexInParent", depth == 0 ? -1 : path[depth - 1]);
        json.put("depth", depth);
        return json;
    }

    /** 在匹配结果里取第 position 个节点执行动作（ACTION_CLICK / ACTION_LONG_CLICK / ACTION_SET_TEXT）。 */
    private static boolean performNodeAction(SelectorSpec spec, int position, int action,
                                             Bundle arguments, long deadline) {
        AccessibilityService service = accessibilityService();
        if (service == null) return false;
        List<NodeMatch> matches = findMatches(service, spec, position + 1, deadline);
        if (position >= matches.size()) {
            Log.i(TAG, "节点动作：" + spec + " 未匹配到第 " + position + " 个节点");
            return false;
        }
        AccessibilityNodeInfo node = matches.get(position).node;
        try {
            if (arguments == null) {
                return node.performAction(action);
            }
            return node.performAction(action, arguments);
        } catch (Throwable error) {
            Log.w(TAG, "节点动作失败: action=" + action + " " + spec, error);
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 手势
    // ------------------------------------------------------------------

    private boolean dispatchPointGesture(int x, int y, long duration) {
        Path path = new Path();
        path.moveTo(x, y);
        return dispatchStrokes(new GestureDescription.StrokeDescription[]{
                new GestureDescription.StrokeDescription(path, 0, Math.max(1L, duration))});
    }

    /**
     * 派发手势并等待完成回调；binder 线程等待，回调走主线程 Handler。
     * dispatchGesture 本身线程无关，不需要额外切主线程。
     */
    private boolean dispatchStrokes(GestureDescription.StrokeDescription[] strokes) {
        if (strokes == null || strokes.length == 0 || strokes.length > MAX_GESTURE_STROKES) {
            return false;
        }
        AccessibilityService service = accessibilityService();
        if (service == null) return false;
        final CountDownLatch latch = new CountDownLatch(1);
        final boolean[] completed = {false};
        GestureDescription description;
        try {
            GestureDescription.Builder builder = new GestureDescription.Builder();
            for (GestureDescription.StrokeDescription stroke : strokes) {
                if (stroke == null) return false;
                builder.addStroke(stroke);
            }
            description = builder.build();
        } catch (Throwable error) {
            Log.w(TAG, "构造手势失败", error);
            return false;
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // 正常不会走到（AIDL 调用都在 binder 线程）：真在主线程时不能等待主线程回调，直接派发。
            try {
                return service.dispatchGesture(description, null, null);
            } catch (Throwable error) {
                Log.w(TAG, "派发手势失败（主线程直接派发）", error);
                return false;
            }
        }
        boolean dispatched;
        try {
            dispatched = service.dispatchGesture(description,
                    new AccessibilityService.GestureResultCallback() {
                        @Override
                        public void onCompleted(GestureDescription gestureDescription) {
                            completed[0] = true;
                            latch.countDown();
                        }

                        @Override
                        public void onCancelled(GestureDescription gestureDescription) {
                            completed[0] = false;
                            latch.countDown();
                        }
                    }, mMainHandler);
        } catch (Throwable error) {
            Log.w(TAG, "派发手势失败", error);
            return false;
        }
        if (!dispatched) return false;
        try {
            if (!latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "手势等待超时");
                return false;
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        }
        return completed[0];
    }

    /** 解析 strokesJson；格式非法返回 null。 */
    private static GestureDescription.StrokeDescription[] parseStrokes(String strokesJson)
            throws JSONException {
        if (strokesJson == null || strokesJson.trim().isEmpty()) return null;
        JSONArray strokesArray = new JSONArray(strokesJson);
        if (strokesArray.length() == 0 || strokesArray.length() > MAX_GESTURE_STROKES) {
            return null;
        }
        GestureDescription.StrokeDescription[] strokes =
                new GestureDescription.StrokeDescription[strokesArray.length()];
        for (int i = 0; i < strokesArray.length(); i++) {
            JSONArray points = strokesArray.getJSONArray(i);
            if (points.length() == 0) return null;
            // Node StrokeDescription supports delaying an individual stroke. Keep
            // existing t values relative to that stroke; old clients omit startTime.
            long startTime = points.getJSONObject(0).optLong("startTime", 0);
            if (startTime < 0) return null;
            Path path = new Path();
            long maxOffset = 0;
            for (int j = 0; j < points.length(); j++) {
                JSONObject point = points.getJSONObject(j);
                float x = (float) point.getDouble("x");
                float y = (float) point.getDouble("y");
                long offset = point.optLong("t", 0);
                if (j == 0) {
                    path.moveTo(x, y);
                } else {
                    path.lineTo(x, y);
                }
                maxOffset = Math.max(maxOffset, offset);
            }
            // 单点手势（点按）也要有正的时长，否则系统会直接取消。
            strokes[i] = new GestureDescription.StrokeDescription(
                    path, startTime, Math.max(1L, maxOffset));
        }
        return strokes;
    }

    // ------------------------------------------------------------------
    // 通用工具
    // ------------------------------------------------------------------

    /** 主线程执行 + 超时（binder 线程等待；超时后延迟动作由 cancelled 标记跳过）。 */
    @SuppressWarnings("unchecked")
    /** Transport/queue failure is not the same outcome as not-found or performAction=false. */
    private <T> T onAutomationMain(String api, MainCall<T> call) {
        if (accessibilityService() == null) throw new IllegalStateException(api + ": 无障碍服务未连接");
        long started = SystemClock.uptimeMillis();
        T result = onMain(TIMEOUT_MS, null, deadline -> {
            if (accessibilityService() == null) throw new IllegalStateException("无障碍服务已断开");
            return call.run(deadline);
        });
        if (result == null || SystemClock.uptimeMillis() - started >= TIMEOUT_MS)
            throw new IllegalStateException(api + ": 主线程自动化请求超时或执行失败，请查看宿主日志");
        return result;
    }

    private <T> T onMain(long timeoutMs, T fallback, MainCall<T> call) {
        final long deadline = SystemClock.uptimeMillis() + timeoutMs;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            try {
                return call.run(deadline);
            } catch (Throwable error) {
                Log.w(TAG, "主线程执行失败（调用方本身在主线程）", error);
                return fallback;
            }
        }
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final Object[] result = new Object[1];
        final Throwable[] failure = new Throwable[1];
        final CountDownLatch latch = new CountDownLatch(1);
        boolean posted = mMainHandler.post(() -> {
            if (cancelled.get() || SystemClock.uptimeMillis() >= deadline) {
                latch.countDown();
                return;
            }
            try {
                result[0] = call.run(deadline);
            } catch (Throwable error) {
                failure[0] = error;
            } finally {
                latch.countDown();
            }
        });
        if (!posted) return fallback;
        try {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                cancelled.set(true);
                Log.w(TAG, "主线程执行超时（" + timeoutMs + "ms）");
                return fallback;
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            cancelled.set(true);
            return fallback;
        }
        if (failure[0] != null) {
            Log.w(TAG, "主线程执行异常", failure[0]);
            return fallback;
        }
        //noinspection unchecked
        return (T) result[0];
    }

    private interface MainCall<T> {
        T run(long deadline) throws Throwable;
    }

    /** 调用方必须是本应用（:script）—— exported=false 之外再兜一层，误配也不会跨应用可用。 */
    private static boolean isTrustedCaller() {
        try {
            return Binder.getCallingUid() == Process.myUid();
        } catch (Throwable error) {
            return false;
        }
    }

    /** 只查系统设置里的无障碍开关（不拉起设置页、不触发服务连接）。 */
    private boolean isServiceEnabledInSettings() {
        try {
            ContentResolver resolver = getContentResolver();
            int enabled = Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0);
            if (enabled != 1) return false;
            String services = Settings.Secure.getString(
                    resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (services == null) return false;
            String prefix = getPackageName() + "/";
            for (String name : TextUtils.split(services, ":")) {
                if (name != null && name.startsWith(prefix)) {
                    return true;
                }
            }
        } catch (Throwable error) {
            Log.w(TAG, "读取无障碍设置失败", error);
        }
        return false;
    }

    /** AIDL 里的 index：-1 表示第 0 个（契约 §3）；同时夹到遍历上限以内，避免 position+1 溢出。 */
    private static int normalizeIndex(int index) {
        if (index <= 0) return 0;
        return Math.min(index, MAX_TRAVERSE_NODES - 1);
    }

    private static String charSequenceToString(CharSequence value) {
        return value == null ? "" : value.toString();
    }

    // ------------------------------------------------------------------
    // selectorJson
    // ------------------------------------------------------------------

    /** 解析后的选择器（字段与 UiSelector 对齐，AND 组合；缺省字段不参与过滤）。 */
    private static final class SelectorSpec {

        private String id;
        private String text;
        private String textContains;
        private String textStartsWith;
        private Pattern textMatches;
        private String desc;
        private String descContains;
        private String className;
        private String packageName;
        private Boolean clickable;
        private Boolean enabled;
        private Boolean scrollable;
        private Integer depth;
        private Integer index;
        private Rect bounds;
        /** 实现扩展：根窗口树里的子下标路径（getLatestNodeDump/findNodes 的 path 原样回传）。 */
        private int[] path;
        /** algorithm=BFS 时逐层遍历；默认 false=DFS（保持旧行为）。 */
        private boolean bfs;

        static SelectorSpec parse(String selectorJson) throws JSONException, PatternSyntaxException {
            SelectorSpec spec = new SelectorSpec();
            if (selectorJson == null || selectorJson.trim().isEmpty()) {
                return spec; // 空选择器 = 匹配全部节点（与 UiSelector 无过滤条件一致）
            }
            JSONObject json = new JSONObject(selectorJson);
            if (json.has("id")) spec.id = json.optString("id", null);
            if (json.has("text")) spec.text = json.optString("text", null);
            if (json.has("textContains")) spec.textContains = json.optString("textContains", null);
            if (json.has("textStartsWith")) spec.textStartsWith = json.optString("textStartsWith", null);
            if (json.has("textMatches")) {
                String regex = json.optString("textMatches", null);
                if (regex != null) spec.textMatches = Pattern.compile(regex);
            }
            if (json.has("desc")) spec.desc = json.optString("desc", null);
            if (json.has("descContains")) spec.descContains = json.optString("descContains", null);
            if (json.has("className")) spec.className = json.optString("className", null);
            if (json.has("packageName")) spec.packageName = json.optString("packageName", null);
            if (json.has("clickable")) spec.clickable = json.getBoolean("clickable");
            if (json.has("enabled")) spec.enabled = json.getBoolean("enabled");
            if (json.has("scrollable")) spec.scrollable = json.getBoolean("scrollable");
            if (json.has("depth")) spec.depth = json.getInt("depth");
            if (json.has("index")) spec.index = json.getInt("index");
            if (json.has("bounds")) {
                JSONObject boundsJson = json.getJSONObject("bounds");
                spec.bounds = new Rect(
                        boundsJson.optInt("left", 0),
                        boundsJson.optInt("top", 0),
                        boundsJson.optInt("right", 0),
                        boundsJson.optInt("bottom", 0));
            }
            if (json.has("path")) {
                JSONArray pathJson = json.getJSONArray("path");
                int length = pathJson.length();
                if (length > MAX_DEPTH) {
                    throw new JSONException("path 过长");
                }
                int[] parsedPath = new int[length];
                for (int i = 0; i < length; i++) {
                    parsedPath[i] = pathJson.getInt(i);
                }
                spec.path = parsedPath;
            }
            if (json.has("algorithm")) {
                String algorithm = json.optString("algorithm", null);
                try {
                    spec.bfs = SelectorTraversal.ALGORITHM_BFS.equals(
                            SelectorTraversal.canonicalAlgorithm(algorithm));
                } catch (IllegalArgumentException error) {
                    throw new JSONException("selectorJson algorithm 无效：" + algorithm);
                }
            }
            return spec;
        }

        boolean matches(AccessibilityNodeInfo node, int nodeDepth, int indexInParent, int[] nodePath) {
            if (id != null) {
                String fullId = node.getViewIdResourceName();
                if (fullId == null) return false;
                if (!fullId.equals(id) && !simplifyId(fullId).equals(id)) return false;
            }
            String nodeText = node.isPassword() ? "" : charSequenceToString(node.getText());
            if (text != null && !nodeText.equals(text)) return false;
            if (textContains != null && !nodeText.contains(textContains)) return false;
            if (textStartsWith != null && !nodeText.startsWith(textStartsWith)) return false;
            if (textMatches != null && !textMatches.matcher(nodeText).matches()) return false;
            String nodeDesc = charSequenceToString(node.getContentDescription());
            if (desc != null && !nodeDesc.equals(desc)) return false;
            if (descContains != null && !nodeDesc.contains(descContains)) return false;
            if (className != null && !className.equals(charSequenceToString(node.getClassName()))) return false;
            if (packageName != null && !packageName.equals(charSequenceToString(node.getPackageName()))) {
                return false;
            }
            if (clickable != null && node.isClickable() != clickable) return false;
            if (enabled != null && node.isEnabled() != enabled) return false;
            if (scrollable != null && node.isScrollable() != scrollable) return false;
            if (depth != null && nodeDepth != depth) return false;
            if (index != null && indexInParent != index) return false;
            if (bounds != null) {
                Rect nodeBounds = new Rect();
                node.getBoundsInScreen(nodeBounds);
                if (!nodeBounds.equals(bounds)) return false;
            }
            if (path != null) {
                if (nodeDepth != path.length) return false;
                for (int i = 0; i < path.length; i++) {
                    if (nodePath[i] != path[i]) return false;
                }
            }
            return true;
        }

        private static String simplifyId(String resourceName) {
            int slash = resourceName.indexOf('/');
            return slash >= 0 ? resourceName.substring(slash + 1) : resourceName;
        }

        @Override
        public String toString() {
            return "SelectorSpec{id=" + id + ", text=" + text + ", desc=" + desc
                    + ", className=" + className + ", depth=" + depth
                    + ", algorithm=" + (bfs ? "BFS" : "DFS")
                    + ", path=" + java.util.Arrays.toString(path) + "}";
        }
    }

    /** 一次匹配结果：节点 + 从根开始的路径 + 深度。 */
    private static final class NodeMatch {
        final AccessibilityNodeInfo node;
        final int depth;
        final int[] path;

        NodeMatch(AccessibilityNodeInfo node, int[] pathSource, int depth) {
            this.node = node;
            this.depth = depth;
            this.path = new int[depth];
            System.arraycopy(pathSource, 0, this.path, 0, depth);
        }
    }
}
