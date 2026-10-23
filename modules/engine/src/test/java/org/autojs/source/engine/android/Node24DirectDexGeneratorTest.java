package org.autojs.source.engine.android;

import org.jf.dexlib2.AccessFlags;
import org.jf.dexlib2.Opcode;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.instruction.Instruction;
import org.jf.dexlib2.iface.instruction.ReferenceInstruction;
import org.jf.dexlib2.iface.instruction.RegisterRangeInstruction;
import org.jf.dexlib2.iface.reference.MethodReference;
import org.jf.dexlib2.iface.reference.Reference;
import org.jf.dexlib2.iface.reference.TypeReference;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.Adler32;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Verifies the direct Smali-to-DEX path against the established classfile path.
 *
 * <p>The tests do not need an Android device: they parse the generated DEX with
 * dexlib2 and compare its class/field/method surface and access flags with the
 * classfile produced by {@link Node24ClassGenerator}. Instruction-level checks
 * cover boxing, wide register pairs, callback dispatch, bridge methods,
 * {@code super$} helpers and the no-super fallback.
 */
public class Node24DirectDexGeneratorTest {

    private static final AtomicInteger CLASS_SEQUENCE = new AtomicInteger();
    private static final String DISPATCHER = "L" + Node24ClassGenerator.DISPATCHER_INTERNAL + ";";

