package com.jdkshen.aijspro.pluginclient;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

/**
 * 调试服务器开关的自动恢复：应用回到前台时，如果上次退出前开关是开着的，
 * 就把 {@link DevPluginServerService} 重新拉起来。
 *
 * <p>为什么不用 Application.onCreate 直接启动：Android 12+ 对后台启动前台服务有限制，
 * 在「用户真的把界面点开」这一刻启动最稳；同时应用被杀后由别的组件把进程拉起来的场景
 * （如 USB 自动发现的 provider 被扩展查询）不会顺带弹出一条「调试服务器运行中」通知。
 * 与 MCP 的 {@code McpAutoLifecycle} 同一套做法。
 */
public final class DevPluginAutoStart implements Application.ActivityLifecycleCallbacks {

    private final Application mApp;
    private int mStarted;

    public DevPluginAutoStart(Application app) {
        mApp = app;
    }

    @Override
    public void onActivityStarted(Activity activity) {
        mStarted++;
        if (mStarted != 1) {
            return;
        }
        // 手动关掉开关时 DevPluginPrefs 已置 false，这里不会被重新拉起；
        // 服务其实还活着（如刚启动完成）时也不重复 start。
        if ((DevPluginPrefs.isServerEnabled(mApp) || !DevPluginServer.getInstance().getReconnectHost().isEmpty())
                && !DevPluginServer.getInstance().isRunning()) {
            DevPluginServerService.start(mApp);
        }
    }

    @Override
    public void onActivityStopped(Activity activity) {
        mStarted = Math.max(0, mStarted - 1);
    }

    @Override
    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
    }

    @Override
    public void onActivityResumed(Activity activity) {
    }

    @Override
    public void onActivityPaused(Activity activity) {
    }

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
    }

    @Override
    public void onActivityDestroyed(Activity activity) {
    }
}
