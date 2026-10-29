package com.stardust.enhancedfloaty;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.Nullable;
import android.view.WindowManager;

import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Created by Stardust on 2017/5/1.
 */

public class FloatyService extends Service {

    public interface ComponentResolver { Class<? extends FloatyService> component(Context context); }
    private static volatile ComponentResolver componentResolver;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public static void setComponentResolver(ComponentResolver resolver) { componentResolver = resolver; }

    public static Intent getServiceIntent(Context context) {
        FloatyService active = instance;
        if (active != null) return new Intent(context, active.getClass());
        ComponentResolver resolver = componentResolver;
        return new Intent(context, resolver == null ? FloatyService.class : resolver.component(context));
    }

    private static CopyOnWriteArraySet<FloatyWindow> windows = new CopyOnWriteArraySet<>();

    public static void addWindow(FloatyWindow window) {
        if (windows.add(window) && instance != null) {
            FloatyService owner = instance;
            Runnable create = () -> {
                if (instance == owner && windows.contains(window) && !window.isCreated()) owner.createWindow(window);
            };
            if (Looper.myLooper() == Looper.getMainLooper()) create.run();
            else MAIN.post(create);
        }
    }

    public static void removeWindow(FloatyWindow window) {
        windows.remove(window);
    }

    /** 服务是否已创建。主线程创建悬浮窗时用它判断能否借服务同步完成创建。 */
    public static boolean isCreated() {
        return instance != null;
    }

    /** 登记一个已经在别处创建好的窗口（不触发创建），仅供生命周期管理。 */
    public static void registerWindow(FloatyWindow window) {
        windows.add(window);
    }

    private static volatile FloatyService instance;
    private WindowManager mWindowManager;

    @Override
    public void onCreate() {
        super.onCreate();
        mWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        instance = this;
        for (FloatyWindow delegate : windows) {
            if (!delegate.isCreated()) {
                createWindow(delegate);
            }
        }
    }

    private void createWindow(FloatyWindow window) {
        try { window.onCreate(this, mWindowManager); }
        catch (RuntimeException error) {
            windows.remove(window);
            android.util.Log.e("FloatyService", "Window creation failed", error);
            try { window.onServiceDestroy(this); }
            catch (RuntimeException cleanup) { android.util.Log.w("FloatyService", "Window cleanup failed", cleanup); }
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
        java.util.ArrayList<FloatyWindow> closing = new java.util.ArrayList<>(windows);
        windows.clear();
        for (FloatyWindow delegate : closing) {
            try { delegate.onServiceDestroy(this); }
            catch (RuntimeException error) { android.util.Log.w("FloatyService", "Window cleanup failed", error); }
        }
    }
}
