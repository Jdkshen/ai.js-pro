package com.stardust.autojs.runtime.api.shizuku;

import android.content.Context;
import java.util.LinkedHashMap;
import java.util.Map;

/** Command-mode rish/i entrypoints use immutable classes in the installed APK, not writable DEX. */
public final class RishRuntime {
    private RishRuntime() { }
    public static Map<String,String> environment(Context context) {
        Map<String,String> values = new LinkedHashMap<>();
        values.put("RISH_APPLICATION_ID", context.getPackageName());
        values.put("AIJS_RISH_MAIN", RishCommandMain.class.getName());
        try { values.put("AIJS_RISH_SOCKET", RishGateway.ensure(context)); }
        catch (java.io.IOException failure) { throw new IllegalStateException("rish本地命令通道初始化失败",failure); }
        return values;
    }
    public static String wrapCommand(Context context, String command) {
        final String socket;
        try { socket = RishGateway.ensure(context); }
        catch (java.io.IOException failure) { throw new IllegalStateException("rish本地命令通道初始化失败",failure); }
        String entry = "RISH_APPLICATION_ID=" + ShizukuCommands.quote(context.getPackageName())
                + " AIJS_RISH_SOCKET=" + ShizukuCommands.quote(socket)
                + " CLASSPATH=" + ShizukuCommands.quote(context.getApplicationInfo().sourceDir)
                + " /system/bin/app_process /system/bin --nice-name=aijs-rish " + RishCommandMain.class.getName();
        return "rish() { " + entry + " \"$@\"; };\ni() { " + entry + " --install \"$@\"; };\n" + command;
    }
}
