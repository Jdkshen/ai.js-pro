package com.jdkshen.aijspro.pluginclient;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 文件类命令（list_files / read_file / write_file）的实现。
 *
 * <p>这三个命令是给自研 VS Code 扩展（AI.js Pro）用的扩展命令 —— 官方
 * {@code hyb1996.auto-js-pro-ext} 没有这套能力，但走同一份
 * {@link DevPluginFrames} 协议与 {@code command}/{@code response_<id>} 消息模型，
 * 官方扩展不发送它们时行为完全不变。
 *
 * <p>路径约定：入参是**相对手机脚本目录**的路径，所有读写都必须在脚本目录内部
 * （规范化后校验，挡掉 {@code ../} 与符号链接逃逸）。
 */
public final class DevPluginFileService {

    /** 命令名：列出脚本目录（data.path 可选，data.recursive 默认 true）。 */
    public static final String LIST_FILES = "list_files";
    /** 命令名：读取一个文本/二进制文件，内容以 bytes 帧返回（data.path）。 */
    public static final String READ_FILE = "read_file";
    /** 命令名：写入一个文本文件（data.path + data.script），用于“保存时推送”。 */
    public static final String WRITE_FILE = "write_file";

    /** 单次列目录最多返回的条目数（防止把超大目录塞进一帧）。 */
    public static final int MAX_LIST_ENTRIES = 5000;
    /** 单个文件读取上限（协议帧另有 256MB 上限，这里更保守）。 */
    public static final int MAX_READ_BYTES = 8 * 1024 * 1024;

    private DevPluginFileService() {
    }

    /** 是否是本服务认识的文件类命令。 */
    public static boolean isFileCommand(String command) {
        return LIST_FILES.equals(command) || READ_FILE.equals(command) || WRITE_FILE.equals(command);
    }

    /**
     * 列出脚本目录（或其中一个子目录）里的文件。
     *
     * @param relativePath 相对脚本目录的路径，空/ null 表示脚本目录本身
     * @param recursive    是否递归子目录
     * @return {@code {files: [{path, size, mtime}], dirs: [path], truncated: boolean}}，
     *         path 是相对脚本目录的路径
     */
    public static JsonObject listFiles(File baseDir, String relativePath, boolean recursive)
            throws IOException {
        File dir = resolve(baseDir, relativePath == null ? "" : relativePath);
        if (!dir.isDirectory()) {
            throw new IOException("不是目录：" + relativePath);
        }
        JsonArray files = new JsonArray();
        JsonArray dirs = new JsonArray();
        boolean truncated = collect(baseDir, dir, recursive, files, dirs);
        JsonObject result = new JsonObject();
        result.add("files", files);
        result.add("dirs", dirs);
        result.addProperty("truncated", truncated);
        return result;
    }

    /** 读取一个文件；返回原始字节（调用方负责按 bytes 帧发回去）。 */
    public static byte[] readFile(File baseDir, String relativePath) throws IOException {
        File file = resolve(baseDir, relativePath == null ? "" : relativePath);
        if (!file.isFile()) {
            throw new IOException("不是文件：" + relativePath);
        }
        if (file.length() > MAX_READ_BYTES) {
            throw new IOException("文件过大（" + file.length() + " 字节，上限 " + MAX_READ_BYTES + "）");
        }
        InputStream in = new FileInputStream(file);
        try {
            java.io.ByteArrayOutputStream out =
                    new java.io.ByteArrayOutputStream(Math.max(64, (int) file.length()));
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    /** 写入一个 UTF-8 文本文件（父目录不存在时自动创建）。 */
    public static JsonObject writeFile(File baseDir, String relativePath, String text)
            throws IOException {
        if (relativePath == null || relativePath.trim().isEmpty()) {
            throw new IOException("缺少写入路径");
        }
        File file = resolve(baseDir, relativePath);
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("无法创建目录：" + parent);
        }
        byte[] bytes = text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8);
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(bytes);
        } finally {
            out.close();
        }
        JsonObject result = new JsonObject();
        result.addProperty("path", relativePath);
        result.addProperty("size", bytes.length);
        return result;
    }

    /**
     * 相对路径 → 真实文件；规范化后必须仍在 {@code baseDir} 内。
     *
     * <p>前导 {@code /} 会被当成相对路径处理（扩展端两种写法都能传），
     * {@code ..} 逃逸与指向脚本目录之外的符号链接都会被拒绝。
     */
    static File resolve(File baseDir, String relativePath) throws IOException {
        String cleaned = relativePath == null ? "" : relativePath.trim();
        while (cleaned.startsWith("/")) {
            cleaned = cleaned.substring(1);
        }
        File file = new File(baseDir, cleaned);
        String base = baseDir.getCanonicalPath();
        String path = file.getCanonicalPath();
        if (!path.equals(base) && !path.startsWith(base + File.separator)) {
            throw new IOException("路径越界：" + relativePath);
        }
        return file;
    }

    private static boolean collect(File baseDir, File dir, boolean recursive,
            JsonArray files, JsonArray dirs) throws IOException {
        File[] children = dir.listFiles();
        if (children == null) {
            throw new IOException("无法读取目录：" + dir);
        }
        java.util.Arrays.sort(children, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File child : children) {
            if (child.isDirectory()) {
                dirs.add(relativePath(baseDir, child));
                if (recursive && collect(baseDir, child, true, files, dirs)) {
                    return true;
                }
                continue;
            }
            if (files.size() >= MAX_LIST_ENTRIES) {
                return true;
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("path", relativePath(baseDir, child));
            entry.addProperty("size", child.length());
            entry.addProperty("mtime", child.lastModified());
            files.add(entry);
        }
        return false;
    }

    private static String relativePath(File baseDir, File file) throws IOException {
        String base = baseDir.getCanonicalPath();
        String path = file.getCanonicalPath();
        if (!path.startsWith(base + File.separator)) {
            throw new IOException("文件不在脚本目录里：" + path);
        }
        return path.substring(base.length() + 1).replace(File.separatorChar, '/');
    }
}
