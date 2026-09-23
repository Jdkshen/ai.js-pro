package com.jdkshen.aijspro.model.script;

import java.io.File;

/**
 * 脚本文件系统里的一个条目描述（不可变值对象）。
 *
 * <p>B1「统一脚本事实」批次1 的只读事实层：路径 / 名称 / 是否目录 / 大小 / 最后修改时间。
 * 不携带 File 句柄（需要时用 {@link #toFile()} 现造），避免把可变对象散到各处消费者手里。
 *
 * @see ScriptRepository
 */
public final class ScriptDescriptor {

    private final String path;
    private final String name;
    private final boolean directory;
    private final long size;
    private final long lastModified;

    private ScriptDescriptor(String path, String name, boolean directory, long size, long lastModified) {
        this.path = path;
        this.name = name;
        this.directory = directory;
        this.size = size;
        this.lastModified = lastModified;
    }

    public static ScriptDescriptor of(File file) {
        return new ScriptDescriptor(file.getPath(), file.getName(), file.isDirectory(),
                file.length(), file.lastModified());
    }

    public String getPath() {
        return path;
    }

    public String getName() {
        return name;
    }

    public boolean isDirectory() {
        return directory;
    }

    public long getSize() {
        return size;
    }

    public long getLastModified() {
        return lastModified;
    }

    /** 是否脚本文件（.js / .auto，与历史 {@code Scripts.FILE_FILTER} 同一判定）。目录恒为 false。 */
    public boolean isScript() {
        return !directory && ScriptRepository.isScriptFile(name);
    }

    public File toFile() {
        return new File(path);
    }

    @Override
    public String toString() {
        return "ScriptDescriptor{" + path + (directory ? " [dir]" : "") + "}";
    }
}
