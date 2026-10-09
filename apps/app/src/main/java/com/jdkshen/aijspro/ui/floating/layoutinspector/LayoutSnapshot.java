package com.jdkshen.aijspro.ui.floating.layoutinspector;

import androidx.annotation.Keep;
import com.google.gson.Gson;
import com.stardust.view.accessibility.NodeInfo;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Versioned, flat JSON snapshots. Earlier parent indices prevent cycles in imported trees. */
final class LayoutSnapshot {
    static final int MAX_NODES = 30000;
    static final int MAX_DEPTH = 256;
    static final long MAX_BYTES = 16 * 1024 * 1024;
    @Keep static class Document {
        String format;
        int version;
        long capturedAt = System.currentTimeMillis();
        List<Record> nodes = new ArrayList<>();
    }
    @Keep static class Record {
        int parent = -1;
        int[] screen, local;
        Map<String, Object> properties;
    }
    static void validate(Document document) {
        if (document == null || !"aijs-layout".equals(document.format) || document.version != 1 ||
                document.nodes == null || document.nodes.isEmpty() || document.nodes.size() > MAX_NODES)
            throw new IllegalArgumentException("不是受支持的 AI.js Pro 布局文件");
        int[] depths = new int[document.nodes.size()];
        for (int i = 0; i < document.nodes.size(); i++) {
            Record r = document.nodes.get(i);
            if (r == null || (i == 0 ? r.parent != -1 : r.parent < 0 || r.parent >= i) ||
                    r.screen == null || r.screen.length != 4 || r.local == null || r.local.length != 4 || r.properties == null)
                throw new IllegalArgumentException("布局文件的节点关系或坐标无效");
            depths[i] = i == 0 ? 0 : depths[r.parent] + 1;
            if (depths[i] > MAX_DEPTH) throw new IllegalArgumentException("布局节点层次过深");
        }
    }
    static void save(File file, NodeInfo root) throws IOException {
        Document document = new Document();
        document.format = "aijs-layout";
        document.version = 1;
        ArrayDeque<NodeInfo> queue = new ArrayDeque<>();
        IdentityHashMap<NodeInfo, Integer> indices = new IdentityHashMap<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            NodeInfo node = queue.removeFirst();
            if (indices.containsKey(node) || document.nodes.size() >= MAX_NODES)
                throw new IOException("布局节点过多或存在循环");
            Record record = new Record();
            record.parent = node == root ? -1 : indices.get(node.getParent());
            android.graphics.Rect b = node.getBoundsInScreen(), p = node.getBoundsInParent();
            record.screen = new int[]{b.left, b.top, b.right, b.bottom};
            record.local = new int[]{p.left, p.top, p.right, p.bottom};
            record.properties = NodeProperties.read(node);
            indices.put(node, document.nodes.size());
            document.nodes.add(record);
            queue.addAll(node.getChildren());
        }
        validate(document);
        String json = new Gson().toJson(document);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IOException("布局文件超过 16 MB");
        File temporary = new File(file.getPath() + ".tmp");
        try {
            try (FileOutputStream output = new FileOutputStream(temporary)) { output.write(bytes); }
            if (!temporary.renameTo(file)) throw new IOException("无法保存布局文件");
        } finally { if (temporary.exists()) temporary.delete(); }
    }
    static NodeInfo load(File file) throws IOException {
        if (!file.isFile() || file.length() > MAX_BYTES) throw new IOException("布局文件不存在或超过 16 MB");
        Document document;
        // Bound the bytes even if the file grows between stat and read.
        try (InputStream input = new FileInputStream(file); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int size;
            while ((size = input.read(buffer)) != -1) {
                if (output.size() + size > MAX_BYTES) throw new IOException("布局文件超过 16 MB");
                output.write(buffer, 0, size);
            }
            document = new Gson().fromJson(output.toString("UTF-8"), Document.class);
        }
        validate(document);
        List<NodeInfo> nodes = new ArrayList<>();
        for (Record record : document.nodes) {
            NodeInfo node = NodeInfo.createSnapshot();
            NodeProperties.restore(node, record.properties);
            node.getBoundsInScreen().set(record.screen[0], record.screen[1], record.screen[2], record.screen[3]);
            node.getBoundsInParent().set(record.local[0], record.local[1], record.local[2], record.local[3]);
            node.setBounds(NodeInfo.Companion.boundsToString(node.getBoundsInScreen()));
            if (record.parent >= 0) nodes.get(record.parent).addSnapshotChild(node);
            nodes.add(node);
        }
        return nodes.get(0);
    }
}
