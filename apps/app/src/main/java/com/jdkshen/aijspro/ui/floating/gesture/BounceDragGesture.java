package com.jdkshen.aijspro.ui.floating.gesture;

import android.animation.ValueAnimator;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.BounceInterpolator;

import com.stardust.enhancedfloaty.WindowBridge;

/**
 * Created by Stardust on 2017/9/26.
 */

public class BounceDragGesture extends DragGesture {

    private long mBounceDuration = 280;
    private static final int MIN_DY_TO_SCREEN_BOTTOM = 100;
    private static final int MIN_DY_TO_SCREEN_TOP = 0;
    private BounceInterpolator mBounceInterpolator;
    private ValueAnimator mBounceAnimator;

    public BounceDragGesture(WindowBridge windowBridge, View view) {
        super(windowBridge, view);
        setAutoKeepToEdge(true);
        mBounceInterpolator = new BounceInterpolator();
    }

    public void setBounceDuration(long bounceDuration) {
        mBounceDuration = bounceDuration;
    }

    @Override
    public boolean onDown(MotionEvent event) {
        cancelBounceAnimator();
        return super.onDown(event);
    }

    @Override
    public void keepToEdge() {
        int fromY = mWindowBridge.getY();
        int maxY = Math.max(MIN_DY_TO_SCREEN_TOP,
                mWindowBridge.getScreenHeight() - mView.getHeight() - MIN_DY_TO_SCREEN_BOTTOM);
        int toY = Math.min(maxY, Math.max(MIN_DY_TO_SCREEN_TOP, fromY));
        int x = mWindowBridge.getX();
        int hiddenWidth = (int) (getKeepToSideHiddenWidthRadio() * (float) mView.getWidth());
        if (x > mWindowBridge.getScreenWidth() / 2) {
            bounce(x, mWindowBridge.getScreenWidth() - mView.getWidth() + hiddenWidth,
                    fromY, toY);
        } else {
            bounce(x, -hiddenWidth, fromY, toY);
        }
    }

    protected void bounce(final int fromX, final int toX, final int fromY, final int toY) {
        cancelBounceAnimator();
        mBounceAnimator = ValueAnimator.ofFloat(0f, 1f);
        mBounceAnimator.addUpdateListener(animation -> {
            float fraction = (float) animation.getAnimatedValue();
            int x = Math.round(fromX + (toX - fromX) * fraction);
            int y = Math.round(fromY + (toY - fromY) * fraction);
            mWindowBridge.updatePosition(x, y);
        });
        mBounceAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (animation == mBounceAnimator) {
                    mBounceAnimator = null;
                }
            }
        });
        mBounceAnimator.setDuration(mBounceDuration);
        mBounceAnimator.setInterpolator(mBounceInterpolator);
        mBounceAnimator.start();
    }

    private void cancelBounceAnimator() {
        if (mBounceAnimator == null) {
            return;
        }
        mBounceAnimator.removeAllListeners();
        mBounceAnimator.cancel();
        mBounceAnimator = null;
    }

    @Override
    public void cancel() {
        cancelBounceAnimator();
        super.cancel();
    }
}
