package org.tensorflow.lite;

import org.tensorflow.lite.nnapi.NnApiDelegate.Options;

public enum DataType {
    FLOAT32(1),
    INT32(2),
    UINT8(3),
    INT64(4),
    STRING(5),
    BOOL(6),
    INT16(7),
    INT8(9);
    
    private static final DataType[] values;
    private final int value;

    /* renamed from: org.tensorflow.lite.DataType$1 */
    static /* synthetic */ class AnonymousClass1 {
        static final /* synthetic */ int[] $SwitchMap$org$tensorflow$lite$DataType;

        /*  JADX ERROR: NullPointerException in pass: BlockSplitter
            java.lang.NullPointerException: Attempt to invoke virtual method 'boolean jadx.core.dex.attributes.AttrNode.contains(jadx.core.dex.attributes.AType)' on a null object reference
            	at jadx.core.dex.visitors.blocksmaker.BlockSplitter.connectExceptionHandlers(Unknown Source:64)
            	at jadx.core.dex.visitors.blocksmaker.BlockSplitter.setupConnections(Unknown Source:58)
            	at jadx.core.dex.visitors.blocksmaker.BlockSplitter.splitBasicBlocks(Unknown Source:22)
            	at jadx.core.dex.visitors.blocksmaker.BlockSplitter.visit(Unknown Source:13)
            	at jadx.core.dex.visitors.DepthTraversal.visit(Unknown Source:9)
            	at jadx.core.dex.visitors.DepthTraversal.visit(Unknown Source:41)
            	at jadx.core.dex.visitors.DepthTraversal.visit(Unknown Source:51)
            	at jadx.core.ProcessClass.process(Unknown Source:54)
            	at jadx.api.JadxDecompiler.processClass(Unknown Source:4)
            	at jadx.api.JavaClass.decompile(Unknown Source:19)
            */
        static {
            $SwitchMap$org$tensorflow$lite$DataType = new int[DataType.values().length];
            try {
                $SwitchMap$org$tensorflow$lite$DataType[DataType.FLOAT32.ordinal()] = 1;
            } catch (NoSuchFieldError noSuchFieldError) {
            }
            try {
                $SwitchMap$org$tensorflow$lite$DataType[DataType.INT32.ordinal()] = 2;
            } catch (NoSuchFieldError noSuchFieldError2) {
            }
            try {
                $SwitchMap$org$tensorflow$lite$DataType[DataType.INT16.ordinal()] = 3;
            } catch (NoSuchFieldError noSuchFieldError3) {
            }
            try {
                $SwitchMap$org$tensorflow$lite$DataType[DataType.INT8.ordinal()] = 4;
            } catch (NoSuchFieldError noSuchFieldError4) {
            }
            try {
                $SwitchMap$org$tensorflow$lite$DataType[DataType.UINT8.ordinal()] = 5;
            } catch (NoSuchFieldError noSuchFieldError5) {
            }
            try {
                $SwitchMap$org$tensorflow$lite$DataType[DataType.INT64.ordinal()] = 6;
            } catch (NoSuchFieldError noSuchFieldError6) {
            }
            try {
                $SwitchMap$org$tensorflow$lite$DataType[DataType.BOOL.ordinal()] = 7;
            } catch (NoSuchFieldError noSuchFieldError7) {
            }
            try {
                $SwitchMap$org$tensorflow$lite$DataType[DataType.STRING.ordinal()] = 8;
            } catch (NoSuchFieldError noSuchFieldError8) {
            }
        }
    }

    static {
        values = values();
    }

    static DataType fromC(int i) {
        for (DataType dataType : values) {
            if (dataType.value == i) {
                return dataType;
            }
        }
        StringBuilder nativeInterpreterWrapper = new StringBuilder("DataType error: DataType ");
        nativeInterpreterWrapper.append(i);
        nativeInterpreterWrapper.append(" is not recognized in Java (version ");
        nativeInterpreterWrapper.append(TensorFlowLite.runtimeVersion());
        nativeInterpreterWrapper.append(")");
        throw new IllegalArgumentException(nativeInterpreterWrapper.toString());
    }

    private DataType(int i) {
        this.value = i;
    }

    public int byteSize() {
        switch (AnonymousClass1.$SwitchMap$org$tensorflow$lite$DataType[ordinal()]) {
            case Options.EXECUTION_PREFERENCE_FAST_SINGLE_ANSWER /*1*/:
            case Options.EXECUTION_PREFERENCE_SUSTAINED_SPEED /*2*/:
                return 4;
            case 3:
                return 2;
            case 4:
            case 5:
                return 1;
            case 6:
                return 8;
            case 7:
            case 8:
                return -1;
            default:
                StringBuilder nativeInterpreterWrapper = new StringBuilder("DataType error: DataType ");
                nativeInterpreterWrapper.append(this);
                nativeInterpreterWrapper.append(" is not supported yet");
                throw new IllegalArgumentException(nativeInterpreterWrapper.toString());
        }
    }

    String toStringName() {
        switch (AnonymousClass1.$SwitchMap$org$tensorflow$lite$DataType[ordinal()]) {
            case Options.EXECUTION_PREFERENCE_FAST_SINGLE_ANSWER /*1*/:
                return "float";
            case Options.EXECUTION_PREFERENCE_SUSTAINED_SPEED /*2*/:
                return "int";
            case 3:
                return "short";
            case 4:
            case 5:
                return "byte";
            case 6:
                return "long";
            case 7:
                return "bool";
            case 8:
                return "string";
            default:
                StringBuilder nativeInterpreterWrapper = new StringBuilder("DataType error: DataType ");
                nativeInterpreterWrapper.append(this);
                nativeInterpreterWrapper.append(" is not supported yet");
                throw new IllegalArgumentException(nativeInterpreterWrapper.toString());
        }
    }

    int c() {
        return this.value;
    }
}