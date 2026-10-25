package com.jdkshen.buildtools.plugin.toolchain.core;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Locale;

/** Pure helpers for descriptor loading and component hash checks; no network/download. */
public final class ComponentStore {
    private ComponentStore() {}

    /** Reads a UTF-8 JSON object without performing any installation. */
    public static JSONObject readJson(InputStream input) throws Exception {
        try { return new JSONObject(readUtf8(input)); }
        finally { if (input != null) try { input.close(); } catch (IOException ignored) {} }
    }

    public static ToolchainDescriptor loadDescriptor(InputStream input) throws Exception {
        try {
            String text = readUtf8(input);
            return ToolchainDescriptor.parse(new JSONObject(text));
        } finally {
            if (input != null) try { input.close(); } catch (IOException ignored) {}
        }
    }

    public static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        InputStream input = new FileInputStream(file);
        try {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        } finally {
            input.close();
        }
        StringBuilder out = new StringBuilder();
        for (byte value : digest.digest()) out.append(String.format(Locale.US, "%02x", value));
        return out.toString();
    }

    /** Installed means hash matches; runnable still requires an actual smoke compile. */
    public static CapabilityState verifyFile(File file, ComponentSpec spec) {
        if (file == null || !file.isFile()) return new CapabilityState(CapabilityState.State.NOT_INSTALLED, "组件文件不存在", "");
        if (spec == null) return new CapabilityState(CapabilityState.State.INSTALLED_UNUSABLE, "缺少组件描述", "");
        try {
            String actual = sha256(file);
            if (!actual.equals(spec.getSha256())) {
                return new CapabilityState(CapabilityState.State.INSTALLED_UNUSABLE, "SHA-256 不匹配", "actual=" + actual);
            }
            return new CapabilityState(CapabilityState.State.RUNNABLE, "", "hash verified; smoke compile not run");
        } catch (Exception error) {
            return new CapabilityState(CapabilityState.State.INSTALLED_UNUSABLE, String.valueOf(error.getMessage()), "");
        }
    }

    private static String readUtf8(InputStream input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = input.read(buffer)) >= 0) if (read > 0) out.write(buffer, 0, read);
        return out.toString("UTF-8");
    }
}
