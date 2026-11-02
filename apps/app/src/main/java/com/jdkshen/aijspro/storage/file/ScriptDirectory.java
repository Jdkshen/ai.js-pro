package com.jdkshen.aijspro.storage.file;

import java.io.File;
import java.io.IOException;

/** Shared path rules for settings and legacy relative script-directory preferences. */
public final class ScriptDirectory {
    private ScriptDirectory() { }

    public static File resolve(String value, File externalRoot) throws IOException {
        if (value == null || value.trim().isEmpty()) throw new IOException("脚本目录不能为空");
        String path = value.trim();
        if (path.indexOf('\0') >= 0 || path.contains("://"))
            throw new IOException("请填写本机目录路径，不能使用文件链接");
        File directory = new File(path);
        return (directory.isAbsolute() ? directory : new File(externalRoot, path)).getCanonicalFile();
    }

    public static File prepare(String value, File externalRoot, File samples) throws IOException {
        File directory = resolve(value, externalRoot);
        if (samples != null) {
            String root = samples.getCanonicalPath();
            if (directory.getPath().equals(root) || directory.getPath().startsWith(root + File.separator))
                throw new IOException("内置示例目录不能作为我的脚本目录");
        }
        if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory())
            throw new IOException("无法创建脚本目录，请检查存储权限和路径");
        if (!directory.isDirectory()) throw new IOException("选择的路径是文件，请填写目录");
        if (!directory.canRead() || !directory.canWrite()) throw new IOException("脚本目录没有读写权限");
        return directory;
    }

    /** Old preferences used /脚本/ as an external-storage-relative suffix. */
    public static File resolvePreference(String value, File externalRoot, boolean absolute) throws IOException {
        File resolved = resolve(value, externalRoot);
        String external = externalRoot.getCanonicalPath();
        if (absolute || resolved.getPath().equals(external) || resolved.getPath().startsWith(external + File.separator))
            return resolved;
        String legacy = value.trim();
        while (legacy.startsWith("/") || legacy.startsWith("\\")) legacy = legacy.substring(1);
        return resolve(legacy.isEmpty() ? "." : legacy, externalRoot);
    }
}