    @Test
    public void wideArgsInterfacesOverloadsAndAbstractFallbackMatchClassfile()
            throws Exception {
        Generated generated = generate(Parent.class, new Class<?>[]{Events.class},
                "compute", "label", "accept", "many", "scale", "abstractVoid",
                "inherited", "protectedWide", "overloaded", "concreteVoid");
        ClassDef dexClass = onlyClass(generated.dex);
        assertClassfileParity(generated, dexClass);

        // Fields and constructors are part of the parity assertion, but check the
        // important wiring explicitly so a future refactor cannot silently drop it.
        assertEquals("L" + generated.className.replace('.', '/') + ";", dexClass.getType());
        assertEquals("L" + Parent.class.getName().replace('.', '/') + ";",
                dexClass.getSuperclass());
        assertEquals(Collections.singletonList("L" + Events.class.getName().replace('.', '/') + ";"),
                dexClass.getInterfaces());

        assertEquals(2, constructors(dexClass).size());
        assertNotNull(dexConstructor(dexClass, String.class, long.class));
        assertNotNull(dexConstructor(dexClass, int.class));
        assertNull(dexConstructorOrNull(dexClass, double.class));

        org.jf.dexlib2.iface.Method constructor =
                dexConstructor(dexClass, String.class, long.class);
        assertEquals(8, constructor.getImplementation().getRegisterCount());
        assertEquals(Opcode.INVOKE_DIRECT, firstInstruction(constructor).getOpcode());
        assertTrue(hasMethodRef(constructor, Opcode.INVOKE_DIRECT,
                "L" + Parent.class.getName().replace('.', '/') + ";", "<init>",
                "(Ljava/lang/String;J)V"));
        int iput = indexOfOpcode(constructor, Opcode.IPUT_OBJECT);
        int construct = indexOfOpcode(constructor, Opcode.INVOKE_INTERFACE);
        assertTrue("constructor must assign the instance dispatcher", iput >= 0);
        assertTrue("construct callback must follow dispatcher assignment",
                construct > iput);
        assertTrue(hasMethodRef(constructor, Opcode.INVOKE_INTERFACE, DISPATCHER,
                "construct", "(Ljava/lang/Object;[Ljava/lang/Object;)V"));

        assertDispatchBoxingAndUnboxing(dexClass, "compute", "J",
                new String[]{"I", "J"},
                new String[]{"Ljava/lang/Integer;", "valueOf(I)Ljava/lang/Integer;",
                        "Ljava/lang/Long;", "valueOf(J)Ljava/lang/Long;"},
                "Ljava/lang/Long;", "longValue()J");
        assertNoSuperHelper(dexClass, "compute");

        assertDispatchBoxingAndUnboxing(dexClass, "many", "J",
                new String[]{"I", "J", "D", "Ljava/lang/Object;", "Z"},
                new String[]{"Ljava/lang/Integer;", "valueOf(I)Ljava/lang/Integer;",
                        "Ljava/lang/Long;", "valueOf(J)Ljava/lang/Long;",
                        "Ljava/lang/Double;", "valueOf(D)Ljava/lang/Double;",
                        "Ljava/lang/Boolean;", "valueOf(Z)Ljava/lang/Boolean;"},
                "Ljava/lang/Long;", "longValue()J");
        org.jf.dexlib2.iface.Method many = dexMethod(dexClass, "many", "J",
                "I", "J", "D", "Ljava/lang/Object;", "Z");
        assertEquals(13, many.getImplementation().getRegisterCount());
        RegisterRangeInstruction manyRange = rangeInstruction(many, Opcode.INVOKE_SUPER_RANGE);
        assertNotNull(manyRange);
        assertEquals(5, manyRange.getStartRegister());
        assertEquals(8, manyRange.getRegisterCount());
        assertTrue(hasMethodRef(many, Opcode.INVOKE_SUPER_RANGE,
                "L" + Parent.class.getName().replace('.', '/') + ";", "many",
                "(IJDLjava/lang/Object;Z)J"));

        assertDispatchBoxingAndUnboxing(dexClass, "scale", "D",
                new String[]{"F", "D"},
                new String[]{"Ljava/lang/Float;", "valueOf(F)Ljava/lang/Float;",
                        "Ljava/lang/Double;", "valueOf(D)Ljava/lang/Double;"},
                "Ljava/lang/Double;", "doubleValue()D");

        org.jf.dexlib2.iface.Method accept = dexMethod(dexClass, "accept", "Z",
                "Ljava/lang/Object;", "D");
        assertTrue(hasMethodRef(accept, Opcode.INVOKE_STATIC, "Ljava/lang/Double;",
                "valueOf", "(D)Ljava/lang/Double;"));
        assertTrue(hasMethodRef(accept, Opcode.INVOKE_VIRTUAL, "Ljava/lang/Boolean;",
                "booleanValue", "()Z"));
        assertTrue(hasOpcode(accept, Opcode.THROW));
        assertFalse(hasOpcode(accept, Opcode.INVOKE_SUPER));
        assertNoSuperHelper(dexClass, "accept");

        org.jf.dexlib2.iface.Method abstractVoid = dexMethod(dexClass, "abstractVoid", "V",
                "Ljava/lang/Object;");
        assertTrue(hasOpcode(abstractVoid, Opcode.RETURN_VOID));
        assertTrue(hasOpcode(abstractVoid, Opcode.THROW));
        assertNoSuperHelper(dexClass, "abstractVoid");

        org.jf.dexlib2.iface.Method label = dexMethod(dexClass, "label",
                "Ljava/lang/String;", "Ljava/lang/Object;");
        assertTrue(hasMethodRef(label, Opcode.INVOKE_SUPER,
                "L" + Parent.class.getName().replace('.', '/') + ";", "label",
                "(Ljava/lang/Object;)Ljava/lang/String;"));
        assertTrue(hasOpcode(label, Opcode.RETURN_OBJECT));
        org.jf.dexlib2.iface.Method superLabel = dexMethod(dexClass, "super$label",
                "Ljava/lang/String;", "Ljava/lang/Object;");
        assertTrue(hasMethodRef(superLabel, Opcode.INVOKE_SUPER,
                "L" + Parent.class.getName().replace('.', '/') + ";", "label",
                "(Ljava/lang/Object;)Ljava/lang/String;"));
        assertTrue(hasOpcode(superLabel, Opcode.RETURN_OBJECT));

        // GrandParent declares this method, not the direct superclass. invoke-super
        // is still legal because it resolves through the superclass chain.
        assertFalse(hasDeclaredMethod(Parent.class, "inherited", Object.class));
        assertTrue(hasDeclaredMethod(GrandParent.class, "inherited", Object.class));
        org.jf.dexlib2.iface.Method inherited = dexMethod(dexClass, "inherited",
                "Ljava/lang/String;", "Ljava/lang/Object;");
        assertTrue(hasMethodRef(inherited, Opcode.INVOKE_SUPER,
                "L" + Parent.class.getName().replace('.', '/') + ";", "inherited",
                "(Ljava/lang/Object;)Ljava/lang/String;"));
        org.jf.dexlib2.iface.Method superInherited = dexMethod(dexClass, "super$inherited",
                "Ljava/lang/String;", "Ljava/lang/Object;");
        assertTrue(hasMethodRef(superInherited, Opcode.INVOKE_SUPER,
                "L" + Parent.class.getName().replace('.', '/') + ";", "inherited",
                "(Ljava/lang/Object;)Ljava/lang/String;"));

        org.jf.dexlib2.iface.Method superProtectedWide = dexMethod(dexClass,
                "super$protectedWide", "J", "I", "J");
        assertTrue(hasMethodRef(superProtectedWide, Opcode.INVOKE_SUPER,
                "L" + Parent.class.getName().replace('.', '/') + ";", "protectedWide",
                "(IJ)J"));
        assertTrue(hasOpcode(superProtectedWide, Opcode.RETURN_WIDE));

        // Both overload descriptors are distinct candidates and both helpers exist.
        assertNotNull(dexMethod(dexClass, "overloaded", "Ljava/lang/String;",
                "Ljava/lang/Object;"));
        assertNotNull(dexMethod(dexClass, "overloaded", "Ljava/lang/String;", "I"));
        assertNotNull(dexMethod(dexClass, "super$overloaded", "Ljava/lang/String;",
                "Ljava/lang/Object;"));
        assertNotNull(dexMethod(dexClass, "super$overloaded", "Ljava/lang/String;", "I"));

        // A concrete void super method must produce a legal return-void helper,
        // not "return-void v0".
        org.jf.dexlib2.iface.Method superConcreteVoid = dexMethod(dexClass,
                "super$concreteVoid", "V", "Ljava/lang/Object;");
        assertTrue(hasMethodRef(superConcreteVoid, Opcode.INVOKE_SUPER,
                "L" + Parent.class.getName().replace('.', '/') + ";", "concreteVoid",
                "(Ljava/lang/Object;)V"));
        assertTrue(hasOpcode(superConcreteVoid, Opcode.RETURN_VOID));
        assertFalse(hasOpcode(superConcreteVoid, Opcode.MOVE_RESULT_OBJECT));
        org.jf.dexlib2.iface.Method concreteVoid = dexMethod(dexClass, "concreteVoid",
                "V", "Ljava/lang/Object;");
        assertTrue(hasMethodRef(concreteVoid, Opcode.INVOKE_SUPER,
                "L" + Parent.class.getName().replace('.', '/') + ";", "concreteVoid",
                "(Ljava/lang/Object;)V"));
        assertTrue(hasOpcode(concreteVoid, Opcode.RETURN_VOID));

        assertGeneratedHelpersAreLegal(dexClass, Parent.class);
    }

