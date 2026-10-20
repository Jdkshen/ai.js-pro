// SPDX-License-Identifier: MIT
package org.autojs.source.engine.android;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.Image;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;

import com.stardust.autojs.core.image.capture.ScreenCapturer;
import com.stardust.util.ScreenMetrics;

import org.json.JSONException;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@code kind:"projection"} 的 :node24 专用授权 Activity + 会话状态。
 *
 * <p>冻结协议要求：专用、非导出、运行在 :node24 进程的授权 Activity；每次请求使用 job-scoped
 * token 和一次性回调，拒绝/超时/取消都要有明确结果，不缓存或复用旧授权 token。授权结果 Intent
 * 只会交给当前 token 对应的 {@link Session}，构造一次 {@link ScreenCapturer} 后立即丢弃。
 *
 * <p>Activity 不导出（manifest {@code exported=false}），同进程静态表只接受带随机 token 的
 * 请求；旧 token 请求、重复结果、进程重建后的孤儿结果都不会被采用。
 */
public class Node24CaptureConsentActivity extends Activity {

    private static final String TAG = "Node24Capture";

    private static final String EXTRA_TOKEN =
            "org.autojs.source.engine.android.NODE24_CAPTURE_TOKEN";
    private static final int REQUEST_PROJECTION = 0x24C1;
    private static final int RESULT_TIMEOUT = RESULT_FIRST_USER + 24;
    /** 宿主等待预算（协议 55s）；Activity 自身提前 1s 结束，避免和宿主超时赛跑。 */
    private static final long REQUEST_TIMEOUT_MS = 55_000L;
    private static final long ACTIVITY_TIMEOUT_MS = 54_000L;
    private static final long DEFAULT_CAPTURE_TIMEOUT_MS = 5_000L;
    private static final int MAX_CAPTURE_DIMENSION = 16_384;
    /** 与 Node24Images 的单图上限一致，避免先分配超大 int[]/Bitmap 再被图片层拒绝。 */
    private static final long MAX_CAPTURE_PIXELS = 16_000_000L;

    private static final Map<String, Pending> PENDING = new HashMap<>();

    private String mToken;
    private Handler mHandler;
    private final Runnable mTimeout = new Runnable() {
        @Override
        public void run() {
            complete(mToken, RESULT_TIMEOUT, null, true);
            finishConsent(Node24CaptureConsentActivity.this);
        }
    };

    // ------------------------------------------------------------------
    // Activity：只负责系统授权弹窗
    // ------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mHandler = new Handler(Looper.getMainLooper());
        // ScreenMetrics 是进程级静态值；:node24 没有主界面 Activity，由本授权页初始化一次，
        // 这样未指定 width/height 时 ScreenCapturer 能使用真实屏幕尺寸（并接受单应用 Capture 回调）。
        try {
            ScreenMetrics.initIfNeeded(this);
        } catch (Throwable error) {
            Log.w(TAG, "init ScreenMetrics failed", error);
        }
        Intent intent = getIntent();
        mToken = intent == null ? null : intent.getStringExtra(EXTRA_TOKEN);
        Pending pending = pending(mToken);
        if (pending == null) {
            // 没有当前 job 的 token（旧请求/伪造 Intent）：不弹窗、不接收结果。
            finish();
            return;
        }
        pending.activity = new WeakReference<>(this);
        if (pending.delivered.get()) {
            // request 在 onCreate 之前已被取消/超时：不要再弹系统授权页。
            finish();
            return;
        }

