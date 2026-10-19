package com.stardust.autojs.runtime.api;

import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import com.stardust.autojs.R;
import com.stardust.autojs.annotation.ScriptInterface;
import com.stardust.autojs.core.floaty.BaseResizableFloatyWindow;
import com.stardust.autojs.core.floaty.RawWindow;
import com.stardust.autojs.core.graphics.ScriptCanvasView;
import com.stardust.autojs.core.ui.JsViewHelper;
import com.stardust.autojs.core.ui.inflater.DynamicLayoutInflater;
import com.stardust.autojs.core.ui.inflater.inflaters.Exceptions;
import com.stardust.autojs.runtime.ScriptRuntime;
import com.stardust.autojs.runtime.exception.ScriptInterruptedException;
import com.stardust.autojs.util.FloatingPermission;
import com.stardust.enhancedfloaty.FloatyService;
import com.stardust.enhancedfloaty.FloatyWindow;
import com.stardust.util.UiHandler;
import com.stardust.util.ViewUtil;

import java.util.concurrent.CopyOnWriteArraySet;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;

/**
 * Created by Stardust on 2017/12/5.
 */

public class Floaty {

    private DynamicLayoutInflater mLayoutInflater;
    private Context mContext;
    private UiHandler mUiHandler;
    private CopyOnWriteArraySet<JsWindow> mWindows = new CopyOnWriteArraySet<>();
    private ScriptRuntime mRuntime;

    public Floaty(UiHandler uiHandler, UI ui, ScriptRuntime runtime) {
        mUiHandler = uiHandler;
        mRuntime = runtime;
        mContext = new ContextThemeWrapper(mUiHandler.getContext(), R.style.ScriptTheme);
        mLayoutInflater = ui.getLayoutInflater();
    }

    /** PDF §2.14：`floaty.checkPermission()` —— 当前应用是否有悬浮窗权限（不触发请求）。 */
    @ScriptInterface
    public boolean checkPermission() {
        return FloatingPermission.canDrawOverlays(mUiHandler.getContext());
    }

    /** PDF §2.14：`floaty.requestPermission()` —— 跳到系统悬浮窗权限界面（不阻塞、不等待）。 */
    @ScriptInterface
    public void requestPermission() {
        FloatingPermission.manageDrawOverlays(mUiHandler.getContext());
    }

    @ScriptInterface
    public JsResizableWindow window(BaseResizableFloatyWindow.ViewSupplier supplier) {
        try {
            FloatingPermission.waitForPermissionGranted(mContext);
        } catch (InterruptedException e) {
            throw new ScriptInterruptedException();
        }
        JsResizableWindow window = new JsResizableWindow(supplier);
        addWindow(window);
        return window;
    }

    public JsResizableWindow window(View view) {
        try {
            FloatingPermission.waitForPermissionGranted(view.getContext());
        } catch (InterruptedException e) {
            throw new ScriptInterruptedException();
        }

        JsResizableWindow window = new JsResizableWindow((context, parent) -> view);
        addWindow(window);
        return window;
    }

    public JsRawWindow rawWindow(RawWindow.RawFloaty floaty) {
        try {
            FloatingPermission.waitForPermissionGranted(mContext);
        } catch (InterruptedException e) {
            throw new ScriptInterruptedException();
        }
        JsRawWindow window = new JsRawWindow(floaty);
        addWindow(window);
        return window;
    }

    public JsRawWindow rawWindow(View view) {
        try {
            FloatingPermission.waitForPermissionGranted(mContext);
        } catch (InterruptedException e) {
            throw new ScriptInterruptedException();
        }
        JsRawWindow window = new JsRawWindow((context, parent) -> view);
        addWindow(window);
        return window;
    }

    /** Pro's direct runtime API supplies a scope and a JS view factory. */
    public JsRawWindow rawWindow(Scriptable scope, Function factory) {
        return rawWindow((context, parent) -> suppliedView(scope, factory, context, parent));
    }

