package org.tensorflow.lite.nnapi;

import org.tensorflow.lite.Delegate;
import org.tensorflow.lite.TensorFlowLite;

public class NnApiDelegate implements Delegate, AutoCloseable {
    private static final long INVALID_DELEGATE_HANDLE = 0;
    private long delegateHandle;

    public static final class Options {
        public static final int EXECUTION_PREFERENCE_FAST_SINGLE_ANSWER = 1;
        public static final int EXECUTION_PREFERENCE_LOW_POWER = 0;
        public static final int EXECUTION_PREFERENCE_SUSTAINED_SPEED = 2;
        public static final int EXECUTION_PREFERENCE_UNDEFINED = -1;
        private String acceleratorName = null;
        private Boolean allowFp16 = null;
        private String cacheDir = null;
        private int executionPreference = -1;
        private Integer maxDelegatedPartitions = null;
        private String modelToken = null;
        private Boolean useNnapiCpu = null;

        public Options setAcceleratorName(String str) {
            this.acceleratorName = str;
            return this;
        }

        public Options setAllowFp16(boolean z) {
            this.allowFp16 = Boolean.valueOf(z);
            return this;
        }

        public Options setCacheDir(String str) {
            this.cacheDir = str;
            return this;
        }

        public Options setExecutionPreference(int i) {
            this.executionPreference = i;
            return this;
        }

        public Options setMaxNumberOfDelegatedPartitions(int i) {
            this.maxDelegatedPartitions = Integer.valueOf(i);
            return this;
        }

        public Options setModelToken(String str) {
            this.modelToken = str;
            return this;
        }

        public Options setUseNnapiCpu(boolean z) {
            this.useNnapiCpu = Boolean.valueOf(z);
            return this;
        }
    }

    public NnApiDelegate(Options options) {
        TensorFlowLite.init();
        int access$000 = options.executionPreference;
        String access$100 = options.acceleratorName;
        String access$200 = options.cacheDir;
        String access$300 = options.modelToken;
        int intValue = options.maxDelegatedPartitions != null ? options.maxDelegatedPartitions.intValue() : -1;
        boolean z = true;
        boolean z2 = false;
        boolean z3 = options.useNnapiCpu != null;
        if (options.useNnapiCpu != null) {
            if (options.useNnapiCpu.booleanValue()) {
                z = false;
            }
        }
        if (options.allowFp16 != null) {
            z2 = options.allowFp16.booleanValue();
        }
        this.delegateHandle = createDelegate(access$000, access$100, access$200, access$300, intValue, z3, z, z2);
    }

    public void close() {
        long j = this.delegateHandle;
        if (j != 0) {
            deleteDelegate(j);
            this.delegateHandle = 0;
        }
    }

    private void checkNotClosed() {
        if (this.delegateHandle == 0) {
            throw new IllegalStateException("Should not access delegate after it has been closed.");
        }
    }

    public int getNnapiErrno() {
        checkNotClosed();
        return getNnapiErrno(this.delegateHandle);
    }

    public NnApiDelegate() {
        this(new Options());
    }

    public long getNativeHandle() {
        return this.delegateHandle;
    }

    public boolean hasErrors() {
        return getNnapiErrno(this.delegateHandle) != 0;
    }

    private static native long createDelegate(int i, String str, String str2, String str3, int i2, boolean z, boolean z2, boolean z3);

    private static native void deleteDelegate(long j);

    private static native int getNnapiErrno(long j);
}