    @Test
    public void allPrimitiveAndArrayReturnsMatchClassfile() throws Exception {
        Generated generated = generate(AllReturns.class, null,
                "booleanValue", "byteValue", "charValue", "shortValue", "intValue",
                "longValue", "floatValue", "doubleValue", "objectValue", "arrayValue",
                "voidValue");
        ClassDef dexClass = onlyClass(generated.dex);
        assertClassfileParity(generated, dexClass);

        assertPrimitiveReturn(dexClass, "booleanValue", "Z", "Ljava/lang/Boolean;",
                "booleanValue", "()Z", Opcode.RETURN);
        assertPrimitiveReturn(dexClass, "byteValue", "B", "Ljava/lang/Byte;",
                "byteValue", "()B", Opcode.RETURN);
        assertPrimitiveReturn(dexClass, "charValue", "C", "Ljava/lang/Character;",
                "charValue", "()C", Opcode.RETURN);
        assertPrimitiveReturn(dexClass, "shortValue", "S", "Ljava/lang/Short;",
                "shortValue", "()S", Opcode.RETURN);
        assertPrimitiveReturn(dexClass, "intValue", "I", "Ljava/lang/Integer;",
                "intValue", "()I", Opcode.RETURN);
        assertPrimitiveReturn(dexClass, "longValue", "J", "Ljava/lang/Long;",
                "longValue", "()J", Opcode.RETURN_WIDE);
        assertPrimitiveReturn(dexClass, "floatValue", "F", "Ljava/lang/Float;",
                "floatValue", "()F", Opcode.RETURN);
        assertPrimitiveReturn(dexClass, "doubleValue", "D", "Ljava/lang/Double;",
                "doubleValue", "()D", Opcode.RETURN_WIDE);

        org.jf.dexlib2.iface.Method objectValue = dexMethod(dexClass, "objectValue",
                "Ljava/lang/String;", "Ljava/lang/Object;");
        assertTrue(hasTypeRef(objectValue, Opcode.CHECK_CAST, "Ljava/lang/String;"));
        assertTrue(hasOpcode(objectValue, Opcode.RETURN_OBJECT));

        org.jf.dexlib2.iface.Method arrayValue = dexMethod(dexClass, "arrayValue", "[I",
                "[I");
        assertTrue(hasTypeRef(arrayValue, Opcode.CHECK_CAST, "[I"));
        assertTrue(hasOpcode(arrayValue, Opcode.RETURN_OBJECT));
        org.jf.dexlib2.iface.Method superArray = dexMethod(dexClass, "super$arrayValue",
                "[I", "[I");
        assertTrue(hasOpcode(superArray, Opcode.RETURN_OBJECT));
        assertTrue(hasOpcode(superArray, Opcode.INVOKE_SUPER));

        org.jf.dexlib2.iface.Method voidValue = dexMethod(dexClass, "voidValue", "V",
                "Ljava/lang/Object;");
        assertTrue(hasOpcode(voidValue, Opcode.RETURN_VOID));
        assertTrue(hasOpcode(voidValue, Opcode.INVOKE_SUPER));
        org.jf.dexlib2.iface.Method superVoid = dexMethod(dexClass, "super$voidValue", "V",
                "Ljava/lang/Object;");
        assertTrue(hasOpcode(superVoid, Opcode.RETURN_VOID));
        assertFalse(hasOpcode(superVoid, Opcode.MOVE_RESULT_OBJECT));

        assertGeneratedHelpersAreLegal(dexClass, AllReturns.class);
    }

    @Test
    public void covariantBridgeAndMostSpecificSuperMatchClassfile() throws Exception {
        Generated generated = generate(CovariantBase.class,
                new Class<?>[]{CovariantInterface.class}, "value");
        ClassDef dexClass = onlyClass(generated.dex);
        assertClassfileParity(generated, dexClass);

        org.jf.dexlib2.iface.Method stringValue = dexMethod(dexClass, "value",
                "Ljava/lang/String;");
        org.jf.dexlib2.iface.Method objectValue = dexMethod(dexClass, "value",
                "Ljava/lang/Object;");
        assertFalse(AccessFlags.BRIDGE.isSet(stringValue.getAccessFlags()));
        assertFalse(AccessFlags.SYNTHETIC.isSet(stringValue.getAccessFlags()));
        assertTrue(AccessFlags.BRIDGE.isSet(objectValue.getAccessFlags()));
        assertTrue(AccessFlags.SYNTHETIC.isSet(objectValue.getAccessFlags()));
        assertTrue(hasMethodRef(objectValue, Opcode.INVOKE_VIRTUAL, dexClass.getType(),
                "value", "()Ljava/lang/String;"));
        assertTrue(hasOpcode(objectValue, Opcode.RETURN_OBJECT));

        assertTrue(hasMethodRef(stringValue, Opcode.INVOKE_SUPER,
                "L" + CovariantBase.class.getName().replace('.', '/') + ";", "value",
                "()Ljava/lang/Object;"));
        assertTrue(hasOpcode(stringValue, Opcode.CHECK_CAST));
        assertTrue(hasOpcode(stringValue, Opcode.RETURN_OBJECT));

        org.jf.dexlib2.iface.Method superValue = dexMethod(dexClass, "super$value",
                "Ljava/lang/Object;");
        assertTrue(hasMethodRef(superValue, Opcode.INVOKE_SUPER,
                "L" + CovariantBase.class.getName().replace('.', '/') + ";", "value",
                "()Ljava/lang/Object;"));
        assertTrue(hasOpcode(superValue, Opcode.RETURN_OBJECT));

        Generated mid = generate(CovariantMid.class,
                new Class<?>[]{CovariantInterface.class}, "value");
        ClassDef midClass = onlyClass(mid.dex);
        assertClassfileParity(mid, midClass);
        assertNotNull(dexMethod(midClass, "super$value", "Ljava/lang/String;"));
        assertTrue(hasMethodRef(dexMethod(midClass, "super$value", "Ljava/lang/String;"),
                Opcode.INVOKE_SUPER,
                "L" + CovariantMid.class.getName().replace('.', '/') + ";", "value",
                "()Ljava/lang/String;"));
    }

