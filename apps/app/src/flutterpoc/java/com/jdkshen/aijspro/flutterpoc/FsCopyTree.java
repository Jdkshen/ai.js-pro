package com.jdkshen.aijspro.flutterpoc;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Shared recursive file-tree copier for the Flutter POC file operations.
 *
 * <p>This class was extracted from {@code PocPackaging.copyTree} and
 * {@code PocWorkspace.copyTree}. The two historical implementations looked almost
 * identical but differed in edge-case policy, so the shared core takes a {@link Mode}:
 *
 * <ul>
 *   <li>{@link Mode#PACKAGING}: {@code target.mkdirs()} for directories, creates a
 *       target file's parent with {@code mkdirs()} first, silently treats an unreadable
 *       source directory as empty, and uses a 64 KiB copy buffer (the old
 *       PocPackaging behaviour);</li>
 *   <li>{@link Mode#WORKSPACE}: single-level {@code target.mkdir()} for directories,
 *       does not create a target file's parent (relies on the caller/recursive directory
 *       creation), throws when {@code source.listFiles()} returns null, and uses an
 *       8 KiB copy buffer (the old PocWorkspace behaviour).</li>
 * </ul>
 *
 * <p>Both modes preserve the original overwrite semantics: existing directories are
 * merged, existing files are truncated/replaced, and file attributes are not copied.
 * Symbolic links are handled exactly as {@link FileInputStream} and {@link File#isDirectory()}
 * handled them before (no extra NOFOLLOW/cycle checks).
 */
final class FsCopyTree {
    private enum Mode {
        PACKAGING(true, true, false, 64 * 1024),
        WORKSPACE(false, false, true, 8 * 1024);

        final boolean deepDirectoryCreate;
        final boolean createFileParent;
        final boolean failOnUnreadableDirectory;
        final int bufferSize;

        Mode(boolean deepDirectoryCreate, boolean createFileParent,
             boolean failOnUnreadableDirectory, int bufferSize) {
            this.deepDirectoryCreate = deepDirectoryCreate;
            this.createFileParent = createFileParent;
            this.failOnUnreadableDirectory = failOnUnreadableDirectory;
            this.bufferSize = bufferSize;
        }
    }

    private FsCopyTree() {
    }

    /** Equivalent to the old {@code PocPackaging.copyTree(File, File)}. */
    static void copy(File source, File target) throws IOException {
        copy(source, target, Mode.PACKAGING);
    }

    /** Equivalent to the old {@code PocWorkspace.copyTree(File, File)}. */
    static void copyWorkspace(File source, File target) throws IOException {
        copy(source, target, Mode.WORKSPACE);
    }

    private static void copy(File source, File target, Mode mode) throws IOException {
        if (source.isDirectory()) {
            if (mode.deepDirectoryCreate) {
                if (!target.isDirectory() && !target.mkdirs()) {
                    throw new IOException("无法创建目录：" + target);
                }
            } else if (!target.mkdir() && !target.isDirectory()) {
                throw new IOException("无法创建目录：" + target);
            }
            File[] children = source.listFiles();
            if (children == null) {
                if (mode.failOnUnreadableDirectory) {
                    throw new IOException("无法读取目录：" + source);
                }
                return;
            }
            for (File child : children) {
                copy(child, new File(target, child.getName()), mode);
            }
            return;
        }
        if (mode.createFileParent) {
            File parent = target.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("无法创建目录：" + parent);
            }
        }
        try (InputStream input = new FileInputStream(source);
             OutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[mode.bufferSize];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }
    }
}
