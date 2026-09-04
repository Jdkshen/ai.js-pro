package com.jdkshen.aijspro.ui.floating;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.PointF;
import androidx.annotation.AttrRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.widget.FrameLayout;

import com.jdkshen.aijspro.R;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Created by Stardust on 2017/9/25.
 */

public class CircularActionMenu extends FrameLayout {

    public interface OnStateChangeListener {
        void onExpanding(CircularActionMenu menu);

        void onExpanded(CircularActionMenu menu);

        void onCollapsing(CircularActionMenu menu);

        void onCollapsed(CircularActionMenu menu);

        void onMeasured(CircularActionMenu menu);
    }

    public static class OnStateChangeListenerAdapter implements OnStateChangeListener {

        @Override
        public void onExpanding(CircularActionMenu menu) {

        }

        @Override
        public void onExpanded(CircularActionMenu menu) {

        }

        @Override
        public void onCollapsing(CircularActionMenu menu) {

        }

        @Override
        public void onCollapsed(CircularActionMenu menu) {

        }

        @Override
        public void onMeasured(CircularActionMenu menu) {

        }
    }

    private PointF[] mItemExpandedPositionOffsets;
    private CopyOnWriteArrayList<OnStateChangeListener> mOnStateChangeListeners = new CopyOnWriteArrayList<>();
    private boolean mExpanded;
    private boolean mExpanding = false;
    private boolean mCollapsing = false;
    private float mRadius = 200;
    private float mAngle = (float) Math.toRadians(90);
    private long mDuration = 180;
    private int mExpandedHeight = -1;
    private int mExpandedWidth = -1;
    private final Interpolator mInterpolator = new DecelerateInterpolator(1.5f);
    private int mExpansionDirection = 1;
    private ValueAnimator mMenuAnimator;
    private float mExpansionProgress;
    private boolean mDrawingAnimation;


    public CircularActionMenu(@NonNull Context context) {
        super(context);
        init(null);
    }

    public CircularActionMenu(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(attrs);
    }

    public CircularActionMenu(@NonNull Context context, @Nullable AttributeSet attrs, @AttrRes int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(attrs);
    }

    private void init(AttributeSet attrs) {
        if (attrs == null)
            return;
        TypedArray a = getContext().obtainStyledAttributes(attrs, R.styleable.CircularActionMenu);
        mRadius = a.getDimensionPixelSize(R.styleable.CircularActionMenu_cam_radius, (int) mRadius);
        int angleInDegree = a.getInt(R.styleable.CircularActionMenu_cam_angle, 0);
        if (angleInDegree != 0) {
            mAngle = (float) Math.toRadians(angleInDegree);
        }
        for (int i = 0; i < getItemCount(); i++) {
            View v = getItemAt(i);
            LayoutParams params = (LayoutParams) v.getLayoutParams();
            params.gravity = Gravity.START | Gravity.LEFT | Gravity.CENTER_VERTICAL;
            // FIXME: 2017/10/17 Not working
            updateViewLayout(v, params);
        }
        requestLayout();
    }

    public float getRadius() {
        return mRadius;
    }

    public void setRadius(float radius) {
        mRadius = radius;
    }

    public float getAngle() {
        return mAngle;
    }

    public void setAngle(float angle) {
        mAngle = angle;
    }

    public void expand(int direction) {
        if (mExpanding || (mExpanded && !mCollapsing)) {
            return;
        }
        float startProgress = mCollapsing ? getExpansionProgress() : 0f;
        mExpansionDirection = direction == Gravity.RIGHT ? 1 : -1;
        cancelItemAnimations();
        mExpanding = true;
        mCollapsing = false;
        mExpanded = false;
        prepareDrawingAnimation(startProgress);
        setVisibility(VISIBLE);
        setItemsEnabled(false);
        for (OnStateChangeListener l : mOnStateChangeListeners) {
            l.onExpanding(CircularActionMenu.this);
        }
        animateTo(1f, startProgress);
    }

