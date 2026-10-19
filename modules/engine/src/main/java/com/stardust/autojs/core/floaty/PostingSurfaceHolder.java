package com.stardust.autojs.core.floaty;

import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.View;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 把 {@link SurfaceHolder} 的回调投递到「脚本线程」的代理（ImGui 类 native 项目的关键）。
 *
 * <p>背景（见 docs/reports/2026-10-17_ImGui桥native上下文定位.md）：第三方 native 项目
 * （auto_jspro_imgui）在自己的 JS 里给 {@code SurfaceView.getHolder()} 挂
 * {@code SurfaceHolder.Callback}，回调里调 {@code nativeInit}（CreateContext + eglMakeCurrent，
 * 并且不释放 current）。SurfaceView 的回调由 ViewRootImpl 在**加窗线程（main）**触发，而
 * 脚本帧循环（setTimeout 16ms）跑在**脚本线程**：同一个 EGLContext 被另一线程 makeCurrent 时
 * 返回 EGL_BAD_ACCESS(3002)，同时 init / render / 退出 shutdown 不在同一线程串行，会导致
 * DestroyContext 插到帧中间而崩溃。
 *
 * <p>本代理在脚本线程调用 {@link #addCallback} 时记下脚本线程的 Handler，之后 Surface 回调
 * 仍由 main 触发，但**转发到脚本线程执行**；于是 nativeInit、帧循环、nativeShutdown 全在
 * 脚本线程串行。触摸监听（脚本 `setOnTouchListener`）同样转投脚本线程，避免 ImGui 输入
 * 与渲染跨线程。
 *
 * <p>线程/生命周期约定：
 * <ul>
 *   <li>没有 Looper（或就在同一线程）时直接同步调用，保持旧行为；</li>
 *   <li>窗口销毁（View detach / surfaceDestroyed）后 {@link #release()} 停止投递并移除桥接
 *       callback，避免脚本结束后 Handler 队列里还压着野回调；</li>
 *   <li>所有对真实 holder 的操作（addCallback/removeCallback/格式/大小）都委托给
 *       {@code mDelegate}，代理不改变 SurfaceView 本身的行为。</li>
 * </ul>
 */
final class PostingSurfaceHolder implements SurfaceHolder {

    private static final String TAG = "PostingSurfaceHolder";

    private final SurfaceHolder mDelegate;
    /** 脚本线程 Handler：在 addCallback 时按调用线程（脚本线程）延迟创建。 */
    private volatile Handler mScriptHandler;
    /** 发起回调的脚本线程（addCallback 的调用线程），用于捕获执行引擎线索。 */
    private volatile Thread mScriptThread;
    /** 捕获时的执行引擎（Rhino 引擎；null 表示无法判断，只靠 released 标记）。 */
    private volatile Object mEngineHint;
    private volatile boolean mEngineHintCaptured;
    private final CopyOnWriteArrayList<Callback> mCallbacks = new CopyOnWriteArrayList<>();
    private volatile boolean mReleased;
    private volatile boolean mBridgeInstalled;

    /** 桥接 callback：真实 Surface 回调（main）→ 投递脚本线程 → 逐个回调脚本 callback。 */
    private final Callback mBridge = new Callback() {
        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            dispatch(Event.CREATED, 0, 0, 0);
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            dispatch(Event.CHANGED, format, width, height);
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            dispatch(Event.DESTROYED, 0, 0, 0);
        }
    };

    private enum Event {
        CREATED, CHANGED, DESTROYED
    }

    PostingSurfaceHolder(SurfaceHolder delegate) {
        mDelegate = delegate;
    }

    /** 脚本线程（SurfaceHolder.addCallback 的调用线程）的 Handler；没有 Looper 返回 null。 */
    private Handler ensureScriptHandler() {
        Handler handler = mScriptHandler;
        if (handler != null) {
            return handler;
        }
        captureScriptThreadIfNeeded();
        synchronized (this) {
            if (mScriptHandler == null) {
                Looper looper = Looper.myLooper();
                if (looper != null) {
                    mScriptHandler = new Handler(looper);
                }
            }
            return mScriptHandler;
        }
    }

    /**
     * 在脚本线程捕获「本线程的执行引擎」：B 的 barrier 在脚本线程 inline destroy 不会排掉
     * 已 post 的 Surface 回调，执行前要靠引擎 isDestroyed() 丢弃（见 ScriptThreadCallbackGuard）。
     */
    private void captureScriptThreadIfNeeded() {
        if (mEngineHintCaptured) {
            return;
        }
        synchronized (this) {
            if (mEngineHintCaptured) {
                return;
            }
            mScriptThread = Thread.currentThread();
            mEngineHint = ScriptThreadCallbackGuard.captureEngineOfCurrentThread();
            mEngineHintCaptured = true;
        }
    }

    /**
     * 回调现在是否可以安全执行：窗口已 close/release 或引擎已经 destroy 都不行。
     * 引擎线索拿不到时（非 Rhino/服务不可用）只靠 released 标记，保持旧行为。
     */
    private boolean canInvoke() {
        if (mReleased) {
            return false;
        }
        return ScriptThreadCallbackGuard.isEngineAlive(mEngineHint);
    }

    private void installBridgeIfNeeded() {
        synchronized (this) {
            if (mBridgeInstalled) {
                return;
            }
            mBridgeInstalled = true;
        }
        try {
            mDelegate.addCallback(mBridge);
        } catch (Throwable error) {
            Log.w(TAG, "addCallback(bridge) failed", error);
        }
    }

    private void dispatch(Event event, int format, int width, int height) {
        if (!canInvoke()) {
            return;
        }
        Handler handler = mScriptHandler;
        if (handler == null) {
            // 脚本线程没有 Looper：维持旧行为（直接在 Surface 回调线程执行）。
            runCallbacks(event, format, width, height);
            return;
        }
        try {
            handler.post(() -> {
                if (canInvoke()) {
                    runCallbacks(event, format, width, height);
                }
            });
        } catch (Throwable error) {
            Log.w(TAG, "post surface callback failed", error);
        }
    }

    private void runCallbacks(Event event, int format, int width, int height) {
        if (!canInvoke()) {
            return;
        }
        for (Callback callback : mCallbacks) {
            try {
                switch (event) {
                    case CREATED:
                        callback.surfaceCreated(mDelegate);
                        break;
                    case CHANGED:
                        callback.surfaceChanged(mDelegate, format, width, height);
                        break;
                    case DESTROYED:
                        callback.surfaceDestroyed(mDelegate);
                        break;
                    default:
                        break;
                }
            } catch (Throwable error) {
                Log.w(TAG, "surface callback failed: " + event, error);
            }
        }
    }

    // ---- 脚本侧（主线程之外）调用的 SurfaceHolder 接口 ----------------------

    @Override
    public void addCallback(Callback callback) {
        if (callback == null) {
            return;
        }
        ensureScriptHandler();
        mCallbacks.addIfAbsent(callback);
        installBridgeIfNeeded();
    }

    @Override
    public void removeCallback(Callback callback) {
        if (callback == null) {
            return;
        }
        mCallbacks.remove(callback);
        if (mCallbacks.isEmpty() && mBridgeInstalled) {
            synchronized (this) {
                if (mBridgeInstalled) {
                    mBridgeInstalled = false;
                } else {
                    return;
                }
            }
            try {
                mDelegate.removeCallback(mBridge);
            } catch (Throwable error) {
                Log.w(TAG, "removeCallback(bridge) failed", error);
            }
        }
    }

    @Override
    public Surface getSurface() {
        return mDelegate.getSurface();
    }

    @Override
    public boolean isCreating() {
        return mDelegate.isCreating();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void setType(int type) {
        mDelegate.setType(type);
    }

    @Override
    public void setFixedSize(int width, int height) {
        mDelegate.setFixedSize(width, height);
    }

    @Override
    public void setSizeFromLayout() {
        mDelegate.setSizeFromLayout();
    }

    @Override
    public void setFormat(int format) {
        mDelegate.setFormat(format);
    }

    @Override
    public void setKeepScreenOn(boolean screenOn) {
        mDelegate.setKeepScreenOn(screenOn);
    }

    @Override
    public Canvas lockCanvas() {
        return mDelegate.lockCanvas();
    }

    @Override
    public Canvas lockCanvas(Rect dirty) {
        return mDelegate.lockCanvas(dirty);
    }

    @Override
    public void unlockCanvasAndPost(Canvas canvas) {
        mDelegate.unlockCanvasAndPost(canvas);
    }

    @Override
    public Rect getSurfaceFrame() {
        return mDelegate.getSurfaceFrame();
    }

    // ---- 触摸事件转投脚本线程 ------------------------------------------------

    /** 包装脚本的 OnTouchListener：事件先由 main 收到，再转投脚本线程执行。 */
    View.OnTouchListener wrapTouchListener(final View.OnTouchListener listener) {
        if (listener == null) {
            return null;
        }
        final Handler handler = ensureScriptHandler();
        if (handler == null) {
            return listener;
        }
        return new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (!canInvoke()) {
                    return false;
                }
                if (Looper.myLooper() == handler.getLooper()) {
                    return listener.onTouch(v, event);
                }
                final MotionEvent copy = MotionEvent.obtain(event);
                try {
                    handler.post(() -> {
                        try {
                            if (canInvoke()) {
                                listener.onTouch(v, copy);
                            }
                        } finally {
                            copy.recycle();
                        }
                    });
                } catch (Throwable error) {
                    Log.w(TAG, "post touch event failed", error);
                    copy.recycle();
                }
                return true;
            }
        };
    }

    /** 窗口销毁 / 视图 detach 时调用：停止投递、移除桥接 callback。 */
    void release() {
        if (mReleased) {
            return;
        }
        mReleased = true;
        mCallbacks.clear();
        synchronized (this) {
            if (mBridgeInstalled) {
                mBridgeInstalled = false;
                try {
                    mDelegate.removeCallback(mBridge);
                } catch (Throwable error) {
                    Log.w(TAG, "removeCallback(bridge) on release failed", error);
                }
            }
        }
    }
}