    @Test
    public void selectionAndInterfaceDeduplicationMatchClassfile() throws Exception {
        Generated generated = generate(FilterHost.class,
                new Class<?>[]{ClickA.class, ClickB.class},
                "finalMethod", "staticMethod", "privateMethod", "packageMethod",
                "overridable", "click");
        ClassDef dexClass = onlyClass(generated.dex);
        assertClassfileParity(generated, dexClass);

        assertEquals(new TreeSet<>(Arrays.asList(
                        "<init>()V",
                        "overridable()Ljava/lang/String;",
                        "super$overridable()Ljava/lang/String;",
                        "click()V")),
                new TreeSet<>(dexMethodKeys(dexClass)));
        assertNoSuperHelper(dexClass, "finalMethod");
        assertNoSuperHelper(dexClass, "staticMethod");
        assertNoSuperHelper(dexClass, "privateMethod");
        assertNoSuperHelper(dexClass, "packageMethod");
        assertNoSuperHelper(dexClass, "click");
        assertEquals(new HashSet<>(Arrays.asList(
                        "L" + ClickA.class.getName().replace('.', '/') + ";",
                        "L" + ClickB.class.getName().replace('.', '/') + ";")),
                new HashSet<>(dexClass.getInterfaces()));
    }

    @Test
    public void dexHeaderChecksumAndWideRangeConstructorAreValid() throws Exception {
        Generated generated = generate(WideCtorHost.class, null);
        byte[] dex = generated.dex;
        assertTrue(dex.length > 0x70);
        assertEquals('d', dex[0]);
        assertEquals('e', dex[1]);
        assertEquals('x', dex[2]);
        assertEquals('\n', dex[3]);
        assertEquals("035", new String(dex, 4, 3, StandardCharsets.US_ASCII));

        ByteBuffer header = ByteBuffer.wrap(dex).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(dex.length, header.getInt(32));
        assertEquals(0x70, header.getInt(36));
        assertEquals(0x12345678, header.getInt(40));

        Adler32 adler32 = new Adler32();
        adler32.update(dex, 12, dex.length - 12);
        assertEquals(adler32.getValue(), header.getInt(8) & 0xffffffffL);

        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        sha1.update(dex, 32, dex.length - 32);
        byte[] signature = sha1.digest();
        for (int i = 0; i < signature.length; i++) {
            assertEquals("signature byte " + i, signature[i], dex[12 + i]);
        }

        ClassDef dexClass = onlyClass(dex);
        org.jf.dexlib2.iface.Method constructor = dexConstructor(dexClass,
                int.class, long.class, double.class, Object.class, boolean.class);
        assertEquals(12, constructor.getImplementation().getRegisterCount());
        RegisterRangeInstruction range = rangeInstruction(constructor,
                Opcode.INVOKE_DIRECT_RANGE);
        assertNotNull(range);
        assertEquals(4, range.getStartRegister());
        assertEquals(8, range.getRegisterCount());
        assertTrue(hasMethodRef(constructor, Opcode.INVOKE_DIRECT_RANGE,
                "L" + WideCtorHost.class.getName().replace('.', '/') + ";", "<init>",
                "(IJDLjava/lang/Object;Z)V"));
        assertTrue(hasOpcode(constructor, Opcode.INVOKE_INTERFACE));

        byte[] repeated = Node24DirectDexGenerator.generateDex(generated.className,
                WideCtorHost.class, null, Collections.<String>emptySet());
        assertArrayEquals("DEX generation must be deterministic", dex, repeated);
    }

    @Test
    public void nullJsMethodsGeneratesOnlyConstructorAndFields() {
        String className = "generated.node24.direct.NullMethods"
                + CLASS_SEQUENCE.incrementAndGet();
        byte[] dex = Node24DirectDexGenerator.generateDex(className, WideCtorHost.class,
                null, null);
        ClassDef dexClass = onlyClass(dex);
        assertEquals(new TreeSet<>(Collections.singletonList(
                        "<init>(IJDLjava/lang/Object;Z)V")),
                new TreeSet<>(dexMethodKeys(dexClass)));
    }

