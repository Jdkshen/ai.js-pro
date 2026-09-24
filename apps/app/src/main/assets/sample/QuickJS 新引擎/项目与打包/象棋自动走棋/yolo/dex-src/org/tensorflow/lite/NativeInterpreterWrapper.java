package org.tensorflow.lite;

import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;
import org.tensorflow.lite.Interpreter.Options;
import org.tensorflow.lite.nnapi.NnApiDelegate;

final class NativeInterpreterWrapper implements AutoCloseable {
    private static final int ERROR_BUFFER_SIZE = 512;
    private long cancellationFlagHandle;
    private final List<Delegate> delegates;
    private long errorHandle;
    private long inferenceDurationNanoseconds;
    private Tensor[] inputTensors;
    private Map<String, Integer> inputsIndexes;
    private long interpreterHandle;
    private boolean isMemoryAllocated;
    private ByteBuffer modelByteBuffer;
    private long modelHandle;
    private Tensor[] outputTensors;
    private Map<String, Integer> outputsIndexes;
    private final List<AutoCloseable> ownedDelegates;
    private Map<Integer, Integer> tensorToInputsIndexes;
    private Map<Integer, Integer> tensorToOutputsIndexes;

    public void close() {
        Tensor[] tensorArr;
        Tensor tensor;
        int i = 0;
        while (true) {
            tensorArr = this.inputTensors;
            if (i >= tensorArr.length) {
                break;
            }
            tensor = tensorArr[i];
            if (tensor != null) {
                tensor.close();
                this.inputTensors[i] = null;
            }
            i++;
        }
        i = 0;
        while (true) {
            tensorArr = this.outputTensors;
            if (i >= tensorArr.length) {
                break;
            }
            tensor = tensorArr[i];
            if (tensor != null) {
                tensor.close();
                this.outputTensors[i] = null;
            }
            i++;
        }
        delete(this.errorHandle, this.modelHandle, this.interpreterHandle);
        deleteCancellationFlag(this.cancellationFlagHandle);
        this.errorHandle = 0;
        this.modelHandle = 0;
        this.interpreterHandle = 0;
        this.cancellationFlagHandle = 0;
        this.modelByteBuffer = null;
        this.inputsIndexes = null;
        this.outputsIndexes = null;
        this.isMemoryAllocated = false;
        this.delegates.clear();
        for (AutoCloseable close : this.ownedDelegates) {
            try {
                close.close();
            } catch (Exception e) {
                PrintStream printStream = System.err;
                StringBuilder nativeInterpreterWrapper2 = new StringBuilder("Failed to close flex delegate: ");
                nativeInterpreterWrapper2.append(e);
                printStream.println(nativeInterpreterWrapper2.toString());
            }
        }
        this.ownedDelegates.clear();
    }

    void run(Object[] objArr, Map<Integer, Object> map) {
        this.inferenceDurationNanoseconds = -1;
        if (objArr == null || objArr.length == 0) {
            throw new IllegalArgumentException("Input error: Inputs should not be null or empty.");
        } else if (map != null) {
            int i;
            int i2 = 0;
            for (i = 0; i < objArr.length; i++) {
                int[] inputShapeIfDifferent = getInputTensor(i).getInputShapeIfDifferent(objArr[i]);
                if (inputShapeIfDifferent != null) {
                    resizeInput(i, inputShapeIfDifferent);
                }
            }
            i = this.isMemoryAllocated ? 0 : 1;
            if (i != 0) {
                allocateTensors(this.interpreterHandle, this.errorHandle);
                this.isMemoryAllocated = true;
            }
            for (int i3 = 0; i3 < objArr.length; i3++) {
                getInputTensor(i3).setTo(objArr[i3]);
            }
            long nanoTime = System.nanoTime();
            run(this.interpreterHandle, this.errorHandle);
            long nanoTime2 = System.nanoTime() - nanoTime;
            if (i != 0) {
                while (true) {
                    Tensor[] tensorArr = this.outputTensors;
                    if (i2 >= tensorArr.length) {
                        break;
                    }
                    Tensor tensor = tensorArr[i2];
                    if (tensor != null) {
                        tensor.refreshShape();
                    }
                    i2++;
                }
            }
            for (Entry entry : map.entrySet()) {
                if (entry.getValue() != null) {
                    getOutputTensor(((Integer) entry.getKey()).intValue()).copyTo(entry.getValue());
                }
            }
            this.inferenceDurationNanoseconds = nanoTime2;
        } else {
            throw new IllegalArgumentException("Input error: Outputs should not be null.");
        }
    }

