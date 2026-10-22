package com.stardust.autojs.runtime.api;

import com.stardust.autojs.runtime.ScriptRuntime;
import com.stardust.pio.PFileInterface;
import com.stardust.pio.PFiles;
import com.stardust.pio.UncheckedIOException;
import com.stardust.util.Func1;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Created by Stardust on 2018/1/23.
 */

public class Files {

    /**
     * Auto.js Pro 的 `files.stat(path)` 返回对象（只读快照）。
     * Rhino 下 getter 会映射为 `stat.size` / `stat.path` / `stat.lastModified` 属性。
     */
    public static class FileStat {
        private final String path;
        private final long lastModified;
        private final long size;
        private final boolean readable;
        private final boolean writable;
        private final boolean executable;
        private final boolean hidden;

        public FileStat(String path, long lastModified, long size,
                        boolean readable, boolean writable, boolean executable, boolean hidden) {
            this.path = path;
            this.lastModified = lastModified;
            this.size = size;
            this.readable = readable;
            this.writable = writable;
            this.executable = executable;
            this.hidden = hidden;
        }

        public String getPath() {
            return path;
        }

        public long getLastModified() {
            return lastModified;
        }

        public long getSize() {
            return size;
        }

        public boolean isReadable() {
            return readable;
        }

        public boolean isWritable() {
            return writable;
        }

        public boolean isExecutable() {
            return executable;
        }

        public boolean isHidden() {
            return hidden;
        }
    }

    private final ScriptRuntime mRuntime;
    private final android.content.Context mContext;
    private final String mWorkingDirectory;

    public Files(ScriptRuntime runtime) {
        mRuntime = runtime;
        mContext = runtime == null ? null : runtime.getUiHandler().getContext();
        mWorkingDirectory = null;
    }

    /** The Node bridge shares the file implementation without creating a Rhino runtime. */
    public Files(android.content.Context context, String workingDirectory) {
        mRuntime = null;
        mContext = context;
        mWorkingDirectory = workingDirectory;
    }

    /**
     * Auto.js Pro 的 `files.observe(path)`：监听目录/文件变化。
     *
     * @return 支持 `on(event, callback)` / `stopWatching()` / `startWatching()` 的监听对象
     */
    public com.stardust.autojs.core.io.ScriptFileObserver observe(String path) {
        if (mRuntime == null) throw new IllegalStateException("File observation requires the engine event adapter");
        return new com.stardust.autojs.core.io.ScriptFileObserver(mRuntime, path(path));
    }

    // FIXME: 2018/10/16 is not correct in sub-directory?
    public String path(String relativePath) {
        String cwd = cwd();
        if (cwd == null || relativePath == null || relativePath.startsWith("/"))
            return relativePath;
        File f = new File(cwd);
        String[] paths = relativePath.split("/");
        for (String path : paths) {
            if (path.equals("."))
                continue;
            if (path.equals("..")) {
                f = f.getParentFile();
                continue;
            }
            f = new File(f, path);
        }
        String path = f.getPath();
        return relativePath.endsWith(File.separator) ? path + "/" : path;
    }

    public String cwd() {
        return mRuntime == null ? mWorkingDirectory : mRuntime.engines.myEngine().cwd();
    }

    /**
     * Auto.js Pro 的 `files.dirname(path)`：返回父目录（与 Pro 一致：不做 path() 归一化，
     * 相对路径时行为等价于 `new File(path).getParent()`）。
     */
    public String dirname(String path) {
        return new File(path).getParent();
    }

    /**
     * Auto.js Pro 的 `files.stat(path)`：路径经 `path()` 归一化后取 canonicalPath（失败为 null）。
     */
    public FileStat stat(String path) {
        String canonicalPath;
        File file = new File(path(path));
        try {
            canonicalPath = file.getCanonicalPath();
        } catch (IOException e) {
            canonicalPath = null;
        }
        return new FileStat(canonicalPath, file.lastModified(), file.length(),
                file.canRead(), file.canWrite(), file.canExecute(), file.isHidden());
    }

    public PFileInterface open(String path, String mode, String encoding, int bufferSize) {
        return PFiles.open(path(path), mode, encoding, bufferSize);
    }

