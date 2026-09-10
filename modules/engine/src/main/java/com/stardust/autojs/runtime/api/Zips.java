package com.stardust.autojs.runtime.api;

import android.content.Context;

import com.stardust.autojs.annotation.ScriptInterface;

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
 * Zip helpers: zip / unzip / list.
 * Aligns with the Auto.js Pro "Zip压缩与解压" sample category.
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

    @ScriptInterface
    public boolean unzip(String zipPath, String destDir) {
        try {
            File dir = new File(destDir);
            if (!dir.exists() && !dir.mkdirs()) {
                return false;
            }
            try (ZipInputStream zis = new ZipInputStream(
                    new BufferedInputStream(new FileInputStream(zipPath)))) {
                ZipEntry entry;
                byte[] buffer = new byte[8192];
                while ((entry = zis.getNextEntry()) != null) {
                    File outFile = new File(dir, entry.getName());
                    if (entry.isDirectory()) {
                        if (!outFile.exists()) {
                            outFile.mkdirs();
                        }
                        continue;
                    }
                    File parent = outFile.getParentFile();
                    if (parent != null && !parent.exists()) {
                        parent.mkdirs();
                    }
                    try (BufferedOutputStream bos = new BufferedOutputStream(
                            new FileOutputStream(outFile))) {
                        int count;
                        while ((count = zis.read(buffer)) > 0) {
                            bos.write(buffer, 0, count);
                        }
                    }
                }
            }
            return true;
        } catch (Exception e) {
            throw new RuntimeException("unzip failed: " + e.getMessage());
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
