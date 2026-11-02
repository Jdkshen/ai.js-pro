package com.stardust.autojs.runtime.tool;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.PrintStream;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.channels.ClosedByInterruptException;
import org.json.JSONObject;

/** Public, versioned component sources; independent from APK update channels. */
public final class ToolchainSources {
    public static final String GITHUB = "https://github.com/Jdkshen/ai.js-pro/releases/download/toolchains-cpp-r29-v1/cpp-arm64-v1.json";
    public static final String PROXY = "https://gh-proxy.org/" + GITHUB;
    public static final String BACKUP_PROXY = "https://gh-proxy.com/" + GITHUB;
    private ToolchainSources() { }

    static URL installManifest(String[] args) throws Exception {
        if (args.length == 2 && "install".equals(args[0]) && "cpp".equals(args[1])) return null;
        if (args.length == 4 && "install".equals(args[0]) && "cpp".equals(args[1]) && "--manifest".equals(args[2]))
            return new URL(args[3]);
        throw new IllegalArgumentException("用法：aijs-toolchain install cpp [--manifest <清单URL>]");
    }

    public static JSONObject install(ToolchainStore store, URL explicit, String abi, int sdk, PrintStream out) throws Exception {
        // A custom/LAN source is deliberate: do not replace it with an unrelated public source.
        if (explicit != null) return store.install(explicit, abi, sdk, out);
        return attempt(new URL[]{new URL(PROXY), new URL(BACKUP_PROXY), new URL(GITHUB)},
                url -> store.install(url, abi, sdk, out), out);
    }

    interface Attempt<T> { T run(URL url) throws Exception; }

    static <T> T attempt(URL[] urls, Attempt<T> operation, PrintStream out) throws Exception {
        IOException previous = null;
        for (int i = 0; i < urls.length; i++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("编译环境下载已取消");
            if (out != null) out.println("[toolchain] 公网源 " + (i + 1) + "/" + urls.length + ": " + urls[i]);
            try { return operation.run(urls[i]); }
            catch (IOException error) {
                if (Thread.currentThread().isInterrupted() || error instanceof ClosedByInterruptException
                        || (error instanceof InterruptedIOException && !(error instanceof SocketTimeoutException))) throw error;
                if (previous != null) error.addSuppressed(previous);
                previous = error;
                if (out != null) out.println("[toolchain] 下载失败：" + error.getMessage()
                        + (i + 1 < urls.length ? "；正在切换下载源" : ""));
            }
        }
        throw new IOException("C/C++ 公网下载失败，请检查网络；也可用 --manifest 指定局域网或自托管清单。", previous);
    }
}