    public JsResizableWindow window(Scriptable scope, Function factory) {
        return window((context, parent) -> suppliedView(scope, factory, context, parent));
    }

    private View suppliedView(Scriptable scope, Function factory, Context context, ViewGroup parent) {
        Object value = mRuntime.bridges.callFunction(factory, scope, new Object[]{context, parent});
        return (View) org.mozilla.javascript.Context.jsToJava(value, View.class);
    }

    private static void closeCanvases(View view) {
        if (view instanceof ScriptCanvasView) ((ScriptCanvasView) view).closeCanvas();
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) closeCanvases(group.getChildAt(i));
        }
    }

    private synchronized void addWindow(JsWindow window) {
        mWindows.add(window);
    }

    private synchronized boolean removeWindow(JsWindow window) {
        return mWindows.remove(window);
    }

    /**
     * 让 {@link FloatyService} 创建窗口。
     *
     * <p>经典 {@code "ui";} 模式脚本运行在主线程上，而窗口创建依赖主线程空闲：
     * 先 post 再阻塞等待会自锁（脚本永久卡在 {@code floaty.rawWindow}）。所以主线程上
     * 就地处理：服务已创建 → 直接 addWindow（创建同步完成）；服务尚未创建 → 用宿主
     * Context 就地创建，并把窗口登记给服务做生命周期管理（服务启动时会跳过已创建窗口）。
     */
    private void requestWindowCreation(FloatyWindow window) {
        Context context = mUiHandler.getContext();
        if (Looper.myLooper() == Looper.getMainLooper()) {
            startFloatyService(context);
            if (FloatyService.isCreated()) {
                FloatyService.addWindow(window);
            } else {
                window.onCreate(context, (WindowManager) context.getSystemService(Context.WINDOW_SERVICE));
                FloatyService.registerWindow(window);
            }
            return;
        }
        mUiHandler.post(() -> {
            startFloatyService(context);
            if (FloatyService.isCreated()) {
                FloatyService.addWindow(window);
            } else {
                // 隔离脚本进程（android:process=":native"）里 FloatyService 还没创建时，
                // 直接在主 looper 上就地建窗，避免脚本线程永久阻塞在 waitForCreation()。
                window.onCreate(context, (WindowManager) context.getSystemService(Context.WINDOW_SERVICE));
                FloatyService.registerWindow(window);
            }
        });
    }

    private static void startFloatyService(Context context) {
        try {
            context.startService(new Intent(context, FloatyService.class));
        } catch (Throwable ignored) {
            // Android 8+ 后台启动服务可能被拒；窗口创建本身不依赖这里成功。
        }
    }

    public synchronized void closeAll() {
        for (JsWindow window : mWindows) {
            window.close(false);
        }
        mWindows.clear();
    }

    public interface JsWindow {
        void close(boolean removeFromWindows);
    }

    public class JsRawWindow implements JsWindow {

        private RawWindow mWindow;
        private boolean mExitOnClose;

        public JsRawWindow(RawWindow.RawFloaty floaty) {
            mWindow = new RawWindow(floaty);
            requestWindowCreation(mWindow);
            RuntimeException exception = mWindow.waitForCreation();
            if (exception != Exceptions.NO_EXCEPTION && exception != null) {
                throw exception;
            }
        }

        public View findView(String id) {
            return JsViewHelper.findViewByStringId(mWindow.getContentView(), id);
        }

        public int getX() {
            return mWindow.getWindowBridge().getX();
        }

        public int getY() {
            return mWindow.getWindowBridge().getY();
        }

        public int getWidth() {
            return mWindow.getWindowView().getWidth();
        }

        public int getHeight() {
            return mWindow.getWindowView().getHeight();
        }

        public void setSize(int w, int h) {
            runWithWindow(() -> {
                        mWindow.getWindowBridge().updateMeasure(w, h);
                        ViewUtil.setViewMeasure(mWindow.getWindowView(), w, h);
                    }
            );
        }

        public void setTouchable(boolean touchable) {
            runWithWindow(() -> mWindow.setTouchable(touchable));
        }

        private void runWithWindow(Runnable r) {
            if (mWindow == null)
                return;
            if (Looper.myLooper() == Looper.getMainLooper()) {
                r.run();
                return;
            }
            mUiHandler.post(() -> {
                if (mWindow == null)
                    return;
                r.run();
            });
        }

        public void setPosition(int x, int y) {
            runWithWindow(() -> mWindow.getWindowBridge().updatePosition(x, y));
        }

        public void exitOnClose() {
            mExitOnClose = true;
        }

        public void requestFocus() {
            mWindow.requestWindowFocus();
        }

        public void disableFocus() {
            mWindow.disableWindowFocus();
        }

        public void close() {
            close(true);
        }

        public void close(boolean removeFromWindows) {
            if (removeFromWindows && !removeWindow(this)) {
                return;
            }
            runWithWindow(() -> {
                closeCanvases(mWindow.getContentView());
                mWindow.close();
                mWindow = null;
                if (mExitOnClose) {
                    mRuntime.exit();
                }
            });
        }

    }

    public class JsResizableWindow implements JsWindow {

        private View mView;
        private volatile BaseResizableFloatyWindow mWindow;
        private boolean mExitOnClose = false;

        public JsResizableWindow(BaseResizableFloatyWindow.ViewSupplier supplier) {
            mWindow = new BaseResizableFloatyWindow(mContext, (context, parent) -> {
                mView = supplier.inflate(context, parent);
                return mView;
            });
            requestWindowCreation(mWindow);
            RuntimeException exception = mWindow.waitForCreation();
            if (exception != Exceptions.NO_EXCEPTION && exception != null) {
                throw exception;
            }
            mWindow.setOnCloseButtonClickListener(v -> close());
            //setSize(mWindow.getWindowBridge().getScreenWidth() / 2, mWindow.getWindowBridge().getScreenHeight() / 2);
        }

        public View findView(String id) {
            return JsViewHelper.findViewByStringId(mView, id);
        }

        public int getX() {
            return mWindow.getWindowBridge().getX();
        }

        public int getY() {
            return mWindow.getWindowBridge().getY();
        }

        public int getWidth() {
            return mWindow.getRootView().getWidth();
        }

        public int getHeight() {
            return mWindow.getRootView().getHeight();
        }

        public void setSize(int w, int h) {
            runWithWindow(() -> {
                        mWindow.getWindowBridge().updateMeasure(w, h);
                        ViewUtil.setViewMeasure(mWindow.getRootView(), w, h);
                    }
            );
        }


        private void runWithWindow(Runnable r) {
            if (mWindow == null)
                return;
            if (Looper.myLooper() == Looper.getMainLooper()) {
                r.run();
                return;
            }
            mUiHandler.post(() -> {
                if (mWindow == null)
                    return;
                r.run();
            });
        }

        public void setPosition(int x, int y) {
            runWithWindow(() -> mWindow.getWindowBridge().updatePosition(x, y));
        }

        public void setAdjustEnabled(boolean enabled) {
            runWithWindow(() -> mWindow.setAdjustEnabled(enabled));
        }

        public boolean isAdjustEnabled() {
            return mWindow.isAdjustEnabled();
        }

        public void exitOnClose() {
            mExitOnClose = true;
        }

        public void requestFocus() {
            mWindow.requestWindowFocus();
        }

        public void disableFocus() {
            mWindow.disableWindowFocus();
        }

        public void close() {
            close(true);
        }

        public void close(boolean removeFromWindows) {
            if (removeFromWindows && !removeWindow(this)) {
                return;
            }
            runWithWindow(() -> {
                closeCanvases(mView);
                mWindow.close();
                mWindow = null;
                if (mExitOnClose) {
                    mRuntime.exit();
                }
            });
        }
    }


}
