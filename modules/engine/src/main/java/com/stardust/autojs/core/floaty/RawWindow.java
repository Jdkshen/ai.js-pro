package com.stardust.autojs.core.floaty;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import com.stardust.autojs.R;
import com.stardust.autojs.core.ui.inflater.inflaters.Exceptions;
import com.stardust.autojs.runtime.exception.ScriptInterruptedException;
import com.stardust.concurrent.VolatileBox;
import com.stardust.concurrent.VolatileDispose;
import com.stardust.enhancedfloaty.FloatyService;
import com.stardust.enhancedfloaty.FloatyWindow;
import com.stardust.enhancedfloaty.WindowBridge;
import com.stardust.enhancedfloaty.util.WindowTypeCompat;

public class RawWindow extends FloatyWindow {



    public interface RawFloaty {

        View inflateWindowView(Context context, ViewGroup parent);
    }

    private VolatileDispose<RuntimeException> mInflateException = new VolatileDispose<>();
    private RawFloaty mRawFloaty;
    private View mContentView;

    public RawWindow(RawFloaty rawFloaty) {
        mRawFloaty = rawFloaty;
    }

    @Override
    public void onCreate(Context context, WindowManager windowManager) {
        try {
            super.onCreate(context, windowManager);
        } catch (RuntimeException e) {
            mInflateException.setAndNotify(e);
            return;
        }
        mInflateException.setAndNotify(Exceptions.NO_EXCEPTION);
    }

    @Override
    protected View onCreateView(Context context) {
        ViewGroup windowView = (ViewGroup) View.inflate(context, R.layout.raw_window, null);
        mContentView = mRawFloaty.inflateWindowView(context, windowView);
        // ImGui 类 native 项目：把窗口里的原生 SurfaceView 换成「回调投递到脚本线程」的版本，
        // 让 nativeInit/帧循环/shutdown 在同一线程串行（修 EGL_BAD_ACCESS 与 DestroyContext 竞态）。
        // 窗口创建发生在 main 线程，但脚本线程此时还阻塞在 waitForCreation()，尚未拿到 view 引用，
        // 所以替换后脚本按 id 找到的就是新实例。
        FloatySurfaceThreading.rebindSurfaceViews(windowView);
        return windowView;
    }

    public RuntimeException waitForCreation() {
        return mInflateException.blockedGetOrThrow(ScriptInterruptedException.class);
    }

    public View getContentView() {
        return mContentView;
    }

    @Override
    protected WindowManager.LayoutParams onCreateWindowLayoutParams() {
        int flags =
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_FULLSCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            flags |= WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS;
        }
        WindowManager.LayoutParams layoutParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowTypeCompat.getWindowType(),
                flags,
                PixelFormat.TRANSLUCENT);
        layoutParams.gravity = Gravity.TOP | Gravity.START;
        return layoutParams;
    }

    @Override
    public void close() {
        // C 侧 STOP 顺序：closeAll（本方法）→ stopExecution → engine.destroy。这里同步释放
        // Surface 回调投递（早于 removeView 的异步 detach），确保 destroy 后队列里不再执行脚本回调。
        FloatySurfaceThreading.releaseSurfaceViews(getWindowView());
        FloatySurfaceThreading.releaseSurfaceViews(getContentView());
        super.close();
    }

    public void disableWindowFocus() {
        WindowManager.LayoutParams windowLayoutParams = getWindowLayoutParams();
        windowLayoutParams.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        updateWindowLayoutParams(windowLayoutParams);
    }

    public void requestWindowFocus() {
        WindowManager.LayoutParams windowLayoutParams = getWindowLayoutParams();
        windowLayoutParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        updateWindowLayoutParams(windowLayoutParams);
        getWindowView().requestLayout();
    }

    public void setTouchable(boolean touchable) {
        WindowManager.LayoutParams windowLayoutParams = getWindowLayoutParams();
        if (touchable) {
            windowLayoutParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        } else {
            windowLayoutParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }
        updateWindowLayoutParams(windowLayoutParams);
    }

}
