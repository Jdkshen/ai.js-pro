package com.jdkshen.aijspro.ui.editor;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import com.jdkshen.aijspro.external.open.ExternalFileIntents;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** URI documents keep their original URI as the save target; the cache is only editor backing. */
final class ExternalEditorDocument {
    static File read(Context context, Uri uri) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        String name = ExternalFileIntents.displayName(context, uri);
        File directory = new File(context.getCacheDir(), "external-editor/" + UUID.randomUUID());
        if (!directory.mkdirs()) throw new IOException("无法创建文件缓存");
        File file = new File(directory, safeName(name));
        try (InputStream input = resolver.openInputStream(uri)) {
            if (input == null) throw new IOException("无法读取外部文件");
            try (OutputStream output = new FileOutputStream(file)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            }
            return file;
        } catch (IOException | RuntimeException error) {
            file.delete();
            directory.delete();
            throw error;
        }
    }

    static void write(ContentResolver resolver, Uri uri, String text) throws IOException {
        // Explicit truncate mode matters when replacing a long document with shorter text.
        try (OutputStream output = resolver.openOutputStream(uri, "wt")) {
            if (output == null) throw new IOException("外部应用未提供写入通道");
            output.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    static String safeName(String name) {
        if (name == null) return "外部文件.txt";
        String clean = name.replaceAll("[\\\\/\\p{Cntrl}]", "_");
        return clean.isEmpty() || clean.equals(".") || clean.equals("..") ? "外部文件.txt" : clean;
    }

    private ExternalEditorDocument() { }
}
