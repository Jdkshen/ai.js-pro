package org.tensorflow.lite;

import java.util.Map;

public interface InterpreterApi extends AutoCloseable {

    public static class Options {
        Boolean allowCancellation;
        int numThreads = -1;
        Boolean useNNAPI;

        public Options setCancellable(boolean z) {
            this.allowCancellation = Boolean.valueOf(z);
            return this;
        }

        public Options setNumThreads(int i) {
            this.numThreads = i;
            return this;
        }

        public Options setUseNNAPI(boolean z) {
            this.useNNAPI = Boolean.valueOf(z);
            return this;
        }
    }

    void allocateTensors();

    int getInputIndex(String str);

    Tensor getInputTensor(int i);

    int getInputTensorCount();

    Long getLastNativeInferenceDurationNanoseconds();

    int getOutputIndex(String str);

    Tensor getOutputTensor(int i);

    int getOutputTensorCount();

    void resizeInput(int i, int[] iArr);

    void resizeInput(int i, int[] iArr, boolean z);

    void run(Object obj, Object obj2);

    void runForMultipleInputsOutputs(Object[] objArr, Map<Integer, Object> map);
}