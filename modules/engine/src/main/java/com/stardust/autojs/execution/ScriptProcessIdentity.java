package com.stardust.autojs.execution;

import android.app.Application;
import android.content.Context;
import android.os.Build;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;

/** Shared process identity for engine UI, floating services and application bootstrap. */
public final class ScriptProcessIdentity {
    private static volatile String cachedName;
    private ScriptProcessIdentity() { }

    public static String name() {
        String current = cachedName;
        if (current != null) return current;
        if (Build.VERSION.SDK_INT >= 28) current = Application.getProcessName();
        if (current == null || current.isEmpty()) {
            try (FileInputStream input = new FileInputStream("/proc/self/cmdline")) {
                byte[] bytes = new byte[512];
                int count = input.read(bytes), end = 0;
                while (end < count && bytes[end] != 0) end++;
                current = new String(bytes, 0, end, StandardCharsets.UTF_8);
            } catch (Exception ignored) { current = ""; }
        }
        cachedName = current;
        return current;
    }

    public static String suffix(Context context) {
        String process = name(), packageName = context.getPackageName();
        if (process.isEmpty() || process.equals(packageName)) return "";
        if (process.startsWith(packageName + ":")) return process.substring(packageName.length());
        throw new IllegalStateException("Unknown runtime process: " + process);
    }
}
