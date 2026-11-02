package com.jdkshen.aijspro.storage.file;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Bounded directory traversal shared by the file and sample browsers. */
public final class WorkspaceListing {
    private WorkspaceListing() { }
    public static List<File> read(File root, String query, boolean regex, boolean recursive,
                                  boolean hidden, int limit) throws IOException {
        Pattern pattern = null;
        try { if (regex && !query.isEmpty()) pattern = Pattern.compile(query, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE); }
        catch (PatternSyntaxException error) { throw new IOException("正则表达式无效，请修改搜索内容", error); }
        String needle = query.toLowerCase(Locale.ROOT);
        String boundary = root.getCanonicalPath();
        ArrayDeque<File> pending = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        List<File> matches = new ArrayList<>();
        pending.add(root);
        int scanned = 0;
        while (!pending.isEmpty()) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("目录读取已取消");
            File directory = pending.removeFirst();
            if (!visited.add(directory.getCanonicalPath())) continue;
            File[] children = directory.listFiles();
            if (children == null) throw new IOException("无法读取目录：" + directory.getName());
            for (File child : children) {
                if (++scanned > 20000) throw new IOException("搜索范围超过 20000 项，请进入子目录后再搜索");
                String name = child.getName();
                if (name.startsWith(".autojs-glass-") || name.startsWith(".flutter-") || (!hidden && name.startsWith("."))) continue;
                String canonical = child.getCanonicalPath();
                if (!canonical.equals(boundary) && !canonical.startsWith(boundary + File.separator)) continue;
                if (recursive && !query.isEmpty() && child.isDirectory()) pending.add(child);
                if (pattern != null ? !pattern.matcher(name).find() : !name.toLowerCase(Locale.ROOT).contains(needle)) continue;
                if (matches.size() >= limit) throw new IOException("目录条目超过 " + limit + "，请进入子目录后再搜索");
                matches.add(child);
            }
        }
        return matches;
    }
}
