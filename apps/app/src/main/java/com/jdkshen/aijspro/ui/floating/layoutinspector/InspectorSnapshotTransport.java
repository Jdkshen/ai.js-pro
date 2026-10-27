package com.jdkshen.aijspro.ui.floating.layoutinspector;

import android.content.Context;
import androidx.annotation.Keep;
import com.google.gson.Gson;
import com.stardust.view.accessibility.LayoutInspector;
import com.stardust.view.accessibility.NodeInfo;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Same-UID IPC carries a random cache token, never a node tree in a Binder transaction. */
final class InspectorSnapshotTransport {
    static final int MAX_WINDOWS = 32;
    static final long MAX_TOTAL_BYTES = 32L * 1024 * 1024;
    private static final int MAX_MANIFEST_BYTES = 64 * 1024;

    static final class Payload {
        final NodeInfo root;
        final List<LayoutInspector.CapturedWindow> windows;
        final boolean hierarchy;
        Payload(NodeInfo root, List<LayoutInspector.CapturedWindow> windows, boolean hierarchy) {
            this.root = root; this.windows = windows; this.hierarchy = hierarchy;
        }
    }
    @Keep static final class Manifest {
        String format = "aijs-inspector-ipc";
        int version = 1, rootCount, selectedRoot;
        boolean hierarchy;
        List<WindowRecord> windows = new ArrayList<>();
    }
    @Keep static final class WindowRecord {
        int id, type, layer, root;
        boolean active, focused;
    }

    private InspectorSnapshotTransport() { }

    static String write(Context context, NodeInfo root,
                        List<LayoutInspector.CapturedWindow> windows, boolean hierarchy) throws IOException {
        String token = UUID.randomUUID().toString().replace("-", "");
        File directory = resolveDirectory(context.getCacheDir(), token, true);
        boolean complete = false;
        try {
            if (root == null) throw new IOException("没有可传输的布局根节点");
            List<LayoutInspector.CapturedWindow> source = windows == null ? Collections.emptyList() : windows;
            if (source.size() > MAX_WINDOWS) source = source.subList(0, MAX_WINDOWS);
            Manifest manifest = new Manifest(); manifest.hierarchy = hierarchy;
            List<NodeInfo> roots = new ArrayList<>();
            IdentityHashMap<NodeInfo, Integer> indices = new IdentityHashMap<>();
            roots.add(root); indices.put(root, 0);
            for (LayoutInspector.CapturedWindow window : source) {
                checkInterrupted();
                if (window == null || window.getRoot() == null) continue;
                NodeInfo node = window.getRoot();
                Integer index = indices.get(node);
                if (index == null) { index = roots.size(); roots.add(node); indices.put(node, index); }
                WindowRecord record = new WindowRecord();
                record.id = window.getId(); record.type = window.getType(); record.layer = window.getLayer();
                record.active = window.getActive(); record.focused = window.getFocused(); record.root = index;
                manifest.windows.add(record);
            }
            manifest.rootCount = roots.size(); validate(manifest);
            checkNodeCount(roots);
            long bytes = 0;
            for (int i = 0; i < roots.size(); i++) {
                checkInterrupted(); File file = rootFile(directory, i);
                LayoutSnapshot.save(file, roots.get(i));
                bytes += file.length();
                if (bytes > MAX_TOTAL_BYTES) throw new IOException("布局快照总大小超过 32 MiB");
            }
            checkInterrupted();
            byte[] header = new Gson().toJson(manifest).getBytes(StandardCharsets.UTF_8);
            if (header.length > MAX_MANIFEST_BYTES) throw new IOException("布局传输清单过大");
            try (FileOutputStream out = new FileOutputStream(child(directory, "manifest.json"))) { out.write(header); }
            checkInterrupted(); complete = true; return token;
        } catch (RuntimeException error) {
            throw new IOException("无法准备布局快照", error);
        } finally { if (!complete) discard(context, token); }
    }

    static Payload read(Context context, String token) throws IOException {
        File directory = resolveDirectory(context.getCacheDir(), token, false);
        Manifest manifest;
        try { manifest = new Gson().fromJson(readBounded(child(directory, "manifest.json")), Manifest.class); }
        catch (RuntimeException error) { throw new IOException("布局传输清单无效", error); }
        try { validate(manifest); }
        catch (IllegalArgumentException error) { throw new IOException(error.getMessage(), error); }
        long bytes = 0;
        List<NodeInfo> roots = new ArrayList<>();
        for (int i = 0; i < manifest.rootCount; i++) {
            checkInterrupted(); File file = rootFile(directory, i);
            if (!file.isFile() || file.length() > LayoutSnapshot.MAX_BYTES) throw new IOException("布局文件不可用");
            bytes += file.length();
            if (bytes > MAX_TOTAL_BYTES) throw new IOException("布局快照总大小超过 32 MiB");
            roots.add(LayoutSnapshot.load(file));
        }
        checkNodeCount(roots);
        List<LayoutInspector.CapturedWindow> windows = new ArrayList<>();
        for (WindowRecord window : manifest.windows) windows.add(new LayoutInspector.CapturedWindow(
                window.id, window.type, window.active, window.focused, window.layer, roots.get(window.root)));
        return new Payload(roots.get(manifest.selectedRoot), windows, manifest.hierarchy);
    }

