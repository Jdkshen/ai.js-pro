package com.github.aakira.expandablelayout;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.animation.DecelerateInterpolator;
import android.widget.RelativeLayout;

import com.jdkshen.aijspro.R;

/**
 * Simplified ExpandableRelativeLayout - replaces the original library.
 * Provides expand/collapse with smooth height animation.
 */
public class ExpandableRelativeLayout extends RelativeLayout {

    private boolean mExpanded = false;
    private int mCollapsedHeight = 0;
    private int mExpandedHeight = 0;

    public ExpandableRelativeLayout(Context context) {
        this(context, null);
    }

    public ExpandableRelativeLayout(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ExpandableRelativeLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context, attrs);
    }

    private void init(Context context, AttributeSet attrs) {
        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.ExpandableRelativeLayout);
            mExpanded = a.getBoolean(R.styleable.ExpandableRelativeLayout_ael_expanded, false);
            a.recycle();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.measureChildren(widthMeasureSpec, heightMeasureSpec);
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    public void expand() {
        if (mExpanded) return;
        mExpanded = true;
        measure(MeasureSpec.makeMeasureSpec(getWidth(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        mExpandedHeight = getMeasuredHeight();
        animateHeight(0, mExpandedHeight);
    }

    public void collapse() {
        if (!mExpanded) return;
        mExpanded = false;
        animateHeight(getHeight(), 0);
    }

    public boolean isExpanded() {
        return mExpanded;
    }

    private void animateHeight(int from, int to) {
        ValueAnimator animator = ValueAnimator.ofInt(from, to);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.setDuration(300);
        animator.addUpdateListener(animation -> {
            int value = (int) animation.getAnimatedValue();
            getLayoutParams().height = value;
            requestLayout();
        });
        animator.start();
    }
}
