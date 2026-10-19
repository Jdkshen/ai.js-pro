package com.stardust.pio;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

/** API 26+ directory listing without UnixFileSystem.list0's NewStringUTF conversion. */
final class SafeDirectoryLister {
    private SafeDirectoryLister() { }

    static String[] list(File directory) {
        ArrayList<String> names = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory.toPath())) {
            for (Path entry : entries) {
                Path rawName = entry.getFileName();
                String name = rawName.toString();
                // NIO decodes malformed bytes with replacements. A String-based API cannot
                // address those paths faithfully, so never return a misleading replacement.
                if (rawName.equals(rawName.getFileSystem().getPath(name))) {
                    names.add(name);
                }
            }
        } catch (IOException | DirectoryIteratorException | SecurityException error) {
            // Preserve PFiles.listDir's empty-array behavior for unreadable/non-directories.
            return new String[0];
        }
        return names.toArray(new String[0]);
    }
}
