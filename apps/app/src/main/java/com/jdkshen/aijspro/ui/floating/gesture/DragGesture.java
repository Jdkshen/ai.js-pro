package com.jdkshen.aijspro.ui.floating.gesture;

import androidx.core.view.GestureDetectorCompat;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

import com.stardust.enhancedfloaty.WindowBridge;

/**
 * Created by Stardust on 2017/4/18.
 */

public class DragGesture extends GestureDetector.SimpleOnGestureListener {

    protected WindowBridge mWindowBridge;
    protected View mView;

    private float mKeepToSideHiddenWidthRadio = 0.5f;
    private int mInitialX;
    private int mInitialY;
    private float mInitialTouchX;
    private float mInitialTouchY;
    private View.OnClickListener mOnClickListener;
    private boolean mAutoKeepToEdge;
    private float mPressedAlpha = 1.0f;
    private float mUnpressedAlpha = 0.4f;
    private boolean mEnabled = true;
    private boolean mDragging;

    public DragGesture(WindowBridge windowBridge, View view) {
        mWindowBridge = windowBridge;
        mView = view;
        setupView();
    }

    private void setupView() {
        final GestureDetectorCompat gestureDetector = new GestureDetectorCompat(mView.getContext(), this);
        mView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                gestureDetector.onTouchEvent(event);
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    if (mDragging && mEnabled && !onTheEdge() && mAutoKeepToEdge) {
                        keepToEdge();
                    }
                    mView.setAlpha(mUnpressedAlpha);
                    mDragging = false;
                }
                return true;
            }
        });
    }

    public boolean isEnabled() {
        return mEnabled;
    }

    public void setEnabled(boolean enabled) {
        mEnabled = enabled;
    }

    protected boolean onTheEdge() {
        int dX1 = Math.abs(mWindowBridge.getX());
        int dX2 = Math.abs(mWindowBridge.getX() - mWindowBridge.getScreenWidth());
        return Math.min(dX1, dX2) < 5;
    }

    public float getPressedAlpha() {
        return mPressedAlpha;
    }

    public void setPressedAlpha(float pressedAlpha) {
        mPressedAlpha = pressedAlpha;
    }

    public float getUnpressedAlpha() {
        return mUnpressedAlpha;
    }

    public void setUnpressedAlpha(float unpressedAlpha) {
        mUnpressedAlpha = unpressedAlpha;
    }

    public void setAutoKeepToEdge(boolean autoKeepToEdge) {
        mAutoKeepToEdge = autoKeepToEdge;
    }

    public boolean isAutoKeepToEdge() {
        return mAutoKeepToEdge;
    }

    public void setKeepToSideHiddenWidthRadio(float keepToSideHiddenWidthRadio) {
        mKeepToSideHiddenWidthRadio = keepToSideHiddenWidthRadio;
    }

    public float getKeepToSideHiddenWidthRadio() {
        return mKeepToSideHiddenWidthRadio;
    }

    @Override
    public boolean onDown(MotionEvent event) {
        mInitialX = mWindowBridge.getX();
        mInitialY = mWindowBridge.getY();
        mInitialTouchX = event.getRawX();
        mInitialTouchY = event.getRawY();
        mDragging = false;
        return true;
    }

    @Override
    public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
        if (!mEnabled) {
            return false;
        }
        mDragging = true;
        mWindowBridge.updatePosition(mInitialX + (int) (e2.getRawX() - mInitialTouchX),
                mInitialY + (int) (e2.getRawY() - mInitialTouchY));
        mView.setAlpha(mPressedAlpha);
        return true;
    }

    public void cancel() {
        mDragging = false;
    }


    public void keepToEdge() {
        int x = mWindowBridge.getX();
        int hiddenWidth = (int) (mKeepToSideHiddenWidthRadio * mView.getWidth());
        if (x > mWindowBridge.getScreenWidth() / 2)
            mWindowBridge.updatePosition(mWindowBridge.getScreenWidth() - mView.getWidth() + hiddenWidth, mWindowBridge.getY());
        else
            mWindowBridge.updatePosition(-hiddenWidth, mWindowBridge.getY());
    }

    @Override
    public boolean onSingleTapUp(MotionEvent e) {
        if (mOnClickListener != null)
            mOnClickListener.onClick(mView);
        return true;
    }

    public void setOnDraggedViewClickListener(View.OnClickListener onClickListener) {
        mOnClickListener = onClickListener;
    }
}
