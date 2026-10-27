package com.jdkshen.aijspro.ui.floating.layoutinspector;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.os.Build;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import com.stardust.view.accessibility.NodeInfo;
import com.stardust.util.ViewUtil;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;

/**
 * Created by Stardust on 2017/3/10.
 */

public class LayoutBoundsView extends View {

    private static final int COLOR_SHADOW = 0x6a000000;
    private NodeInfo mTouchedNode;
    private Paint mBoundsPaint;
    private Paint mFillingPaint;
    private OnNodeInfoSelectListener mOnNodeInfoSelectListener;
    private int mTouchedNodeBoundsColor = Color.RED;
    private int mNormalNodeBoundsColor = Color.GREEN;
    private final RectF mTouchedNodeBounds = new RectF();
    private final Path mBoundsPath = new Path();
    private final IdentityHashMap<NodeInfo, InspectorHitIndex.Item<NodeInfo>> mGeometry = new IdentityHashMap<>();
    private InspectorHitIndex<NodeInfo> mHitIndex = new InspectorHitIndex<>(Collections.emptyList());
    private InspectorHitIndex.Item<NodeInfo> mTouchedGeometry;
    private boolean mTrackingTouch;

    private final int[] mBoundsInScreen = new int[2];
    private int mWindowLeft;
    protected int mStatusBarHeight;

    public LayoutBoundsView(Context context) {
        super(context);
        init();
    }

    public LayoutBoundsView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public LayoutBoundsView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    public LayoutBoundsView(Context context, @Nullable AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init();
    }


    public void setOnNodeInfoSelectListener(OnNodeInfoSelectListener onNodeInfoSelectListener) {
        mOnNodeInfoSelectListener = onNodeInfoSelectListener;
    }

    public void setRootNode(NodeInfo rootNode) {
        mTouchedNode = null;
        mTouchedGeometry = null;
        mTouchedNodeBounds.setEmpty();
        mTrackingTouch = false;
        mGeometry.clear(); mBoundsPath.rewind();
        ArrayList<InspectorHitIndex.Item<NodeInfo>> entries = new ArrayList<>();
        ArrayDeque<NodeInfo> pending = new ArrayDeque<>();
        if (rootNode != null) pending.add(rootNode);
        while (!pending.isEmpty()) {
            NodeInfo node = pending.removeFirst();
            if (mGeometry.containsKey(node)) continue;
            Rect bounds = node.getBoundsInScreen();
            InspectorHitIndex.Item<NodeInfo> item = new InspectorHitIndex.Item<>(node,
                    bounds.left, bounds.top, bounds.right, bounds.bottom);
            mGeometry.put(node, item); entries.add(item);
            if (item.isValid()) mBoundsPath.addRect(item.left, item.top, item.right, item.bottom, Path.Direction.CW);
            for (NodeInfo child : node.getChildren()) if (child != null) pending.add(child);
        }
        mHitIndex = new InspectorHitIndex<>(entries);
        invalidate();
    }


    private void init() {
        mBoundsPaint = new Paint();
        mBoundsPaint.setStyle(Paint.Style.STROKE);
        mFillingPaint = new Paint();
        mFillingPaint.setStyle(Paint.Style.FILL);
        mFillingPaint.setColor(COLOR_SHADOW);
        setWillNotDraw(false);
        mStatusBarHeight = ViewUtil.getStatusBarHeight(getContext());
    }


    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        updateWindowOrigin();
        if (mTouchedGeometry != null) {
            canvas.save();
            canvas.clipRect(mTouchedNodeBounds, Region.Op.DIFFERENCE);
        }
        canvas.drawRect(0, 0, canvas.getWidth(), canvas.getHeight(), mFillingPaint);
        if (mTouchedGeometry != null) {
            canvas.restore();
        }
        canvas.save(); canvas.translate(-mWindowLeft, -mStatusBarHeight);
        mBoundsPaint.setColor(mNormalNodeBoundsColor);
        canvas.drawPath(mBoundsPath, mBoundsPaint);
        if (mTouchedGeometry != null) {
            mBoundsPaint.setColor(mTouchedNodeBoundsColor);
            canvas.drawRect(mTouchedGeometry.left, mTouchedGeometry.top,
                    mTouchedGeometry.right, mTouchedGeometry.bottom, mBoundsPaint);
        }
        canvas.restore();
    }

    public Paint getBoundsPaint() {
        return mBoundsPaint;
    }

    public Paint getFillingPaint() {
        return mFillingPaint;
    }

    public void setTouchedNodeBoundsColor(int touchedNodeBoundsColor) {
        if (mTouchedNodeBoundsColor == touchedNodeBoundsColor) return;
        mTouchedNodeBoundsColor = touchedNodeBoundsColor;
        invalidate();
    }

    public void setNormalNodeBoundsColor(int normalNodeBoundsColor) {
        if (mNormalNodeBoundsColor == normalNodeBoundsColor) return;
        mNormalNodeBoundsColor = normalNodeBoundsColor;
        invalidate();
    }

    static void drawRect(Canvas canvas, Rect rect, int statusBarHeight, Paint paint) {
        canvas.drawRect(rect.left, (float) rect.top - statusBarHeight,
                rect.right, (float) rect.bottom - statusBarHeight, paint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_CANCEL) { mTrackingTouch = false; return true; }
        if (action == MotionEvent.ACTION_DOWN) mTrackingTouch = true;
        if (!mTrackingTouch) return true;
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP) {
            setSelectedNode(mHitIndex.find((int) event.getRawX(), (int) event.getRawY()));
        }
        if (action == MotionEvent.ACTION_UP) { mTrackingTouch = false; performClick(); }
        return true;
    }

    @Override public boolean performClick() {
        super.performClick();
        if (mTouchedNode != null && mOnNodeInfoSelectListener != null)
            mOnNodeInfoSelectListener.onNodeSelect(mTouchedNode);
        return true;
    }

    public void setSelectedNode(NodeInfo selectedNode) {
        if (mTouchedNode == selectedNode) return;
        mTouchedNode = selectedNode;
        mTouchedGeometry = mGeometry.get(selectedNode);
        if (selectedNode != null && mTouchedGeometry == null) {
            Rect bounds = selectedNode.getBoundsInScreen();
            mTouchedGeometry = new InspectorHitIndex.Item<>(selectedNode,
                    bounds.left, bounds.top, bounds.right, bounds.bottom);
        }
        updateSelectedBounds();
        invalidate();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (updateWindowOrigin()) invalidate();
    }

    @Override protected void onDetachedFromWindow() {
        mTrackingTouch = false;
        super.onDetachedFromWindow();
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (updateWindowOrigin()) invalidate();
    }

    private boolean updateWindowOrigin() {
        getLocationOnScreen(mBoundsInScreen);
        if (mWindowLeft == mBoundsInScreen[0] && mStatusBarHeight == mBoundsInScreen[1]) return false;
        mWindowLeft = mBoundsInScreen[0]; mStatusBarHeight = mBoundsInScreen[1]; updateSelectedBounds();
        return true;
    }

    private void updateSelectedBounds() {
        if (mTouchedGeometry == null) { mTouchedNodeBounds.setEmpty(); return; }
        mTouchedNodeBounds.set((float) mTouchedGeometry.left - mWindowLeft, (float) mTouchedGeometry.top - mStatusBarHeight,
                (float) mTouchedGeometry.right - mWindowLeft, (float) mTouchedGeometry.bottom - mStatusBarHeight);
    }

    public int getStatusBarHeight() {
        return mStatusBarHeight;
    }
}
