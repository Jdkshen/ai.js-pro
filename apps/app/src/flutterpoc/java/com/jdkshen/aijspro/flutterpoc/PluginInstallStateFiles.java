package com.jdkshen.aijspro.flutterpoc;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * task-131：插件安装状态与下载目录的**纯文件逻辑**（不依赖 Android / org.json ⇒ 可 JVM 单测）。
 *
 * <p>背景（源码复查发现）：PluginBridge 原来「persist() 在写、readPersisted() 恒 return null」⇒ 只写不读的
 * 半成品（进程重启后复盘不了「上次装到一半/失败」）；下载目录 cache/plugins/ 也从不清（象棋 60.5MB、
 * 打包插件 86MB 级，几轮更新就几百 MB）。这里把两件事抽成可测的纯逻辑，PluginBridge 只做委托。</p>
 */
final class PluginInstallStateFiles {

    private PluginInstallStateFiles() {
    }

    /** 某 id 的状态文件（调用方给目录，便于测试）。 */
    static File stateFileIn(File dir, String id) {
        return new File(dir, id + ".json");
    }

    /** 写文本（原子性不在这里做；失败返回 false，调用方记日志）。 */
    static boolean writeText(File file, String text) {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                return false;
            }
            FileOutputStream output = new FileOutputStream(file);
            try {
                output.write(text.getBytes("UTF-8"));
            } finally {
                output.close();
            }
            return true;
        } catch (Throwable error) {
            return false;
        }
    }

    /** 读文本（不存在/读失败 ⇒ null）。 */
    static String readText(File file) {
        try {
            if (file == null || !file.isFile()) {
                return null;
            }
            FileInputStream input = new FileInputStream(file);
            try {
                java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int read;
                while ((read = input.read(chunk)) > 0) {
                    buffer.write(chunk, 0, read);
                }
                String text = new String(buffer.toByteArray(), "UTF-8");
                return text.trim().isEmpty() ? null : text;
            } finally {
                try {
                    input.close();
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable error) {
            return null;
        }
    }

    /**
     * 清理下载目录：只删**不再需要**的 .apk（keep = 本次清单里仍在用的文件名）；其它文件一律不动。
     *
     * <p>为什么不在交给安装器后立刻删：系统安装器是**异步读文件**的，立刻删会让安装失败。所以策略是
     * 「下一次 pluginCatalog 时清理非当前版本」。</p>
     *
     * @return 被删掉的文件名（便于日志/测试断言）
     */
    static List<String> pruneDownloadedApks(File dir, Set<String> keep) {
        List<String> removed = new ArrayList<>();
        File[] files = dir == null ? null : dir.listFiles();
        if (files == null) {
            return removed;
        }
        for (File file : files) {
            if (!file.isFile()) {
                continue;
            }
            String name = file.getName();
            if (!name.endsWith(".apk")) {
                continue;
            }
            if (keep != null && keep.contains(name)) {
                continue;
            }
            if (file.delete()) {
                removed.add(name);
            }
        }
        return removed;
    }
}
