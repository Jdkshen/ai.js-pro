package com.jdkshen.aijspro.model.script;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
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
            try (ZipOutputStream output = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(archive)))) {
                addEntry(source, source, projectName, output, new HashSet<>(), new byte[8192]);
            }
            return archive;
        } catch (IOException | RuntimeException error) {
            archive.delete();
            exportDirectory.delete();
            throw error;
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
