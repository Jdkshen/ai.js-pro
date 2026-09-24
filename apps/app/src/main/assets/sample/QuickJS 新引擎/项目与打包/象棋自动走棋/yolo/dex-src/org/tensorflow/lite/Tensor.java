package org.tensorflow.lite;

import java.lang.reflect.Array;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.ShortBuffer;
import java.util.Arrays;

public final class Tensor {
    private final DataType dtype;
    private long nativeHandle;
    private final QuantizationParams quantizationParamsCopy;
    private int[] shapeCopy;
    private final int[] shapeSignatureCopy;

    public static class QuantizationParams {
        private final float scale;
        private final int zeroPoint;

        public QuantizationParams(float f, int i) {
            this.scale = f;
            this.zeroPoint = i;
        }

        public float getScale() {
            return this.scale;
        }

        public int getZeroPoint() {
            return this.zeroPoint;
        }
    }

    DataType dataTypeOf(Object obj) {
        if (obj != null) {
            Class cls = obj.getClass();
            if (cls.isArray()) {
                while (cls.isArray()) {
                    cls = cls.getComponentType();
                }
                if (Float.TYPE.equals(cls)) {
                    return DataType.FLOAT32;
                }
                if (Integer.TYPE.equals(cls)) {
                    return DataType.INT32;
                }
                if (Short.TYPE.equals(cls)) {
                    return DataType.INT16;
                }
                if (Byte.TYPE.equals(cls)) {
                    return this.dtype == DataType.STRING ? DataType.STRING : DataType.UINT8;
                } else {
                    if (Long.TYPE.equals(cls)) {
                        return DataType.INT64;
                    }
                    if (Boolean.TYPE.equals(cls)) {
                        return DataType.BOOL;
                    }
                    if (String.class.equals(cls)) {
                        return DataType.STRING;
                    }
                }
            }
            if (!Float.class.equals(cls)) {
                if (!(obj instanceof FloatBuffer)) {
                    if (!Integer.class.equals(cls)) {
                        if (!(obj instanceof IntBuffer)) {
                            if (!Short.class.equals(cls)) {
                                if (!(obj instanceof ShortBuffer)) {
                                    if (Byte.class.equals(cls)) {
                                        return DataType.UINT8;
                                    }
                                    if (!Long.class.equals(cls)) {
                                        if (!(obj instanceof LongBuffer)) {
                                            if (Boolean.class.equals(cls)) {
                                                return DataType.BOOL;
                                            }
                                            if (String.class.equals(cls)) {
                                                return DataType.STRING;
                                            }
                                        }
                                    }
                                    return DataType.INT64;
                                }
                            }
                            return DataType.INT16;
                        }
                    }
                    return DataType.INT32;
                }
            }
            return DataType.FLOAT32;
        }
        StringBuilder nativeInterpreterWrapper = new StringBuilder("DataType error: cannot resolve DataType of ");
        nativeInterpreterWrapper.append(obj.getClass().getName());
        throw new IllegalArgumentException(nativeInterpreterWrapper.toString());
    }

    private void setTo(Buffer buffer) {
        if (buffer instanceof ByteBuffer) {
            ByteBuffer byteBuffer = (ByteBuffer) buffer;
            if (byteBuffer.isDirect() && byteBuffer.order() == ByteOrder.nativeOrder()) {
                writeDirectBuffer(this.nativeHandle, buffer);
            } else {
                buffer().put(byteBuffer);
            }
        } else if (buffer instanceof LongBuffer) {
            LongBuffer longBuffer = (LongBuffer) buffer;
            if (longBuffer.isDirect() && longBuffer.order() == ByteOrder.nativeOrder()) {
                writeDirectBuffer(this.nativeHandle, buffer);
            } else {
                buffer().asLongBuffer().put(longBuffer);
            }
        } else if (buffer instanceof FloatBuffer) {
            FloatBuffer floatBuffer = (FloatBuffer) buffer;
            if (floatBuffer.isDirect() && floatBuffer.order() == ByteOrder.nativeOrder()) {
                writeDirectBuffer(this.nativeHandle, buffer);
            } else {
                buffer().asFloatBuffer().put(floatBuffer);
            }
        } else if (buffer instanceof IntBuffer) {
            IntBuffer intBuffer = (IntBuffer) buffer;
            if (intBuffer.isDirect() && intBuffer.order() == ByteOrder.nativeOrder()) {
                writeDirectBuffer(this.nativeHandle, buffer);
            } else {
                buffer().asIntBuffer().put(intBuffer);
            }
        } else if (buffer instanceof ShortBuffer) {
            ShortBuffer shortBuffer = (ShortBuffer) buffer;
            if (shortBuffer.isDirect() && shortBuffer.order() == ByteOrder.nativeOrder()) {
                writeDirectBuffer(this.nativeHandle, buffer);
            } else {
                buffer().asShortBuffer().put(shortBuffer);
            }
        } else {
            StringBuilder nativeInterpreterWrapper = new StringBuilder("Unexpected input buffer type: ");
            nativeInterpreterWrapper.append(buffer);
            throw new IllegalArgumentException(nativeInterpreterWrapper.toString());
        }
    }

