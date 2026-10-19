// SPDX-License-Identifier: MIT
package org.autojs.source.engine;

/** Isolated-process, one-run Node 24 embedder candidate. Not legacy Pluto ABI. */
public final class Node24Engine implements AutoCloseable {
    static {
        String path = System.getProperty("autojs.node24.library");
        if (path == null) System.loadLibrary("ajsnode24bridge");
        else System.load(path);
    }
    private volatile long handle = nativeCreate();
    public static final class Result {
        public final int exitCode;
        public final boolean cancelled;
        public final boolean timedOut;
        public final String error;
        public Result(int exitCode, boolean cancelled, boolean timedOut, String error) {
            this.exitCode = exitCode;
            this.cancelled = cancelled;
            this.timedOut = timedOut;
            this.error = error;
        }
        public boolean succeeded() { return exitCode == 0 && !cancelled && error.isEmpty(); }
    }
    public static String compiledVersion() { return nativeCompiledVersion(); }
    public interface Host { String invoke(String request) throws Exception; }
    /** Host-backed task; zero timeout runs until the event loop ends or cancelled. */
    public Result execute(String source,String filename,long timeoutMs,Host host) {
        if(filename==null||!filename.startsWith("/")||filename.indexOf('\0')>=0)throw new IllegalArgumentException("Absolute filename required");
        return nativeExecuteWithHost(handle,source,filename,timeoutMs,host);
    }
    /** Runs until the Node event loop ends; call from a worker thread. */
    public Result execute(String source, String absoluteFilename, long timeoutMs) {
        if (absoluteFilename == null || !absoluteFilename.startsWith("/") || absoluteFilename.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Use an absolute Android/Linux filename without NUL");
        return nativeExecute(handle, source, absoluteFilename, timeoutMs);
    }
    /** May be called from another thread, including while JS/I/O is running. */
    public void cancel() { nativeCancel(handle); }
    @Override public synchronized void close() {
        long current = handle;
        handle = 0;
        if (current != 0) nativeClose(current);
    }
    private static native long nativeCreate();
    private static native Result nativeExecute(long handle, String source, String filename, long timeoutMs);
    private static native Result nativeExecuteWithHost(long handle,String source,String filename,long timeoutMs,Host host);
    private static native void nativeCancel(long handle);
    private static native void nativeClose(long handle);
    private static native String nativeCompiledVersion();
}
