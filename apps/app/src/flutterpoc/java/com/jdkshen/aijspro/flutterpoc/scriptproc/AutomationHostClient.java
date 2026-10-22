package com.jdkshen.aijspro.flutterpoc.scriptproc;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import com.stardust.autojs.host.IAutomationHost;
import com.stardust.autojs.host.IAutomationHostCallback;
import com.stardust.autojs.runtime.api.remote.RemoteAutomationBridge;

/**
 * Phase1 P1：{@code :script} 进程 -> 主进程 {@code AutomationHostService} 的 AIDL 客户端。
 *
 * <p>职责：显式 ComponentName（同 uid，exported=false）绑定主进程自动化服务，把
 * {@link IAutomationHost} 的 17 个同步方法封装成 engine 侧的纯 Java
 * {@link RemoteAutomationBridge.Backend}（engine 不能依赖 app 的 AIDL 生成类，所以由本类实现接口、
 * 由 {@code ScriptHostService} 注入）。
 *
 * <p>绑定策略：
 * <ul>
 *   <li>进程内单例；第一次调用（脚本线程）懒绑定，同步等待最多 {@link #BIND_TIMEOUT_MS}；</li>
 *   <li>调用失败（RemoteException/DeadObjectException/服务为空）时标记断开，下一次调用自动重绑一次
 *       （不做无限重试，避免主进程异常时把脚本线程卡死）；</li>
 *   <li>{@link #disconnect()} 由 ScriptHostService 在最后一个脚本结束时调用。</li>
 * </ul>
 *
 * <p>坐标约定：AIDL 参数一律是设备像素；脚本坐标的 ScreenMetrics 换算在引擎侧
 * （SimpleActionAutomator/RemoteUiObject）完成，本客户端不二次缩放。
 */
public final class AutomationHostClient implements RemoteAutomationBridge.Backend {

    private static final String TAG = "AutomationHostClient";

    /** 主进程自动化宿主服务（task-19 实现，manifest exported=false、与 :script 同 uid）。 */
    public static final String SERVICE_CLASS = "com.jdkshen.aijspro.automation.AutomationHostService";

    /** 绑定等待预算：主进程服务是常驻的，正常 <100ms；2s 只是兜底。 */
    private static final long BIND_TIMEOUT_MS = 2000L;

    private static volatile AutomationHostClient sInstance;

    public static AutomationHostClient getInstance(Context context) {
        AutomationHostClient client = sInstance;
        if (client == null) {
            synchronized (AutomationHostClient.class) {
                if (sInstance == null) {
                    Context app = context.getApplicationContext();
                    sInstance = new AutomationHostClient(app == null ? context : app);
                }
                client = sInstance;
            }
        }
        return client;
    }