    private void throwIfDstShapeIsIncompatible(Object obj) {
        if (isBuffer(obj)) {
            Buffer buffer = (Buffer) obj;
            if (numBytes() > (isByteBuffer(obj) ? buffer.capacity() : buffer.capacity() * this.dtype.byteSize())) {
                throw new IllegalArgumentException(String.format("Cannot copy from a TensorFlowLite tensor (%s) with %d bytes to a Java Buffer with %d bytes.", new Object[]{name(), Integer.valueOf(numBytes()), Integer.valueOf(isByteBuffer(obj) ? buffer.capacity() : buffer.capacity() * this.dtype.byteSize())}));
            }
            return;
        }
        if (!Arrays.equals(computeShapeOf(obj), this.shapeCopy)) {
            throw new IllegalArgumentException(String.format("Cannot copy from a TensorFlowLite tensor (%s) with shape %s to a Java object with shape %s.", new Object[]{name(), Arrays.toString(this.shapeCopy), Arrays.toString(computeShapeOf(obj))}));
        }
    }

    private void throwIfSrcShapeIsIncompatible(Object obj) {
        if (isBuffer(obj)) {
            Buffer buffer = (Buffer) obj;
            if (numBytes() != (isByteBuffer(obj) ? buffer.capacity() : buffer.capacity() * this.dtype.byteSize())) {
                throw new IllegalArgumentException(String.format("Cannot copy to a TensorFlowLite tensor (%s) with %d bytes from a Java Buffer with %d bytes.", new Object[]{name(), Integer.valueOf(numBytes()), Integer.valueOf(isByteBuffer(obj) ? buffer.capacity() : buffer.capacity() * this.dtype.byteSize())}));
            }
            return;
        }
        if (!Arrays.equals(computeShapeOf(obj), this.shapeCopy)) {
            throw new IllegalArgumentException(String.format("Cannot copy to a TensorFlowLite tensor (%s) with shape %s from a Java object with shape %s.", new Object[]{name(), Arrays.toString(this.shapeCopy), Arrays.toString(computeShapeOf(obj))}));
        }
    }

    private void copyTo(Buffer buffer) {
        if (buffer instanceof ByteBuffer) {
            ((ByteBuffer) buffer).put(buffer());
        } else if (buffer instanceof FloatBuffer) {
            ((FloatBuffer) buffer).put(buffer().asFloatBuffer());
        } else if (buffer instanceof LongBuffer) {
            ((LongBuffer) buffer).put(buffer().asLongBuffer());
        } else if (buffer instanceof IntBuffer) {
            ((IntBuffer) buffer).put(buffer().asIntBuffer());
        } else if (buffer instanceof ShortBuffer) {
            ((ShortBuffer) buffer).put(buffer().asShortBuffer());
        } else {
            StringBuilder nativeInterpreterWrapper = new StringBuilder("Unexpected output buffer type: ");
            nativeInterpreterWrapper.append(buffer);
            throw new IllegalArgumentException(nativeInterpreterWrapper.toString());
        }
    }

    void setTo(Object obj) {
        if (obj != null) {
            throwIfTypeIsIncompatible(obj);
            throwIfSrcShapeIsIncompatible(obj);
            if (isBuffer(obj)) {
                setTo((Buffer) obj);
            } else if (this.dtype == DataType.STRING && this.shapeCopy.length == 0) {
                writeScalar(this.nativeHandle, obj);
            } else if (obj.getClass().isArray()) {
                writeMultiDimensionalArray(this.nativeHandle, obj);
            } else {
                writeScalar(this.nativeHandle, obj);
            }
        } else if (!hasDelegateBufferHandle(this.nativeHandle)) {
            throw new IllegalArgumentException("Null inputs are allowed only if the Tensor is bound to a buffer handle.");
        }
    }

    int[] computeShapeOf(Object obj) {
        int computeNumDimensions = computeNumDimensions(obj);
        if (this.dtype == DataType.STRING) {
            Class cls = obj.getClass();
            if (cls.isArray()) {
                while (cls.isArray()) {
                    cls = cls.getComponentType();
                }
                if (Byte.TYPE.equals(cls)) {
                    computeNumDimensions--;
                }
            }
        }
        int[] iArr = new int[computeNumDimensions];
        fillShape(obj, 0, iArr);
        return iArr;
    }

    private Tensor(long j) {
        this.nativeHandle = j;
        this.dtype = DataType.fromC(dtype(j));
        this.shapeCopy = shape(j);
        this.shapeSignatureCopy = shapeSignature(j);
        this.quantizationParamsCopy = new QuantizationParams(quantizationScale(j), quantizationZeroPoint(j));
    }

