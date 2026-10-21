package org.autojs.source.engine.android;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.json.JSONObject;

/** Lazily extracts the bundled Node/npm shell tools. No runtime download. */
public final class Node24Tools {
    private static final String ASSETS = "node24/tools/";
    private static String prepared;
    private Node24Tools() { }
    public static synchronized String prepare(Context context) throws Exception {
        if (prepared != null) return prepared;
        JSONObject pin;
        try (InputStream input = context.getAssets().open(ASSETS + "tools.json")) {
            pin = new JSONObject(new String(read(input), StandardCharsets.UTF_8));
        }
        // CLI tools only need pinned files; they must never load a JS runtime in
        // the caller (MCP's compile-only check runs outside the Node pool).
        File libraryDir = Node24Libraries.prepareFiles(context);
        if (!libraryDir.getName().equals(pin.getString("libnode.so").substring(0,16)))
            throw new IOException("Node CLI/library version mismatch");
        File root = new File(context.getDir("node24-tools",0), pin.getString("npm.zip").substring(0,16));
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Cannot create Node tools directory");
        try (RandomAccessFile lockFile = new RandomAccessFile(new File(root,".lock"),"rw");
             java.nio.channels.FileLock lock = lockFile.getChannel().lock()) {
            File node = new File(libraryDir,"node");
            copyPinned(context,"node",node,pin.getString("node"));
            if (!node.setExecutable(true,true)) throw new IOException("Cannot make Node CLI executable");
            File zip = new File(root,"npm.zip");
            copyPinned(context,"npm.zip",zip,pin.getString("npm.zip"));
            File ready = new File(root,".ready");
            if (!ready.isFile()) {
                try (ZipInputStream input = new ZipInputStream(new FileInputStream(zip))) {
                    ZipEntry entry;
                    while ((entry=input.getNextEntry()) != null) {
                        File file = new File(root,entry.getName()).getCanonicalFile();
                        if (!file.getPath().startsWith(root.getCanonicalPath()+File.separator))
                            throw new IOException("Invalid npm archive entry");
                        if (entry.isDirectory()) { file.mkdirs(); continue; }
                        file.getParentFile().mkdirs();
                        try (FileOutputStream output = new FileOutputStream(file)) { copy(input,output); }
                    }
                }
                if (!new File(root,"npm/bin/npm-cli.js").isFile()) throw new IOException("npm entry missing");
                ready.createNewFile();
            }
            prepared = new JSONObject().put("node",node.getAbsolutePath())
                    .put("root",root.getAbsolutePath()).put("npm",new File(root,"npm/bin/npm-cli.js").getPath())
                    .put("cache",new File(context.getCacheDir(),"npm").getPath()).toString();
            return prepared;
        }
    }
    private static void copyPinned(Context context,String name,File file,String expected) throws Exception {
        if (file.isFile() && digest(file).equals(expected)) return;
        File part = new File(file.getParentFile(),file.getName()+".part");
        try (InputStream input=context.getAssets().open(ASSETS+name); FileOutputStream output=new FileOutputStream(part)) {
            copy(input,output); output.getFD().sync();
        }
        if (!digest(part).equals(expected)) throw new IOException("Node tools hash mismatch: "+name);
        if (!part.renameTo(file)) throw new IOException("Cannot commit Node tool: "+name);
    }
    private static void copy(InputStream input,OutputStream output) throws IOException {
        byte[] buffer=new byte[32768]; int n;
        while ((n=input.read(buffer))!=-1) output.write(buffer,0,n);
    }
    private static byte[] read(InputStream input) throws IOException {
        ByteArrayOutputStream output=new ByteArrayOutputStream(); copy(input,output); return output.toByteArray();
    }
    private static String digest(File file) throws Exception {
        MessageDigest md=MessageDigest.getInstance("SHA-256");
        try (InputStream input=new FileInputStream(file)) { byte[] buffer=new byte[32768]; int n;
            while ((n=input.read(buffer))!=-1) md.update(buffer,0,n); }
        StringBuilder out=new StringBuilder(); for (byte value:md.digest()) out.append(String.format(java.util.Locale.ROOT,"%02x",value&255));
        return out.toString();
    }
}
