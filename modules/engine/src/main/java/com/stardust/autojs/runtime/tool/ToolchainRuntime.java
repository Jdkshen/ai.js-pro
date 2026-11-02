package com.stardust.autojs.runtime.tool;

import android.content.Context;
import android.os.Build;
import com.stardust.autojs.runtime.api.shizuku.ShizukuCommands;
import org.json.JSONObject;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/** Small command/environment adapter; no compiler payload or startup download in the APK. */
public final class ToolchainRuntime {
    private ToolchainRuntime() { }
    public static File root(Context context) {
        File directory = new File(context.getNoBackupFilesDir(), "compiler-toolchains");
        // Older Android versions expose app data via an OS-owned /data/user alias.
        // Normalize that alias before the installer checks its private root for links.
        try { return directory.getCanonicalFile(); }
        catch (java.io.IOException error) { throw new IllegalStateException("无法定位应用编译目录", error); }
    }
    public static boolean canExecuteDownloaded(Context context) {
        return Build.VERSION.SDK_INT < 29 || context.getApplicationInfo().targetSdkVersion < 29;
    }
    public static Map<String,String> environment(Context context) {
        Map<String,String> env = new LinkedHashMap<>();
        env.put("AIJS_TOOLCHAIN_HOME", root(context).getAbsolutePath());
        env.put("AIJS_TOOLCHAIN_MAIN", ToolchainMain.class.getName());
        env.put("AIJS_TOOLCHAIN_EXEC_ALLOWED", canExecuteDownloaded(context) ? "1" : "0");
        try {
            File directory = new ToolchainStore(root(context)).activeDirectory();
            if (directory != null && canExecuteDownloaded(context)) {
                String path = System.getenv("PATH");
                env.put("PATH", new File(directory,"bin").getAbsolutePath() + ":" + (path == null ? "/system/bin" : path));
                env.put("AIJS_CPP_ROOT", directory.getAbsolutePath());
                env.put("CC", "clang"); env.put("CXX", "clang++");
            }
        } catch (Exception ignored) {
            // Terminal must remain usable when a component's metadata is damaged; status reports it.
        }
        return env;
    }
    public static JSONObject status(Context context) {
        JSONObject result;
        try { result = new ToolchainStore(root(context)).status(); }
        catch (Exception error) { result = new JSONObject(); try { result.put("error", error.getMessage()); } catch (Exception ignored) { } }
        try {
            result.put("home", root(context).getAbsolutePath());
            result.put("canExecuteDownloaded", canExecuteDownloaded(context));
            result.put("abi", Build.SUPPORTED_ABIS.length == 0 ? "unknown" : Build.SUPPORTED_ABIS[0]);
            result.put("sdkInt", Build.VERSION.SDK_INT);
            result.put("statusCommand", "aijs-toolchain status");
            result.put("installCommand", "aijs-toolchain install cpp --manifest <清单URL>");
            result.put("note", "仅按需下载C/C++组件；安装不代表已试编译。使用app后端，编译器及可执行产物放应用私有目录。");
        } catch (Exception ignored) { }
        return result;
    }
    public static String wrapCommand(Context context, String command) {
        String entry = "AIJS_TOOLCHAIN_HOME=" + ShizukuCommands.quote(root(context).getAbsolutePath())
                + " AIJS_TOOLCHAIN_EXEC_ALLOWED=" + (canExecuteDownloaded(context) ? "1" : "0")
                + " CLASSPATH=" + ShizukuCommands.quote(context.getApplicationInfo().sourceDir)
                + " /system/bin/app_process /system/bin --nice-name=aijs-toolchain " + ToolchainMain.class.getName();
        // Refresh PATH after a successful install so `install ... && clang ...` works in one job.
        return "aijs-toolchain() { " + entry + " \"$@\"; _aijs_tc_result=$?; "
                + "if [ \"$_aijs_tc_result\" -eq 0 ]; then _aijs_tc_path=\"$(" + entry + " path)\"; "
                + "if [ -n \"$_aijs_tc_path\" ]; then export AIJS_CPP_ROOT=\"$_aijs_tc_path\"; "
                + "export PATH=\"$_aijs_tc_path/bin:$PATH\"; export CC=clang CXX=clang++; fi; fi; return \"$_aijs_tc_result\"; };\n" + command;
    }
}