    static void fillShape(Object obj, int i, int[] iArr) {
        if (iArr != null) {
            if (i != iArr.length) {
                int length = Array.getLength(obj);
                int i2 = iArr[i];
                int i3 = 0;
                if (i2 == 0) {
                    iArr[i] = length;
                } else if (i2 != length) {
                    throw new IllegalArgumentException(String.format("Mismatched lengths (%d and %d) in dimension %d", new Object[]{Integer.valueOf(iArr[i]), Integer.valueOf(length), Integer.valueOf(i)}));
                }
                while (i3 < length) {
                    fillShape(Array.get(obj, i3), i + 1, iArr);
                    i3++;
                }
            }
        }
    }

    private void throwIfTypeIsIncompatible(Object obj) {
        if (!isByteBuffer(obj)) {
            DataType dataTypeOf = dataTypeOf(obj);
            if (dataTypeOf != this.dtype && !dataTypeOf.toStringName().equals(this.dtype.toStringName())) {
                throw new IllegalArgumentException(String.format("Cannot convert between a TensorFlowLite tensor with type %s and a Java object of type %s (which is compatible with the TensorFlowLite type %s).", new Object[]{this.dtype, obj.getClass().getName(), dataTypeOf}));
            }
        }
    }

    Object copyTo(Object obj) {
        if (obj != null) {
            throwIfTypeIsIncompatible(obj);
            throwIfDstShapeIsIncompatible(obj);
            if (isBuffer(obj)) {
                copyTo((Buffer) obj);
            } else {
                readMultiDimensionalArray(this.nativeHandle, obj);
            }
            return obj;
        } else if (hasDelegateBufferHandle(this.nativeHandle)) {
            return obj;
        } else {
            throw new IllegalArgumentException("Null outputs are allowed only if the Tensor is bound to a buffer handle.");
        }
    }

    static int computeNumDimensions(Object obj) {
        if (obj != null) {
            if (obj.getClass().isArray()) {
                if (Array.getLength(obj) != 0) {
                    return computeNumDimensions(Array.get(obj, 0)) + 1;
                }
                throw new IllegalArgumentException("Array lengths cannot be 0.");
            }
        }
        return 0;
    }

    int[] getInputShapeIfDifferent(Object obj) {
        if (obj == null || isBuffer(obj)) {
            return null;
        }
        throwIfTypeIsIncompatible(obj);
        int[] computeShapeOf = computeShapeOf(obj);
        return Arrays.equals(this.shapeCopy, computeShapeOf) ? null : computeShapeOf;
    }

    static int computeNumElements(int[] iArr) {
        int i = 1;
        for (int i2 : iArr) {
            i *= i2;
        }
        return i;
    }

    void close() {
        delete(this.nativeHandle);
        this.nativeHandle = 0;
    }

    private ByteBuffer buffer() {
        return buffer(this.nativeHandle).order(ByteOrder.nativeOrder());
    }

    static Tensor fromIndex(long j, int i) {
        return new Tensor(create(j, i));
    }

    private static boolean isBuffer(Object obj) {
        return obj instanceof Buffer;
    }

    private static boolean isByteBuffer(Object obj) {
        return obj instanceof ByteBuffer;
    }

    public ByteBuffer asReadOnlyBuffer() {
        return buffer().asReadOnlyBuffer().order(ByteOrder.nativeOrder());
    }

    public DataType dataType() {
        return this.dtype;
    }

    public int index() {
        return index(this.nativeHandle);
    }

    public String name() {
        return name(this.nativeHandle);
    }

    public int numBytes() {
        return numBytes(this.nativeHandle);
    }

    public int numDimensions() {
        return this.shapeCopy.length;
    }

    public int numElements() {
        return computeNumElements(this.shapeCopy);
    }

    public QuantizationParams quantizationParams() {
        return this.quantizationParamsCopy;
    }

    void refreshShape() {
        this.shapeCopy = shape(this.nativeHandle);
    }

    public int[] shape() {
        return this.shapeCopy;
    }

    public int[] shapeSignature() {
        return this.shapeSignatureCopy;
    }

    private static native ByteBuffer buffer(long j);

    private static native long create(long j, int i);

    private static native void delete(long j);

    private static native int dtype(long j);

    private static native boolean hasDelegateBufferHandle(long j);

    private static native int index(long j);

    private static native String name(long j);

    private static native int numBytes(long j);

    private static native float quantizationScale(long j);

    private static native int quantizationZeroPoint(long j);

    private static native void readMultiDimensionalArray(long j, Object obj);

    private static native int[] shape(long j);

    private static native int[] shapeSignature(long j);

    private static native void writeDirectBuffer(long j, Buffer buffer);

    private static native void writeMultiDimensionalArray(long j, Object obj);

    private static native void writeScalar(long j, Object obj);
}