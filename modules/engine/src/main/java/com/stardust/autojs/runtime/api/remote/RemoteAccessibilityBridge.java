package com.stardust.autojs.runtime.api.remote;

import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.Nullable;

import com.stardust.app.GlobalAppContext;
import com.stardust.autojs.core.accessibility.AccessibilityBridge;
import com.stardust.autojs.core.activity.ActivityInfoProvider;
import com.stardust.autojs.runtime.exception.ScriptInterruptedException;
import com.stardust.util.UiHandler;
import com.stardust.view.accessibility.AccessibilityNotificationObserver;
import com.stardust.view.accessibility.AccessibilityService;

/**
 * :script 进程里的「远程无障碍桥」：把 {@link AccessibilityBridge} 的可用性检查转给主进程
 * （{@code AutomationHostService}），本地服务相关方法返回空的/降级结果。
 *
 * <p>为什么需要它：Rhino 的 {@code __automator__.js} 里 {@code auto()/auto.waitFor()} 直接调
 * {@code runtime.accessibilityBridge.ensureServiceEnabled()/waitForServiceEnabled()}；:script 进程
 * 没有 AccessibilityService，原实现会误报「无障碍服务已启用但未运行」并拉起设置页。包装后：
 * <ul>
 *   <li>{@link #ensureServiceEnabled()} / {@link #waitForServiceEnabled()} 走远程
 *       {@link RemoteAutomationBridge#ensureAccessibility()}（中文错误与主进程版同义）；</li>
 *   <li>{@link #getService()} 返回 null（{@code auto.service} 等需要本地服务的 API 批一不支持）；</li>
 *   <li>{@link #getInfoProvider()} / {@link #getNotificationObserver()} 仍委托给原桥（:script 里
 *       这两个对象不依赖 AccessibilityService 连接，currentPackage 等可继续用）。</li>
 * </ul>
 *
 * <p>由 {@code ScriptRuntime} 在隔离脚本进程里自动包在原始 bridge 外面。
 */
public final class RemoteAccessibilityBridge extends AccessibilityBridge {

    private static final String TAG = "RemoteAccessibilityBridge";
    private static final long WAIT_TIMEOUT_MILLIS = 10000L;
    private static final long WAIT_INTERVAL_MILLIS = 100L;

    private final AccessibilityBridge mDelegate;

    public RemoteAccessibilityBridge(AccessibilityBridge delegate) {
        super(GlobalAppContext.get(), delegate.getConfig(), new UiHandler(GlobalAppContext.get()));
        mDelegate = delegate;
    }

    @Override
    public void ensureServiceEnabled() {
        if (RemoteAutomationBridge.isActive()) {
            // 与原版 auto() 语义一致：无障碍不可用时抛中文异常（不会拉起设置页）。
            RemoteAutomationBridge.ensureAccessibility();
            return;
        }
        mDelegate.ensureServiceEnabled();
    }

    @Override
    public void waitForServiceEnabled() {
        if (!RemoteAutomationBridge.isActive()) {
            mDelegate.waitForServiceEnabled();
            return;
        }
        long deadline = SystemClock.elapsedRealtime() + WAIT_TIMEOUT_MILLIS;
        while (true) {
            if (RemoteAutomationBridge.isAccessibilityReady()) {
                return;
            }
            if (SystemClock.elapsedRealtime() > deadline) {
                // 超时：抛与 auto() 一致的中文错误（未开启/已开启但未运行两种情况区分）。
                RemoteAutomationBridge.ensureAccessibility();
                return;
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new ScriptInterruptedException();
            }
            try {
                Thread.sleep(WAIT_INTERVAL_MILLIS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new ScriptInterruptedException();
            }
        }
    }

    @Nullable
    @Override
    public AccessibilityService getService() {
        if (RemoteAutomationBridge.isActive()) {
            // 远程模式没有本地 AccessibilityService；需要服务对象的 API（auto.service/auto.windows）
            // 批一不支持，返回 null 让脚本侧自然降级（脚本可判断 null）。
            Log.d(TAG, "getService() in remote mode returns null");
            return null;
        }
        return mDelegate.getService();
    }

    @Override
    public ActivityInfoProvider getInfoProvider() {
        return mDelegate.getInfoProvider();
    }

    @Override
    public AccessibilityNotificationObserver getNotificationObserver() {
        return mDelegate.getNotificationObserver();
    }

    /** 当前进程是否处于远程（:script）代理模式。 */
    public boolean isRemote() {
        return RemoteAutomationBridge.isActive();
    }

    /** :script 侧截图：走主进程 AutomationHost 截图并写 PNG，返回实际路径（失败空串）。 */
    public String screenshotTo(String targetPath) {
        if (!RemoteAutomationBridge.isActive()) {
            return "";
        }
        try {
            return RemoteAutomationBridge.screenshot(targetPath);
        } catch (Throwable error) {
            Log.w(TAG, "screenshotTo failed", error);
            return "";
        }
    }

    @Override
    public void clearCache() {
        // 远程模式没有本地无障碍缓存可清（主进程每次查询自己处理缓存）。
        if (!RemoteAutomationBridge.isActive()) {
            mDelegate.clearCache();
        }
    }
}