        MediaProjectionManager manager = (MediaProjectionManager)
                getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (manager == null) {
            complete(mToken, RESULT_CANCELED, null, false);
            finish();
            return;
        }
        try {
            // :node24 里没有无障碍服务实例：让主进程代持自动点击（整屏授权框）。
            CaptureConsentBridge.arm(this);
            // 与主进程一致：API 34+ 强制整屏授权，避免单应用模式切 App 黑帧。
            startActivityForResult(
                    com.stardust.autojs.core.image.capture.ScreenCaptureIntents.create(manager),
                    REQUEST_PROJECTION);
            if (pending.delivered.get()) {
                finish();
                return;
            }
            mHandler.postDelayed(mTimeout, ACTIVITY_TIMEOUT_MS);
        } catch (Throwable error) {
            Log.w(TAG, "start screen capture consent failed", error);
            complete(mToken, RESULT_CANCELED, null, false);
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PROJECTION) {
            return;
        }
        if (mHandler != null) {
            mHandler.removeCallbacks(mTimeout);
        }
        CaptureConsentBridge.disarm(this);
        complete(mToken, resultCode, data, false);
        finish();
    }

    @Override
    protected void onDestroy() {
        if (mHandler != null) {
            mHandler.removeCallbacks(mTimeout);
        }
        // 用户返回/Activity 被系统回收且没有结果 = 拒绝；不把旧 token 留给后续任务。
        if (!isChangingConfigurations()) {
            complete(mToken, RESULT_CANCELED, null, false);
        }
        super.onDestroy();
    }

    /**
     * 结束授权页：先关闭本页通过 startActivityForResult 启动的系统投屏授权子 Activity
     * （SystemUI 的 MediaProjectionPermissionActivity），再 finish 自己。只 finish 父页会在
     * 取消/超时后留下孤儿系统弹窗；两步分别捕获异常，保证关闭子页失败时父页仍会结束。
     */
    private static void finishConsent(Activity activity) {
        if (activity == null) {
            return;
        }
        try {
            activity.finishActivity(REQUEST_PROJECTION);
        } catch (Throwable error) {
            Log.w(TAG, "finish projection consent child failed", error);
        }
        try {
            activity.finish();
        } catch (Throwable error) {
            Log.w(TAG, "finish consent activity failed", error);
        }
    }

    // ------------------------------------------------------------------
    // job-scoped 会话
    // ------------------------------------------------------------------

    /** 每个 Node 任务一个；由 {@link Node24HostBridge} 持有并在任务结束时 close。 */
    public static final class Session implements AutoCloseable {

        private final Object mLock = new Object();
        private ScreenCapturer mCapturer;
        private String mPendingToken;
        private boolean mUsed;
        private boolean mClosed;

        /**
         * 请求系统投屏授权。拒绝返回 false；55s 超时/中断/关闭抛中文异常；只有成功才创建
         * {@link ScreenCapturer}。一个 job 最多成功建立一次投屏会话。
         */
        public boolean request(Context context, JSONObject options) throws Exception {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                throw new IllegalStateException("屏幕截图需要 Android 5.0 及以上");
            }
            final String token;
            final Pending pending;
            final int orientation;
            final int[] size;
            final int density;
            final boolean explicitSize;
            synchronized (mLock) {
                if (mClosed) {
                    throw new IllegalStateException("本次 Node 任务的屏幕投影已释放");
                }
                if (mUsed) {
                    throw new IllegalStateException("本次 Node 任务已使用过屏幕投影；"
                            + "请重新运行脚本以获取新的系统授权");
                }
                if (mCapturer != null && !mCapturer.isReleased()) {
                    throw new IllegalStateException("本次 Node 任务已存在屏幕投影；请先 stop()");
                }
                if (mPendingToken != null) {
                    throw new IllegalStateException("本次 Node 任务已有屏幕授权请求正在进行");
                }
                JSONObject safeOptions = options == null ? new JSONObject() : options;
                orientation = normalizeOrientation(safeOptions.opt("orientation"));
                int requestedWidth = safeOptions.has("width")
                        ? safeOptions.optInt("width", -1) : -1;
                int requestedHeight = safeOptions.has("height")
                        ? safeOptions.optInt("height", -1) : -1;
                int[] display = displayMetrics(context);
                size = resolveCaptureSize(requestedWidth, requestedHeight,
                        display[0], display[1], orientation);
                density = display[2] > 0 ? display[2]
                        : context.getResources().getDisplayMetrics().densityDpi;
                explicitSize = requestedWidth > 0 || requestedHeight > 0;
                token = newToken();
                pending = new Pending();
                mPendingToken = token;
                synchronized (PENDING) {
                    PENDING.put(token, pending);
                }
            }

            // task-180：授权 Activity 必须与请求进程同进程（Session/PENDING 是进程内静态表），
            // dev-1129 起 Node 引擎跑在 :node24_1/_2/_3 池进程，需选对应池的授权页子类。
            String consentClass = consentActivityClassForCurrentProcess(context);
            Intent intent = new Intent().setClassName(context.getPackageName(), consentClass)
                    .putExtra(EXTRA_TOKEN, token)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            // task-180：:node24 是后台进程，直接 startActivity 会被后台启动限制拦下；
            // 先让主进程（通常可见）通过 PendingIntent 启动本授权页，1.2s 内未收到页面回调则直连兜底。
            boolean relayed = false;
            try {
                Intent relay = new Intent("com.jdkshen.aijspro.NODE24_CAPTURE_CONSENT")
                        .setPackage(context.getPackageName())
                        .putExtra(EXTRA_TOKEN, token)
                        .putExtra("org.autojs.source.engine.android.NODE24_CAPTURE_ACTIVITY", consentClass);
                context.sendBroadcast(relay);
                relayed = true;
            } catch (Throwable relayError) {
                Log.w(TAG, "broadcast capture consent relay failed", relayError);
            }
            if (!relayed) {
                try {
                    context.startActivity(intent);
                } catch (Throwable error) {
                    Node24CaptureConsentActivity.cancelPending(token);
                    throw new IllegalStateException("无法打开系统屏幕截图授权界面：" + describe(error), error);
                }
            }

            boolean delivered;
            try {
                delivered = pending.done.await(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Node24CaptureConsentActivity.cancelPending(token);
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待屏幕截图授权被中断", interrupted);
            }
            synchronized (mLock) {
                if (token.equals(mPendingToken)) {
                    mPendingToken = null;
                }
                if (mClosed) {
                    Node24CaptureConsentActivity.cancelPending(token);
                    throw new IllegalStateException("本次 Node 任务的屏幕投影已释放");
                }
            }
            if (!delivered) {
                Node24CaptureConsentActivity.cancelPending(token);
                throw new IllegalStateException(
                        "等待屏幕截图授权超时（55 秒）；请重试并在系统弹窗中选择「开始录制」");
            }
            if (pending.timedOut) {
                throw new IllegalStateException(
                        "等待屏幕截图授权超时（55 秒）；请重试并在系统弹窗中选择「开始录制」");
            }
            if (pending.resultCode != RESULT_OK) {
                // 用户拒绝是正常结果，不是异常；不缓存任何授权。
                return false;
            }

            Intent data = pending.data;
            pending.data = null;
            if (data == null) {
                throw new IllegalStateException("系统返回的屏幕截图授权为空，请重试");
            }
            // 未显式指定尺寸时把 -1 交给 ScreenCapturer，让 Android 14 的单应用捕获回调生效；
            // 只有 Activity 未能初始化 ScreenMetrics 时才退回预先算好的屏幕尺寸，避免 0x0 ImageReader。
            // ScreenMetrics 由授权 Activity onCreate 初始化，必须等授权返回后再读。
            boolean screenMetricsReady = ScreenMetrics.getDeviceScreenWidth() > 0
                    && ScreenMetrics.getDeviceScreenHeight() > 0;
            int captureWidth = explicitSize || !screenMetricsReady ? size[0] : -1;
            int captureHeight = explicitSize || !screenMetricsReady ? size[1] : -1;
            ScreenCapturer capturer;
            try {
                // ScreenCapturer 会注册 OrientationEventListener；放到有 Looper 的主线程创建，
                // 避免 Node 工作线程没有 Looper 时 sensor 注册失败。捕获/释放仍可在 Node 线程调用。
                capturer = createCapturerOnMainThread(context, data, orientation, density,
                        captureWidth, captureHeight);
            } catch (Throwable error) {
                throw new IllegalStateException("启动屏幕捕获失败（授权可能已被系统取消）："
                        + describe(error), error);
            }
            synchronized (mLock) {
                if (mClosed) {
                    try {
                        capturer.release();
                    } catch (Throwable ignored) {
                        // 已关闭时只做清理。
                    }
                    throw new IllegalStateException("本次 Node 任务的屏幕投影已释放");
                }
                mCapturer = capturer;
                mUsed = true;
            }
            return true;
        }

        /** 复制一帧为新的图片句柄。fresh=false 时优先返回最近帧。 */
        public JSONObject capture(Node24Images images, boolean fresh, long timeoutMillis)
                throws Exception {
            final ScreenCapturer capturer;
            synchronized (mLock) {
                if (mClosed) {
                    throw new IllegalStateException("本次 Node 任务的屏幕投影已释放");
                }
                capturer = mCapturer;
                if (capturer == null) {
                    throw new IllegalStateException("尚未获得屏幕截图授权；请先 request()");
                }
                if (capturer.isReleased()) {
                    mCapturer = null;
                    throw new IllegalStateException("屏幕投影授权已失效（系统已停止录屏或锁屏）；"
                            + "请重新运行脚本并重新授权");
                }
            }
            Image frame;
            try {
                frame = capturer.capture(fresh, normalizeCaptureTimeout(timeoutMillis));
            } catch (Throwable error) {
                if (capturer.isReleased()) {
                    synchronized (mLock) {
                        if (mCapturer == capturer) {
                            mCapturer = null;
                        }
                    }
                }
                throw new IllegalStateException("屏幕截图失败：" + describe(error), error);
            }
            if (frame == null) {
                throw new IllegalStateException("屏幕截图失败：没有得到有效帧");
            }
            // 不要 close(frame)：ScreenCapturer 把该帧保留为 mUnderUsingImage，latestImage/
            // 下一帧复用和 release 时由它统一关闭；提前 close 会让下一帧拿到失效 Image。
            // release()/onProjectionStopped() 也在 ScreenCapturer 的 monitor 内关闭该帧，因此
            // 只在「检查 released + 复制像素」时短暂持锁；等待帧的 capturer.capture() 在锁外。
            Bitmap bitmap;
            synchronized (capturer) {
                if (capturer.isReleased()) {
                    throw new IllegalStateException("屏幕投影授权已失效（系统已停止录屏或锁屏）；"
                            + "请重新运行脚本并重新授权");
                }
                bitmap = frameToBitmap(frame);
            }
            return images.register(bitmap);
        }

        /** 幂等停止并释放投屏；不会复用旧授权。 */
        public boolean stop() {
            String token;
            ScreenCapturer capturer;
            synchronized (mLock) {
                token = mPendingToken;
                mPendingToken = null;
                capturer = mCapturer;
                mCapturer = null;
            }
            Node24CaptureConsentActivity.cancelPending(token);
            if (capturer != null) {
                try {
                    capturer.release();
                } catch (Throwable error) {
                    Log.w(TAG, "release ScreenCapturer failed", error);
                }
            }
            return true;
        }

        /** 只取消还在等待系统授权的 Activity（脚本 cancel 用），不释放已建立的投屏。 */
        public void cancelPending() {
            String token;
            synchronized (mLock) {
                token = mPendingToken;
                mPendingToken = null;
            }
            Node24CaptureConsentActivity.cancelPending(token);
        }

        @Override
        public void close() {
            boolean first;
            synchronized (mLock) {
                first = !mClosed;
                mClosed = true;
            }
            if (first) {
                stop();
            }
        }
    }

    /** 宿主 kind:"projection" 入口；返回统一 envelope。 */
    public static JSONObject handle(Context context, Session session, Node24Images images,
                                    JSONObject request) {
        try {
            String action = request.optString("action", "");
            switch (action) {
                case "request": {
                    JSONObject options = request.optJSONObject("options");
                    return ok(session.request(context, options == null ? new JSONObject() : options));
                }
                case "capture": {
                    boolean fresh = request.optBoolean("fresh", true);
                    long timeout = request.optLong("timeoutMillis", DEFAULT_CAPTURE_TIMEOUT_MS);
                    return ok(session.capture(images, fresh, timeout));
                }
                case "stop":
                    return ok(session.stop());
                default:
                    return error("projection 不支持的操作：" + action
                            + "（支持 request/capture/stop）");
            }
        } catch (Throwable error) {
            Log.w(TAG, "projection action failed: " + request, error);
            return error(describe(error));
        }
    }

    // ------------------------------------------------------------------
    // 授权回调（同进程，token 精确匹配）
    // ------------------------------------------------------------------

    private static Pending pending(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        synchronized (PENDING) {
            return PENDING.get(token);
        }
    }

    private static boolean complete(String token, int resultCode, Intent data, boolean timedOut) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        Pending pending;
        synchronized (PENDING) {
            pending = PENDING.remove(token);
        }
        if (pending == null) {
            return false;
        }
        return pending.complete(resultCode, data, timedOut);
    }

    /** 取消 pending（超时/中断/任务关闭）；幂等，迟到的系统结果会因 token 不在表里被丢弃。 */
    private static void cancelPending(String token) {
        if (token == null || token.isEmpty()) {
            return;
        }
        Pending pending;
        synchronized (PENDING) {
            pending = PENDING.remove(token);
        }
        if (pending != null) {
            pending.complete(RESULT_CANCELED, null, false);
            pending.finishActivity();
        }
    }

    private static final class Pending {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicBoolean delivered = new AtomicBoolean(false);
        volatile int resultCode = RESULT_CANCELED;
        volatile Intent data;
        volatile boolean timedOut;
        volatile WeakReference<Activity> activity;

        boolean complete(int code, Intent result, boolean timeout) {
            if (!delivered.compareAndSet(false, true)) {
                return false;
            }
            resultCode = code;
            data = result;
            timedOut = timeout;
            done.countDown();
            return true;
        }

        void finishActivity() {
            WeakReference<Activity> reference = activity;
            final Activity activity = reference == null ? null : reference.get();
            if (activity == null) {
                return;
            }
            try {
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        finishConsent(activity);
                    }
                });
            } catch (Throwable error) {
                Log.w(TAG, "finish consent activity failed", error);
            }
        }
    }

    // ------------------------------------------------------------------
    // 纯逻辑（单测覆盖）
    // ------------------------------------------------------------------

    /** 当前 :node24 池进程对应的授权页类；非池进程用基类。 */
    private static String consentActivityClassForCurrentProcess(Context context) {
        String process = currentProcessName(context);
        if (process != null) {
            if (process.endsWith(":node24_1")) return Node24CaptureConsentActivity1.class.getName();
            if (process.endsWith(":node24_2")) return Node24CaptureConsentActivity2.class.getName();
            if (process.endsWith(":node24_3")) return Node24CaptureConsentActivity3.class.getName();
        }
        return Node24CaptureConsentActivity.class.getName();
    }

    private static String currentProcessName(Context context) {
        try {
            byte[] raw = java.nio.file.Files.readAllBytes(
                    new java.io.File("/proc/self/cmdline").toPath());
            String name = new String(raw, java.nio.charset.StandardCharsets.UTF_8).trim();
            if (!name.isEmpty()) return name;
        } catch (Throwable ignored) {
        }
        try {
            android.app.ActivityManager manager = (android.app.ActivityManager)
                    context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager != null) {
                int pid = android.os.Process.myPid();
                java.util.List<android.app.ActivityManager.RunningAppProcessInfo> list =
                        manager.getRunningAppProcesses();
                if (list != null) {
                    for (android.app.ActivityManager.RunningAppProcessInfo info : list) {
                        if (info != null && info.pid == pid) return info.processName;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    static String newToken() {
        return UUID.randomUUID().toString();
    }

    static int normalizeOrientation(Object raw) {
        if (raw == null || raw == JSONObject.NULL) {
            return ScreenCapturer.ORIENTATION_AUTO;
        }
        if (raw instanceof Number) {
            int value = ((Number) raw).intValue();
            // -1 是 Pro/旧接口的「自动」，0 是 Configuration.ORIENTATION_UNDEFINED。
            if (value == -1 || value == ScreenCapturer.ORIENTATION_AUTO) {
                return ScreenCapturer.ORIENTATION_AUTO;
            }
            if (value == ScreenCapturer.ORIENTATION_PORTRAIT) {
                return ScreenCapturer.ORIENTATION_PORTRAIT;
            }
            if (value == ScreenCapturer.ORIENTATION_LANDSCAPE) {
                return ScreenCapturer.ORIENTATION_LANDSCAPE;
            }
            throw new IllegalArgumentException("orientation 只支持 -1/0(自动)、1(竖屏)、2(横屏)，收到：" + value);
        }
        if (raw instanceof String) {
            String value = ((String) raw).trim().toLowerCase(java.util.Locale.US);
            if (value.isEmpty() || "auto".equals(value) || "undefined".equals(value)) {
                return ScreenCapturer.ORIENTATION_AUTO;
            }
            if ("portrait".equals(value)) {
                return ScreenCapturer.ORIENTATION_PORTRAIT;
            }
            if ("landscape".equals(value)) {
                return ScreenCapturer.ORIENTATION_LANDSCAPE;
            }
        }
        throw new IllegalArgumentException("orientation 只支持 auto/portrait/landscape 或 -1/0/1/2：" + raw);
    }

    /**
     * 请求尺寸优先；未指定时用真实显示尺寸，并按请求方向交换宽高——与实际捕获面一致，
     * 避免 :node24 进程里 ScreenMetrics 未初始化导致 0x0 的 ImageReader。
     */
    static int[] resolveCaptureSize(int requestedWidth, int requestedHeight,
                                    int displayWidth, int displayHeight, int orientation) {
        if (requestedWidth > 0 || requestedHeight > 0) {
            if (requestedWidth <= 0 || requestedHeight <= 0) {
                throw new IllegalArgumentException("width/height 必须同时为正数或同时省略");
            }
            checkCaptureSize(requestedWidth, requestedHeight);
            return new int[]{requestedWidth, requestedHeight};
        }
        if (displayWidth <= 0 || displayHeight <= 0) {
            throw new IllegalStateException("无法获取屏幕尺寸（" + displayWidth + "x" + displayHeight + "）");
        }
        int width = displayWidth;
        int height = displayHeight;
        if (orientation == ScreenCapturer.ORIENTATION_LANDSCAPE && width < height) {
            width = displayHeight;
            height = displayWidth;
        } else if (orientation == ScreenCapturer.ORIENTATION_PORTRAIT && height < width) {
            width = displayHeight;
            height = displayWidth;
        }
        checkCaptureSize(width, height);
        return new int[]{width, height};
    }

    private static void checkCaptureSize(int width, int height) {
        long pixels = (long) width * (long) height;
        if (width <= 0 || height <= 0 || width > MAX_CAPTURE_DIMENSION
                || height > MAX_CAPTURE_DIMENSION || pixels > MAX_CAPTURE_PIXELS) {
            throw new IllegalArgumentException("屏幕捕获尺寸无效：" + width + "x" + height
                    + "（每边上限 " + MAX_CAPTURE_DIMENSION + "，总像素上限 "
                    + MAX_CAPTURE_PIXELS + "）");
        }
    }

    static long normalizeCaptureTimeout(long timeoutMillis) {
        return Math.max(0L, Math.min(ScreenCapturer.MAX_CAPTURE_TIMEOUT_MILLIS, timeoutMillis));
    }

    /**
     * ScreenCapturer 的构造会创建 OrientationEventListener（依赖 Looper）。Node 工作线程没有
     * Looper，所以统一丢到主线程构造并同步等待；构造完成后 capture/release 仍由 Node 线程调用。
     */
    private static ScreenCapturer createCapturerOnMainThread(
            final Context context, final Intent data, final int orientation, final int density,
            final int width, final int height) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return new ScreenCapturer(context, data, orientation, density,
                    null, width, height, false);
        }
        // 主线程与等待线程通过同一把 handoff 锁交接所有权：任一方先取得锁，另一方就
        // 明确知道该由谁 release 迟到的 ScreenCapturer，避免在 check 与赋值之间泄漏。
        final Object handoff = new Object();
        final boolean[] abandoned = new boolean[1];
        final ScreenCapturer[] holder = new ScreenCapturer[1];
        final Throwable[] failure = new Throwable[1];
        final CountDownLatch done = new CountDownLatch(1);
        boolean posted = new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                ScreenCapturer created = null;
                try {
                    synchronized (handoff) {
                        if (abandoned[0]) {
                            return;
                        }
                    }
                    created = new ScreenCapturer(context, data, orientation, density,
                            null, width, height, false);
                    boolean releaseNow;
                    synchronized (handoff) {
                        releaseNow = abandoned[0];
                        if (!releaseNow) {
                            holder[0] = created;
                            created = null;
                        }
                    }
                    if (releaseNow) {
                        // 宿主已超时/中断放弃；这里创建出来的资源必须自己回收，不能无人持有。
                        releaseCapturerQuietly(created, "release abandoned ScreenCapturer");
                        created = null;
                    }
                } catch (Throwable error) {
                    failure[0] = error;
                } finally {
                    if (created != null) {
                        releaseCapturerQuietly(created, "release failed ScreenCapturer");
                    }
                    done.countDown();
                }
            }
        });
        if (!posted) {
            throw new IllegalStateException("主线程不可用，无法启动屏幕捕获");
        }
        try {
            if (!done.await(10L, TimeUnit.SECONDS)) {
                releaseCapturerQuietly(abandonAndTake(handoff, abandoned, holder),
                        "release timed-out ScreenCapturer");
                throw new IllegalStateException("启动屏幕捕获超时（主线程无响应）");
            }
        } catch (InterruptedException interrupted) {
            releaseCapturerQuietly(abandonAndTake(handoff, abandoned, holder),
                    "release interrupted ScreenCapturer");
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待启动屏幕捕获被中断", interrupted);
        }
        if (failure[0] != null) {
            if (failure[0] instanceof Exception) {
                throw (Exception) failure[0];
            }
            throw new IllegalStateException("启动屏幕捕获失败", failure[0]);
        }
        ScreenCapturer result;
        synchronized (handoff) {
            result = holder[0];
            holder[0] = null;
        }
        if (result == null) {
            throw new IllegalStateException("启动屏幕捕获失败：ScreenCapturer 为空");
        }
        return result;
    }

    /** 标记放弃并取走已交接的迟到结果；未交接的由主线程任务在同样持锁时看到放弃并自行释放。 */
    private static ScreenCapturer abandonAndTake(Object handoff, boolean[] abandoned,
                                                 ScreenCapturer[] holder) {
        synchronized (handoff) {
            abandoned[0] = true;
            ScreenCapturer late = holder[0];
            holder[0] = null;
            return late;
        }
    }

    private static void releaseCapturerQuietly(ScreenCapturer capturer, String message) {
        if (capturer == null) {
            return;
        }
        try {
            capturer.release();
        } catch (Throwable error) {
            Log.w(TAG, message, error);
        }
    }

    private static int[] displayMetrics(Context context) {
        DisplayMetrics metrics = new DisplayMetrics();
        try {
            WindowManager manager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            Display display = manager == null ? null : manager.getDefaultDisplay();
            if (display != null) {
                display.getRealMetrics(metrics);
            }
        } catch (Throwable error) {
            Log.w(TAG, "read real display metrics failed", error);
        }
        int density = metrics.densityDpi;
        if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0) {
            DisplayMetrics resources = context.getResources().getDisplayMetrics();
            metrics = resources;
            if (density <= 0) {
                density = resources.densityDpi;
            }
        }
        return new int[]{metrics.widthPixels, metrics.heightPixels, density};
    }

    /** 把 ImageReader 的 RGBA_8888 帧复制成独立 Bitmap（处理 rowStride 对齐）。 */
    private static Bitmap frameToBitmap(Image frame) {
        int width = frame.getWidth();
        int height = frame.getHeight();
        if (width <= 0 || height <= 0) {
            throw new IllegalStateException("屏幕帧尺寸无效：" + width + "x" + height);
        }
        long pixelCount = (long) width * (long) height;
        if (pixelCount > MAX_CAPTURE_PIXELS) {
            throw new IllegalStateException("屏幕帧过大：" + width + "x" + height
                    + "（" + pixelCount + " 像素，上限 " + MAX_CAPTURE_PIXELS + "）");
        }
        Image.Plane[] planes = frame.getPlanes();
        if (planes == null || planes.length == 0) {
            throw new IllegalStateException("屏幕帧没有可用平面");
        }
        Image.Plane plane = planes[0];
        ByteBuffer buffer = plane.getBuffer();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
        if (pixelStride < 4 || rowStride < width * pixelStride) {
            throw new IllegalStateException("屏幕帧像素布局不支持：rowStride=" + rowStride
                    + " pixelStride=" + pixelStride + " width=" + width);
        }
        int base = buffer.position();
        long required = (long) base + (long) rowStride * (height - 1)
                + (long) pixelStride * (width - 1) + 4L;
        if (required > buffer.limit()) {
            throw new IllegalStateException("屏幕帧缓冲区不足：需要 " + required
                    + " 字节，实际 " + buffer.limit());
        }
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            int row = base + y * rowStride;
            int output = y * width;
            for (int x = 0; x < width; x++) {
                int offset = row + x * pixelStride;
                int r = buffer.get(offset) & 0xFF;
                int g = buffer.get(offset + 1) & 0xFF;
                int b = buffer.get(offset + 2) & 0xFF;
                // 屏幕帧是显示内容，统一按不透明处理，避免某些 ROM 写 0 alpha 变全透明。
                pixels[output + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
    }

    private static JSONObject ok(Object value) {
        try {
            return new JSONObject().put("ok", true)
                    .put("value", value == null ? JSONObject.NULL : value);
        } catch (JSONException error) {
            return error("projection 响应编码失败：" + error.getMessage());
        }
    }

    private static JSONObject error(String message) {
        try {
            return new JSONObject().put("ok", false)
                    .put("error", message == null ? "未知屏幕投影错误" : message);
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
}
