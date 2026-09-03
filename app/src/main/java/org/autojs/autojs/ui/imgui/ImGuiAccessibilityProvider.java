package org.autojs.autojs.ui.imgui;

import android.content.Context;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.View;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Exposes selected Dear ImGui widgets as Android virtual accessibility nodes.
 *
 * Native code publishes an immutable per-frame semantic snapshot. This provider never reads live
 * ImGui objects, which keeps Android accessibility queries independent from the render thread.
 */
final class ImGuiAccessibilityProvider extends AccessibilityNodeProvider {

    private static final int HOST_VIEW_ID = View.NO_ID;
    private static final int FLAG_CLICKABLE = 1;
    private static final int FLAG_SCROLLABLE = 1 << 1;
    private static final int FLAG_SELECTED = 1 << 2;
    private static final char FIELD_SEPARATOR = '\u001f';

    private final View mHost;
    private final AccessibilityManager mAccessibilityManager;
    private final Map<Integer, VirtualNode> mNodes = new LinkedHashMap<>();
    private int mSnapshotRevision = -1;
    private int mAccessibilityFocusedId = HOST_VIEW_ID;

    ImGuiAccessibilityProvider(View host) {
        mHost = host;
        mAccessibilityManager = (AccessibilityManager) host.getContext()
                .getSystemService(Context.ACCESSIBILITY_SERVICE);
    }

    @Override
    public AccessibilityNodeInfo createAccessibilityNodeInfo(int virtualViewId) {
        refreshSnapshot();
        if (virtualViewId == HOST_VIEW_ID) return createHostNode();
        VirtualNode node = mNodes.get(virtualViewId);
        return node == null ? null : createVirtualNode(node);
    }

    @Override
    public List<AccessibilityNodeInfo> findAccessibilityNodeInfosByText(
            String searched, int virtualViewId) {
        if (searched == null) return Collections.emptyList();
        refreshSnapshot();
        String query = searched.toLowerCase(Locale.ROOT);
        List<AccessibilityNodeInfo> matches = new ArrayList<>();
        for (VirtualNode node : mNodes.values()) {
            if (node.text.toLowerCase(Locale.ROOT).contains(query)) {
                matches.add(createVirtualNode(node));
            }
        }
        return matches;
    }

    @Override
    public AccessibilityNodeInfo findFocus(int focus) {
        if (focus != AccessibilityNodeInfo.FOCUS_ACCESSIBILITY ||
                mAccessibilityFocusedId == HOST_VIEW_ID) {
            return null;
        }
        return createAccessibilityNodeInfo(mAccessibilityFocusedId);
    }

    @Override
    public boolean performAction(int virtualViewId, int action, Bundle arguments) {
        if (virtualViewId == HOST_VIEW_ID) return mHost.performAccessibilityAction(action, arguments);
        refreshSnapshot();
        VirtualNode node = mNodes.get(virtualViewId);
        if (node == null) return false;

        if (action == AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS) {
            if (mAccessibilityFocusedId == virtualViewId) return false;
            int previous = mAccessibilityFocusedId;
            mAccessibilityFocusedId = virtualViewId;
            if (previous != HOST_VIEW_ID) {
                sendEvent(previous, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED);
            }
            sendEvent(virtualViewId, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED);
            return true;
        }
        if (action == AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS) {
            if (mAccessibilityFocusedId != virtualViewId) return false;
            mAccessibilityFocusedId = HOST_VIEW_ID;
            sendEvent(virtualViewId, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED);
            return true;
        }
        if (action == AccessibilityNodeInfo.ACTION_CLICK && node.isClickable()) {
            boolean handled = ImGuiNativeBridge.performAccessibilityAction(virtualViewId, action);
            if (handled) {
                sendEvent(virtualViewId, AccessibilityEvent.TYPE_VIEW_CLICKED);
                mHost.postDelayed(this::invalidateVirtualTree, 32L);
            }
            return handled;
        }
        if ((action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ||
                action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) && node.isScrollable()) {
            boolean handled = ImGuiNativeBridge.performAccessibilityAction(virtualViewId, action);
            if (handled) {
                sendEvent(virtualViewId, AccessibilityEvent.TYPE_VIEW_SCROLLED);
                mHost.postDelayed(this::invalidateVirtualTree, 32L);
            }
            return handled;
        }
        return false;
    }

