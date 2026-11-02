package com.stardust.autojs.runtime.tool;

import android.os.Build;
import androidx.annotation.Keep;
import java.io.File;
import java.net.URL;

/** Optional compilers are downloaded by an isolated app_process, never Application.onCreate. */
@Keep
public final class ToolchainMain {
    private ToolchainMain() { }

    @Keep public static void main(String[] args) {
        try {
            String home = System.getenv("AIJS_TOOLCHAIN_HOME");
            if (home == null || home.isEmpty()) throw new IllegalStateException("请从 AI.js Pro 内置终端或 MCP app 后端调用");
            ToolchainStore store = new ToolchainStore(new File(home));
            String operation = args.length == 0 ? "help" : args[0];
            if ("status".equals(operation) || "list".equals(operation)) {
                if (args.length != 1) throw new IllegalArgumentException("用法：aijs-toolchain status");
                System.out.println(store.status().toString(2));
            } else if ("path".equals(operation)) {
                if (args.length != 1) throw new IllegalArgumentException("用法：aijs-toolchain path");
                File directory = store.activeDirectory();
                if (directory != null) System.out.println(directory.getCanonicalPath());
            } else if ("install".equals(operation)) {
                if (args.length != 4 || !"cpp".equals(args[1]) || !"--manifest".equals(args[2]))
                    throw new IllegalArgumentException("用法：aijs-toolchain install cpp --manifest <清单URL>");
                if (!"1".equals(System.getenv("AIJS_TOOLCHAIN_EXEC_ALLOWED")))
                    throw new IllegalStateException("当前应用 target/系统不支持从私有目录执行下载的编译器；需要另配工具插件");
                String abi = Build.SUPPORTED_ABIS.length == 0 ? "unknown" : Build.SUPPORTED_ABIS[0];
                System.out.println(store.install(new URL(args[3]), abi, Build.VERSION.SDK_INT, System.out).toString(2));
                System.out.println("编译环境已安装；执行 clang --version 或 clang++ --version 检查。");
            } else if ("remove".equals(operation)) {
                if (args.length != 2 || !"cpp".equals(args[1])) throw new IllegalArgumentException("用法：aijs-toolchain remove cpp");
                System.out.println(store.remove().toString(2));
            } else if ("help".equals(operation) || "--help".equals(operation)) {
                System.out.println("AI.js Pro 可选编译组件（不内嵌在主APK）\n"
                        + "aijs-toolchain status\n"
                        + "aijs-toolchain install cpp --manifest <清单URL>\n"
                        + "aijs-toolchain path\n"
                        + "aijs-toolchain remove cpp\n\n"
                        + "使用 app 后端安装/编译，无需 Shizuku。编译产物放 $HOME 等私有目录；共享存储通常不可直接执行。\n"
                        + "卸载组件前先结束使用该组件的编译任务。当前只管理 C/C++，不安装 JDK/Gradle 或完整 Android SDK。");
            } else throw new IllegalArgumentException("未知操作：" + operation + "；请运行 aijs-toolchain help");
        } catch (Throwable failure) {
            System.err.println("AI.js Pro toolchain: " + failure.getMessage());
            System.err.flush();
            // This entry point runs in its own app_process, never the app/engine VM.
            System.exit(1);
        }
    }
}