    private void animateTo(float targetProgress, float startProgress) {
        if (Math.abs(targetProgress - startProgress) < 0.001f) {
            finishAnimation(targetProgress);
            return;
        }
        long duration = Math.max(1L,
                (long) (mDuration * Math.abs(targetProgress - startProgress)));
        mMenuAnimator = ValueAnimator.ofFloat(startProgress, targetProgress);
        mMenuAnimator.setDuration(duration);
        mMenuAnimator.setInterpolator(mInterpolator);
        mMenuAnimator.addUpdateListener(animation -> {
            mExpansionProgress = (float) animation.getAnimatedValue();
            postInvalidateOnAnimation();
        });
        mMenuAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (animation == mMenuAnimator) {
                    mMenuAnimator = null;
                    finishAnimation(targetProgress);
                }
            }
        });
        mMenuAnimator.start();
    }

    private void finishAnimation(float targetProgress) {
        mExpansionProgress = targetProgress;
        mDrawingAnimation = false;
        if (targetProgress >= 1f) {
            applyExpandedItemTransforms();
            mExpanding = false;
            mCollapsing = false;
            mExpanded = true;
            setItemsEnabled(true);
            for (OnStateChangeListener listener : mOnStateChangeListeners) {
                listener.onExpanded(this);
            }
        } else {
            resetItemTransforms();
            mExpanding = false;
            mCollapsing = false;
            mExpanded = false;
            setItemsEnabled(false);
            setVisibility(GONE);
            for (OnStateChangeListener listener : mOnStateChangeListeners) {
                listener.onCollapsed(this);
            }
        }
    }

    private void applyExpandedItemTransforms() {
        if (mItemExpandedPositionOffsets == null
                || mItemExpandedPositionOffsets.length != getItemCount()) {
            return;
        }
        for (int i = 0; i < getItemCount(); i++) {
            View item = getItemAt(i);
            PointF offset = mItemExpandedPositionOffsets[i];
            item.setTranslationX(mExpansionDirection * offset.x);
            item.setTranslationY(offset.y);
            item.setScaleX(1f);
            item.setScaleY(1f);
        }
    }

    private void resetItemTransforms() {
        for (int i = 0; i < getItemCount(); i++) {
            View item = getItemAt(i);
            item.setTranslationX(0f);
            item.setTranslationY(0f);
            item.setScaleX(1f);
            item.setScaleY(1f);
        }
    }

    private void prepareDrawingAnimation(float progress) {
        mExpansionProgress = Math.max(0f, Math.min(1f, progress));
        mDrawingAnimation = true;
        resetItemTransforms();
        postInvalidateOnAnimation();
    }

    private float getExpansionProgress() {
        return mDrawingAnimation ? mExpansionProgress : (mExpanded ? 1f : 0f);
    }

    private void setItemsEnabled(boolean enabled) {
        for (int i = 0; i < getItemCount(); i++) {
            getItemAt(i).setEnabled(enabled);
        }
    }

    private void cancelItemAnimations() {
        if (mMenuAnimator != null) {
            mMenuAnimator.removeAllListeners();
            mMenuAnimator.removeAllUpdateListeners();
            mMenuAnimator.cancel();
            mMenuAnimator = null;
        }
    }

    public View getItemAt(int i) {
        return getChildAt(i);
    }

    public void collapse() {
        if (mCollapsing || (!mExpanded && !mExpanding)) {
            return;
        }
        float startProgress = getExpansionProgress();
        cancelItemAnimations();
        prepareDrawingAnimation(startProgress);
        mCollapsing = true;
        mExpanding = false;
        mExpanded = false;
        setItemsEnabled(false);
        for (OnStateChangeListener l : mOnStateChangeListeners) {
            l.onCollapsing(CircularActionMenu.this);
        }
        animateTo(0f, startProgress);
    }

    public void addOnStateChangeListener(OnStateChangeListener onStateChangeListener) {
        mOnStateChangeListeners.add(onStateChangeListener);
    }

    public boolean removeOnStateChangeListener(OnStateChangeListener listener) {
        return mOnStateChangeListeners.remove(listener);
    }

    public boolean isExpanded() {
        return mExpanded || mExpanding;
    }

    public boolean isExpanding() {
        return mExpanding;
    }

    public boolean isCollapsing() {
        return mCollapsing;
    }


    public int getItemCount() {
        return getChildCount();
    }

    private void calcExpandedPositions() {
        mItemExpandedPositionOffsets = new PointF[getItemCount()];
        double averageAngle = mAngle / (getItemCount() - 1);
        for (int i = 0; i < getItemCount(); i++) {
            double angle = -mAngle / 2 + i * averageAngle;
            mItemExpandedPositionOffsets[i] = new PointF((float) (mRadius * Math.cos(angle)),
                    (float) (mRadius * Math.sin(angle)));
        }
    }

    private void calcExpandedSize() {
        int maxX = 0;
        int maxY = 0;
        int minY = Integer.MAX_VALUE;
        int maxWidth = 0;
        for (int i = 0; i < getItemCount(); i++) {
            View item = getItemAt(i);
            maxWidth = Math.max(item.getMeasuredWidth(), maxWidth);
            maxX = Math.max((int) (mItemExpandedPositionOffsets[i].x + item.getMeasuredWidth()), maxX);
            // FIXME: 2017/9/26 这样算出来的高度略大
            maxY = Math.max((int) (mItemExpandedPositionOffsets[i].y + item.getMeasuredHeight()), maxY);
            minY = Math.min((int) (mItemExpandedPositionOffsets[i].y - item.getMeasuredHeight()), minY);
        }
        mExpandedWidth = maxX;
        mExpandedHeight = maxY - minY;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        measureChildren(widthMeasureSpec, heightMeasureSpec);
        calcExpandedPositions();
        if (mExpandedHeight == -1 || mExpandedWidth == -1) {
            calcExpandedSize();
        }
        setMeasuredDimension(2 * mExpandedWidth, mExpandedHeight);
        if (mDrawingAnimation) {
            resetItemTransforms();
        } else if (mExpanded) {
            applyExpandedItemTransforms();
        } else {
            resetItemTransforms();
        }
        for (OnStateChangeListener listener : mOnStateChangeListeners) {
            listener.onMeasured(this);
        }
    }

    @Override
    protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
        if (!mDrawingAnimation || mItemExpandedPositionOffsets == null) {
            return super.drawChild(canvas, child, drawingTime);
        }
        int index = indexOfChild(child);
        if (index < 0 || index >= mItemExpandedPositionOffsets.length) {
            return super.drawChild(canvas, child, drawingTime);
        }
        PointF offset = mItemExpandedPositionOffsets[index];
        int saveCount = canvas.save();
        canvas.translate(mExpansionDirection * offset.x * mExpansionProgress,
                offset.y * mExpansionProgress);
        float pivotX = child.getLeft() + child.getWidth() / 2f;
        float pivotY = child.getTop() + child.getHeight() / 2f;
        canvas.scale(mExpansionProgress, mExpansionProgress, pivotX, pivotY);
        boolean drawn = super.drawChild(canvas, child, drawingTime);
        canvas.restoreToCount(saveCount);
        return drawn;
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelItemAnimations();
        mDrawingAnimation = false;
        super.onDetachedFromWindow();
    }

    @Override
    protected void measureChildren(int widthMeasureSpec, int heightMeasureSpec) {
        for (int i = 0; i < getChildCount(); ++i) {
            final View child = getChildAt(i);
            measureChild(child, widthMeasureSpec, heightMeasureSpec);
        }
    }

    public int getExpandedHeight() {
        return mExpandedHeight;
    }

    public int getExpandedWidth() {
        return mExpandedWidth;
    }

}
