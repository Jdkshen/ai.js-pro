package com.jdkshen.aijspro.ui.floating.layoutinspector;

import android.content.Context;
import android.view.View;
import com.jdkshen.aijspro.ui.floating.FloatyWindowManger;
import com.stardust.view.accessibility.LayoutInspector;
import com.stardust.view.accessibility.NodeInfo;

public class LayoutBoundsFloatyWindow extends InspectorFloatyWindow {
    private LayoutBoundsView bounds;
    public LayoutBoundsFloatyWindow(NodeInfo root) { super(root, false); }
    LayoutBoundsFloatyWindow(InspectorSession session) { super(session, false); }
    public static void capture(LayoutInspector inspector, Context context) {
        LayoutInspector.CaptureAvailableListener listener = new LayoutInspector.CaptureAvailableListener() {
            @Override public void onCaptureAvailable(NodeInfo capture) {
                inspector.removeCaptureAvailableListener(this);
                if (capture == null) { LayoutInspectorDiagnostics.show(context, inspector); return; }
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                        FloatyWindowManger.addWindow(context, new LayoutBoundsFloatyWindow(capture)));
            }
        };
        inspector.addCaptureAvailableListener(listener);
        if (!inspector.captureCurrentWindow()) listener.onCaptureAvailable(null);
    }
    @Override View createInspectorView() {
        bounds = new LayoutBoundsView(context);
        bounds.getBoundsPaint().setStrokeWidth(2f);
        bounds.setOnNodeInfoSelectListener(node -> selectedByTouch(node, bounds));
        return bounds;
    }
    @Override void renderRoot() { bounds.setRootNode(session.root); bounds.setSelectedNode(session.selected); }
    @Override void renderSelection(NodeInfo node) { bounds.setSelectedNode(node); }
}