    private final Context mContext;
    private final Object mLock = new Object();
    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            synchronized (mLock) {
                mService = IAutomationHost.Stub.asInterface(binder);
                mBinder = binder;
                mBound = true;
                mBinding = false;
                try {
                    binder.linkToDeath(mDeathRecipient, 0);
                } catch (Throwable error) {
                    Log.w(TAG, "linkToDeath failed", error);
                }
                mLock.notifyAll();
            }
            Log.i(TAG, "AutomationHostService connected");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            synchronized (mLock) {
                mService = null;
                mBinding = false;
                mLock.notifyAll();
            }
            Log.w(TAG, "AutomationHostService disconnected");
        }
    };

    private final IBinder.DeathRecipient mDeathRecipient = new IBinder.DeathRecipient() {
        @Override
        public void binderDied() {
            synchronized (mLock) {
                mService = null;
                mBinder = null;
                mBound = false;
                mBinding = false;
                mLock.notifyAll();
            }
            Log.w(TAG, "AutomationHostService binder died");
        }
    };

    private IAutomationHost mService;
    private IBinder mBinder;
    private boolean mBinding;
    private boolean mBound;

    // ---- task-163：主进程 -> :script 无障碍事件回调 ----
    private RemoteAutomationBridge.AccessibilityEventListener mAccessibilityEventListener;
    private IAutomationHostCallback.Stub mAccessibilityCallback;

    private AutomationHostClient(Context context) {
        mContext = context;
    }

    // ---- 绑定管理 --------------------------------------------------------

    /**
     * 同步绑定主进程服务（最多等 {@link #BIND_TIMEOUT_MS}）。已连上时直接返回 true。
     *
     * <p>可在脚本线程安全调用；onServiceConnected/onServiceDisconnected 在主线程回调。
     */
    public boolean connect() {
        synchronized (mLock) {
            if (mService != null) {
                return true;
            }
            if (mBinding) {
                waitForConnectionLocked();
                return mService != null;
            }
            mBinding = true;
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(mContext.getPackageName(), SERVICE_CLASS));
            boolean bound;
            try {
                bound = mContext.bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
            } catch (Throwable error) {
                Log.w(TAG, "bindService failed: " + SERVICE_CLASS, error);
                bound = false;
            }
            if (!bound) {
                mBinding = false;
                mLock.notifyAll();
                return false;
            }
            mBound = true;
            waitForConnectionLocked();
            if (mService == null) {
                // 超时：释放这次绑定，下一次调用会重试。
                try {
                    mContext.unbindService(mConnection);
                } catch (Throwable error) {
                    Log.w(TAG, "unbind after timeout failed", error);
                }
                mBound = false;
                mBinding = false;
                Log.w(TAG, "bind AutomationHostService timeout");
            }
            return mService != null;
        }
    }

    private void waitForConnectionLocked() {
        long deadline = SystemClock.elapsedRealtime() + BIND_TIMEOUT_MS;
        while (mService == null && mBinding) {
            long remaining = deadline - SystemClock.elapsedRealtime();
            if (remaining <= 0) {
                return;
            }
            try {
                mLock.wait(remaining);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** 解绑并清空服务引用（ScriptHostService 在最后一个脚本结束时调用）。 */
    public void disconnect() {
        synchronized (mLock) {
            if (mService != null && mAccessibilityCallback != null) {
                try {
                    mService.unregisterEventListener(mAccessibilityCallback);
                } catch (Throwable error) {
                    Log.w(TAG, "unregister accessibility listener failed", error);
                }
            }
            mAccessibilityEventListener = null;
            mAccessibilityCallback = null;
            if (mBinder != null) {
                try {
                    mBinder.unlinkToDeath(mDeathRecipient, 0);
                } catch (Throwable ignored) {
                    // binder 已死：忽略。
                }
            }
            mService = null;
            mBinder = null;
            mBinding = false;
            if (mBound) {
                try {
                    mContext.unbindService(mConnection);
                } catch (Throwable error) {
                    Log.w(TAG, "unbindService failed", error);
                }
                mBound = false;
            }
        }
        Log.i(TAG, "AutomationHostService disconnected by client");
    }

    private IAutomationHost serviceOrNull() {
        synchronized (mLock) {
            if (mService != null) {
                return mService;
            }
        }
        return connect() ? mService : null;
    }

    private IAutomationHost requireService() {
        IAutomationHost service = serviceOrNull();
        if (service == null) {
            throw new IllegalStateException(
                    "主进程 AutomationHostService 未就绪（未注册/主进程不可用/绑定超时）");
        }
        return service;
    }

    /** 调用失败时记日志；连接类错误把服务标记为断开，下一次调用自动重绑。 */
    private void onCallFailed(String api, Throwable error) {
        Log.w(TAG, "IAutomationHost." + api + " failed", error);
        if (error instanceof RemoteException || !(error instanceof RuntimeException)) {
            synchronized (mLock) {
                mService = null;
                mBinder = null;
                mBinding = false;
            }
        }
    }

    // ---- RemoteAutomationBridge.Backend 实现（全部同步，失败返回安全默认值） ----

    @Override
    public boolean isReady() {
        try {
            return requireService().isReady();
        } catch (Throwable error) {
            onCallFailed("isReady", error);
            return false;
        }
    }

    @Override
    public boolean isAccessibilityServiceEnabled() {
        try {
            return requireService().isAccessibilityServiceEnabled();
        } catch (Throwable error) {
            onCallFailed("isAccessibilityServiceEnabled", error);
            return false;
        }
    }

    @Override
    public String getLatestNodeDump() {
        try {
            String dump = requireService().getLatestNodeDump();
            return dump == null ? "" : dump;
        } catch (Throwable error) {
            onCallFailed("getLatestNodeDump", error);
            return "";
        }
    }

    @Override
    public String findNodes(String selectorJson) {
        try {
            String nodes = requireService().findNodes(selectorJson == null ? "{}" : selectorJson);
            return nodes == null ? "[]" : nodes;
        } catch (Throwable error) {
            onCallFailed("findNodes", error);
            return "[]";
        }
    }

    @Override
    public String getNodeInfo(String selectorJson, int index) {
        try {
            String node = requireService().getNodeInfo(
                    selectorJson == null ? "{}" : selectorJson, index);
            return node == null ? "" : node;
        } catch (Throwable error) {
            onCallFailed("getNodeInfo", error);
            return "";
        }
    }

    @Override
    public boolean clickNode(String selectorJson, int index) {
        try {
            return requireService().clickNode(selectorJson == null ? "{}" : selectorJson, index);
        } catch (Throwable error) {
            onCallFailed("clickNode", error);
            return false;
        }
    }

    @Override
    public boolean longClickNode(String selectorJson, int index) {
        try {
            return requireService().longClickNode(selectorJson == null ? "{}" : selectorJson, index);
        } catch (Throwable error) {
            onCallFailed("longClickNode", error);
            return false;
        }
    }

    @Override
    public boolean setTextNode(String selectorJson, int index, String text) {
        try {
            return requireService().setTextNode(
                    selectorJson == null ? "{}" : selectorJson, index, text == null ? "" : text);
        } catch (Throwable error) {
            onCallFailed("setTextNode", error);
            return false;
        }
    }

    @Override
    public boolean click(int x, int y) {
        try {
            return requireService().click(x, y);
        } catch (Throwable error) {
            onCallFailed("click", error);
            return false;
        }
    }

    @Override
    public boolean longClick(int x, int y) {
        try {
            return requireService().longClick(x, y);
        } catch (Throwable error) {
            onCallFailed("longClick", error);
            return false;
        }
    }

    @Override
    public boolean swipe(int x1, int y1, int x2, int y2, long duration) {
        try {
            return requireService().swipe(x1, y1, x2, y2, duration);
        } catch (Throwable error) {
            onCallFailed("swipe", error);
            return false;
        }
    }

    @Override
    public boolean gesture(String strokesJson) {
        try {
            return requireService().gesture(strokesJson == null ? "[]" : strokesJson);
        } catch (Throwable error) {
            onCallFailed("gesture", error);
            return false;
        }
    }

    @Override
    public boolean back() {
        try {
            return requireService().back();
        } catch (Throwable error) {
            onCallFailed("back", error);
            return false;
        }
    }

    @Override
    public boolean home() {
        try {
            return requireService().home();
        } catch (Throwable error) {
            onCallFailed("home", error);
            return false;
        }
    }

    @Override
    public boolean recents() {
        try {
            return requireService().recents();
        } catch (Throwable error) {
            onCallFailed("recents", error);
            return false;
        }
    }

    @Override
    public boolean notifications() {
        try {
            return requireService().notifications();
        } catch (Throwable error) {
            onCallFailed("notifications", error);
            return false;
        }
    }

    @Override
    public String screenshot(String targetPath) {
        try {
            String saved = requireService().screenshot(targetPath == null ? "" : targetPath);
            return saved == null ? "" : saved;
        } catch (Throwable error) {
            onCallFailed("screenshot", error);
            return "";
        }
    }

    @Override
    public ParcelFileDescriptor screenshotPixels(int maxWidth, int maxHeight, int format) {
        try {
            return requireService().screenshotPixels(maxWidth, maxHeight, format);
        } catch (Throwable error) {
            onCallFailed("screenshotPixels", error);
            return null;
        }
    }

    // ---- task-163：无障碍事件反向通道 ------------------------------------

    @Override
    public boolean subscribeAccessibilityEvents(
            final RemoteAutomationBridge.AccessibilityEventListener listener) {
        if (listener == null) {
            return false;
        }
        try {
            IAutomationHost service = requireService();
            synchronized (mLock) {
                mAccessibilityEventListener = listener;
                if (mAccessibilityCallback == null) {
                    mAccessibilityCallback = new IAutomationHostCallback.Stub() {
                        @Override
                        public void onAccessibilityEvent(String eventJson) {
                            RemoteAutomationBridge.AccessibilityEventListener target;
                            synchronized (mLock) {
                                target = mAccessibilityEventListener;
                            }
                            if (target == null) {
                                return;
                            }
                            try {
                                target.onAccessibilityEvent(eventJson);
                            } catch (Throwable error) {
                                Log.w(TAG, "forward accessibility event failed", error);
                            }
                        }

                        @Override
                        public void onServiceStateChanged(boolean ready) {
                            RemoteAutomationBridge.AccessibilityEventListener target;
                            synchronized (mLock) {
                                target = mAccessibilityEventListener;
                            }
                            if (target == null) {
                                return;
                            }
                            try {
                                target.onServiceStateChanged(ready);
                            } catch (Throwable error) {
                                Log.w(TAG, "forward service state failed", error);
                            }
                        }
                    };
                }
            }
            service.registerEventListener(mAccessibilityCallback);
            Log.i(TAG, "accessibility event listener registered");
            return true;
        } catch (Throwable error) {
            onCallFailed("subscribeAccessibilityEvents", error);
            return false;
        }
    }

    @Override
    public void unsubscribeAccessibilityEvents(
            RemoteAutomationBridge.AccessibilityEventListener listener) {
        IAutomationHost service;
        IAutomationHostCallback.Stub callback;
        synchronized (mLock) {
            mAccessibilityEventListener = null;
            callback = mAccessibilityCallback;
            mAccessibilityCallback = null;
            service = mService;
        }
        if (service == null || callback == null) {
            return;
        }
        try {
            service.unregisterEventListener(callback);
        } catch (Throwable error) {
            Log.w(TAG, "unsubscribeAccessibilityEvents failed", error);
        }
    }
}