    private void init(long j, long j2, Options options) {
        if (options == null) {
            options = new Options();
        }
        this.errorHandle = j;
        this.modelHandle = j2;
        this.interpreterHandle = createInterpreter(j2, j, options.numThreads);
        if (options.allowCancellation != null && options.allowCancellation.booleanValue()) {
            this.cancellationFlagHandle = createCancellationFlag(this.interpreterHandle);
        }
        this.inputTensors = new Tensor[getInputCount(this.interpreterHandle)];
        this.outputTensors = new Tensor[getOutputCount(this.interpreterHandle)];
        if (options.allowFp16PrecisionForFp32 != null) {
            allowFp16PrecisionForFp32(this.interpreterHandle, options.allowFp16PrecisionForFp32.booleanValue());
        }
        if (options.allowBufferHandleOutput != null) {
            allowBufferHandleOutput(this.interpreterHandle, options.allowBufferHandleOutput.booleanValue());
        }
        applyDelegates(options);
        int useXNNPACKValue = options.useXNNPACK != null ? (options.useXNNPACK.booleanValue() ? 1 : 0) : -1;
        if (useXNNPACKValue == 1) {
            useXNNPACK(this.interpreterHandle, j, useXNNPACKValue, options.numThreads);
        }
        allocateTensors(this.interpreterHandle, j);
        this.isMemoryAllocated = true;
    }

    public void runSignature(Map<String, Object> map, Map<String, Object> map2, String str) {
        if (map == null || map.isEmpty()) {
            throw new IllegalArgumentException("Input error: Inputs should not be null or empty.");
        } else if (map2 != null) {
            initTensorIndexesMaps();
            TreeMap<Integer, Object> treeMap = new TreeMap<Integer, Object>();
            Map<Integer, Object> treeMap2 = new TreeMap<Integer, Object>();
            for (Entry entry : map.entrySet()) {
                treeMap.put((Integer) this.tensorToInputsIndexes.get(Integer.valueOf(getInputTensorIndexFromSignature(this.interpreterHandle, (String) entry.getKey(), str))), entry.getValue());
            }
            for (Entry entry2 : map2.entrySet()) {
                treeMap2.put((Integer) this.tensorToOutputsIndexes.get(Integer.valueOf(getOutputTensorIndexFromSignature(this.interpreterHandle, (String) entry2.getKey(), str))), entry2.getValue());
            }
            Object[] objArr = new Object[map.size()];
            int i = 0;
            for (Entry<Integer, Object> value : treeMap.entrySet()) {
                int i2 = i + 1;
                objArr[i] = value.getValue();
                i = i2;
            }
            run(objArr, treeMap2);
        } else {
            throw new IllegalArgumentException("Input error: Outputs should not be null.");
        }
    }

