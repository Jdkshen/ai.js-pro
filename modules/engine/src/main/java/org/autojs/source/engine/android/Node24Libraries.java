// SPDX-License-Identifier: MIT
package org.autojs.source.engine.android;

import android.content.Context;
import android.content.pm.PackageInfo;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.json.JSONObject;

/** Own, hash-pinned Node runtime; loaded only in the dedicated process. */
public final class Node24Libraries {
    private static boolean loaded;
    private static File directory;
    static synchronized File load(Context context) throws Exception {
        if (!SourceEngineBootstrap.isNodeProcess(context)) throw new IllegalStateException("Node24 requires :node24 process");
        if (loaded) return directory;
        directory=prepareFiles(context);
        String before=maps();
        if (before.contains("/libv8.so") || before.contains("/libnode.so"))
            throw new IllegalStateException("A legacy Node/V8 library was loaded before Node24");
        System.load(new File(directory,"libc++_shared.so").getPath());
        System.load(new File(directory,"libnode.so").getPath());
        System.setProperty("autojs.node24.library",new File(directory,"libajsnode24bridge.so").getPath());
        // Initialize the Java native owner after its path has been established.
        Class.forName("org.autojs.source.engine.Node24Engine");
        loaded=true;
        return directory;
    }
    public static synchronized File prepareFiles(Context context)throws Exception {
        // task-127：用户明确不要下载——只允许内置 assets；缺失直接中文报错，绝不发网络请求。
        String prefix = "source-engine-upgrade/node24/arm64-v8a/";
        try (InputStream probe = context.getAssets().open(prefix + "runtime.json")) {
            android.util.Log.i("C-NODE24", "runtime from bundled assets prefix=" + prefix);
            return prepareFromAssets(context, prefix, probe);
        } catch (IOException assetsMissing) {
            throw new IOException("此版本未内置 Node24 运行时（assets/source-engine-upgrade 缺失），"
                    + "请更新完整安装包；本机不会联网下载", assetsMissing);
        }
    }

    /** 旧版 APK（assets 里仍打包 runtime）的兼容路径。 */
    private static File prepareFromAssets(Context context, String prefix, InputStream runtimeInput) throws Exception {
        long started = System.nanoTime();
        JSONObject pin;
        try (InputStream input = runtimeInput) {
            pin = new JSONObject(new String(read(input), StandardCharsets.UTF_8));
        }
        Map<String, String> pins = Node24RuntimeFileCache.pinsFromJson(pin);
        long runtimeJsonMs = elapsedMs(started);
        String expectedCore = pins.get("libnode.so");
        File requestedDirectory = new File(context.getDir("node24-runtime", 0),
                expectedCore.substring(0, 16));

        long prepareStarted = System.nanoTime();
        Node24RuntimeFileCache.Result result = Node24RuntimeFileCache.prepare(
                requestedDirectory, appIdentity(context), pins,
                name -> context.getAssets().open(prefix + name));
        long prepareFilesMs = elapsedMs(prepareStarted);
        directory = result.directory;
        android.util.Log.i("C-NODE24", "runtime cache " + result.stats.summary()
                + " runtimeJsonMs=" + runtimeJsonMs
                + " prepareFilesMs=" + prepareFilesMs
                + " totalMs=" + elapsedMs(started));
        if (result.stats.stateCommitFailed) {
            android.util.Log.w("C-NODE24",
                    "runtime cache state not committed; next process will full-verify");
        }
        return directory;
    }

    /**
     * 安装身份：版本和 PackageManager 的安装/更新时间一起变化。缓存把完整 pins 和这个身份绑定，
     * 覆盖安装后第一个 Node 进程会完整校验全部三个文件，而不是只相信 libnode 的哈希目录名。
     */
    @SuppressWarnings("deprecation") // versionCode is the API 26-compatible install identity.
    private static String appIdentity(Context context) throws IOException {
        String packageName = context.getPackageName();
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(packageName, 0);
            return packageName
                    + ":" + info.versionCode
                    + ":" + String.valueOf(info.versionName)
                    + ":" + info.firstInstallTime
                    + ":" + info.lastUpdateTime;
        } catch (Exception error) {
            throw new IOException("Cannot read app install identity for Node runtime cache", error);
        }
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1000000L;
    }

    static String maps() throws IOException {
        try (InputStream input=new FileInputStream("/proc/self/maps")) { return new String(read(input),StandardCharsets.UTF_8); }
    }
    private static byte[] read(InputStream input) throws IOException {
        ByteArrayOutputStream output=new ByteArrayOutputStream();byte[] buffer=new byte[16384];int size;
        while ((size=input.read(buffer))!=-1) output.write(buffer,0,size);
        return output.toByteArray();
    }
}
