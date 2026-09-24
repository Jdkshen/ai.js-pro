package org.tensorflow.lite;

public final class TensorFlowLite {
    private static final String LIBNAME = "tensorflowlite_jni";
    private static final Throwable LOAD_LIBRARY_EXCEPTION;
    private static volatile boolean isInit = false;

    public static void init() {
        if (!isInit) {
            try {
                nativeRuntimeVersion();
                isInit = true;
            } catch (UnsatisfiedLinkError e) {
                Object e2 = e;
                Throwable th = LOAD_LIBRARY_EXCEPTION;
                if (th != null) {
                    e2 = th;
                }
                StringBuilder nativeInterpreterWrapper = new StringBuilder("Failed to load native TensorFlow Lite methods. Check that the correct native libraries are present, and, if using a custom native library, have been properly loaded via System.loadLibrary():\n  ");
                nativeInterpreterWrapper.append(e2);
                throw new UnsatisfiedLinkError(nativeInterpreterWrapper.toString());
            }
        }
    }

    static {
        Throwable e;
        try {
            System.loadLibrary(LIBNAME);
            e = null;
        } catch (UnsatisfiedLinkError e2) {
            e = e2;
        }
        LOAD_LIBRARY_EXCEPTION = e;
    }

    public static String runtimeVersion() {
        init();
        return nativeRuntimeVersion();
    }

    public static String schemaVersion() {
        init();
        return nativeSchemaVersion();
    }

    private TensorFlowLite() {
    }

    @Deprecated
    public static String version() {
        return schemaVersion();
    }

    private static native String nativeRuntimeVersion();

    private static native String nativeSchemaVersion();
}