    private void applyDelegates(Options options) {
        boolean hasUnresolvedFlexOp = hasUnresolvedFlexOp(this.interpreterHandle);
        if (hasUnresolvedFlexOp) {
            Delegate maybeCreateFlexDelegate = maybeCreateFlexDelegate(options.delegates);
            if (maybeCreateFlexDelegate != null) {
                this.ownedDelegates.add((AutoCloseable) maybeCreateFlexDelegate);
                applyDelegate(this.interpreterHandle, this.errorHandle, maybeCreateFlexDelegate.getNativeHandle());
            }
        }
        try {
            for (Delegate delegate : options.delegates) {
                applyDelegate(this.interpreterHandle, this.errorHandle, delegate.getNativeHandle());
                this.delegates.add(delegate);
            }
            if (options.useNNAPI != null && options.useNNAPI.booleanValue()) {
                NnApiDelegate nnApiDelegate = new NnApiDelegate();
                this.ownedDelegates.add(nnApiDelegate);
                applyDelegate(this.interpreterHandle, this.errorHandle, nnApiDelegate.getNativeHandle());
            }
        } catch (IllegalArgumentException e) {
            Object obj = (!hasUnresolvedFlexOp || hasUnresolvedFlexOp(this.interpreterHandle)) ? null : 1;
            if (obj != null) {
                PrintStream printStream = System.err;
                StringBuilder nativeInterpreterWrapper = new StringBuilder("Ignoring failed delegate application: ");
                nativeInterpreterWrapper.append(e);
                printStream.println(nativeInterpreterWrapper.toString());
                return;
            }
            throw e;
        }
    }

    NativeInterpreterWrapper(ByteBuffer byteBuffer, Options options) {
        this.cancellationFlagHandle = 0;
        this.inferenceDurationNanoseconds = -1;
        this.isMemoryAllocated = false;
        this.delegates = new ArrayList();
        this.ownedDelegates = new ArrayList();
        TensorFlowLite.init();
        if (byteBuffer == null || !((byteBuffer instanceof MappedByteBuffer) || (byteBuffer.isDirect() && byteBuffer.order() == ByteOrder.nativeOrder()))) {
            throw new IllegalArgumentException("Model ByteBuffer should be either a MappedByteBuffer of the model file, or a direct ByteBuffer using ByteOrder.nativeOrder() which contains bytes of model content.");
        }
        this.modelByteBuffer = byteBuffer;
        long createErrorReporter = createErrorReporter(ERROR_BUFFER_SIZE);
        init(createErrorReporter, createModelWithBuffer(this.modelByteBuffer, createErrorReporter), options);
    }

    NativeInterpreterWrapper(String str, Options options) {
        this.cancellationFlagHandle = 0;
        this.inferenceDurationNanoseconds = -1;
        this.isMemoryAllocated = false;
        this.delegates = new ArrayList();
        this.ownedDelegates = new ArrayList();
        TensorFlowLite.init();
        long createErrorReporter = createErrorReporter(ERROR_BUFFER_SIZE);
        init(createErrorReporter, createModel(str, createErrorReporter), options);
    }

    private void initTensorIndexesMaps() {
        if (this.tensorToInputsIndexes == null) {
            this.tensorToInputsIndexes = new HashMap();
            this.tensorToOutputsIndexes = new HashMap();
            int inputTensorCount = getInputTensorCount();
            for (int i = 0; i < inputTensorCount; i++) {
                this.tensorToInputsIndexes.put(Integer.valueOf(getInputTensorIndex(this.interpreterHandle, i)), Integer.valueOf(i));
            }
            inputTensorCount = getOutputTensorCount();
            for (int i2 = 0; i2 < inputTensorCount; i2++) {
                this.tensorToOutputsIndexes.put(Integer.valueOf(getOutputTensorIndex(this.interpreterHandle, i2)), Integer.valueOf(i2));
            }
        }
    }

    int getInputIndex(String str) {
        if (this.inputsIndexes == null) {
            String[] inputNames = getInputNames(this.interpreterHandle);
            this.inputsIndexes = new HashMap();
            if (inputNames != null) {
                for (int i = 0; i < inputNames.length; i++) {
                    this.inputsIndexes.put(inputNames[i], Integer.valueOf(i));
                }
            }
        }
        if (this.inputsIndexes.containsKey(str)) {
            return ((Integer) this.inputsIndexes.get(str)).intValue();
        }
        throw new IllegalArgumentException(String.format("Input error: '%s' is not a valid name for any input. Names of inputs and their indexes are %s", new Object[]{str, this.inputsIndexes}));
    }

