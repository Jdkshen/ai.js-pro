package com.jdkshen.aijspro.external;

import android.app.Application;
import android.content.Context;
import android.os.Build;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;

/** Process identity is shared by startup and global script control. No ActivityManager scan. */
public final class HostProcess {
    private static volatile String name;
    private HostProcess() {}

    public static String name() {
        String current = name;
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
        name = current;
        return current;
    }

    public static boolean isMain(Context context) { return isMain(context.getPackageName(), name()); }

    static boolean isMain(String packageName, String processName) {
        return processName == null || processName.isEmpty() || packageName.equals(processName);
    }
}