    public Object open(String path, String mode, String encoding) {
        return PFiles.open(path(path), mode, encoding);
    }

    public Object open(String path, String mode) {
        return PFiles.open(path(path), mode);
    }

    public Object open(String path) {
        return PFiles.open(path(path));
    }

    public boolean create(String path) {
        return PFiles.create(path(path));
    }

    public boolean createIfNotExists(String path) {
        return PFiles.createIfNotExists(path(path));
    }

    public boolean createWithDirs(String path) {
        return PFiles.createWithDirs(path(path));
    }

    public boolean exists(String path) {
        return PFiles.exists(path(path));
    }

    public boolean ensureDir(String path) {
        return PFiles.ensureDir(path(path));
    }

    public String read(InputStream stream) { return read(stream, "UTF-8"); }

    public String read(InputStream stream, String encoding) {
        try {
            return new String(readBytes(stream), encoding);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            PFiles.closeSilently(stream);
        }
    }

    /** Read to EOF: available() is not the length of a content-provider or network stream. */
    public byte[] readBytes(InputStream stream) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                if (count > 0) output.write(buffer, 0, count);
                else {
                    int value = stream.read();
                    if (value < 0) break;
                    output.write(value);
                }
            }
            return output.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void write(OutputStream stream, String text) { PFiles.write(stream, text); }

    public void write(OutputStream stream, String text, String encoding) { PFiles.write(stream, text, encoding); }
    public String read(String path, String encoding) {
        return PFiles.read(path(path), encoding);
    }


    public String read(String path) {
        return PFiles.read(path(path));
    }

    public String readAssets(String path, String encoding) {
        try {
            return PFiles.read(mContext.getAssets().open(path), encoding);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String readAssets(String path) {
        return readAssets(path, "UTF-8");
    }

    public byte[] readBytes(String path) {
        return PFiles.readBytes(path(path));
    }

    public void write(String path, String text) {
        PFiles.write(path(path), text);
    }

    public void write(String path, String text, String encoding) {
        PFiles.write(path(path), text, encoding);
    }

    public void append(String path, String text) {
        PFiles.append(path(path), text);
    }

    public void append(String path, String text, String encoding) {
        PFiles.append(path(path), text, encoding);
    }

    public void appendBytes(String path, byte[] bytes) {
        PFiles.appendBytes(path(path), bytes);
    }

    public void writeBytes(String path, byte[] bytes) {
        PFiles.writeBytes(path(path), bytes);
    }

    public boolean copy(String pathFrom, String pathTo) {
        return PFiles.copy(path(pathFrom), path(pathTo));
    }

    public boolean renameWithoutExtension(String path, String newName) {
        return PFiles.renameWithoutExtension(path(path), newName);
    }

    public boolean rename(String path, String newName) {
        return PFiles.rename(path(path), newName);
    }

    public boolean move(String path, String newPath) {
        return PFiles.move(path(path), newPath);
    }

    public String getExtension(String fileName) {
        return PFiles.getExtension(fileName);
    }

    public String getName(String filePath) {
        return PFiles.getName(filePath);
    }

    public String getNameWithoutExtension(String filePath) {
        return PFiles.getNameWithoutExtension(filePath);
    }

    public boolean remove(String path) {
        return PFiles.remove(path(path));
    }

    public boolean removeDir(String path) {
        return PFiles.removeDir(path(path));
    }

    public String getSdcardPath() {
        return PFiles.getSdcardPath();
    }

    public String[] listDir(String path) {
        return PFiles.listDir(path(path));
    }

    public String[] listDir(String path, Func1<String, Boolean> filter) {
        return PFiles.listDir(path(path), filter);
    }

    public boolean isFile(String path) {
        return PFiles.isFile(path(path));
    }

    public boolean isDir(String path) {
        return PFiles.isDir(path(path));
    }

    public boolean isEmptyDir(String path) {
        return PFiles.isEmptyDir(path(path));
    }

    public static String join(String parent, String... child) {
        return PFiles.join(parent, child);
    }

    public String getHumanReadableSize(long bytes) {
        return PFiles.getHumanReadableSize(bytes);
    }

    public String getSimplifiedPath(String path) {
        return PFiles.getSimplifiedPath(path);
    }

}