    int getOutputIndex(String str) {
        if (this.outputsIndexes == null) {
            String[] outputNames = getOutputNames(this.interpreterHandle);
            this.outputsIndexes = new HashMap();
            if (outputNames != null) {
                for (int i = 0; i < outputNames.length; i++) {
                    this.outputsIndexes.put(outputNames[i], Integer.valueOf(i));
                }
            }
        }
        if (this.outputsIndexes.containsKey(str)) {
            return ((Integer) this.outputsIndexes.get(str)).intValue();
        }
        throw new IllegalArgumentException(String.format("Input error: '%s' is not a valid name for any output. Names of outputs and their indexes are %s", new Object[]{str, this.outputsIndexes}));
    }

    Tensor getInputTensor(String str, String str2) {
        if (str != null) {
            initTensorIndexesMaps();
            int inputTensorIndexFromSignature = getInputTensorIndexFromSignature(this.interpreterHandle, str, str2);
            if (this.tensorToInputsIndexes.containsKey(Integer.valueOf(inputTensorIndexFromSignature))) {
                return getInputTensor(((Integer) this.tensorToInputsIndexes.get(Integer.valueOf(inputTensorIndexFromSignature))).intValue());
            }
            throw new IllegalArgumentException(String.format("Invalid input tensor name (%s) for signature (%s).", new Object[]{str, str2}));
        }
        throw new IllegalArgumentException("Invalid input tensor name provided (null)");
    }

    Tensor getOutputTensor(String str, String str2) {
        if (str != null) {
            initTensorIndexesMaps();
            int outputTensorIndexFromSignature = getOutputTensorIndexFromSignature(this.interpreterHandle, str, str2);
            if (this.tensorToOutputsIndexes.containsKey(Integer.valueOf(outputTensorIndexFromSignature))) {
                return getOutputTensor(((Integer) this.tensorToOutputsIndexes.get(Integer.valueOf(outputTensorIndexFromSignature))).intValue());
            }
            throw new IllegalArgumentException(String.format("Invalid output tensor name (%s) for signature (%s).", new Object[]{str, str2}));
        }
        throw new IllegalArgumentException("Invalid output tensor name provided (null)");
    }

    void allocateTensors() {
        if (!this.isMemoryAllocated) {
            this.isMemoryAllocated = true;
            allocateTensors(this.interpreterHandle, this.errorHandle);
            int i = 0;
            while (true) {
                Tensor[] tensorArr = this.outputTensors;
                if (i < tensorArr.length) {
                    Tensor tensor = tensorArr[i];
                    if (tensor != null) {
                        tensor.refreshShape();
                    }
                    i++;
                } else {
                    return;
                }
            }
        }
    }

    Tensor getInputTensor(int i) {
        if (i >= 0) {
            Tensor[] tensorArr = this.inputTensors;
            if (i < tensorArr.length) {
                Tensor tensor = tensorArr[i];
                if (tensor != null) {
                    return tensor;
                }
                long j = this.interpreterHandle;
                tensor = Tensor.fromIndex(j, getInputTensorIndex(j, i));
                tensorArr[i] = tensor;
                return tensor;
            }
        }
        StringBuilder nativeInterpreterWrapper = new StringBuilder("Invalid input Tensor index: ");
        nativeInterpreterWrapper.append(i);
        throw new IllegalArgumentException(nativeInterpreterWrapper.toString());
    }

    Tensor getOutputTensor(int i) {
        if (i >= 0) {
            Tensor[] tensorArr = this.outputTensors;
            if (i < tensorArr.length) {
                Tensor tensor = tensorArr[i];
                if (tensor != null) {
                    return tensor;
                }
                long j = this.interpreterHandle;
                tensor = Tensor.fromIndex(j, getOutputTensorIndex(j, i));
                tensorArr[i] = tensor;
                return tensor;
            }
        }
        StringBuilder nativeInterpreterWrapper = new StringBuilder("Invalid output Tensor index: ");
        nativeInterpreterWrapper.append(i);
        throw new IllegalArgumentException(nativeInterpreterWrapper.toString());
    }

