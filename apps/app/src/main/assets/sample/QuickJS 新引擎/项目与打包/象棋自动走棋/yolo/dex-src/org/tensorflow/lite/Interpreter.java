package org.tensorflow.lite;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class Interpreter implements InterpreterApi {
    String[] signatureNameList;
    NativeInterpreterWrapper wrapper;

    public static class Options extends org.tensorflow.lite.InterpreterApi.Options {
        Boolean allowBufferHandleOutput;
        Boolean allowFp16PrecisionForFp32;
        final List<Delegate> delegates = new ArrayList();
        Boolean useXNNPACK;

        public Options addDelegate(Delegate delegate) {
            this.delegates.add(delegate);
            return this;
        }

        public Options setAllowBufferHandleOutput(boolean z) {
            this.allowBufferHandleOutput = Boolean.valueOf(z);
            return this;
        }

        @Deprecated
        public Options setAllowFp16PrecisionForFp32(boolean z) {
            this.allowFp16PrecisionForFp32 = Boolean.valueOf(z);
            return this;
        }

        public org.tensorflow.lite.InterpreterApi.Options setCancellable(boolean z) {
            super.setCancellable(z);
            return this;
        }

        public org.tensorflow.lite.InterpreterApi.Options setNumThreads(int i) {
            super.setNumThreads(i);
            return this;
        }

        public org.tensorflow.lite.InterpreterApi.Options setUseNNAPI(boolean z) {
            super.setUseNNAPI(z);
            return this;
        }

        public Options setUseXNNPACK(boolean z) {
            this.useXNNPACK = Boolean.valueOf(z);
            return this;
        }
    }

    public Tensor getInputTensorFromSignature(String str, String str2) {
        checkNotClosed();
        if (str2 == null) {
            String[] strArr = this.signatureNameList;
            if (strArr.length == 1) {
                str2 = strArr[0];
            }
        }
        if (str2 != null) {
            return this.wrapper.getInputTensor(str, str2);
        }
        StringBuilder nativeInterpreterWrapper = new StringBuilder("Input error: SignatureDef methodName should not be null. null is only allowed if the model has a single Signature. Available Signatures: ");
        nativeInterpreterWrapper.append(Arrays.toString(this.signatureNameList));
        throw new IllegalArgumentException(nativeInterpreterWrapper.toString());
    }

    public Tensor getOutputTensorFromSignature(String str, String str2) {
        checkNotClosed();
        if (str2 == null) {
            String[] strArr = this.signatureNameList;
            if (strArr.length == 1) {
                str2 = strArr[0];
            }
        }
        if (str2 != null) {
            return this.wrapper.getOutputTensor(str, str2);
        }
        StringBuilder nativeInterpreterWrapper = new StringBuilder("Input error: SignatureDef methodName should not be null. null is only allowed if the model has a single Signature. Available Signatures: ");
        nativeInterpreterWrapper.append(Arrays.toString(this.signatureNameList));
        throw new IllegalArgumentException(nativeInterpreterWrapper.toString());
    }

    public void runSignature(Map<String, Object> map, Map<String, Object> map2, String str) {
        checkNotClosed();
        if (str == null) {
            String[] strArr = this.signatureNameList;
            if (strArr.length == 1) {
                str = strArr[0];
            }
        }
        if (str != null) {
            this.wrapper.runSignature(map, map2, str);
            return;
        }
        StringBuilder nativeInterpreterWrapper = new StringBuilder("Input error: SignatureDef methodName should not be null. null is only allowed if the model has a single Signature. Available Signatures: ");
        nativeInterpreterWrapper.append(Arrays.toString(this.signatureNameList));
        throw new IllegalArgumentException(nativeInterpreterWrapper.toString());
    }

    public Interpreter(File file, Options options) {
        this.wrapper = new NativeInterpreterWrapper(file.getAbsolutePath(), options);
        this.signatureNameList = getSignatureDefNames();
    }

    public Interpreter(ByteBuffer byteBuffer, Options options) {
        this.wrapper = new NativeInterpreterWrapper(byteBuffer, options);
        this.signatureNameList = getSignatureDefNames();
    }

    public void close() {
        NativeInterpreterWrapper nativeInterpreterWrapper = this.wrapper;
        if (nativeInterpreterWrapper != null) {
            nativeInterpreterWrapper.close();
            this.wrapper = null;
        }
    }

    protected void finalize() throws Throwable {
        try {
            close();
        } finally {
            super.finalize();
        }
    }

    public void run(Object obj, Object obj2) {
        Object[] objArr = new Object[]{obj};
        HashMap hashMap = new HashMap();
        hashMap.put(Integer.valueOf(0), obj2);
        runForMultipleInputsOutputs(objArr, hashMap);
    }

    private void checkNotClosed() {
        if (this.wrapper == null) {
            throw new IllegalStateException("Internal error: The Interpreter has already been closed.");
        }
    }

    public void allocateTensors() {
        checkNotClosed();
        this.wrapper.allocateTensors();
    }

    int getExecutionPlanLength() {
        checkNotClosed();
        return this.wrapper.getExecutionPlanLength();
    }

    public int getInputIndex(String str) {
        checkNotClosed();
        return this.wrapper.getInputIndex(str);
    }

    public Tensor getInputTensor(int i) {
        checkNotClosed();
        return this.wrapper.getInputTensor(i);
    }

    public int getInputTensorCount() {
        checkNotClosed();
        return this.wrapper.getInputTensorCount();
    }

    public Long getLastNativeInferenceDurationNanoseconds() {
        checkNotClosed();
        return this.wrapper.getLastNativeInferenceDurationNanoseconds();
    }

    public int getOutputIndex(String str) {
        checkNotClosed();
        return this.wrapper.getOutputIndex(str);
    }

    public Tensor getOutputTensor(int i) {
        checkNotClosed();
        return this.wrapper.getOutputTensor(i);
    }

    public int getOutputTensorCount() {
        checkNotClosed();
        return this.wrapper.getOutputTensorCount();
    }

    public String[] getSignatureDefNames() {
        checkNotClosed();
        return this.wrapper.getSignatureDefNames();
    }

    public String[] getSignatureInputs(String str) {
        checkNotClosed();
        return this.wrapper.getSignatureInputs(str);
    }

    public String[] getSignatureOutputs(String str) {
        checkNotClosed();
        return this.wrapper.getSignatureOutputs(str);
    }

    public void resetVariableTensors() {
        checkNotClosed();
        this.wrapper.resetVariableTensors();
    }

    public void resizeInput(int i, int[] iArr) {
        checkNotClosed();
        this.wrapper.resizeInput(i, iArr, false);
    }

    public void resizeInput(int i, int[] iArr, boolean z) {
        checkNotClosed();
        this.wrapper.resizeInput(i, iArr, z);
    }

    public void runForMultipleInputsOutputs(Object[] objArr, Map<Integer, Object> map) {
        checkNotClosed();
        this.wrapper.run(objArr, (Map) map);
    }

    public void runSignature(Map<String, Object> map, Map<String, Object> map2) {
        checkNotClosed();
        runSignature(map, map2, null);
    }

    public Interpreter(File file) {
        this(file, null);
    }

    public Interpreter(ByteBuffer byteBuffer) {
        this(byteBuffer, null);
    }

    public void setCancelled(boolean z) {
        this.wrapper.setCancelled(z);
    }
}