package com.stardust.autojs.runtime.tool;

import java.util.Arrays;

/** CLI entry point for an isolated app_process, never run a compiler's main() in the host. */
public final class AndroidBuildTool {
    private AndroidBuildTool() {}

    public static void main(String[] args) {
        try {
            compile(args);
        } catch (Throwable error) {
            // app_process otherwise reports only "Killed" on its pipe and sends the useful
            // stack to logcat. Preserve compiler/linkage errors in the MCP terminal output.
            error.printStackTrace(System.err);
            System.err.flush();
            System.exit(1);
        }
    }

    private static void compile(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("Usage: AndroidBuildTool d8|smali <compiler arguments>");
            System.exit(2);
            return;
        }
        String[] compilerArgs = Arrays.copyOfRange(args, 1, args.length);
        if ("d8".equals(args[0])) {
            com.android.tools.r8.D8.main(compilerArgs);
        } else if ("smali".equals(args[0])) {
            org.jf.smali.Main.main(compilerArgs);
        } else {
            System.err.println("Unknown compiler: " + args[0]);
            System.exit(2);
        }
    }
}
