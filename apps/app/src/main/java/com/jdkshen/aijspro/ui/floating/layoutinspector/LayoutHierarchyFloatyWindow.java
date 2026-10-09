package com.jdkshen.aijspro.ui.floating.layoutinspector;

import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.FrameLayout;
import android.graphics.Paint;
import com.stardust.view.accessibility.NodeInfo;

public class LayoutHierarchyFloatyWindow extends InspectorFloatyWindow {
    private LayoutHierarchyView tree;
    private HorizontalScrollView scroll;
    public LayoutHierarchyFloatyWindow(NodeInfo root) { super(root, true); }
    LayoutHierarchyFloatyWindow(InspectorSession session) { super(session, true); }
    @Override View createInspectorView() {
        tree = new LayoutHierarchyView(context);
        tree.setBackgroundColor(0xddffffff);
        tree.setShowClickedNodeBounds(true);
        tree.setClickedColor(0xffd7eaff);
        tree.getBoundsPaint().setStrokeWidth(3);
        tree.getBoundsPaint().setColor(0xffd32f2f);
        tree.setOnNodeSelectedListener(this::selectionChanged);
        tree.setOnItemLongClickListener((view, node) -> {
            tree.setSelectedNode(node);
            selectedByTouch(node, view);
        });
        scroll = new HorizontalScrollView(context);
        scroll.setFillViewport(true);
        int width = treeWidth();
        tree.setMinimumWidth(width);
        scroll.addView(tree, new FrameLayout.LayoutParams(width, -1));
        scroll.setOnScrollChangeListener((view, x, y, oldX, oldY) -> tree.invalidate());
        return scroll;
    }
    private int treeWidth() {
        android.util.DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        float width = metrics.widthPixels;
        Paint paint = new Paint();
        for (NodeInfo node : session.nodes) {
            paint.setTextSize(12 * metrics.scaledDensity);
            float text = paint.measureText(node.getClassName() == null ? "" : node.getClassName());
            paint.setTextSize(10 * metrics.scaledDensity);
            text = Math.max(text, paint.measureText(LayoutHierarchyView.rowDetail(node)));
            width = Math.max(width, text + (32 + (Math.max(0, node.getDepth()) + 1) * 8) * metrics.density);
        }
        return Math.min(Math.round(1600 * metrics.density), (int) Math.ceil(width));
    }
    @Override void renderRoot() {
        int width = treeWidth(); tree.setMinimumWidth(width);
        tree.setLayoutParams(new FrameLayout.LayoutParams(width, -1));
        tree.setRootNode(session.root); if (session.selected != null) tree.setSelectedNode(session.selected);
    }
    @Override void renderSelection(NodeInfo node) { tree.setSelectedNode(node); }
}
