package com.stardust.autojs.runtime.api;

import android.content.Context;

import com.stardust.autojs.annotation.ScriptInterface;

import org.mozilla.javascript.Scriptable;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Zip helpers: zip / unzip / list（旧接口）+ PDF §2.35 的 `$zip.zipDir/zipFile/zipFiles/unzip/open`。
 *
 * <p>文档原文：「zip 功能来自 zip4j，可阅读相关文档获取更多信息」，所以与文档同名的那一套
 * 直接转发给 zip4j（含加密压缩、AES 强度、压缩级别等选项）；`zip`/`unzip`/`list` 保留原有的
 * {@code java.util.zip} 实现，避免改动老脚本的行为。
 */
public class Zips {

    private final Context mContext;

    public Zips(Context context) {
        mContext = context;
    }

    @ScriptInterface
    public boolean zip(String srcDir, String destZip) {
        try (ZipOutputStream zos = new ZipOutputStream(
                new BufferedOutputStream(new FileOutputStream(destZip)))) {
            File root = new File(srcDir);
            if (!root.isDirectory()) {
                return false;
            }
            File[] children = root.listFiles();
            if (children != null) {
                for (File child : children) {
                    addEntry(root, child, zos);
                }
            }
            return true;
        } catch (Exception e) {
            throw new RuntimeException("zip failed: " + e.getMessage());
        }
    }

    private void addEntry(File root, File file, ZipOutputStream zos) throws Exception {
        String name = file.getAbsolutePath().substring(root.getAbsolutePath().length() + 1)
                .replace('\\', '/');
        if (file.isDirectory()) {
            zos.putNextEntry(new ZipEntry(name + "/"));
            zos.closeEntry();
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    addEntry(root, child, zos);
                }
            }
        } else {
            zos.putNextEntry(new ZipEntry(name));
            try (BufferedInputStream bis = new BufferedInputStream(new FileInputStream(file))) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = bis.read(buffer)) > 0) {
                    zos.write(buffer, 0, count);
                }
            }
            zos.closeEntry();
        }
    }

    /** 解压单个条目到 dest（可指定新文件名）。 */
    @ScriptInterface
    public boolean unzip(String zipFile, String dest) {
        return unzip(zipFile, dest, null);
    }

    @ScriptInterface
    public boolean unzip(String zipFile, String dest, Scriptable options) {
        File dir = new File(dest);
        if (!dir.exists() && !dir.mkdirs()) {
            return false;
        }
        try {
            net.lingala.zip4j.core.ZipFile zip = openZip(zipFile);
            applyPassword(zip, options);
            if (options == null) {
                zip.extractAll(dest);
            } else {
                zip.extractAll(dest, ZipSupport.unzipParameters(options));
            }
            return true;
        } catch (Exception e) {
            throw new RuntimeException("unzip failed: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // PDF §2.35.1：与文档同名的一套接口（文档原文「zip 功能来自 zip4j」）
    // ------------------------------------------------------------------

    /** `$zip.zipDir(dir, dest[, options])`：压缩文件夹（含子目录）。 */
    @ScriptInterface
    public boolean zipDir(String dir, String dest) {
        return zipDir(dir, dest, null);
    }

    @ScriptInterface
    public boolean zipDir(String dir, String dest, Scriptable options) {
        try {
            File folder = new File(dir);
            if (!folder.isDirectory()) {
                throw new RuntimeException("不是文件夹: " + dir);
            }
            net.lingala.zip4j.model.ZipParameters parameters = ZipSupport.zipParameters(options);
            net.lingala.zip4j.core.ZipFile zip = newZipFile(dest);
            zip.createZipFileFromFolder(folder, parameters, false, 0);
            return true;
        } catch (Exception e) {
            throw new RuntimeException("zipDir failed: " + e.getMessage());
        }
    }

    /** `$zip.zipFile(file, dest[, options])`：压缩单个文件。 */
    @ScriptInterface
    public boolean zipFile(String file, String dest) {
        return zipFile(file, dest, null);
    }

    @ScriptInterface
    public boolean zipFile(String file, String dest, Scriptable options) {
        try {
            File src = new File(file);
            if (!src.isFile()) {
                throw new RuntimeException("不是文件: " + file);
            }
            net.lingala.zip4j.model.ZipParameters parameters = ZipSupport.zipParameters(options);
            net.lingala.zip4j.core.ZipFile zip = newZipFile(dest);
            zip.createZipFile(src, parameters);
            return true;
        } catch (Exception e) {
            throw new RuntimeException("zipFile failed: " + e.getMessage());
        }
    }

    /** `$zip.zipFiles(fileList, dest[, options])`：压缩多个文件。 */
    @ScriptInterface
    public boolean zipFiles(Object fileList, String dest) {
        return zipFiles(fileList, dest, null);
    }

    @ScriptInterface
    public boolean zipFiles(Object fileList, String dest, Scriptable options) {
        try {
            java.util.ArrayList<File> files = ZipFile.toFileList(fileList);
            if (files.isEmpty()) {
                throw new RuntimeException("fileList 不能为空");
            }
            net.lingala.zip4j.model.ZipParameters parameters = ZipSupport.zipParameters(options);
            net.lingala.zip4j.core.ZipFile zip = newZipFile(dest);
            zip.createZipFile(files, parameters);
            return true;
        } catch (Exception e) {
            throw new RuntimeException("zipFiles failed: " + e.getMessage());
        }
    }

    /** `$zip.open(file)`：打开压缩包，返回文档里的 ZipFile 对象。 */
    @ScriptInterface
    public ZipFile open(String file) {
        return new ZipFile(file);
    }

    private static net.lingala.zip4j.core.ZipFile newZipFile(String dest) throws Exception {
        File destFile = new File(dest);
        File parent = destFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        if (destFile.exists() && !destFile.delete()) {
            throw new RuntimeException("无法覆盖已存在的压缩包: " + dest);
        }
        return new net.lingala.zip4j.core.ZipFile(destFile);
    }

    private static net.lingala.zip4j.core.ZipFile openZip(String path) throws Exception {
        return new net.lingala.zip4j.core.ZipFile(path);
    }

    private static void applyPassword(net.lingala.zip4j.core.ZipFile zip, Scriptable options)
            throws Exception {
        String password = ZipSupport.stringOption(options, "password");
        if (password != null && !password.isEmpty()) {
            zip.setPassword(password);
        }
    }

    @ScriptInterface
    public String[] list(String zipPath) {
        try (ZipInputStream zis = new ZipInputStream(
                new BufferedInputStream(new FileInputStream(zipPath)))) {
            List<String> names = new ArrayList<>();
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                names.add(entry.getName());
            }
            return names.toArray(new String[0]);
        } catch (Exception e) {
            throw new RuntimeException("list failed: " + e.getMessage());
        }
    }
}
