package com.jdkshen.aijspro.model.script;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Creates a complete source archive before it is offered to another application. */
public final class ProjectArchive {
    private ProjectArchive() {}

    public static File create(File sourceDirectory, File cacheDirectory) throws IOException {
        return create(sourceDirectory, cacheDirectory, Long.MAX_VALUE);
    }

    /** Limits the completed ZIP, including headers and central directory, while streaming. */
    public static File create(File sourceDirectory, File cacheDirectory, long maxBytes) throws IOException {
        if (maxBytes <= 0) throw new IllegalArgumentException("Archive limit must be positive");
        File source = sourceDirectory.getCanonicalFile();
        if (!source.isDirectory()) throw new IOException("Project directory does not exist: " + source);
        File cache = cacheDirectory.getCanonicalFile();
        if (isWithin(source, cache)) throw new IOException("Archive cache must be outside the project");
        if (!cache.isDirectory() && !cache.mkdirs()) throw new IOException("Cannot create archive cache: " + cache);
        File exportDirectory = new File(cache, "project-" + UUID.randomUUID());
        if (!exportDirectory.mkdir()) throw new IOException("Cannot create archive directory: " + exportDirectory);
        String projectName = source.getName();
        if (projectName.isEmpty()) projectName = "project";
        File archive = new File(exportDirectory, projectName + ".zip");
        try {
            // Own the file separately: ZIP close/finish may itself fail at the limit.
            // The descriptor must still close before deleting a partial archive.
            try (OutputStream fileOutput = new FileOutputStream(archive);
                 ZipOutputStream output = new ZipOutputStream(new BufferedOutputStream(
                         new LimitedOutputStream(fileOutput, maxBytes)))) {
                addEntry(source, source, projectName, output, new HashSet<>(), new byte[8192]);
            }
            return archive;
        } catch (IOException | RuntimeException error) {
            archive.delete();
            exportDirectory.delete();
            throw error;
        }
    }

    public static final class ArchiveTooLargeException extends IOException {
        private static final long serialVersionUID = 1L;

        ArchiveTooLargeException(long limit) {
            super("Archive exceeds " + limit + " bytes");
        }
    }

    private static final class LimitedOutputStream extends FilterOutputStream {
        private final long limit;
        private long written;

        LimitedOutputStream(OutputStream output, long limit) {
            super(output);
            this.limit = limit;
        }

        @Override public void write(int value) throws IOException {
            if (written == limit) throw new ArchiveTooLargeException(limit);
            out.write(value);
            written++;
        }

        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            if (length > limit - written) throw new ArchiveTooLargeException(limit);
            out.write(bytes, offset, length);
            written += length;
        }
    }

    private static boolean isWithin(File root, File file) {
        String prefix = root.getPath();
        if (!prefix.endsWith(File.separator)) prefix += File.separator;
        return root.equals(file) || file.getPath().startsWith(prefix);
    }

    private static void addEntry(File root, File file, String name, ZipOutputStream output,
                                 Set<String> activeDirectories, byte[] buffer) throws IOException {
        File canonical = file.getCanonicalFile();
        if (!isWithin(root, canonical)) throw new IOException("Project entry points outside the project: " + file);
        if (file.isDirectory()) {
            if (!activeDirectories.add(canonical.getPath())) throw new IOException("Project directory contains a loop: " + file);
            try {
                File[] children = file.listFiles();
                if (children == null) throw new IOException("Cannot read project directory: " + file);
                if (children.length == 0) {
                    output.putNextEntry(new ZipEntry(name + "/"));
                    output.closeEntry();
                } else {
                    Arrays.sort(children, (left, right) -> left.getName().compareTo(right.getName()));
                    for (File child : children) addEntry(root, child, name + "/" + child.getName(), output,
                            activeDirectories, buffer);
                }
            } finally {
                activeDirectories.remove(canonical.getPath());
            }
        } else {
            if (!file.isFile()) throw new IOException("Project entry is not a regular file: " + file);
            ZipEntry entry = new ZipEntry(name);
            entry.setTime(file.lastModified());
            output.putNextEntry(entry);
            try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(file))) {
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            }
            output.closeEntry();
        }
    }
}
