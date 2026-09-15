package com.stardust.autojs.runtime.api;

import com.stardust.autojs.annotation.ScriptInterface;

import org.mozilla.javascript.Scriptable;

import java.util.ArrayList;
import java.util.List;

import net.lingala.zip4j.exception.ZipException;

/**
 * PDF §2.35.2 的 `ZipFile` 对象（`$zip.open(file)` 的返回值）。
 *
 * <p>名字与文档一致；实现直接转发给 zip4j（文档原文：「zip 功能来自 zip4j」），
 * 所以 `getFileHeader(s)` 返回的就是 zip4j 的 FileHeader（可读 fileName / isDirectory /
 * uncompressedSize / compressedSize / lastModFileTime / crc32 等字段）。
 */
public class ZipFile {

    private final String mPath;
    private String mPassword;

    public ZipFile(String path) {
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("open(file) 需要压缩包路径");
        }
        mPath = path;
    }

    /**
     * zip4j 1.3.2 的 ProgressMonitor 状态是绑在 ZipFile 实例上的，同一个实例连着做多个操作会报
     * `invalid operation - Zip4j is in busy state`；所以每次操作都新建一个实例（不持有文件句柄，开销很小）。
     */
    private net.lingala.zip4j.core.ZipFile open() throws ZipException {
        net.lingala.zip4j.core.ZipFile zip = new net.lingala.zip4j.core.ZipFile(mPath);
        if (mPassword != null) {
            zip.setPassword(mPassword);
        }
        return zip;
    }

    /** 返回路径。 */
    @ScriptInterface
    public String getPath() {
        return mPath;
    }

    /** 是否是有效压缩包。 */
    @ScriptInterface
    public boolean isValidZipFile() {
        try {
            return open().isValidZipFile();
        } catch (ZipException e) {
            throw new RuntimeException("isValidZipFile 失败: " + e.getMessage());
        }
    }

    /** 设置密码（加密压缩包的解压/追加都要先设密码），返回自身便于链式调用。 */
    @ScriptInterface
    public ZipFile setPassword(String password) {
        mPassword = password;
        try {
            open();
        } catch (ZipException e) {
            throw new RuntimeException("setPassword 失败: " + e.getMessage());
        }
        return this;
    }

    /** 压缩包是否加密。 */
    @ScriptInterface
    public boolean isEncrypted() {
        try {
            return open().isEncrypted();
        } catch (ZipException e) {
            throw new RuntimeException("isEncrypted 失败: " + e.getMessage());
        }
    }

    /** 往压缩包里加一个文件。 */
    @ScriptInterface
    public void addFile(String file) {
        addFile(file, null);
    }

    @ScriptInterface
    public void addFile(String file, Scriptable options) {
        try {
            open().addFile(new java.io.File(file), ZipSupport.zipParameters(options));
        } catch (ZipException e) {
            throw new RuntimeException("addFile 失败: " + e.getMessage());
        }
    }

    /** 往压缩包里加多个文件。 */
    @ScriptInterface
    public void addFiles(Object fileList) {
        addFiles(fileList, null);
    }

    @ScriptInterface
    public void addFiles(Object fileList, Scriptable options) {
        try {
            open().addFiles(toFileList(fileList), ZipSupport.zipParameters(options));
        } catch (ZipException e) {
            throw new RuntimeException("addFiles 失败: " + e.getMessage());
        }
    }

    /** 往压缩包里加一个文件夹（含子目录）。 */
    @ScriptInterface
    public void addFolder(String folder) {
        addFolder(folder, null);
    }

    @ScriptInterface
    public void addFolder(String folder, Scriptable options) {
        try {
            open().addFolder(folder, ZipSupport.zipParameters(options));
        } catch (ZipException e) {
            throw new RuntimeException("addFolder 失败: " + e.getMessage());
        }
    }

    /** 从压缩包里删除一个条目。 */
    @ScriptInterface
    public void removeFile(String file) {
        try {
            open().removeFile(file);
        } catch (ZipException e) {
            throw new RuntimeException("removeFile 失败: " + e.getMessage());
        }
    }

    /** 解压单个条目到 dest（可指定新文件名）。 */
    @ScriptInterface
    public void extractFile(String file, String dest) {
        extractFile(file, dest, null, null);
    }

    @ScriptInterface
    public void extractFile(String file, String dest, Scriptable options, String newFileName) {
        try {
            net.lingala.zip4j.core.ZipFile zip = open();
            net.lingala.zip4j.model.FileHeader header = zip.getFileHeader(file);
            if (header == null) {
                throw new RuntimeException("压缩包里没有 " + file);
            }
            boolean hasOptions = options != null;
            boolean hasNewName = newFileName != null && !newFileName.isEmpty();
            if (hasOptions && hasNewName) {
                zip.extractFile(header, dest, ZipSupport.unzipParameters(options), newFileName);
            } else if (hasOptions) {
                zip.extractFile(header, dest, ZipSupport.unzipParameters(options));
            } else if (hasNewName) {
                zip.extractFile(header, dest, new net.lingala.zip4j.model.UnzipParameters(), newFileName);
            } else {
                zip.extractFile(header, dest);
            }
        } catch (ZipException e) {
            throw new RuntimeException("extractFile 失败: " + e.getMessage());
        }
    }

    /** 全部解压到 dest。 */
    @ScriptInterface
    public void extractAll(String dest) {
        try {
            open().extractAll(dest);
        } catch (ZipException e) {
            throw new RuntimeException("extractAll 失败: " + e.getMessage());
        }
    }

    @ScriptInterface
    public void extractAll(String dest, Scriptable options) {
        try {
            open().extractAll(dest, ZipSupport.unzipParameters(options));
        } catch (ZipException e) {
            throw new RuntimeException("extractAll 失败: " + e.getMessage());
        }
    }

    /** 单个条目的头信息（zip4j FileHeader）。 */
    @ScriptInterface
    public Object getFileHeader(String file) {
        try {
            net.lingala.zip4j.model.FileHeader header = open().getFileHeader(file);
            if (header == null) {
                throw new RuntimeException("压缩包里没有 " + file);
            }
            return header;
        } catch (ZipException e) {
            throw new RuntimeException("getFileHeader 失败: " + e.getMessage());
        }
    }

    /** 所有条目的头信息（zip4j FileHeader 数组）。 */
    @ScriptInterface
    @SuppressWarnings("unchecked")
    public Object[] getFileHeaders() {
        try {
            List<net.lingala.zip4j.model.FileHeader> headers = open().getFileHeaders();
            return headers == null ? new Object[0] : headers.toArray();
        } catch (ZipException e) {
            throw new RuntimeException("getFileHeaders 失败: " + e.getMessage());
        }
    }

    /** JS 数组 / 单个字符串 → File 列表（Rhino 传进来的是 NativeArray）。 */
    static ArrayList<java.io.File> toFileList(Object fileList) {
        ArrayList<java.io.File> files = new ArrayList<>();
        if (fileList == null) {
            return files;
        }
        if (fileList instanceof org.mozilla.javascript.Wrapper) {
            fileList = ((org.mozilla.javascript.Wrapper) fileList).unwrap();
        }
        if (fileList instanceof Scriptable) {
            Scriptable array = (Scriptable) fileList;
            Object length = org.mozilla.javascript.ScriptableObject.getProperty(array, "length");
            if (length instanceof Number) {
                int count = ((Number) length).intValue();
                for (int i = 0; i < count; i++) {
                    Object item = org.mozilla.javascript.ScriptableObject.getProperty(array, i);
                    if (item != null && item != Scriptable.NOT_FOUND) {
                        files.add(new java.io.File(String.valueOf(item)));
                    }
                }
                return files;
            }
        }
        files.add(new java.io.File(String.valueOf(fileList)));
        return files;
    }
}