    void invalidateVirtualTree() {
        mSnapshotRevision = -1;
        sendEvent(HOST_VIEW_ID, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
    }

    private AccessibilityNodeInfo createHostNode() {
        AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain(mHost);
        mHost.onInitializeAccessibilityNodeInfo(info);
        info.setPackageName(mHost.getContext().getPackageName());
        info.setClassName("android.view.ViewGroup");
        info.setContentDescription("AI.js Pro 工作区");
        for (VirtualNode node : mNodes.values()) info.addChild(mHost, node.id);
        return info;
    }

    private AccessibilityNodeInfo createVirtualNode(VirtualNode node) {
        AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain();
        info.setPackageName(mHost.getContext().getPackageName());
        info.setClassName(classNameForRole(node.role));
        info.setSource(mHost, node.id);
        info.setParent(mHost);
        info.setText(node.text);
        info.setContentDescription(node.text);
        info.setEnabled(mHost.isEnabled());
        info.setClickable(node.isClickable());
        info.setScrollable(node.isScrollable());
        info.setSelected((node.flags & FLAG_SELECTED) != 0);
        info.setFocusable(true);
        info.setAccessibilityFocused(mAccessibilityFocusedId == node.id);
        info.setBoundsInParent(node.bounds);

        int[] location = new int[2];
        mHost.getLocationOnScreen(location);
        Rect screenBounds = new Rect(node.bounds);
        screenBounds.offset(location[0], location[1]);
        info.setBoundsInScreen(screenBounds);
        Rect visibleBounds = new Rect();
        info.setVisibleToUser(mHost.isShown() && mHost.getGlobalVisibleRect(visibleBounds) &&
                Rect.intersects(visibleBounds, screenBounds));

        if (node.isClickable()) info.addAction(AccessibilityNodeInfo.ACTION_CLICK);
        if (node.isScrollable()) {
            info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
            info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
        }
        if (mAccessibilityFocusedId == node.id) {
            info.addAction(AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS);
        } else {
            info.addAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
        }
        return info;
    }

    private void refreshSnapshot() {
        int nativeRevision = ImGuiNativeBridge.getAccessibilityRevision();
        if (nativeRevision == mSnapshotRevision) return;
        String[] records = ImGuiNativeBridge.getAccessibilitySnapshot();
        Map<Integer, VirtualNode> updated = new LinkedHashMap<>();
        if (records != null) {
            for (String record : records) {
                VirtualNode node = parseNode(record);
                if (node != null) updated.put(node.id, node);
            }
        }
        mNodes.clear();
        mNodes.putAll(updated);
        if (mAccessibilityFocusedId != HOST_VIEW_ID &&
                !mNodes.containsKey(mAccessibilityFocusedId)) {
            mAccessibilityFocusedId = HOST_VIEW_ID;
        }
        mSnapshotRevision = nativeRevision;
    }

    private VirtualNode parseNode(String record) {
        if (record == null) return null;
        String[] fields = record.split(String.valueOf(FIELD_SEPARATOR), 8);
        if (fields.length != 8) return null;
        try {
            int id = Integer.parseInt(fields[0]);
            Rect bounds = new Rect(Integer.parseInt(fields[1]), Integer.parseInt(fields[2]),
                    Integer.parseInt(fields[3]), Integer.parseInt(fields[4]));
            return new VirtualNode(id, bounds, Integer.parseInt(fields[5]),
                    Integer.parseInt(fields[6]), fields[7]);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private void sendEvent(int virtualViewId, int eventType) {
        if (mAccessibilityManager == null || !mAccessibilityManager.isEnabled() ||
                !mHost.isAttachedToWindow() || !mHost.isShown()) {
            return;
        }
        AccessibilityEvent event = AccessibilityEvent.obtain(eventType);
        event.setPackageName(mHost.getContext().getPackageName());
        event.setClassName(virtualViewId == HOST_VIEW_ID
                ? "android.view.ViewGroup" : "android.view.View");
        event.setSource(mHost, virtualViewId);
        if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            event.setContentChangeTypes(AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE);
        }
        VirtualNode node = mNodes.get(virtualViewId);
        if (node != null) event.getText().add(node.text);
        ViewParent parent = mHost.getParent();
        if (parent == null) return;
        try {
            // Android 16 throws when accessibility is disabled between the enabled-state check
            // above and ViewRootImpl dispatching the event. Treat that race as a dropped event.
            parent.requestSendAccessibilityEvent(mHost, event);
        } catch (IllegalStateException ignored) {
            // Accessibility was disabled while this event was being dispatched.
        }
    }

    private String classNameForRole(int role) {
        switch (role) {
            case 1:
                return "android.widget.Button";
            case 2:
                return "android.widget.ListView";
            case 3:
                return "android.widget.TextView";
            case 4:
                return "android.widget.TabWidget";
            default:
                return "android.widget.TextView";
        }
    }

    private static final class VirtualNode {
        final int id;
        final Rect bounds;
        final int role;
        final int flags;
        final String text;

        VirtualNode(int id, Rect bounds, int role, int flags, String text) {
            this.id = id;
            this.bounds = bounds;
            this.role = role;
            this.flags = flags;
            this.text = text;
        }

        boolean isClickable() {
            return (flags & FLAG_CLICKABLE) != 0;
        }

        boolean isScrollable() {
            return (flags & FLAG_SCROLLABLE) != 0;
        }
    }
}
