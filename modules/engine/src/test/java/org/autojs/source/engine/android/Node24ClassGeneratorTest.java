package org.autojs.source.engine.android;

import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class Node24ClassGeneratorTest {

    private static final AtomicInteger CLASS_SEQUENCE = new AtomicInteger();

    @Test
    public void generatesSqliteLikeAbstractOverridesAndFailsExplicitlyWithoutDispatcher()
            throws Exception {
        Class<?> generated = generate(SqliteLike.class, null,
                "onCreate", "onUpgrade");

        assertFalse(Modifier.isAbstract(generated.getModifiers()));
        Method onCreate = generated.getDeclaredMethod("onCreate", Object.class);
        Method onUpgrade = generated.getDeclaredMethod(
                "onUpgrade", Object.class, int.class, int.class);
        assertPublic(onCreate);
        assertPublic(onUpgrade);
        assertFalse(Modifier.isAbstract(onCreate.getModifiers()));
        assertFalse(Modifier.isAbstract(onUpgrade.getModifiers()));
        assertNoMethod(generated, "super$onCreate", Object.class);
        assertNoMethod(generated, "super$onUpgrade", Object.class, int.class, int.class);

        RecordingDispatcher dispatcher = new RecordingDispatcher(null);
        setDispatcher(generated, dispatcher);
        Object instance = newInstance(generated,
                new Class<?>[]{String.class, int.class}, "app.db", 3);
        assertEquals(1, dispatcher.constructCount.get());
        assertArrayEquals(new Object[]{"app.db", 3}, dispatcher.constructedArgs.get(0));

        SqliteLike sqlite = (SqliteLike) instance;
        Object database = new Object();
        sqlite.onCreate(database);
        assertEquals("onCreate", dispatcher.invokedMethods.get(0));
        assertSame(instance, dispatcher.invokedReceivers.get(0));
        assertEquals(1, dispatcher.invokedArgs.get(0).length);
        assertSame(database, dispatcher.invokedArgs.get(0)[0]);

        sqlite.onUpgrade(database, 4, 5);
        assertEquals("onUpgrade", dispatcher.invokedMethods.get(1));
        Object[] upgradeArgs = dispatcher.invokedArgs.get(1);
        assertEquals(3, upgradeArgs.length);
        assertSame(database, upgradeArgs[0]);
        assertEquals(Integer.valueOf(4), upgradeArgs[1]);
        assertEquals(Integer.valueOf(5), upgradeArgs[2]);

        setDispatcher(generated, null);
        Object plain = newInstance(generated,
                new Class<?>[]{String.class, int.class}, "plain.db", 1);
        try {
            ((SqliteLike) plain).onCreate(database);
            fail("abstract override without dispatcher must throw");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("onCreate"));
        }
    }

    @Test
    public void overridesProtectedOnDrawAndInterfaceClickWithSuperForwarding() throws Exception {
        Class<?> generated = generate(ButtonLike.class, new Class<?>[]{Clickable.class},
                "onDraw", "onClick");

        Method onDraw = generated.getDeclaredMethod("onDraw", Object.class);
        Method onDrawWithDepth = generated.getDeclaredMethod("onDraw", Object.class, int.class);
        assertPublic(onDraw);
        assertPublic(onDrawWithDepth);
        assertEquals(void.class, onDraw.getReturnType());

        Method superDraw = generated.getDeclaredMethod("super$onDraw", Object.class);
        Method superDrawWithDepth = generated.getDeclaredMethod(
                "super$onDraw", Object.class, int.class);
        assertPublic(superDraw);
        assertPublic(superDrawWithDepth);
        assertTrue(Modifier.isFinal(superDraw.getModifiers()));
        assertTrue(Modifier.isFinal(superDrawWithDepth.getModifiers()));
        assertNoMethod(generated, "super$onClick", Object.class);

        RecordingDispatcher dispatcher = new RecordingDispatcher(null);
        setDispatcher(generated, dispatcher);
        Object instance = newInstance(generated, new Class<?>[]{String.class}, "button");
        ButtonLike button = (ButtonLike) instance;
        Clickable clickable = (Clickable) instance;

        clickable.onClick(button);
        assertEquals("onClick", dispatcher.invokedMethods.get(0));
        assertEquals(1, dispatcher.invokedArgs.get(0).length);
        assertSame(button, dispatcher.invokedArgs.get(0)[0]);

        onDraw.invoke(instance, "canvas");
        assertEquals("onDraw", dispatcher.invokedMethods.get(1));
        assertEquals(1, dispatcher.invokedArgs.get(1).length);
        assertEquals("canvas", dispatcher.invokedArgs.get(1)[0]);
        assertEquals(0, button.drawCalls);

        onDrawWithDepth.invoke(instance, "canvas", 2);
        assertEquals("onDraw", dispatcher.invokedMethods.get(2));
        assertEquals(2, dispatcher.invokedArgs.get(2).length);
        assertEquals(Integer.valueOf(2), dispatcher.invokedArgs.get(2)[1]);

        int dispatcherCalls = dispatcher.invokedMethods.size();
        superDraw.invoke(instance, "canvas");
        assertEquals(1, button.drawCalls);
        superDrawWithDepth.invoke(instance, "canvas", 3);
        assertEquals(101, button.drawCalls);
        assertEquals(dispatcherCalls, dispatcher.invokedMethods.size());

        setDispatcher(generated, null);
        Object plain = newInstance(generated, new Class<?>[]{String.class}, "plain");
        Method plainOnDraw = generated.getDeclaredMethod("onDraw", Object.class);
        plainOnDraw.invoke(plain, "canvas");
        assertEquals(1, ((ButtonLike) plain).drawCalls);
        try {
            ((Clickable) plain).onClick(plain);
            fail("abstract onClick without dispatcher must throw");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("onClick"));
        }
    }

    @Test
    public void generatesAccessibleConstructorsAndBoxesConstructArguments() throws Exception {
        Class<?> generated = generate(BoxHost.class, null);

        Field staticDispatcher = generated.getField("__dispatcher");
        assertTrue(Modifier.isPublic(staticDispatcher.getModifiers()));
        assertTrue(Modifier.isStatic(staticDispatcher.getModifiers()));
        assertEquals(Node24ClassGenerator.Dispatcher.class, staticDispatcher.getType());
        Field instanceDispatcher = generated.getDeclaredField("__instanceDispatcher");
        assertTrue(Modifier.isPrivate(instanceDispatcher.getModifiers()));
        assertTrue(Modifier.isFinal(instanceDispatcher.getModifiers()));
        assertEquals(Node24ClassGenerator.Dispatcher.class, instanceDispatcher.getType());

        Constructor<?> fiveArgs = generated.getConstructor(
                int.class, long.class, double.class, boolean.class, Object.class);
        Constructor<?> protectedArgs = generated.getConstructor(String.class);
        assertNoConstructor(generated, double.class);

        RecordingDispatcher dispatcher = new RecordingDispatcher(null);
        final BoxHost[] observedDuringConstruct = new BoxHost[1];
        final boolean[] dispatcherWasAssigned = new boolean[1];
        dispatcher.constructHandler = new ConstructHandler() {
            @Override
            public void construct(Object receiver, Object[] args) {
                BoxHost host = (BoxHost) receiver;
                observedDuringConstruct[0] = host;
                try {
                    Field field = receiver.getClass().getDeclaredField("__instanceDispatcher");
                    field.setAccessible(true);
                    dispatcherWasAssigned[0] = field.get(receiver) != null;
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            }
        };
        setDispatcher(generated, dispatcher);

        Object instance = fiveArgs.newInstance(7, 9L, 1.25, true, "object");
        assertEquals(1, dispatcher.constructCount.get());
        Object[] args = dispatcher.constructedArgs.get(0);
        assertEquals(5, args.length);
        assertSame(Integer.class, args[0].getClass());
        assertSame(Long.class, args[1].getClass());
        assertSame(Double.class, args[2].getClass());
        assertSame(Boolean.class, args[3].getClass());
        assertSame(String.class, args[4].getClass());
        assertEquals(7, args[0]);
        assertEquals(9L, args[1]);
        assertEquals(1.25, (Double) args[2], 0.0);
        assertEquals(true, args[3]);
        assertEquals("object", args[4]);
        assertSame(instance, observedDuringConstruct[0]);
        assertTrue(dispatcherWasAssigned[0]);
        BoxHost host = (BoxHost) instance;
        assertEquals(7, host.intValue);
        assertEquals(9L, host.longValue);
        assertEquals(1.25, host.doubleValue, 0.0);
        assertTrue(host.booleanValue);
        assertEquals("object", host.objectValue);

        Object protectedInstance = protectedArgs.newInstance("protected");
        assertEquals(2, dispatcher.constructCount.get());
        assertArrayEquals(new Object[]{"protected"}, dispatcher.constructedArgs.get(1));
        assertEquals("protected", ((BoxHost) protectedInstance).objectValue);

        setDispatcher(generated, null);
        Object plain = fiveArgs.newInstance(1, 2L, 3.0, false, "plain");
        assertEquals(2, dispatcher.constructCount.get());
        assertEquals(1, ((BoxHost) plain).intValue);
    }

    @Test
    public void boxesArgumentsAndUnboxesEverySupportedReturnKind() throws Exception {
        Class<?> generated = generate(ReturnHost.class, null,
                "intValue", "longValue", "doubleValue", "booleanValue",
                "voidValue", "objectValue", "charValue", "byteValue",
                "shortValue", "floatValue");

        RecordingDispatcher dispatcher = new RecordingDispatcher(
                new InvokeHandler() {
                    @Override
                    public Object invoke(Object receiver, String method, Object[] args) {
                        if ("intValue".equals(method)) {
                            return ((Integer) args[0]) + 10;
                        }
                        if ("longValue".equals(method)) {
                            return ((Long) args[0]) + 20L;
                        }
                        if ("doubleValue".equals(method)) {
                            return ((Double) args[0]) + 1.5d;
                        }
                        if ("booleanValue".equals(method)) {
                            return args[0];
                        }
                        if ("voidValue".equals(method)) {
                            return null;
                        }
                        if ("objectValue".equals(method)) {
                            return "js:" + args[0];
                        }
                        if ("charValue".equals(method)) {
                            return Character.valueOf((char) (((Character) args[0]) + 2));
                        }
                        if ("byteValue".equals(method)) {
                            return Byte.valueOf((byte) (((Byte) args[0]) + 2));
                        }
                        if ("shortValue".equals(method)) {
                            return Short.valueOf((short) (((Short) args[0]) + 2));
                        }
                        if ("floatValue".equals(method)) {
                            return Float.valueOf(((Float) args[0]) + 0.5f);
                        }
                        throw new AssertionError("unexpected method " + method);
                    }
                });
        setDispatcher(generated, dispatcher);
        Object instance = newInstance(generated, new Class<?>[0]);
        ReturnHost host = (ReturnHost) instance;

        assertEquals(15, host.intValue(5));
        assertSame(Integer.class, dispatcher.invokedArgs.get(0)[0].getClass());
        assertEquals(25L, host.longValue(5L));
        assertSame(Long.class, dispatcher.invokedArgs.get(1)[0].getClass());
        assertEquals(3.0d, host.doubleValue(1.5d), 0.0d);
        assertSame(Double.class, dispatcher.invokedArgs.get(2)[0].getClass());
        assertTrue(host.booleanValue(true));
        assertSame(Boolean.class, dispatcher.invokedArgs.get(3)[0].getClass());
        assertNull(host.lastVoid);
        host.voidValue("void-arg");
        assertNull(host.lastVoid);
        assertSame(String.class, dispatcher.invokedArgs.get(4)[0].getClass());
        assertEquals("void-arg", dispatcher.invokedArgs.get(4)[0]);
        assertEquals("js:object", host.objectValue("object"));
        assertSame(String.class, dispatcher.invokedArgs.get(5)[0].getClass());
        assertEquals('C', host.charValue('A'));
        assertSame(Character.class, dispatcher.invokedArgs.get(6)[0].getClass());
        assertEquals((byte) 3, host.byteValue((byte) 1));
        assertSame(Byte.class, dispatcher.invokedArgs.get(7)[0].getClass());
        assertEquals((short) 4, host.shortValue((short) 2));
        assertSame(Short.class, dispatcher.invokedArgs.get(8)[0].getClass());
        assertEquals(1.5f, host.floatValue(1.0f), 0.0f);
        assertSame(Float.class, dispatcher.invokedArgs.get(9)[0].getClass());

        setDispatcher(generated, null);
        ReturnHost plain = (ReturnHost) newInstance(generated, new Class<?>[0]);
        assertEquals(6, plain.intValue(5));
        assertEquals(7L, plain.longValue(5L));
        assertEquals(2.0d, plain.doubleValue(1.5d), 0.0d);
        assertFalse(plain.booleanValue(true));
        plain.voidValue("base-void");
        assertEquals("base-void", plain.lastVoid);
        assertEquals("base:object", plain.objectValue("object"));
        assertEquals('B', plain.charValue('A'));
        assertEquals((byte) 2, plain.byteValue((byte) 1));
        assertEquals((short) 3, plain.shortValue((short) 2));
        assertEquals(1.25f, plain.floatValue(1.0f), 0.0f);
    }

    @Test
    public void virtualCallsFromSuperConstructorFallBackToSuper() throws Exception {
        Class<?> generated = generate(InitHost.class, null, "hook");
        RecordingDispatcher dispatcher = new RecordingDispatcher(null);
        setDispatcher(generated, dispatcher);

        Object instance = newInstance(generated, new Class<?>[]{String.class}, "x");
        InitHost host = (InitHost) instance;
        assertEquals(Arrays.asList("name:x", "super:ctor", "super:7"), host.log);
        assertTrue(dispatcher.invokedMethods.isEmpty());
        assertEquals(1, dispatcher.constructCount.get());

        host.hook("later");
        assertEquals(1, dispatcher.invokedMethods.size());
        assertEquals("hook", dispatcher.invokedMethods.get(0));
        assertArrayEquals(new Object[]{"later"}, dispatcher.invokedArgs.get(0));

        Method superHook = generated.getDeclaredMethod("super$hook", String.class);
        superHook.invoke(instance, "manual");
        assertEquals(Arrays.asList("name:x", "super:ctor", "super:7", "super:manual"), host.log);
        assertEquals(1, dispatcher.invokedMethods.size());
    }

    @Test
    public void filtersNonOverridableMethodsAndDeduplicatesInterfaceSignatures() throws Exception {
        Class<?> generated = generate(FilterHost.class,
                new Class<?>[]{ClickA.class, ClickB.class},
                "finalMethod", "staticMethod", "privateMethod", "packageMethod",
                "overridable", "click");

        assertNoMethod(generated, "finalMethod");
        assertNoMethod(generated, "staticMethod");
        assertNoMethod(generated, "privateMethod");
        assertNoMethod(generated, "packageMethod");
        Method overridable = generated.getDeclaredMethod("overridable");
        assertPublic(overridable);

        Method[] clickMethods = generated.getDeclaredMethods();
        int clickCount = 0;
        for (Method method : clickMethods) {
            if ("click".equals(method.getName())) {
                clickCount++;
                assertEquals(void.class, method.getReturnType());
                assertEquals(0, method.getParameterTypes().length);
            }
        }
        assertEquals(1, clickCount);

        RecordingDispatcher dispatcher = new RecordingDispatcher(
                new InvokeHandler() {
                    @Override
                    public Object invoke(Object receiver, String method, Object[] args) {
                        return "js:" + method;
                    }
                });
        setDispatcher(generated, dispatcher);
        Object instance = newInstance(generated, new Class<?>[0]);
        FilterHost host = (FilterHost) instance;

        assertEquals("final", host.finalMethod());
        assertEquals("static", FilterHost.staticMethod());
        assertEquals("package", host.packageMethod());
        assertEquals("js:overridable", host.overridable());
        assertEquals(1, dispatcher.invokedMethods.size());

        Method privateMethod = FilterHost.class.getDeclaredMethod("privateMethod");
        privateMethod.setAccessible(true);
        assertEquals("private", privateMethod.invoke(host));

        ((ClickA) instance).click();
        ((ClickB) instance).click();
        assertEquals(3, dispatcher.invokedMethods.size());
        assertEquals("click", dispatcher.invokedMethods.get(1));
        assertEquals("click", dispatcher.invokedMethods.get(2));
        assertEquals(0, dispatcher.invokedArgs.get(1).length);
    }

    @Test
    public void generatesCovariantBridgeAndCallsMostSpecificSuperImplementation()
            throws Exception {
        Class<?> generated = generate(CovariantBase.class,
                new Class<?>[]{CovariantInterface.class}, "value");

        Method stringValue = null;
        Method objectValue = null;
        for (Method method : generated.getDeclaredMethods()) {
            if (!"value".equals(method.getName())
                    || method.getParameterTypes().length != 0) {
                continue;
            }
            if (method.getReturnType() == String.class) {
                stringValue = method;
            } else if (method.getReturnType() == Object.class) {
                objectValue = method;
            }
        }
        assertNotNull(stringValue);
        assertNotNull(objectValue);
        assertPublic(stringValue);
        assertFalse(stringValue.isBridge());
        assertFalse(stringValue.isSynthetic());
        assertPublic(objectValue);
        assertTrue(objectValue.isBridge());
        assertTrue(objectValue.isSynthetic());

        Method superValue = generated.getDeclaredMethod("super$value");
        assertEquals(Object.class, superValue.getReturnType());

        RecordingDispatcher dispatcher = new RecordingDispatcher(
                new InvokeHandler() {
                    @Override
                    public Object invoke(Object receiver, String method, Object[] args) {
                        return "js-value";
                    }
                });
        setDispatcher(generated, dispatcher);
        Object instance = newInstance(generated, new Class<?>[0]);

        assertEquals("js-value", ((CovariantInterface) instance).value());
        assertEquals("js-value", ((CovariantBase) instance).value());
        assertEquals(2, dispatcher.invokedMethods.size());
        assertEquals("base", superValue.invoke(instance));
        assertEquals(2, dispatcher.invokedMethods.size());

        setDispatcher(generated, null);
        Object plain = newInstance(generated, new Class<?>[0]);
        assertEquals("base", ((CovariantInterface) plain).value());
        assertEquals("base", ((CovariantBase) plain).value());

        Class<?> mostSpecific = generate(CovariantMid.class,
                new Class<?>[]{CovariantInterface.class}, "value");
        setDispatcher(mostSpecific, new RecordingDispatcher(
                new InvokeHandler() {
                    @Override
                    public Object invoke(Object receiver, String method, Object[] args) {
                        return "js-mid";
                    }
                }));
        Object mid = newInstance(mostSpecific, new Class<?>[0]);
        assertEquals("js-mid", ((CovariantInterface) mid).value());
        assertEquals("mid", mostSpecific.getDeclaredMethod("super$value").invoke(mid));
    }

    private static Class<?> generate(Class<?> superClass, Class<?>[] interfaces,
                                     String... methods) throws Exception {
        String className = "generated.node24.Gen" + CLASS_SEQUENCE.incrementAndGet();
        LinkedHashSet<String> names = new LinkedHashSet<>(Arrays.asList(methods));
        byte[] bytes = Node24ClassGenerator.generate(className, superClass, interfaces, names);
        assertEquals(0xCAFEBABE, ((bytes[0] & 0xff) << 24) | ((bytes[1] & 0xff) << 16)
                | ((bytes[2] & 0xff) << 8) | (bytes[3] & 0xff));
        assertEquals(52, ((bytes[6] & 0xff) << 8) | (bytes[7] & 0xff));
        return new ByteLoader(className, bytes).define();
    }

    private static void setDispatcher(Class<?> generated,
                                      Node24ClassGenerator.Dispatcher dispatcher)
            throws Exception {
        Field field = generated.getField("__dispatcher");
        field.set(null, dispatcher);
    }

    private static Object newInstance(Class<?> generated, Class<?>[] parameterTypes,
                                      Object... args) throws Exception {
        return generated.getConstructor(parameterTypes).newInstance(args);
    }

    private static void assertPublic(Method method) {
        assertTrue("method should be public: " + method, Modifier.isPublic(method.getModifiers()));
    }

    private static void assertNoMethod(Class<?> type, String name, Class<?>... parameterTypes) {
        try {
            type.getDeclaredMethod(name, parameterTypes);
            fail("unexpected method " + name + " on " + type.getName());
        } catch (NoSuchMethodException expected) {
            // expected
        }
    }

    private static void assertNoConstructor(Class<?> type, Class<?>... parameterTypes) {
        try {
            type.getDeclaredConstructor(parameterTypes);
            fail("unexpected constructor on " + type.getName()
                    + Arrays.toString(parameterTypes));
        } catch (NoSuchMethodException expected) {
            // expected
        }
    }

    private interface InvokeHandler {
        Object invoke(Object receiver, String method, Object[] args);
    }

    private interface ConstructHandler {
        void construct(Object receiver, Object[] args);
    }

    private static final class RecordingDispatcher implements Node24ClassGenerator.Dispatcher {
        final AtomicInteger constructCount = new AtomicInteger();
        final List<String> invokedMethods = new ArrayList<>();
        final List<Object[]> invokedArgs = new ArrayList<>();
        final List<Object> invokedReceivers = new ArrayList<>();
        final List<Object[]> constructedArgs = new ArrayList<>();
        final List<Object> constructedReceivers = new ArrayList<>();
        InvokeHandler invokeHandler;
        ConstructHandler constructHandler;

        RecordingDispatcher(InvokeHandler invokeHandler) {
            this.invokeHandler = invokeHandler;
        }

        @Override
        public Object invoke(Object receiver, String method, Object[] args) {
            invokedMethods.add(method);
            invokedReceivers.add(receiver);
            invokedArgs.add(args);
            return invokeHandler == null ? null : invokeHandler.invoke(receiver, method, args);
        }

        @Override
        public void construct(Object receiver, Object[] args) {
            constructCount.incrementAndGet();
            constructedReceivers.add(receiver);
            constructedArgs.add(args);
            if (constructHandler != null) {
                constructHandler.construct(receiver, args);
            }
        }
    }

    private static final class ByteLoader extends ClassLoader {
        private final String className;
        private final byte[] bytes;

        ByteLoader(String className, byte[] bytes) {
            super(Node24ClassGeneratorTest.class.getClassLoader());
            this.className = className;
            this.bytes = bytes;
        }

        Class<?> define() {
            return defineClass(className, bytes, 0, bytes.length);
        }
    }

    public static abstract class SqliteLike {
        public final String name;
        public final int version;

        public SqliteLike(String name, int version) {
            this.name = name;
            this.version = version;
        }

        public abstract void onCreate(Object db);

        public abstract void onUpgrade(Object db, int oldVersion, int newVersion);
    }

    public interface Clickable {
        void onClick(Object view);
    }

    public static class ViewLike {
        public int drawCalls;

        protected void onDraw(Object canvas) {
            drawCalls += 1;
        }

        protected void onDraw(Object canvas, int depth) {
            drawCalls += 100;
        }
    }

    public static class ButtonLike extends ViewLike {
        public final String text;

        public ButtonLike(String text) {
            this.text = text;
        }
    }

    public static class BoxHost {
        public final int intValue;
        public final long longValue;
        public final double doubleValue;
        public final boolean booleanValue;
        public final Object objectValue;

        public BoxHost() {
            this(0, 0L, 0.0d, false, null);
        }

        public BoxHost(int intValue, long longValue, double doubleValue,
                       boolean booleanValue, Object objectValue) {
            this.intValue = intValue;
            this.longValue = longValue;
            this.doubleValue = doubleValue;
            this.booleanValue = booleanValue;
            this.objectValue = objectValue;
        }

        protected BoxHost(String text) {
            this(-1, 0L, 0.0d, false, text);
        }

        BoxHost(double ignored) {
            this((int) ignored, 0L, ignored, false, "package");
        }
    }

    public static class ReturnHost {
        public Object lastVoid;

        public int intValue(int value) {
            return value + 1;
        }

        public long longValue(long value) {
            return value + 2L;
        }

        public double doubleValue(double value) {
            return value + 0.5d;
        }

        public boolean booleanValue(boolean value) {
            return !value;
        }

        public void voidValue(Object value) {
            lastVoid = value;
        }

        public Object objectValue(Object value) {
            return "base:" + value;
        }

        public char charValue(char value) {
            return (char) (value + 1);
        }

        public byte byteValue(byte value) {
            return (byte) (value + 1);
        }

        public short shortValue(short value) {
            return (short) (value + 1);
        }

        public float floatValue(float value) {
            return value + 0.25f;
        }
    }

    public static class InitHost {
        public final List<String> log = new ArrayList<>();

        public InitHost(String name) {
            log.add("name:" + name);
            hook("ctor");
            hook(7);
        }

        public void hook(String value) {
            log.add("super:" + value);
        }

        public void hook(int value) {
            log.add("super:" + value);
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
}
