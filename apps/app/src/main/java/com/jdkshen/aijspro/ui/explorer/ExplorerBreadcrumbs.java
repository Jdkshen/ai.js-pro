package com.jdkshen.aijspro.ui.explorer;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public final class ExplorerBreadcrumbs {
    public static final class Segment {
        public final String label;
        public final String path;
        Segment(String label, File file) { this.label = label; this.path = file.getAbsolutePath(); }
    }
    private static File normalized(String path) {
        try { return new File(path).getCanonicalFile(); }
        catch (java.io.IOException ignored) { return new File(path).getAbsoluteFile(); }
    }
    private static boolean inside(File root, File file) {
        return file.equals(root) || file.getPath().startsWith(root.getPath() + File.separator);
    }
    public static List<Segment> build(String scripts, String storage, String current) {
        File root = normalized(scripts), internal = normalized(storage), target = normalized(current);
        List<Segment> result = new ArrayList<>();
        // 段序：内部存储 › 脚本 › …子目录链。label 用于显示、path 用于点击跳转，
        // 两者分开提供：同前缀兄弟目录（scripts-other）不会误挂到脚本目录下。
        result.add(new Segment("内部存储", internal));
        File base = internal;
        if (inside(root, target)) {
            result.add(new Segment("脚本", root));
            base = root;
        }
        if (!inside(base, target)) { result.add(new Segment(target.getName(), target)); return result; }
        List<File> tail = new ArrayList<>();
        for (File file = target; file != null && !file.equals(base); file = file.getParentFile()) tail.add(0, file);
        for (File file : tail) result.add(new Segment(file.getName(), file));
        return result;
    }
    private ExplorerBreadcrumbs() { }
}
