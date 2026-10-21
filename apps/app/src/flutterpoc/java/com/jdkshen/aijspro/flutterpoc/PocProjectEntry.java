package com.jdkshen.aijspro.flutterpoc;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;

/** Resolves the IDE run entry without changing the project's APK build entry. */
final class PocProjectEntry {
    private PocProjectEntry() { }

    static File resolve(File directory, JSONObject config) throws IOException {
        File project = directory.getCanonicalFile();
        Object value = config.opt("main");
        String main = value == null || value == JSONObject.NULL ? "main.js" : string(value, "main");
        File configured = new File(project, main).getCanonicalFile();
        if (configured.isFile()) return configured;

        // Pro Node frontend projects point main at a bundle produced by their APK
        // preparation hook. Before that build, the same-named root .node.js is the
        // development launcher (npm install/start + LoadingActivity + WebActivity).
        // Do not guess main.js or another filename for a broken project config.
        JSONObject scripts = config.optJSONObject("scripts");
        Object build = scripts == null ? null : scripts.opt("build-apk-pre-prepare");
        boolean hasBuildHook = build instanceof String && !((String) build).trim().isEmpty();
        JSONObject info = config.optJSONObject("buildInfo");
        boolean release = info != null && (Boolean.TRUE.equals(info.opt("isRelease"))
                || Boolean.TRUE.equals(info.opt("is_release")) || Boolean.TRUE.equals(info.opt("release")));
        if (!release && hasBuildHook && configured.getName().endsWith(".node.js")
                && configured.getPath().startsWith(project.getPath() + File.separator)
                && !project.equals(configured.getParentFile())
                && new File(project, "package.json").isFile()) {
            File development = new File(project, configured.getName()).getCanonicalFile();
            if (project.equals(development.getParentFile()) && development.isFile()) return development;
        }
        throw new IOException("项目入口文件不存在：" + main);
    }

    private static String string(Object value, String field) throws IOException {
        if (!(value instanceof String) || ((String) value).trim().isEmpty())
            throw new IOException("project.json 的 " + field + " 必须是非空字符串");
        return (String) value;
    }
}