    @Test
    public void validationAndRejectionMessagesMatchClassfile() {
        assertRejectsSame("generated.node24.direct.Invalid", null, null);
        assertRejectsSame("generated.node24.direct.Invalid", ClickA.class, null);
        assertRejectsSame("generated.node24.direct.Invalid", int.class, null);
        assertRejectsSame("generated.node24.direct.Invalid", String[].class, null);
        assertRejectsSame("generated.node24.direct.Invalid", FinalHost.class, null);
        assertRejectsSame("generated.node24.direct.Invalid", PrivateHost.class, null);
        assertRejectsSame("generated.node24.direct.Invalid", NoCtorHost.class, null);
        assertRejectsSame("generated.node24.direct.Invalid", Object.class,
                new Class<?>[]{Parent.class});
        assertRejectsSame("generated.node24.direct.Invalid", Object.class,
                new Class<?>[]{null});
        assertRejectsSame("generated.node24.direct.Invalid", Object.class,
                new Class<?>[]{HiddenInterface.class});
        assertRejectsSame("bad/name", Object.class, null);
        assertRejectsSame("", Object.class, null);
        assertRejectsSame(".Bad", Object.class, null);
        assertRejectsSame("Bad.", Object.class, null);
        assertRejectsSame("Bad..Name", Object.class, null);
        assertRejectsSame("java.lang.Object", Object.class, null);
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    public interface Events {
        boolean accept(Object value, double scale);
    }

    public static class GrandParent {
        public String inherited(Object value) {
            return "gp:" + value;
        }

        protected long protectedWide(int first, long second) {
            return first + second;
        }
    }

    public abstract static class Parent extends GrandParent {
        public Parent(String name, long version) {
        }

        protected Parent(int version) {
        }

        Parent(double ignored) {
        }

        public abstract long compute(int number, long offset);

        public abstract void abstractVoid(Object value);

        protected String label(Object value) {
            return String.valueOf(value);
        }

        public long many(int a, long b, double c, Object d, boolean e) {
            return a + b + (long) c;
        }

        public double scale(float value, double factor) {
            return value * factor;
        }

        public void concreteVoid(Object value) {
        }

        public String overloaded(Object value) {
            return "object:" + value;
        }

        public String overloaded(int value) {
            return "int:" + value;
        }
    }

    public static class WideCtorHost {
        public WideCtorHost(int a, long b, double c, Object d, boolean e) {
        }
    }

    public static class AllReturns {
        public boolean booleanValue(boolean value) {
            return !value;
        }

        public byte byteValue(byte value) {
            return (byte) (value + 1);
        }

        public char charValue(char value) {
            return (char) (value + 1);
        }

        public short shortValue(short value) {
            return (short) (value + 1);
        }

        public int intValue(int value) {
            return value + 1;
        }

        public long longValue(long value) {
            return value + 1L;
        }

        public float floatValue(float value) {
            return value + 0.5f;
        }

        public double doubleValue(double value) {
            return value + 0.5d;
        }

        public String objectValue(Object value) {
            return "object:" + value;
        }

        public int[] arrayValue(int[] value) {
            return value;
        }

        public void voidValue(Object value) {
        }
    }

    public static class FilterHost {
        public final String finalMethod() {
            return "final";
        }

        public static String staticMethod() {
            return "static";
        }

        private String privateMethod() {
            return "private";
        }

        String packageMethod() {
            return "package";
        }

        public String overridable() {
            return "base";
        }
    }

    public interface ClickA {
        void click();
    }

    public interface ClickB {
        void click();
    }

    public static class CovariantBase {
        public Object value() {
            return "base";
        }
    }

    public interface CovariantInterface {
        String value();
    }

    public static class CovariantMid extends CovariantBase implements CovariantInterface {
        @Override
        public String value() {
            return "mid";
        }
    }

    public static final class FinalHost {
        public FinalHost() {
        }
    }

    private static class PrivateHost {
        public PrivateHost() {
        }
    }

    public static class NoCtorHost {
        private NoCtorHost() {
        }
    }

    private interface HiddenInterface {
        void hidden();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static final class Generated {
        final String className;
        final Class<?> classfile;
        final byte[] dex;

        Generated(String className, Class<?> classfile, byte[] dex) {
            this.className = className;
            this.classfile = classfile;
            this.dex = dex;
        }
    }

    private static Generated generate(Class<?> superClass, Class<?>[] interfaces,
                                      String... methodNames) throws Exception {
        String className = "generated.node24.direct.Gen" + CLASS_SEQUENCE.incrementAndGet();
        Set<String> names = new LinkedHashSet<>(Arrays.asList(methodNames));
        byte[] classfileBytes = Node24ClassGenerator.generate(className, superClass,
                interfaces, names);
        Class<?> classfile = new ByteLoader(className, classfileBytes).define();
        byte[] dex = Node24DirectDexGenerator.generateDex(className, superClass,
                interfaces, names);
        return new Generated(className, classfile, dex);
    }

    private static Set<String> names(String... methods) {
        return new LinkedHashSet<>(Arrays.asList(methods));
    }

    private static ClassDef onlyClass(byte[] dex) {
        DexBackedDexFile file = new DexBackedDexFile(Opcodes.forApi(21), dex);
        assertEquals(1, file.getClasses().size());
        return file.getClasses().iterator().next();
    }

    private static void assertClassfileParity(Generated generated, ClassDef dexClass) {
        Class<?> classfile = generated.classfile;
        assertEquals(classfile.getName().replace('.', '/'), strip(dexClass.getType()));
        assertEquals(classfile.getSuperclass().getName().replace('.', '/'),
                strip(dexClass.getSuperclass()));

        Set<String> classfileInterfaces = new HashSet<>();
        for (Class<?> iface : classfile.getInterfaces()) {
            classfileInterfaces.add(iface.getName().replace('.', '/'));
        }
        Set<String> dexInterfaces = new HashSet<>();
        for (String iface : dexClass.getInterfaces()) {
            dexInterfaces.add(strip(iface));
        }
        assertEquals(classfileInterfaces, dexInterfaces);

        assertEquals(Modifier.isPublic(classfile.getModifiers()),
                AccessFlags.PUBLIC.isSet(dexClass.getAccessFlags()));
        assertEquals(Modifier.isFinal(classfile.getModifiers()),
                AccessFlags.FINAL.isSet(dexClass.getAccessFlags()));
        assertEquals(Modifier.isAbstract(classfile.getModifiers()),
                AccessFlags.ABSTRACT.isSet(dexClass.getAccessFlags()));
        assertEquals(Modifier.isInterface(classfile.getModifiers()),
                AccessFlags.INTERFACE.isSet(dexClass.getAccessFlags()));
        assertEquals(Modifier.isStatic(classfile.getModifiers()),
                AccessFlags.STATIC.isSet(dexClass.getAccessFlags()));

        Map<String, Field> classfileFields = new HashMap<>();
        for (Field field : classfile.getDeclaredFields()) {
            classfileFields.put(field.getName() + ":" + typeDescriptor(field.getType()), field);
        }
        Map<String, org.jf.dexlib2.iface.Field> dexFields = new HashMap<>();
        for (org.jf.dexlib2.iface.Field field : dexClass.getFields()) {
            dexFields.put(field.getName() + ":" + field.getType(), field);
        }
        assertEquals(classfileFields.keySet(), dexFields.keySet());
        for (Map.Entry<String, Field> entry : classfileFields.entrySet()) {
            assertFieldFlags(entry.getKey(), entry.getValue(),
                    dexFields.get(entry.getKey()));
        }

        Map<String, Method> classfileMethods = new HashMap<>();
        for (Method method : classfile.getDeclaredMethods()) {
            classfileMethods.put(method.getName()
                    + Node24ClassGenerator.methodDescriptor(
                    method.getReturnType(), method.getParameterTypes()), method);
        }
        Map<String, Constructor<?>> classfileConstructors = new HashMap<>();
        for (Constructor<?> constructor : classfile.getDeclaredConstructors()) {
            classfileConstructors.put("<init>"
                    + Node24ClassGenerator.methodDescriptor(
                    void.class, constructor.getParameterTypes()), constructor);
        }
        Map<String, org.jf.dexlib2.iface.Method> dexMethods = new HashMap<>();
        for (org.jf.dexlib2.iface.Method method : dexClass.getMethods()) {
            dexMethods.put(dexMethodKey(method), method);
        }

        Set<String> classfileKeys = new HashSet<>(classfileMethods.keySet());
        classfileKeys.addAll(classfileConstructors.keySet());
        assertEquals(classfileKeys, dexMethods.keySet());

        for (Map.Entry<String, Method> entry : classfileMethods.entrySet()) {
            assertMethodFlags(entry.getKey(), entry.getValue(),
                    dexMethods.get(entry.getKey()));
        }
        for (Map.Entry<String, Constructor<?>> entry : classfileConstructors.entrySet()) {
            assertConstructorFlags(entry.getKey(), entry.getValue(),
                    dexMethods.get(entry.getKey()));
        }
    }

    private static void assertFieldFlags(String key, Field classfile,
                                         org.jf.dexlib2.iface.Field dex) {
        int cf = classfile.getModifiers();
        int df = dex.getAccessFlags();
        assertEquals(key + " public", Modifier.isPublic(cf), AccessFlags.PUBLIC.isSet(df));
        assertEquals(key + " private", Modifier.isPrivate(cf), AccessFlags.PRIVATE.isSet(df));
        assertEquals(key + " protected", Modifier.isProtected(cf),
                AccessFlags.PROTECTED.isSet(df));
        assertEquals(key + " static", Modifier.isStatic(cf), AccessFlags.STATIC.isSet(df));
        assertEquals(key + " final", Modifier.isFinal(cf), AccessFlags.FINAL.isSet(df));
        assertEquals(key + " volatile", Modifier.isVolatile(cf),
                AccessFlags.VOLATILE.isSet(df));
        assertEquals(key + " transient", Modifier.isTransient(cf),
                AccessFlags.TRANSIENT.isSet(df));
        assertEquals(key + " synthetic", classfile.isSynthetic(),
                AccessFlags.SYNTHETIC.isSet(df));
    }

    private static void assertMethodFlags(String key, Method classfile,
                                          org.jf.dexlib2.iface.Method dex) {
        int cf = classfile.getModifiers();
        int df = dex.getAccessFlags();
        assertEquals(key + " public", Modifier.isPublic(cf), AccessFlags.PUBLIC.isSet(df));
        assertEquals(key + " private", Modifier.isPrivate(cf), AccessFlags.PRIVATE.isSet(df));
        assertEquals(key + " protected", Modifier.isProtected(cf),
                AccessFlags.PROTECTED.isSet(df));
        assertEquals(key + " static", Modifier.isStatic(cf), AccessFlags.STATIC.isSet(df));
        assertEquals(key + " final", Modifier.isFinal(cf), AccessFlags.FINAL.isSet(df));
        assertEquals(key + " abstract", Modifier.isAbstract(cf),
                AccessFlags.ABSTRACT.isSet(df));
        assertEquals(key + " synchronized", Modifier.isSynchronized(cf),
                AccessFlags.SYNCHRONIZED.isSet(df));
        assertEquals(key + " native", Modifier.isNative(cf), AccessFlags.NATIVE.isSet(df));
        assertEquals(key + " bridge", classfile.isBridge(), AccessFlags.BRIDGE.isSet(df));
        assertEquals(key + " synthetic", classfile.isSynthetic(),
                AccessFlags.SYNTHETIC.isSet(df));
    }

    private static void assertConstructorFlags(String key, Constructor<?> classfile,
                                               org.jf.dexlib2.iface.Method dex) {
        int cf = classfile.getModifiers();
        int df = dex.getAccessFlags();
        assertEquals(key + " public", Modifier.isPublic(cf), AccessFlags.PUBLIC.isSet(df));
        assertEquals(key + " private", Modifier.isPrivate(cf), AccessFlags.PRIVATE.isSet(df));
        assertEquals(key + " protected", Modifier.isProtected(cf),
                AccessFlags.PROTECTED.isSet(df));
        assertEquals(key + " static", Modifier.isStatic(cf), AccessFlags.STATIC.isSet(df));
        assertEquals(key + " final", Modifier.isFinal(cf), AccessFlags.FINAL.isSet(df));
        assertEquals(key + " synthetic", classfile.isSynthetic(),
                AccessFlags.SYNTHETIC.isSet(df));
        assertTrue(key + " constructor flag", AccessFlags.CONSTRUCTOR.isSet(df));
    }

    private static void assertDispatchBoxingAndUnboxing(
            ClassDef dexClass, String name, String returnType, String[] parameterTypes,
            String[] expectedCalls, String unboxOwner, String unboxDescriptor) {
        org.jf.dexlib2.iface.Method method = dexMethod(dexClass, name, returnType,
                parameterTypes);
        assertTrue(name + " has an implementation", method.getImplementation() != null);
        assertTrue(name + " dispatches", hasMethodRef(method, Opcode.INVOKE_INTERFACE,
                DISPATCHER, "invoke",
                "(Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;"));
        for (int i = 0; i < expectedCalls.length; i += 2) {
            String callDescriptor = expectedCalls[i + 1];
            int paren = callDescriptor.indexOf('(');
            assertTrue("malformed expected call " + callDescriptor, paren > 0);
            assertTrue(name + " must reference " + expectedCalls[i] + "."
                            + callDescriptor,
                    hasMethodRef(method, Opcode.INVOKE_STATIC, expectedCalls[i],
                            callDescriptor.substring(0, paren),
                            callDescriptor.substring(paren)));
        }
        int unboxParen = unboxDescriptor.indexOf('(');
        assertTrue("malformed unbox descriptor " + unboxDescriptor, unboxParen > 0);
        assertTrue(name + " unboxes with " + unboxOwner + "." + unboxDescriptor,
                hasMethodRef(method, Opcode.INVOKE_VIRTUAL, unboxOwner,
                        unboxDescriptor.substring(0, unboxParen),
                        unboxDescriptor.substring(unboxParen)));
        assertTrue(name + " consumes the dispatcher result",
                hasOpcode(method, Opcode.MOVE_RESULT_OBJECT));
        Opcode expectedReturn;
        if (returnType.equals("V")) {
            expectedReturn = Opcode.RETURN_VOID;
        } else if (returnType.equals("J") || returnType.equals("D")) {
            expectedReturn = Opcode.RETURN_WIDE;
        } else if (returnType.startsWith("L") || returnType.startsWith("[")) {
            expectedReturn = Opcode.RETURN_OBJECT;
        } else {
            expectedReturn = Opcode.RETURN;
        }
        assertTrue(name + " returns with " + expectedReturn,
                hasOpcode(method, expectedReturn));
    }

    private static org.jf.dexlib2.iface.Method dexMethod(ClassDef dexClass, String name,
                                                         String returnType,
                                                         String... parameterTypes) {
        org.jf.dexlib2.iface.Method found = dexMethodOrNull(dexClass, name, returnType,
                parameterTypes);
        assertNotNull("missing DEX method " + name + Arrays.toString(parameterTypes)
                + returnType, found);
        return found;
    }

    private static org.jf.dexlib2.iface.Method dexMethodOrNull(
            ClassDef dexClass, String name, String returnType, String... parameterTypes) {
        for (org.jf.dexlib2.iface.Method method : dexClass.getMethods()) {
            if (!method.getName().equals(name) || !method.getReturnType().equals(returnType)) {
                continue;
            }
            if (method.getParameterTypes().size() != parameterTypes.length) {
                continue;
            }
            boolean matches = true;
            for (int i = 0; i < parameterTypes.length; i++) {
                if (!method.getParameterTypes().get(i).toString()
                        .equals(parameterTypes[i])) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return method;
            }
        }
        return null;
    }

    private static org.jf.dexlib2.iface.Method dexConstructor(ClassDef dexClass,
                                                              Class<?>... parameterTypes) {
        return dexMethod(dexClass, "<init>", "V", descriptors(parameterTypes));
    }

    private static org.jf.dexlib2.iface.Method dexConstructorOrNull(ClassDef dexClass,
                                                                    Class<?>... parameterTypes) {
        return dexMethodOrNull(dexClass, "<init>", "V", descriptors(parameterTypes));
    }

    private static String[] descriptors(Class<?>... types) {
        String[] out = new String[types.length];
        for (int i = 0; i < types.length; i++) {
            out[i] = typeDescriptor(types[i]);
        }
        return out;
    }

    private static String typeDescriptor(Class<?> type) {
        if (type == void.class) return "V";
        if (type == boolean.class) return "Z";
        if (type == byte.class) return "B";
        if (type == char.class) return "C";
        if (type == short.class) return "S";
        if (type == int.class) return "I";
        if (type == long.class) return "J";
        if (type == float.class) return "F";
        if (type == double.class) return "D";
        if (type.isArray()) return type.getName().replace('.', '/');
        return "L" + type.getName().replace('.', '/') + ";";
    }

    private static String dexMethodKey(org.jf.dexlib2.iface.Method method) {
        StringBuilder builder = new StringBuilder(method.getName()).append('(');
        for (CharSequence parameter : method.getParameterTypes()) {
            builder.append(parameter);
        }
        return builder.append(')').append(method.getReturnType()).toString();
    }

    private static Set<String> dexMethodKeys(ClassDef dexClass) {
        Set<String> keys = new TreeSet<>();
        for (org.jf.dexlib2.iface.Method method : dexClass.getMethods()) {
            keys.add(dexMethodKey(method));
        }
        return keys;
    }

    private static List<org.jf.dexlib2.iface.Method> constructors(ClassDef dexClass) {
        List<org.jf.dexlib2.iface.Method> result = new ArrayList<>();
        for (org.jf.dexlib2.iface.Method method : dexClass.getMethods()) {
            if ("<init>".equals(method.getName())) {
                result.add(method);
            }
        }
        return result;
    }

    private static Instruction firstInstruction(org.jf.dexlib2.iface.Method method) {
        return instructions(method).get(0);
    }

    private static List<Instruction> instructions(org.jf.dexlib2.iface.Method method) {
        List<Instruction> result = new ArrayList<>();
        assertNotNull("method has no implementation", method.getImplementation());
        for (Instruction instruction : method.getImplementation().getInstructions()) {
            result.add(instruction);
        }
        return result;
    }

    private static boolean hasOpcode(org.jf.dexlib2.iface.Method method, Opcode opcode) {
        for (Instruction instruction : instructions(method)) {
            if (instruction.getOpcode() == opcode) {
                return true;
            }
        }
        return false;
    }

    private static int indexOfOpcode(org.jf.dexlib2.iface.Method method, Opcode opcode) {
        List<Instruction> list = instructions(method);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).getOpcode() == opcode) {
                return i;
            }
        }
        return -1;
    }

    private static RegisterRangeInstruction rangeInstruction(
            org.jf.dexlib2.iface.Method method, Opcode opcode) {
        for (Instruction instruction : instructions(method)) {
            if (instruction.getOpcode() == opcode
                    && instruction instanceof RegisterRangeInstruction) {
                return (RegisterRangeInstruction) instruction;
            }
        }
        return null;
    }

    private static boolean hasMethodRef(org.jf.dexlib2.iface.Method method, Opcode opcode,
                                        String definingClass, String name,
                                        String descriptor) {
        for (Instruction instruction : instructions(method)) {
            if (instruction.getOpcode() != opcode
                    || !(instruction instanceof ReferenceInstruction)) {
                continue;
            }
            Reference reference = ((ReferenceInstruction) instruction).getReference();
            if (!(reference instanceof MethodReference)) {
                continue;
            }
            MethodReference methodReference = (MethodReference) reference;
            if (methodReference.getDefiningClass().equals(definingClass)
                    && methodReference.getName().equals(name)
                    && methodReferenceDescriptor(methodReference).equals(descriptor)) {
                return true;
            }
        }
        return false;
    }

    private static String methodReferenceDescriptor(MethodReference reference) {
        StringBuilder builder = new StringBuilder("(");
        for (CharSequence parameter : reference.getParameterTypes()) {
            builder.append(parameter);
        }
        return builder.append(')').append(reference.getReturnType()).toString();
    }

    private static void assertPrimitiveReturn(ClassDef dexClass, String name,
                                              String returnType, String wrapper,
                                              String unboxName, String unboxDescriptor,
                                              Opcode expectedReturn) {
        org.jf.dexlib2.iface.Method method = dexMethod(dexClass, name, returnType,
                returnType);
        assertTrue(name + " boxes its parameter", hasMethodRef(method, Opcode.INVOKE_STATIC,
                wrapper, "valueOf", "(" + returnType + ")L" + wrapper.substring(1)));
        assertTrue(name + " unboxes the return value", hasMethodRef(method,
                Opcode.INVOKE_VIRTUAL, wrapper, unboxName, unboxDescriptor));
        assertTrue(name + " casts the dispatcher result", hasTypeRef(method,
                Opcode.CHECK_CAST, wrapper));
        assertTrue(name + " returns " + expectedReturn, hasOpcode(method, expectedReturn));
        assertNotNull(name + " super helper", dexMethod(dexClass, "super$" + name,
                returnType, returnType));
    }

    private static boolean hasTypeRef(org.jf.dexlib2.iface.Method method, Opcode opcode,
                                      String type) {
        for (Instruction instruction : instructions(method)) {
            if (instruction.getOpcode() != opcode
                    || !(instruction instanceof ReferenceInstruction)) {
                continue;
            }
            Reference reference = ((ReferenceInstruction) instruction).getReference();
            if (reference instanceof TypeReference
                    && ((TypeReference) reference).getType().equals(type)) {
                return true;
            }
        }
        return false;
    }

    private static void assertNoSuperHelper(ClassDef dexClass, String name) {
        assertNull("unexpected helper for " + name,
                dexMethodOrNull(dexClass, "super$" + name, "V"));
        for (org.jf.dexlib2.iface.Method method : dexClass.getMethods()) {
            if (("super$" + name).equals(method.getName())) {
                fail("unexpected helper " + method.getName()
                        + method.getParameterTypes() + method.getReturnType());
            }
        }
    }

    private static void assertGeneratedHelpersAreLegal(ClassDef dexClass,
                                                       Class<?> directSuper) {
        Set<String> superClasses = new HashSet<>();
        for (Class<?> type = directSuper; type != null; type = type.getSuperclass()) {
            superClasses.add("L" + type.getName().replace('.', '/') + ";");
        }
        for (org.jf.dexlib2.iface.Method method : dexClass.getMethods()) {
            if (!method.getName().startsWith("super$")) {
                continue;
            }
            assertTrue(method.getName() + " public",
                    AccessFlags.PUBLIC.isSet(method.getAccessFlags()));
            assertTrue(method.getName() + " final",
                    AccessFlags.FINAL.isSet(method.getAccessFlags()));
            assertFalse(method.getName() + " static",
                    AccessFlags.STATIC.isSet(method.getAccessFlags()));
            assertFalse(method.getName() + " abstract",
                    AccessFlags.ABSTRACT.isSet(method.getAccessFlags()));
            assertNotNull(method.getName() + " implementation", method.getImplementation());
            List<Instruction> list = instructions(method);
            boolean sawSuper = false;
            for (Instruction instruction : list) {
                if (instruction.getOpcode() != Opcode.INVOKE_SUPER
                        && instruction.getOpcode() != Opcode.INVOKE_SUPER_RANGE) {
                    continue;
                }
                sawSuper = true;
                assertTrue(instruction.getOpcode() + " must carry a reference",
                        instruction instanceof ReferenceInstruction);
                Reference reference = ((ReferenceInstruction) instruction).getReference();
                assertTrue(instruction.getOpcode() + " must target a method",
                        reference instanceof MethodReference);
                String target = ((MethodReference) reference).getDefiningClass();
                assertTrue(method.getName() + " targets " + target
                                + " which is not a superclass of " + directSuper.getName(),
                        superClasses.contains(target));
            }
            assertTrue(method.getName() + " must call its super implementation", sawSuper);
            if ("V".equals(method.getReturnType())) {
                assertFalse(method.getName() + " void helper must not move a result",
                        hasOpcode(method, Opcode.MOVE_RESULT_OBJECT));
                assertFalse(method.getName() + " void helper must not move a wide result",
                        hasOpcode(method, Opcode.MOVE_RESULT_WIDE));
            }
        }
    }

    private static void assertRejectsSame(String className, Class<?> superClass,
                                          Class<?>[] interfaces) {
        Set<String> methods = names("method");
        String classfileMessage = null;
        try {
            Node24ClassGenerator.generate(className, superClass, interfaces, methods);
            fail("classfile generator accepted invalid input: " + className);
        } catch (IllegalArgumentException expected) {
            classfileMessage = expected.getMessage();
        }

        try {
            Node24DirectDexGenerator.generateDex(className, superClass, interfaces, methods);
            fail("DEX generator accepted invalid input: " + className);
        } catch (IllegalArgumentException expected) {
            assertEquals("DEX rejection message differs for " + className,
                    classfileMessage, expected.getMessage());
        }
    }

    private static boolean hasDeclaredMethod(Class<?> type, String name,
                                             Class<?>... parameterTypes) {
        try {
            type.getDeclaredMethod(name, parameterTypes);
            return true;
        } catch (NoSuchMethodException expected) {
            return false;
        }
    }

    private static String strip(String descriptor) {
        return descriptor.substring(1, descriptor.length() - 1);
    }

    private static final class ByteLoader extends ClassLoader {
        private final String className;
        private final byte[] bytes;

        ByteLoader(String className, byte[] bytes) {
            super(Node24DirectDexGeneratorTest.class.getClassLoader());
            this.className = className;
            this.bytes = bytes;
        }

        Class<?> define() {
            return defineClass(className, bytes, 0, bytes.length);
        }
    }
}
