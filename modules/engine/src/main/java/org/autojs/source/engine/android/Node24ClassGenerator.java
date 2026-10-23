package org.autojs.source.engine.android;

import org.mozilla.classfile.ByteCode;
import org.mozilla.classfile.ClassFileWriter;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 为 Node24 的 {@code $java.defineClass} 生成真实的 Java 子类字节码。
 *
 * <p>生成类不依赖 Android API，只依赖 Rhino 自带的
 * {@link ClassFileWriter}；可在普通 JVM 上直接定义和验证。生成结果交给
 * {@code AndroidClassLoader.defineClass}；生成器只使用普通虚调用和装拆箱，
 * 显式输出 major 51 以使用轻量 dx，失败时仍可回退 D8。
 *
 * <p>Android 端可直接调用 {@link Node24DirectDexGenerator#generateDex}，两条路径
 * 共享 {@link #plan} 的校验、构造器、覆写/桥接与 {@code super$} 选择结果。
 *
 * <p>契约：
 * <ul>
 *   <li>生成类包含 {@code public static Dispatcher __dispatcher;} 与
 *       {@code private final Dispatcher __instanceDispatcher;}；</li>
 *   <li>父类每个 public/protected 构造器都有同签名 public 构造器，先调用
 *       {@code super(...)}，再写入实例 dispatcher，最后回调
 *       {@code construct(this, boxedArgs)}；</li>
 *   <li>jsMethods 命中的每个可覆写 public/protected 方法生成 public override。
 *       dispatcher 非空时回调 {@code invoke(this, name, boxedArgs)} 并按返回类型
 *       拆箱；为空时（尤其是父构造器内回调到 override 时）安全回退到 super，
 *       没有 super 实现的 abstract 方法显式抛 {@link IllegalStateException}；</li>
 *   <li>有真实 super 实现的 override 额外生成 {@code public final super$name(...)}
 *       供 JS 调用 {@code super.name(...)}。</li>
 * </ul>
 *
 * <p>package-private 方法一律不覆写：AndroidClassLoader 通过子 DexClassLoader
 * 定义生成类，即使包名相同，JVM/ART 的 runtime package 仍不同，覆写/调用
 * package-private 成员都会变成非法访问。
 */
public final class Node24ClassGenerator {

    public interface Dispatcher {
        Object invoke(Object receiver, String method, Object[] args);

        void construct(Object receiver, Object[] args);
    }

    // 用 class literal 而不是写死 binary name：release 若启用 R8，混淆后的
    // Dispatcher 名称仍与实际运行时类一致。
    static final String DISPATCHER_INTERNAL =
            Dispatcher.class.getName().replace('.', '/');
    static final String DISPATCHER_DESCRIPTOR = "L" + DISPATCHER_INTERNAL + ";";
    static final String STATIC_DISPATCHER = "__dispatcher";
    static final String INSTANCE_DISPATCHER = "__instanceDispatcher";
    static final String INVOKE_DESCRIPTOR =
            "(Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;";
    static final String CONSTRUCT_DESCRIPTOR =
            "(Ljava/lang/Object;[Ljava/lang/Object;)V";

    // ClassFileWriter 没有导出这两个 flag；JVM 规范值固定。
    private static final int ACC_BRIDGE = 0x0040;
    private static final int ACC_SYNTHETIC = 0x1000;

    private static final Comparator<Method> METHOD_ORDER = new Comparator<Method>() {
        @Override
        public int compare(Method left, Method right) {
            int byName = left.getName().compareTo(right.getName());
            if (byName != 0) {
                return byName;
            }
            return methodDescriptor(left.getReturnType(), left.getParameterTypes())
                    .compareTo(methodDescriptor(right.getReturnType(), right.getParameterTypes()));
        }
    };

    private static final Comparator<Constructor<?>> CONSTRUCTOR_ORDER =
            new Comparator<Constructor<?>>() {
                @Override
                public int compare(Constructor<?> left, Constructor<?> right) {
                    return methodDescriptor(void.class, left.getParameterTypes())
                            .compareTo(methodDescriptor(void.class, right.getParameterTypes()));
                }
            };

    private Node24ClassGenerator() {
    }

    public static byte[] generate(String className, Class<?> superClass, Class<?>[] interfaces,
                                  Set<String> jsMethods) {
        GenerationPlan plan = plan(className, superClass, interfaces, jsMethods);
        ClassFileWriter cw = new ClassFileWriter(plan.owner, plan.superOwner, null);
        cw.setFlags((short) (ClassFileWriter.ACC_PUBLIC | ClassFileWriter.ACC_SUPER));
        for (Class<?> iface : plan.explicitInterfaces) {
            cw.addInterface(internalName(iface));
        }

        cw.addField(STATIC_DISPATCHER, DISPATCHER_DESCRIPTOR,
                (short) (ClassFileWriter.ACC_PUBLIC | ClassFileWriter.ACC_STATIC));
        cw.addField(INSTANCE_DISPATCHER, DISPATCHER_DESCRIPTOR,
                (short) (ClassFileWriter.ACC_PRIVATE | ClassFileWriter.ACC_FINAL));

        for (Constructor<?> constructor : plan.constructors) {
            emitConstructor(cw, plan.owner, plan.superOwner, constructor);
        }
        for (Candidate candidate : plan.candidates) {
            emitCandidate(cw, plan.owner, plan.superOwner, candidate);
        }
        byte[] bytecode = cw.toByteArray();
        // This generator emits ordinary constructors, boxing and virtual calls,
        // with no Java 8 classfile features. Rhino defaults to major 52; target
        // Java 7 explicitly so these small adapters can use the lightweight dx
        // path. General user jars retain their own version and the D8 path.
        bytecode[6] = 0;
        bytecode[7] = 51;
        return bytecode;
    }

    /**
     * Validates the inputs and resolves exactly which members a generated subclass
     * must contain. Both the classfile and the direct-DEX generators consume this
     * plan, so constructors, overrides, bridge methods and {@code super$} helpers
     * cannot drift between the two output formats.
     */
    static GenerationPlan plan(String className, Class<?> superClass, Class<?>[] interfaces,
                               Set<String> jsMethods) {
        validateClassName(className);
        if (superClass == null) {
            throw new IllegalArgumentException("superClass == null");
        }
        if (superClass.isInterface() || superClass.isArray() || superClass.isPrimitive()) {
            throw new IllegalArgumentException("unsupported super class: " + superClass);
        }
        if (Modifier.isFinal(superClass.getModifiers())) {
            throw new IllegalArgumentException("cannot extend final class: " + superClass.getName());
        }
        requirePublicType(superClass, "super class");
        if (className.equals(superClass.getName())) {
            throw new IllegalArgumentException("generated class must differ from its super class");
        }

        Set<Class<?>> explicitInterfaces = new LinkedHashSet<>();
        if (interfaces != null) {
            for (Class<?> iface : interfaces) {
                if (iface == null) {
                    throw new IllegalArgumentException("interfaces contains null");
                }
                if (!iface.isInterface()) {
                    throw new IllegalArgumentException("not an interface: " + iface.getName());
                }
                requirePublicType(iface, "interface");
                explicitInterfaces.add(iface);
            }
        }

        Set<String> names = jsMethods == null
                ? Collections.<String>emptySet()
                : new LinkedHashSet<>(jsMethods);

        final String owner = internalName(className);
        final String superOwner = internalName(superClass);

        List<Constructor<?>> constructors = collectConstructors(superClass);
        if (constructors.isEmpty()) {
            throw new IllegalArgumentException(
                    "no public/protected constructor in " + superClass.getName());
        }

        List<Class<?>> allInterfaces = collectInterfaces(superClass, explicitInterfaces);
        Map<String, Candidate> candidates = collectCandidates(superClass, allInterfaces, names);
        List<Candidate> ordered = new ArrayList<>(candidates.values());
        Collections.sort(ordered, new Comparator<Candidate>() {
            @Override
            public int compare(Candidate left, Candidate right) {
                return left.key.compareTo(right.key);
            }
        });
        return new GenerationPlan(owner, superOwner,
                Collections.unmodifiableList(new ArrayList<>(explicitInterfaces)),
                Collections.unmodifiableList(constructors),
                Collections.unmodifiableList(ordered));
    }

    /** Immutable output of {@link #plan}; shared by both bytecode backends. */
    static final class GenerationPlan {
        final String owner;
        final String superOwner;
        final List<Class<?>> explicitInterfaces;
        final List<Constructor<?>> constructors;
        final List<Candidate> candidates;

        GenerationPlan(String owner, String superOwner, List<Class<?>> explicitInterfaces,
                       List<Constructor<?>> constructors, List<Candidate> candidates) {
            this.owner = owner;
            this.superOwner = superOwner;
            this.explicitInterfaces = explicitInterfaces;
            this.constructors = constructors;
            this.candidates = candidates;
        }
    }

    private static void validateClassName(String className) {
        if (className == null || className.isEmpty()) {
            throw new IllegalArgumentException("className is empty");
        }
        if (className.indexOf('/') >= 0 || className.startsWith(".")
                || className.endsWith(".") || className.contains("..")) {
            throw new IllegalArgumentException("invalid binary class name: " + className);
        }
    }

    private static void requirePublicType(Class<?> type, String role) {
        Class<?> current = type;
        while (current != null) {
            if (!Modifier.isPublic(current.getModifiers())) {
                throw new IllegalArgumentException(
                        role + " is not publicly accessible: " + type.getName());
            }
            current = current.getEnclosingClass();
        }
    }

    private static List<Constructor<?>> collectConstructors(Class<?> superClass) {
        List<Constructor<?>> result = new ArrayList<>();
        for (Constructor<?> constructor : superClass.getDeclaredConstructors()) {
            if (constructor.isSynthetic()) {
                continue;
            }
            int modifiers = constructor.getModifiers();
            if (Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers)) {
                result.add(constructor);
            }
        }
        Collections.sort(result, CONSTRUCTOR_ORDER);
        return result;
    }

    private static List<Class<?>> collectInterfaces(Class<?> superClass,
                                                    Set<Class<?>> explicitInterfaces) {
        LinkedHashSet<Class<?>> all = new LinkedHashSet<>();
        for (Class<?> iface : explicitInterfaces) {
            addInterfaceTree(iface, all);
        }
        for (Class<?> current = superClass; current != null; current = current.getSuperclass()) {
            for (Class<?> iface : current.getInterfaces()) {
                addInterfaceTree(iface, all);
            }
        }
        List<Class<?>> result = new ArrayList<>(all);
        Collections.sort(result, new Comparator<Class<?>>() {
            @Override
            public int compare(Class<?> left, Class<?> right) {
                return left.getName().compareTo(right.getName());
            }
        });
        return result;
    }

    private static void addInterfaceTree(Class<?> iface, Set<Class<?>> out) {
        if (!out.add(iface)) {
            return;
        }
        for (Class<?> parent : iface.getInterfaces()) {
            addInterfaceTree(parent, out);
        }
    }

    private static Map<String, Candidate> collectCandidates(Class<?> superClass,
                                                            List<Class<?>> allInterfaces,
                                                            Set<String> jsMethods) {
        Map<String, Candidate> candidates = new LinkedHashMap<>();
        Set<String> seenClassSignatures = new HashSet<>();
        Set<String> blockedClassSignatures = new HashSet<>();

        for (Class<?> current = superClass; current != null; current = current.getSuperclass()) {
            Method[] methods = current.getDeclaredMethods();
            Arrays.sort(methods, METHOD_ORDER);
            for (Method method : methods) {
                if (!jsMethods.contains(method.getName())
                        || method.getName().startsWith("<")
                        || method.isSynthetic()
                        || method.isBridge()) {
                    continue;
                }
                String key = signatureKey(method.getName(), method.getParameterTypes());
                if (!seenClassSignatures.add(key)) {
                    // 已经遇到更具体的父类声明，优先它。
                    continue;
                }
                int modifiers = method.getModifiers();
                if (Modifier.isStatic(modifiers)
                        || Modifier.isPrivate(modifiers)
                        || Modifier.isFinal(modifiers)
                        || !isOverridableAccess(modifiers)) {
                    // 该签名被不可覆写的声明占住，禁止从更远祖先里“捡”
                    // 一个可覆写方法，也禁止接口再塞一个同签名方法。
                    blockedClassSignatures.add(key);
                    continue;
                }
                Candidate candidate = candidates.get(key);
                if (candidate == null) {
                    candidate = new Candidate(key, method.getName(), method.getParameterTypes());
                    candidates.put(key, candidate);
                }
                candidate.classMethod = method;
                candidate.addReturnType(method.getReturnType());
            }
        }

        for (Class<?> iface : allInterfaces) {
            Method[] methods = iface.getDeclaredMethods();
            Arrays.sort(methods, METHOD_ORDER);
            for (Method method : methods) {
                if (!jsMethods.contains(method.getName())
                        || method.getName().startsWith("<")
                        || method.isSynthetic()
                        || method.isBridge()) {
                    continue;
                }
                int modifiers = method.getModifiers();
                if (Modifier.isStatic(modifiers) || Modifier.isPrivate(modifiers)) {
                    continue;
                }
                String key = signatureKey(method.getName(), method.getParameterTypes());
                if (blockedClassSignatures.contains(key)) {
                    // 父类里该签名有 final/static/private/package-private 声明：
                    // 生成类既不能合法覆写它，也不能靠接口再塞一个同签名方法。
                    continue;
                }
                Candidate candidate = candidates.get(key);
                if (candidate == null) {
                    candidate = new Candidate(key, method.getName(), method.getParameterTypes());
                    candidates.put(key, candidate);
                }
                candidate.addReturnType(method.getReturnType());
            }
        }

        for (Candidate candidate : candidates.values()) {
            candidate.primaryReturn = choosePrimaryReturn(candidate.returnTypes);
        }
        return candidates;
    }

    private static boolean isOverridableAccess(int modifiers) {
        return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers);
    }

    private static Class<?> choosePrimaryReturn(List<Class<?>> returnTypes) {
        for (Class<?> candidate : returnTypes) {
            boolean mostSpecific = true;
            for (Class<?> other : returnTypes) {
                if (!isCovariantReturn(candidate, other)) {
                    mostSpecific = false;
                    break;
                }
            }
            if (mostSpecific) {
                return candidate;
            }
        }
        return returnTypes.get(0);
    }

    private static boolean isCovariantReturn(Class<?> candidate, Class<?> other) {
        if (candidate == other) {
            return true;
        }
        if (candidate.isPrimitive() || other.isPrimitive()) {
            return false;
        }
        return other.isAssignableFrom(candidate);
    }

    private static void emitConstructor(ClassFileWriter cw, String owner, String superOwner,
                                        Constructor<?> constructor) {
        Class<?>[] parameterTypes = constructor.getParameterTypes();
        String descriptor = methodDescriptor(void.class, parameterTypes);
        cw.startMethod("<init>", descriptor, ClassFileWriter.ACC_PUBLIC);

        cw.addLoadThis();
        loadArguments(cw, parameterTypes, 1);
        cw.addInvoke(ByteCode.INVOKESPECIAL, superOwner, "<init>", descriptor);

        cw.addLoadThis();
        cw.add(ByteCode.GETSTATIC, owner, STATIC_DISPATCHER, DISPATCHER_DESCRIPTOR);
        cw.add(ByteCode.PUTFIELD, owner, INSTANCE_DISPATCHER, DISPATCHER_DESCRIPTOR);

        cw.addLoadThis();
        cw.add(ByteCode.GETFIELD, owner, INSTANCE_DISPATCHER, DISPATCHER_DESCRIPTOR);
        int done = cw.acquireLabel();
        cw.add(ByteCode.IFNULL, done);

        cw.addLoadThis();
        cw.add(ByteCode.GETFIELD, owner, INSTANCE_DISPATCHER, DISPATCHER_DESCRIPTOR);
        cw.addLoadThis();
        addBoxedArguments(cw, parameterTypes, 1);
        cw.addInvoke(ByteCode.INVOKEINTERFACE, DISPATCHER_INTERNAL, "construct",
                CONSTRUCT_DESCRIPTOR);

        cw.markLabel(done);
        cw.add(ByteCode.RETURN);
        cw.stopMethod((short) maxLocals(parameterTypes));
    }

    private static void emitCandidate(ClassFileWriter cw, String owner, String superOwner,
                                      Candidate candidate) {
        Class<?> primary = candidate.primaryReturn;
        emitDispatchOverride(cw, owner, superOwner, candidate, primary);

        for (Class<?> returnType : candidate.returnTypes) {
            if (returnType == primary) {
                continue;
            }
            if (isReferenceCovariant(primary, returnType)) {
                emitBridge(cw, owner, candidate, primary, returnType);
            } else {
                // 同一参数签名出现互不兼容的返回类型（正常 javac 不会生成）时，
                // 仍生成独立 override，至少让每个 JVM 描述符都能被调用。
                emitDispatchOverride(cw, owner, superOwner, candidate, returnType);
            }
        }

        if (candidate.hasConcreteSuper()) {
            emitSuperHelper(cw, superOwner, candidate);
        }
    }

    static boolean isReferenceCovariant(Class<?> primary, Class<?> other) {
        return !primary.isPrimitive() && !other.isPrimitive() && other.isAssignableFrom(primary);
    }

    private static void emitDispatchOverride(ClassFileWriter cw, String owner, String superOwner,
                                             Candidate candidate, Class<?> returnType) {
        String descriptor = methodDescriptor(returnType, candidate.parameterTypes);
        cw.startMethod(candidate.name, descriptor, ClassFileWriter.ACC_PUBLIC);

        cw.addLoadThis();
        cw.add(ByteCode.GETFIELD, owner, INSTANCE_DISPATCHER, DISPATCHER_DESCRIPTOR);
        int fallback = cw.acquireLabel();
        cw.add(ByteCode.IFNULL, fallback);

        cw.addLoadThis();
        cw.add(ByteCode.GETFIELD, owner, INSTANCE_DISPATCHER, DISPATCHER_DESCRIPTOR);
        cw.addLoadThis();
        cw.addLoadConstant(candidate.name);
        addBoxedArguments(cw, candidate.parameterTypes, 1);
        cw.addInvoke(ByteCode.INVOKEINTERFACE, DISPATCHER_INTERNAL, "invoke", INVOKE_DESCRIPTOR);
        emitReturnFromObject(cw, returnType);

        cw.markLabel(fallback);
        if (candidate.hasConcreteSuper()
                && canAdaptSuper(candidate.superReturnType(), returnType)) {
            emitSuperFallback(cw, superOwner, candidate, returnType);
        } else {
            emitMissingSuper(cw, candidate, returnType);
        }
        cw.stopMethod((short) maxLocals(candidate.parameterTypes));
    }

    private static void emitBridge(ClassFileWriter cw, String owner, Candidate candidate,
                                   Class<?> primaryReturn, Class<?> bridgeReturn) {
        String descriptor = methodDescriptor(bridgeReturn, candidate.parameterTypes);
        cw.startMethod(candidate.name, descriptor,
                (short) (ClassFileWriter.ACC_PUBLIC | ACC_BRIDGE | ACC_SYNTHETIC));

        cw.addLoadThis();
        loadArguments(cw, candidate.parameterTypes, 1);
        cw.addInvoke(ByteCode.INVOKEVIRTUAL, owner, candidate.name,
                methodDescriptor(primaryReturn, candidate.parameterTypes));

        if (bridgeReturn == void.class) {
            if (primaryReturn != void.class) {
                popValue(cw, primaryReturn);
            }
            cw.add(ByteCode.RETURN);
        } else if (bridgeReturn.isPrimitive()) {
            cw.add(returnOpcode(bridgeReturn));
        } else {
            cw.add(ByteCode.ARETURN);
        }
        cw.stopMethod((short) maxLocals(candidate.parameterTypes));
    }

    private static void emitSuperHelper(ClassFileWriter cw, String superOwner,
                                        Candidate candidate) {
        Class<?> superReturn = candidate.superReturnType();
        String descriptor = methodDescriptor(superReturn, candidate.parameterTypes);
        cw.startMethod("super$" + candidate.name, descriptor,
                (short) (ClassFileWriter.ACC_PUBLIC | ClassFileWriter.ACC_FINAL));

        cw.addLoadThis();
        loadArguments(cw, candidate.parameterTypes, 1);
        cw.addInvoke(ByteCode.INVOKESPECIAL, superOwner, candidate.name, descriptor);
        cw.add(returnOpcode(superReturn));
        cw.stopMethod((short) maxLocals(candidate.parameterTypes));
    }

    private static boolean canAdaptSuper(Class<?> superReturn, Class<?> methodReturn) {
        if (superReturn == methodReturn) {
            return true;
        }
        if (methodReturn == void.class) {
            return true;
        }
        if (superReturn == void.class) {
            return false;
        }
        if (methodReturn.isPrimitive() || superReturn.isPrimitive()) {
            return false;
        }
        return true;
    }

    private static void emitSuperFallback(ClassFileWriter cw, String superOwner,
                                          Candidate candidate, Class<?> methodReturn) {
        Class<?> superReturn = candidate.superReturnType();
        cw.addLoadThis();
        loadArguments(cw, candidate.parameterTypes, 1);
        cw.addInvoke(ByteCode.INVOKESPECIAL, superOwner, candidate.name,
                methodDescriptor(superReturn, candidate.parameterTypes));

        if (methodReturn == void.class) {
            if (superReturn != void.class) {
                popValue(cw, superReturn);
            }
            cw.add(ByteCode.RETURN);
        } else if (methodReturn.isPrimitive()) {
            cw.add(returnOpcode(methodReturn));
        } else {
            cw.add(ByteCode.CHECKCAST, internalName(methodReturn));
            cw.add(ByteCode.ARETURN);
        }
    }

    private static void popValue(ClassFileWriter cw, Class<?> type) {
        cw.add(type == long.class || type == double.class ? ByteCode.POP2 : ByteCode.POP);
    }

    private static void emitMissingSuper(ClassFileWriter cw, Candidate candidate,
                                         Class<?> returnType) {
        cw.add(ByteCode.NEW, "java/lang/IllegalStateException");
        cw.add(ByteCode.DUP);
        cw.addLoadConstant("Node24 generated method " + candidate.name
                + methodDescriptor(returnType, candidate.parameterTypes)
                + " has no super implementation");
        cw.addInvoke(ByteCode.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>",
                "(Ljava/lang/String;)V");
        cw.add(ByteCode.ATHROW);
    }

    private static void emitReturnFromObject(ClassFileWriter cw, Class<?> returnType) {
        if (returnType == void.class) {
            cw.add(ByteCode.POP);
            cw.add(ByteCode.RETURN);
            return;
        }
        if (!returnType.isPrimitive()) {
            cw.add(ByteCode.CHECKCAST, internalName(returnType));
            cw.add(ByteCode.ARETURN);
            return;
        }
        String wrapper = wrapperInternalName(returnType);
        cw.add(ByteCode.CHECKCAST, wrapper);
        cw.addInvoke(ByteCode.INVOKEVIRTUAL, wrapper, unboxMethodName(returnType),
                "()" + typeDescriptor(returnType));
        cw.add(returnOpcode(returnType));
    }

    private static void addBoxedArguments(ClassFileWriter cw, Class<?>[] parameterTypes,
                                          int firstLocal) {
        cw.addPush(parameterTypes.length);
        cw.add(ByteCode.ANEWARRAY, "java/lang/Object");
        int local = firstLocal;
        for (int i = 0; i < parameterTypes.length; i++) {
            Class<?> type = parameterTypes[i];
            cw.add(ByteCode.DUP);
            cw.addPush(i);
            loadArgument(cw, type, local);
            boxIfPrimitive(cw, type);
            cw.add(ByteCode.AASTORE);
            local += words(type);
        }
    }

    private static void boxIfPrimitive(ClassFileWriter cw, Class<?> type) {
        if (!type.isPrimitive()) {
            return;
        }
        String wrapper = wrapperInternalName(type);
        cw.addInvoke(ByteCode.INVOKESTATIC, wrapper, "valueOf",
                "(" + typeDescriptor(type) + ")L" + wrapper + ";");
    }

    private static void loadArguments(ClassFileWriter cw, Class<?>[] parameterTypes,
                                      int firstLocal) {
        int local = firstLocal;
        for (Class<?> type : parameterTypes) {
            loadArgument(cw, type, local);
            local += words(type);
        }
    }

    private static void loadArgument(ClassFileWriter cw, Class<?> type, int local) {
        if (type == long.class) {
            cw.addLLoad(local);
        } else if (type == double.class) {
            cw.addDLoad(local);
        } else if (type == float.class) {
            cw.addFLoad(local);
        } else if (type.isPrimitive()) {
            cw.addILoad(local);
        } else {
            cw.addALoad(local);
        }
    }

    private static int maxLocals(Class<?>[] parameterTypes) {
        int locals = 1;
        for (Class<?> type : parameterTypes) {
            locals += words(type);
        }
        return locals;
    }

    static int words(Class<?> type) {
        return type == long.class || type == double.class ? 2 : 1;
    }

    private static int returnOpcode(Class<?> type) {
        if (type == void.class) {
            return ByteCode.RETURN;
        }
        if (type == long.class) {
            return ByteCode.LRETURN;
        }
        if (type == float.class) {
            return ByteCode.FRETURN;
        }
        if (type == double.class) {
            return ByteCode.DRETURN;
        }
        if (type.isPrimitive()) {
            return ByteCode.IRETURN;
        }
        return ByteCode.ARETURN;
    }

    static String wrapperInternalName(Class<?> primitiveType) {
        if (primitiveType == boolean.class) {
            return "java/lang/Boolean";
        }
        if (primitiveType == byte.class) {
            return "java/lang/Byte";
        }
        if (primitiveType == char.class) {
            return "java/lang/Character";
        }
        if (primitiveType == short.class) {
            return "java/lang/Short";
        }
        if (primitiveType == int.class) {
            return "java/lang/Integer";
        }
        if (primitiveType == long.class) {
            return "java/lang/Long";
        }
        if (primitiveType == float.class) {
            return "java/lang/Float";
        }
        if (primitiveType == double.class) {
            return "java/lang/Double";
        }
        throw new IllegalArgumentException("not a primitive type: " + primitiveType);
    }

    static String unboxMethodName(Class<?> primitiveType) {
        if (primitiveType == boolean.class) {
            return "booleanValue";
        }
        if (primitiveType == byte.class) {
            return "byteValue";
        }
        if (primitiveType == char.class) {
            return "charValue";
        }
        if (primitiveType == short.class) {
            return "shortValue";
        }
        if (primitiveType == int.class) {
            return "intValue";
        }
        if (primitiveType == long.class) {
            return "longValue";
        }
        if (primitiveType == float.class) {
            return "floatValue";
        }
        if (primitiveType == double.class) {
            return "doubleValue";
        }
        throw new IllegalArgumentException("not a primitive type: " + primitiveType);
    }

    private static String internalName(String binaryName) {
        return binaryName.replace('.', '/');
    }

    static String internalName(Class<?> type) {
        return internalName(type.getName());
    }

    private static String signatureKey(String name, Class<?>[] parameterTypes) {
        StringBuilder builder = new StringBuilder(name.length() + parameterTypes.length * 8);
        builder.append(name).append('(');
        for (Class<?> parameterType : parameterTypes) {
            builder.append(typeDescriptor(parameterType));
        }
        return builder.append(')').toString();
    }

    static String methodDescriptor(Class<?> returnType, Class<?>[] parameterTypes) {
        StringBuilder builder = new StringBuilder(parameterTypes.length * 8 + 8);
        builder.append('(');
        for (Class<?> parameterType : parameterTypes) {
            builder.append(typeDescriptor(parameterType));
        }
        return builder.append(')').append(typeDescriptor(returnType)).toString();
    }

    static String typeDescriptor(Class<?> type) {
        if (type == void.class) {
            return "V";
        }
        if (type == boolean.class) {
            return "Z";
        }
        if (type == byte.class) {
            return "B";
        }
        if (type == char.class) {
            return "C";
        }
        if (type == short.class) {
            return "S";
        }
        if (type == int.class) {
            return "I";
        }
        if (type == long.class) {
            return "J";
        }
        if (type == float.class) {
            return "F";
        }
        if (type == double.class) {
            return "D";
        }
        if (type.isArray()) {
            return type.getName().replace('.', '/');
        }
        return "L" + internalName(type) + ";";
    }

    static final class Candidate {
        final String key;
        final String name;
        final Class<?>[] parameterTypes;
        final List<Class<?>> returnTypes = new ArrayList<>();
        Method classMethod;
        Class<?> primaryReturn;

        Candidate(String key, String name, Class<?>[] parameterTypes) {
            this.key = key;
            this.name = name;
            this.parameterTypes = parameterTypes;
        }

        void addReturnType(Class<?> returnType) {
            if (!returnTypes.contains(returnType)) {
                returnTypes.add(returnType);
            }
        }

        Class<?> superReturnType() {
            return classMethod == null ? null : classMethod.getReturnType();
        }

        boolean hasConcreteSuper() {
            return classMethod != null
                    && !Modifier.isAbstract(classMethod.getModifiers());
        }
    }
}
