package org.autojs.source.engine.android;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Large entry bundles travel through the app's private cache, not Binder strings. */
final class Node24SourceTransport {
    static final int MAX_BYTES = 16 * 1024 * 1024;
    private Node24SourceTransport() { }
    static File write(File cache, String source) throws IOException {
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IOException("Node script exceeds 16 MiB");
        File root = new File(cache, "node24-requests");
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Cannot create Node request directory");
        File file = File.createTempFile("entry-", ".js", root);
        try { Files.write(file.toPath(), bytes); return file; }
        catch (IOException failure) { file.delete(); throw failure; }
    }
    static String read(File cache, String path) throws IOException {
        File root = new File(cache, "node24-requests").getCanonicalFile();
        File file = new File(path).getCanonicalFile();
        if (!root.equals(file.getParentFile()) || !file.getName().startsWith("entry-")
                || !file.getName().endsWith(".js") || !file.isFile())
            throw new IOException("Invalid Node source file");
        try {
            if (file.length() > MAX_BYTES) throw new IOException("Node script exceeds 16 MiB");
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } finally { file.delete(); }
    }
}
