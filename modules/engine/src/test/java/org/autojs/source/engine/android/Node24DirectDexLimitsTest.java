package org.autojs.source.engine.android;

import java.util.Collections;
import org.junit.Test;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import static org.junit.Assert.*;

/** Large signatures must remain valid when parameter registers exceed v15. */
public class Node24DirectDexLimitsTest {
    public static class Many {
        public Object many(int a, int b, int c, int d, int e, int f, int g,
                           int h, int i, int j, int k, int l, int m, int n,
                           int o, Object reference, long wide, double fraction) { return reference; }
    }
    @Test public void largeParameterRegistersAssembleIntoDex() {
        byte[] data = Node24DirectDexGenerator.generateDex("generated.HighRegisters", Many.class,
                null, Collections.singleton("many"));
        DexBackedDexFile dex = new DexBackedDexFile(Opcodes.forApi(21), data);
        assertEquals(1, dex.getClasses().size());
        assertEquals("Lgenerated/HighRegisters;", dex.getClasses().iterator().next().getType());
    }
}
