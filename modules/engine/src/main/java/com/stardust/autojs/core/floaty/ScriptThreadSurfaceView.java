package com.stardust.autojs.core.floaty;

import android.content.Context;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;

/**
 * 回调回到脚本线程的 {@link SurfaceView}（floaty/headless 路径专用）。
 *
 * <p>只替换「{@code android.view.SurfaceView} 原类」实例（见 {@link FloatySurfaceThreading}）；
 * GLSurfaceView 等自定义子类不动。脚本通过 {@code getHolder()} 拿到的就是
 * {@link PostingSurfaceHolder}：Surface 回调与触摸事件都会投递到脚本线程执行，从而让
 * nativeInit（CreateContext/eglMakeCurrent）、帧循环（setTimeout）、nativeShutdown 串行在同一线程。
 */
final class ScriptThreadSurfaceView extends SurfaceView {

    private final PostingSurfaceHolder mPostingHolder;

    ScriptThreadSurfaceView(Context context) {
        super(context);
        mPostingHolder = new PostingSurfaceHolder(super.getHolder());
    }

    @Override
    public SurfaceHolder getHolder() {
        // super 构造期间若框架回调 getHolder()（正常不会），退回真实 holder，避免 NPE。
        return mPostingHolder != null ? mPostingHolder : super.getHolder();
    }

    @Override
    public void setOnTouchListener(View.OnTouchListener l) {
        super.setOnTouchListener(mPostingHolder.wrapTouchListener(l));
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        // 窗口关闭/视图 detach：停止往脚本线程投递（脚本结束后的 Handler 队列不应再有回调）。
        mPostingHolder.release();
    }

    /**
     * 窗口 close() 时**同步**释放（早于 removeView/detach 的异步遍历）：
     * 这样引擎 destroy 前，已经 post 到脚本线程队列里的回调会因 release 标记被丢弃，
     * 不会在 runtime 销毁后再执行脚本回调（C 侧 STOP 顺序：closeAll → stopExecution）。
     */
    void releaseHolder() {
        mPostingHolder.release();
    }
}
