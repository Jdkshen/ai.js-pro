package com.jdkshen.aijspro.ui.floating.layoutinspector;

import com.jdkshen.aijspro.autojs.AutoJs;
import com.stardust.view.accessibility.LayoutInspector;
import com.stardust.view.accessibility.NodeInfo;
import java.util.*;

final class InspectorSession {
    NodeInfo root, selected;
    String source = "当前页面快照";
    final List<LayoutInspector.CapturedWindow> windows = new ArrayList<>();
    final List<NodeInfo> nodes = new ArrayList<>(), matches = new ArrayList<>();
    final IdentityHashMap<NodeInfo, Integer> indices = new IdentityHashMap<>();
    String query = "";
    boolean matchCase, searchOpen;
    int matchIndex = -1;
    float searchX = -1, searchY = -1;
    Set<String> properties;
    InspectorSession(NodeInfo root) {
        this.root = root;
        LayoutInspector inspector = AutoJs.getInstance().getLayoutInspector();
        if (inspector.getCapture() == root) windows.addAll(inspector.getCapturedWindows());
        indexRoot();
    }
    void indexRoot() {
        nodes.clear(); indices.clear(); matches.clear(); matchIndex = -1;
        ArrayDeque<NodeInfo> queue = new ArrayDeque<>(); queue.add(root);
        while (!queue.isEmpty()) {
            NodeInfo node = queue.removeFirst();
            if (indices.containsKey(node)) continue;
            indices.put(node, nodes.size()); nodes.add(node); queue.addAll(node.getChildren());
        }
    }
}
