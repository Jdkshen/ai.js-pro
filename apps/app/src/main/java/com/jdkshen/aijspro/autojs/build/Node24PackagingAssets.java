package com.jdkshen.aijspro.autojs.build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.json.JSONObject;

/** Copies the matching Node runtime and scripts from the host into a packaging workspace. */
final class Node24PackagingAssets {
    private static final String MODULES = "assets/node24/";
    private static final String RUNTIME = "assets/source-engine-upgrade/node24/arm64-v8a/";
    private static final String[] REQUIRED = {
            MODULES + "prelude.js", MODULES + "ui.js", RUNTIME + "runtime.json",
            RUNTIME + "libnode.so", RUNTIME + "libc++_shared.so", RUNTIME + "libajsnode24bridge.so"
    };

    private Node24PackagingAssets() {}

    static boolean requiresRuntime(boolean nodeEntry, JSONObject project) {
        if (nodeEntry || isNodeProject(project)) return true;
        if (project == null) return false;
        JSONObject features = project.optJSONObject("features");
        if (features == null) return false;
        Object value = features.opt("nodejs");
        return Boolean.TRUE.equals(value) || "enabled".equalsIgnoreCase(String.valueOf(value))
                || "auto".equalsIgnoreCase(String.valueOf(value));
    }

    /**
     * 工程是否明确是 Node 工程（{@code type=node/nodejs}）。
     *
     * <p>与 {@link #requiresRuntime} 的区别：后者还包含「只是开了 nodejs 特性位」的工程。
     * 只有本方法为真（或入口脚本本身就是 Node）时，{@code ApkBuilder} 才会把 Node 运行
     * 资产不完整当成打包错误；只开特性位的工程按旧行为只告警，避免把本来能出的包打死。
     */
    static boolean isNodeProject(JSONObject project) {
        if (project == null) return false;
        String type = project.optString("type", "");
        return "node".equalsIgnoreCase(type) || "nodejs".equalsIgnoreCase(type);
    }

    static int copyFromHost(File apk, File workspace) throws IOException {
        String root = workspace.getCanonicalPath() + File.separator;
        int copied = 0;
        try (ZipFile zip = new ZipFile(apk)) {
            for (String name : REQUIRED) {
                ZipEntry entry = zip.getEntry(name);
                if (entry == null || entry.isDirectory() || entry.getSize() == 0) {
                    throw new IOException("宿主缺少 Node 运行时文件：" + name);
                }
            }
            Enumeration<? extends ZipEntry> entries = zip.entries();
            byte[] buffer = new byte[64 * 1024];
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !(name.startsWith(MODULES) || name.startsWith(RUNTIME))) continue;
                if (name.indexOf('\\') >= 0 || name.contains("/../") || name.endsWith("/..")) {
                    throw new IOException("Node 资源路径无效：" + name);
                }
                File target = new File(workspace, name).getCanonicalFile();
                if (!target.getPath().startsWith(root)) throw new IOException("Node 资源路径无效：" + name);
                File parent = target.getParentFile();
                if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("无法创建 Node 资源目录：" + parent);
                try (InputStream input = zip.getInputStream(entry); FileOutputStream output = new FileOutputStream(target)) {
                    int count;
                    while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                }
                copied++;
            }
        }
        return copied;
    }
}
