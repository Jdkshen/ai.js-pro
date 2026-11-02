package com.jdkshen.aijspro.model.script;

import com.stardust.autojs.script.AutoFileSource;
import com.stardust.autojs.script.JavaScriptFileSource;
import com.stardust.autojs.script.ScriptSource;
import com.stardust.autojs.script.StringScriptSource;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Browser execution types. Text resources never accidentally enter the JS parser. */
public final class WorkspaceScriptSource {
    private WorkspaceScriptSource() { }
    public static String extension(File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1);
    }
    public static boolean runnable(File file) {
        String type = extension(file);
        return "js".equals(type) || "mjs".equals(type) || "auto".equals(type) || "xml".equals(type);
    }
    public static ScriptSource create(File file) throws IOException {
        String type = extension(file);
        if ("auto".equals(type)) return new AutoFileSource(file);
        if ("xml".equals(type)) {
            final int limit = 1024 * 1024;
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (FileInputStream input = new FileInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (bytes.size() + read > limit) throw new IOException("XML 布局预览不能超过 1MiB");
                    bytes.write(buffer, 0, read);
                }
            }
            String xml = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
            if (xml.startsWith("\uFEFF")) xml = xml.substring(1);
            return new StringScriptSource(file.getName(), "\"ui\";\nui.layout(" + JSONObject.quote(xml) + ");\n");
        }
        if ("js".equals(type) || "mjs".equals(type)) return new JavaScriptFileSource(file);
        throw new IOException("此文件不能运行，请编辑或使用其他应用打开");
    }
}