    static void validate(Manifest manifest) {
        if (manifest == null || !"aijs-inspector-ipc".equals(manifest.format) || manifest.version != 1
                || manifest.rootCount < 1 || manifest.rootCount > MAX_WINDOWS + 1
                || manifest.selectedRoot < 0 || manifest.selectedRoot >= manifest.rootCount
                || manifest.windows == null || manifest.windows.size() > MAX_WINDOWS)
            throw new IllegalArgumentException("不支持的布局传输清单");
        for (WindowRecord window : manifest.windows) {
            if (window == null || window.root < 0 || window.root >= manifest.rootCount)
                throw new IllegalArgumentException("布局窗口引用无效");
        }
    }

    static File resolveDirectory(File cache, String token, boolean create) throws IOException {
        if (token == null || !token.matches("[0-9a-f]{32}")) throw new IOException("布局传输标识无效");
        File canonicalCache = cache.getCanonicalFile();
        File base = new File(canonicalCache, "inspector-ipc").getCanonicalFile();
        if (!canonicalCache.equals(base.getParentFile())) throw new IOException("布局缓存目录无效");
        File directory = new File(base, token).getCanonicalFile();
        if (!base.equals(directory.getParentFile())) throw new IOException("布局缓存路径越界");
        if (create && !directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建布局缓存");
        if (!create && !directory.isDirectory()) throw new IOException("布局快照已失效");
        return directory;
    }

    private static File child(File directory, String name) throws IOException {
        File file = new File(directory, name).getCanonicalFile();
        if (!directory.getCanonicalFile().equals(file.getParentFile())) throw new IOException("布局文件路径越界");
        return file;
    }
    private static File rootFile(File directory, int index) throws IOException { return child(directory, "nodes-" + index + ".json"); }
    private static String readBounded(File file) throws IOException {
        if (!file.isFile() || file.length() > MAX_MANIFEST_BYTES) throw new IOException("布局传输清单不可用");
        try (InputStream input = new FileInputStream(file); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int size;
            while ((size = input.read(buffer)) != -1) {
                if (output.size() + size > MAX_MANIFEST_BYTES) throw new IOException("布局传输清单过大");
                output.write(buffer, 0, size);
            }
            return output.toString("UTF-8");
        }
    }
    private static void checkNodeCount(List<NodeInfo> roots) throws IOException {
        IdentityHashMap<NodeInfo, Boolean> seen = new IdentityHashMap<>();
        ArrayDeque<NodeInfo> queue = new ArrayDeque<>();
        for (NodeInfo root : roots) {
            queue.add(root);
            while (!queue.isEmpty()) {
                checkInterrupted(); NodeInfo node = queue.removeFirst();
                if (seen.put(node, Boolean.TRUE) != null) continue;
                if (seen.size() > LayoutSnapshot.MAX_NODES) throw new IOException("布局节点总数超过上限");
                queue.addAll(node.getChildren());
            }
        }
    }
    private static void checkInterrupted() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("布局传输已取消");
    }

    /** Only known direct children are removed; no recursive traversal or symlink following. */
    /** Remove orphaned handoff directories left by a previous process death or failed cleanup. */
    static void cleanupOrphans(Context context) {
        try {
            File base = new File(context.getCacheDir(), "inspector-ipc").getCanonicalFile();
            File[] children = base.listFiles();
            if (children == null) return;
            for (File child : children) {
                if (child != null && child.isDirectory()) deleteRecursively(child);
            }
        } catch (IOException | SecurityException ignored) {
        }
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteRecursively(child);
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    static void discard(Context context, String token) {
        try {
            File directory = resolveDirectory(context.getCacheDir(), token, false);
            for (int i = 0; i <= MAX_WINDOWS; i++) {
                child(directory, "nodes-" + i + ".json").delete();
                child(directory, "nodes-" + i + ".json.tmp").delete();
            }
            child(directory, "manifest.json").delete(); directory.delete();
        } catch (IOException | SecurityException ignored) { }
    }
}