    private static Delegate maybeCreateFlexDelegate(List<Delegate> list) {
        try {
            Class cls = Class.forName("org.tensorflow.lite.flex.FlexDelegate");
            for (Delegate isInstance : list) {
                if (cls.isInstance(isInstance)) {
                    return null;
                }
            }
            return (Delegate) cls.getConstructor(new Class[0]).newInstance(new Object[0]);
        } catch (Exception unused) {
            return null;
        }
    }

    void resizeInput(int i, int[] iArr, boolean z) {
        if (resizeInput(this.interpreterHandle, this.errorHandle, i, iArr, z)) {
            this.isMemoryAllocated = false;
            Tensor tensor = this.inputTensors[i];
            if (tensor != null) {
                tensor.refreshShape();
            }
        }
    }

    void setCancelled(boolean z) {
        long j = this.cancellationFlagHandle;
        if (j != 0) {
            setCancelled(this.interpreterHandle, j, z);
            return;
        }
        throw new IllegalStateException("Cannot cancel the inference. Have you called Interpreter.Options.setCancellable?");
    }

    NativeInterpreterWrapper(String str) {
        this(str, null);
    }

    NativeInterpreterWrapper(ByteBuffer byteBuffer) {
        this(byteBuffer, null);
    }

    int getExecutionPlanLength() {
        return getExecutionPlanLength(this.interpreterHandle);
    }

    int getInputTensorCount() {
        return this.inputTensors.length;
    }

    Long getLastNativeInferenceDurationNanoseconds() {
        long j = this.inferenceDurationNanoseconds;
        return j < 0 ? null : Long.valueOf(j);
    }

    int getOutputTensorCount() {
        return this.outputTensors.length;
    }

    public String[] getSignatureDefNames() {
        return getSignatureDefNames(this.interpreterHandle);
    }

    String[] getSignatureInputs(String str) {
        return getSignatureInputs(this.interpreterHandle, str);
    }

    String[] getSignatureOutputs(String str) {
        return getSignatureOutputs(this.interpreterHandle, str);
    }

    void resetVariableTensors() {
        resetVariableTensors(this.interpreterHandle, this.errorHandle);
    }

    void resizeInput(int i, int[] iArr) {
        resizeInput(i, iArr, false);
    }

    private static native long allocateTensors(long j, long j2);

    private static native void allowBufferHandleOutput(long j, boolean z);

    private static native void allowFp16PrecisionForFp32(long j, boolean z);

    private static native void applyDelegate(long j, long j2, long j3);

    private static native long createCancellationFlag(long j);

    private static native long createErrorReporter(int i);

    private static native long createInterpreter(long j, long j2, int i);

    private static native long createModel(String str, long j);

    private static native long createModelWithBuffer(ByteBuffer byteBuffer, long j);

    private static native void delete(long j, long j2, long j3);

    private static native long deleteCancellationFlag(long j);

    private static native int getExecutionPlanLength(long j);

    private static native int getInputCount(long j);

    private static native String[] getInputNames(long j);

    private static native int getInputTensorIndex(long j, int i);

    private static native int getInputTensorIndexFromSignature(long j, String str, String str2);

    private static native int getOutputCount(long j);

    private static native int getOutputDataType(long j, int i);

    private static native String[] getOutputNames(long j);

    private static native int getOutputTensorIndex(long j, int i);

    private static native int getOutputTensorIndexFromSignature(long j, String str, String str2);

    private static native String[] getSignatureDefNames(long j);

    private static native String[] getSignatureInputs(long j, String str);

    private static native String[] getSignatureOutputs(long j, String str);

    private static native boolean hasUnresolvedFlexOp(long j);

    private static native void resetVariableTensors(long j, long j2);

    private static native boolean resizeInput(long j, long j2, int i, int[] iArr, boolean z);

    private static native void run(long j, long j2);

    private static native void setCancelled(long j, long j2, boolean z);

    private static native void useXNNPACK(long j, long j2, int i, int i2);
}