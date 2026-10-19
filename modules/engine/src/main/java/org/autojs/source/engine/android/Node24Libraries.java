// SPDX-License-Identifier: MIT
package org.autojs.source.engine.android;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
        JSONObject pin;
        try (InputStream input = runtimeInput) {
            pin = new JSONObject(new String(read(input), StandardCharsets.UTF_8));
        }
        String expectedCore = pin.getString("libnode.so");
        if (!expectedCore.matches("[0-9a-f]{64}")) throw new IOException("Invalid Node runtime pin");
        directory = new File(context.getDir("node24-runtime", 0), expectedCore.substring(0, 16));
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create Node runtime directory");
        try (RandomAccessFile lockFile = new RandomAccessFile(new File(directory, ".extract.lock"), "rw");
             java.nio.channels.FileLock lock = lockFile.getChannel().lock()) {
            for (String name : new String[]{"libc++_shared.so", "libnode.so", "libajsnode24bridge.so"}) {
                String expected = pin.getString(name);
                File target = new File(directory, name);
                if (!target.isFile() || !digest(target).equals(expected)) {
                    File temporary = new File(directory, name + ".part");
                    if (temporary.exists() && !temporary.delete()) throw new IOException("Cannot remove incomplete runtime file");
                    try (InputStream input = context.getAssets().open(prefix + name);
                         FileOutputStream output = new FileOutputStream(temporary)) {
                        byte[] buffer = new byte[65536];
                        int size;
                        while ((size = input.read(buffer)) != -1) output.write(buffer, 0, size);
                        output.getFD().sync();
                    }
                    if (!digest(temporary).equals(expected)) throw new IOException("Node runtime hash mismatch: " + name);
                    if (!temporary.setReadOnly()) throw new IOException("Cannot make runtime read-only");
                    if (!temporary.renameTo(target)) throw new IOException("Cannot commit runtime: " + name);
                }
            }
        }
        return directory;
    }

    static String maps() throws IOException {
        try (InputStream input=new FileInputStream("/proc/self/maps")) { return new String(read(input),StandardCharsets.UTF_8); }
    }
    private static byte[] read(InputStream input) throws IOException {
        ByteArrayOutputStream output=new ByteArrayOutputStream();byte[] buffer=new byte[16384];int size;
        while ((size=input.read(buffer))!=-1) output.write(buffer,0,size);
        return output.toByteArray();
    }
    private static String digest(File file) throws Exception {
        MessageDigest hash=MessageDigest.getInstance("SHA-256");
        try (InputStream input=new FileInputStream(file)) {
            byte[] buffer=new byte[65536];int size;while((size=input.read(buffer))!=-1)hash.update(buffer,0,size);
        }
        StringBuilder text=new StringBuilder();for(byte value:hash.digest())text.append(String.format(java.util.Locale.ROOT,"%02x",value&255));
        return text.toString();
    }
}
