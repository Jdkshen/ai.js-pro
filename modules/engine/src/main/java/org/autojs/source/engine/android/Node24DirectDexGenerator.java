package org.autojs.source.engine.android;

import org.antlr.runtime.CommonTokenStream;
import org.antlr.runtime.RecognitionException;
import org.antlr.runtime.TokenSource;
import org.antlr.runtime.tree.CommonTree;
import org.antlr.runtime.tree.CommonTreeNodeStream;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.writer.builder.DexBuilder;
import org.jf.dexlib2.writer.io.MemoryDataStore;
import org.jf.smali.smaliFlexLexer;
import org.jf.smali.smaliParser;
import org.jf.smali.smaliTreeWalker;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.Constructor;
import java.util.Set;

/**
 * Assembles a Node24 dynamic Java subclass directly from Smali into a DEX file.
 *
 * <p>This is the Android counterpart of {@link Node24ClassGenerator#generate}.
 * It intentionally consumes the same {@link Node24ClassGenerator.GenerationPlan},
 * so constructors, overload/interface selection, covariant bridges,
 * {@code super$} helpers, dispatcher callbacks and boxing rules are identical to
 * the classfile path. The only difference is the output format: no classfile, no
 * temporary JAR and no dx/D8 subprocess. Smali is parsed, written into a
 * {@link DexBuilder} and serialized in memory.
 *
 * <p>The generated class has the same runtime contract:
 * <ul>
 *   <li>{@code public static Dispatcher __dispatcher} is assigned by the host
 *       bridge before use;</li>
 *   <li>{@code private final Dispatcher __instanceDispatcher} is copied from the
 *       static field after the super constructor and before the construct
 *       callback;</li>
 *   <li>overrides box their arguments and call
 *       {@code Dispatcher.invoke(this, name, boxedArgs)}; a null dispatcher falls
 *       back to {@code invoke-super} (or throws for an abstract method without a
 *       concrete super implementation);</li>
 *   <li>concrete super methods also expose {@code public final super$name(...)}
 *       for {@code super.name(...)} from JavaScript.</li>
 * </ul>
 *
 * <p>Dependency: {@code org.smali:smali:2.5.2} (which brings dexlib2, util,
 * antlr-runtime and guava). The API used here is the public ANTLR/in-memory path:
 * {@code smaliFlexLexer(Reader, int)}, {@code smaliParser}, {@code smaliTreeWalker},
 * {@code DexBuilder(Opcodes)} and {@code DexBuilder.writeTo(DexDataStore)}.
 */
public final class Node24DirectDexGenerator {

    /**
     * DEX 035 / API 21 opcodes. The generated code only uses plain field access,
     * invoke, boxing and branch instructions available on every supported
     * Android version.
     */
    public static final int DEFAULT_DEX_API = 21;

    /** Smali 标签唯一化：基本类型返回值补默认值的 null 分支。 */
    private static final java.util.concurrent.atomic.AtomicInteger RETURN_DEFAULT_LABEL =
            new java.util.concurrent.atomic.AtomicInteger();

    private Node24DirectDexGenerator() {
    }

    /**
     * Generates a single-class DEX for a dynamic subclass using the same
     * validation and member selection as {@link Node24ClassGenerator#generate}.
     *
     * @param className   binary name of the generated class, for example
     *                    {@code generated.node24.Callback}
     * @param superClass  non-final public class to extend
     * @param interfaces  extra public interfaces to implement, or {@code null}
     * @param jsMethods   method names that JavaScript may override
     * @return a complete DEX file whose only class is {@code className}
     */
    public static byte[] generateDex(String className, Class<?> superClass, Class<?>[] interfaces,
                                     Set<String> jsMethods) {
        return generateDex(className, superClass, interfaces, jsMethods, DEFAULT_DEX_API);
    }

    /**
     * Same as {@link #generateDex(String, Class, Class[], Set)} but with an
     * explicit dexlib2 API level. Callers normally use the default.
     */
    public static byte[] generateDex(String className, Class<?> superClass, Class<?>[] interfaces,
                                     Set<String> jsMethods, int apiLevel) {
        if (apiLevel <= 0) {
            throw new IllegalArgumentException("apiLevel must be positive: " + apiLevel);
        }
        return assemble(generateSmali(className, superClass, interfaces, jsMethods), apiLevel,
                className);
    }

    /**
     * Builds the Smali source without assembling it. Package-private so tests can
     * assert the generated helper syntax independently from dexlib2.
     */
    static String generateSmali(String className, Class<?> superClass, Class<?>[] interfaces,
                                Set<String> jsMethods) {
        Node24ClassGenerator.GenerationPlan plan =
                Node24ClassGenerator.plan(className, superClass, interfaces, jsMethods);

        StringBuilder out = new StringBuilder(4096);
        out.append(".class public L").append(plan.owner).append(";\n");
        out.append(".super L").append(plan.superOwner).append(";\n");
        for (Class<?> iface : plan.explicitInterfaces) {
            out.append(".implements L")
                    .append(Node24ClassGenerator.internalName(iface)).append(";\n");
        }
        out.append('\n');

        out.append(".field public static ")
                .append(Node24ClassGenerator.STATIC_DISPATCHER).append(':')
                .append(Node24ClassGenerator.DISPATCHER_DESCRIPTOR).append('\n');
        out.append(".field private final ")
                .append(Node24ClassGenerator.INSTANCE_DISPATCHER).append(':')
                .append(Node24ClassGenerator.DISPATCHER_DESCRIPTOR).append('\n');
        out.append('\n');

        for (Constructor<?> constructor : plan.constructors) {
            emitConstructor(out, plan, constructor);
        }
        for (Node24ClassGenerator.Candidate candidate : plan.candidates) {
            emitCandidate(out, plan, candidate);
        }
        return out.toString();
    }

    private static byte[] assemble(String smali, int apiLevel, String className) {
        try {
            DexBuilder dexBuilder = new DexBuilder(Opcodes.forApi(apiLevel));

            smaliFlexLexer lexer = new smaliFlexLexer(new StringReader(smali), apiLevel);
            // Only a lexical source name for diagnostics; the file is never read.
            lexer.setSourceFile(new File(className.replace('.', '/') + ".smali"));
            CommonTokenStream tokens = new CommonTokenStream((TokenSource) lexer);

            smaliParser parser = new smaliParser(tokens);
            parser.setVerboseErrors(false);
            parser.setAllowOdex(false);
            parser.setApiLevel(apiLevel);
            smaliParser.smali_file_return parsed = parser.smali_file();
            if (parser.getNumberOfSyntaxErrors() > 0 || lexer.getNumberOfSyntaxErrors() > 0) {
                throw new IllegalStateException("Smali parse failed for " + className
                        + " (parser=" + parser.getNumberOfSyntaxErrors()
                        + ", lexer=" + lexer.getNumberOfSyntaxErrors() + ")");
            }

            CommonTree tree = parsed.getTree();
            CommonTreeNodeStream treeStream = new CommonTreeNodeStream(tree);
            treeStream.setTokenStream(tokens);
            smaliTreeWalker walker = new smaliTreeWalker(treeStream);
            walker.setApiLevel(apiLevel);
            walker.setVerboseErrors(false);
            walker.setDexBuilder(dexBuilder);
            walker.smali_file();
            if (walker.getNumberOfSyntaxErrors() > 0) {
                throw new IllegalStateException("Smali assembly failed for " + className
                        + " (" + walker.getNumberOfSyntaxErrors() + " errors)");
            }

            MemoryDataStore output = new MemoryDataStore(4096);
            dexBuilder.writeTo(output);
            byte[] dex = output.getData();
            if (dex.length < 8 || dex[0] != 'd' || dex[1] != 'e' || dex[2] != 'x'
                    || dex[3] != '\n') {
                throw new IllegalStateException("Smali produced an invalid DEX for " + className);
            }
            return dex;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot assemble generated DEX for " + className, e);
        } catch (RecognitionException e) {
            throw new IllegalStateException("Cannot parse generated Smali for " + className, e);
        }
    }

    private static void emitConstructor(StringBuilder out, Node24ClassGenerator.GenerationPlan plan,
                                        Constructor<?> constructor) {
        Class<?>[] parameters = constructor.getParameterTypes();
        String descriptor = Node24ClassGenerator.methodDescriptor(void.class, parameters);
        out.append(".method public constructor <init>").append(descriptor).append('\n');
        out.append("    .locals 4\n");

        emitInvoke(out, "invoke-direct",
                "L" + plan.superOwner + ";-><init>" + descriptor, parameters);

        String instanceField = "L" + plan.owner + ";->"
                + Node24ClassGenerator.INSTANCE_DISPATCHER + ':'
                + Node24ClassGenerator.DISPATCHER_DESCRIPTOR;
        String staticField = "L" + plan.owner + ";->"
                + Node24ClassGenerator.STATIC_DISPATCHER + ':'
                + Node24ClassGenerator.DISPATCHER_DESCRIPTOR;

        out.append("    sget-object v0, ").append(staticField).append('\n');
        out.append("    iput-object v0, p0, ").append(instanceField).append('\n');
        out.append("    iget-object v0, p0, ").append(instanceField).append('\n');
        out.append("    if-eqz v0, :dispatcher_done\n");
        out.append("    iget-object v0, p0, ").append(instanceField).append('\n');

        emitConstant(out, "v2", parameters.length);
        out.append("    new-array v1, v2, [Ljava/lang/Object;\n");
        out.append("    const/4 v2, 0x0\n");
        int parameterRegister = 1;
        for (Class<?> parameter : parameters) {
            emitBoxArgument(out, parameter, parameterRegister, "v1", "v2", "v3", 4);
            out.append("    add-int/lit8 v2, v2, 0x1\n");
            parameterRegister += Node24ClassGenerator.words(parameter);
        }
        out.append("    invoke-interface {v0, p0, v1}, L")
                .append(Node24ClassGenerator.DISPATCHER_INTERNAL).append(";->construct")
                .append(Node24ClassGenerator.CONSTRUCT_DESCRIPTOR).append('\n');
        out.append("    :dispatcher_done\n");
        out.append("    return-void\n");
        out.append(".end method\n\n");
    }

    private static void emitCandidate(StringBuilder out, Node24ClassGenerator.GenerationPlan plan,
                                      Node24ClassGenerator.Candidate candidate) {
        Class<?> primary = candidate.primaryReturn;
        emitDispatchOverride(out, plan, candidate, primary);

        for (Class<?> returnType : candidate.returnTypes) {
            if (returnType == primary) {
                continue;
            }
            if (Node24ClassGenerator.isReferenceCovariant(primary, returnType)) {
                emitBridge(out, plan, candidate, primary, returnType);
            } else {
                // Same fallback as the classfile generator: incompatible duplicate
                // descriptors still get their own dispatch override.
                emitDispatchOverride(out, plan, candidate, returnType);
            }
        }

        if (candidate.hasConcreteSuper()) {
            emitSuperHelper(out, plan, candidate);
        }
    }

    private static void emitDispatchOverride(StringBuilder out,
                                             Node24ClassGenerator.GenerationPlan plan,
                                             Node24ClassGenerator.Candidate candidate,
                                             Class<?> returnType) {
        Class<?>[] parameters = candidate.parameterTypes;
        String descriptor = Node24ClassGenerator.methodDescriptor(returnType, parameters);
        out.append(".method public ").append(candidate.name).append(descriptor).append('\n');
        out.append("    .locals 5\n");

        String instanceField = "L" + plan.owner + ";->"
                + Node24ClassGenerator.INSTANCE_DISPATCHER + ':'
                + Node24ClassGenerator.DISPATCHER_DESCRIPTOR;
        out.append("    iget-object v0, p0, ").append(instanceField).append('\n');
        out.append("    if-eqz v0, :fallback\n");
        out.append("    iget-object v0, p0, ").append(instanceField).append('\n');
        out.append("    const-string v1, \"").append(escapeSmaliString(candidate.name))
                .append("\"\n");

        emitConstant(out, "v3", parameters.length);
        out.append("    new-array v2, v3, [Ljava/lang/Object;\n");
        out.append("    const/4 v3, 0x0\n");
        int parameterRegister = 1;
        for (Class<?> parameter : parameters) {
            emitBoxArgument(out, parameter, parameterRegister, "v2", "v3", "v4", 5);
            out.append("    add-int/lit8 v3, v3, 0x1\n");
            parameterRegister += Node24ClassGenerator.words(parameter);
        }
        out.append("    invoke-interface {v0, p0, v1, v2}, L")
                .append(Node24ClassGenerator.DISPATCHER_INTERNAL).append(";->invoke")
                .append(Node24ClassGenerator.INVOKE_DESCRIPTOR).append('\n');
        emitReturnFromObject(out, returnType);

        out.append("    :fallback\n");
        if (candidate.hasConcreteSuper()
                && canAdaptSuper(candidate.superReturnType(), returnType)) {
            emitSuperFallback(out, plan, candidate, returnType);
        } else {
            emitMissingSuper(out, candidate, returnType);
        }
        out.append(".end method\n\n");
    }

    private static void emitBridge(StringBuilder out, Node24ClassGenerator.GenerationPlan plan,
                                   Node24ClassGenerator.Candidate candidate,
                                   Class<?> primaryReturn, Class<?> bridgeReturn) {
        Class<?>[] parameters = candidate.parameterTypes;
        String descriptor = Node24ClassGenerator.methodDescriptor(bridgeReturn, parameters);
        out.append(".method public synthetic bridge ").append(candidate.name)
                .append(descriptor).append('\n');
        out.append("    .locals 2\n");

        String target = "L" + plan.owner + ";->" + candidate.name
                + Node24ClassGenerator.methodDescriptor(primaryReturn, parameters);
        emitInvoke(out, "invoke-virtual", target, parameters);

        if (bridgeReturn == void.class) {
            emitMoveResult(out, primaryReturn, 0);
            out.append("    return-void\n");
        } else if (bridgeReturn.isPrimitive()) {
            emitMoveResult(out, bridgeReturn, 0);
            out.append("    return").append(returnSuffix(bridgeReturn)).append(" v0\n");
        } else {
            out.append("    move-result-object v0\n");
            out.append("    return-object v0\n");
        }
        out.append(".end method\n\n");
    }

    private static void emitSuperHelper(StringBuilder out, Node24ClassGenerator.GenerationPlan plan,
                                        Node24ClassGenerator.Candidate candidate) {
        Class<?> superReturn = candidate.superReturnType();
        String descriptor = Node24ClassGenerator.methodDescriptor(
                superReturn, candidate.parameterTypes);
        out.append(".method public final super$").append(candidate.name)
                .append(descriptor).append('\n');
        out.append("    .locals 2\n");
        emitInvoke(out, "invoke-super",
                "L" + plan.superOwner + ";->" + candidate.name + descriptor,
                candidate.parameterTypes);
        if (superReturn == void.class) {
            out.append("    return-void\n");
        } else {
            emitMoveResult(out, superReturn, 0);
            out.append("    return").append(returnSuffix(superReturn)).append(" v0\n");
        }
        out.append(".end method\n\n");
    }

    private static void emitSuperFallback(StringBuilder out,
                                          Node24ClassGenerator.GenerationPlan plan,
                                          Node24ClassGenerator.Candidate candidate,
                                          Class<?> methodReturn) {
        Class<?> superReturn = candidate.superReturnType();
        emitInvoke(out, "invoke-super",
                "L" + plan.superOwner + ";->" + candidate.name
                        + Node24ClassGenerator.methodDescriptor(
                        superReturn, candidate.parameterTypes),
                candidate.parameterTypes);

        if (methodReturn == void.class) {
            emitMoveResult(out, superReturn, 0);
            out.append("    return-void\n");
            return;
        }
        if (methodReturn.isPrimitive()) {
            emitMoveResult(out, methodReturn, 0);
            out.append("    return").append(returnSuffix(methodReturn)).append(" v0\n");
            return;
        }
        out.append("    move-result-object v0\n");
        out.append("    check-cast v0, ")
                .append(Node24ClassGenerator.typeDescriptor(methodReturn)).append('\n');
        out.append("    return-object v0\n");
    }

    private static void emitMissingSuper(StringBuilder out,
                                         Node24ClassGenerator.Candidate candidate,
                                         Class<?> returnType) {
        String message = "Node24 generated method " + candidate.name
                + Node24ClassGenerator.methodDescriptor(returnType, candidate.parameterTypes)
                + " has no super implementation";
        out.append("    new-instance v0, Ljava/lang/IllegalStateException;\n");
        out.append("    const-string v1, \"").append(escapeSmaliString(message)).append("\"\n");
        out.append("    invoke-direct {v0, v1}, Ljava/lang/IllegalStateException;-><init>")
                .append("(Ljava/lang/String;)V\n");
        out.append("    throw v0\n");
    }

    private static void emitReturnFromObject(StringBuilder out, Class<?> returnType) {
        if (returnType == void.class) {
            // Consume the dispatcher result even though the Java method is void.
            out.append("    move-result-object v0\n");
            out.append("    return-void\n");
            return;
        }
        out.append("    move-result-object v0\n");
        if (!returnType.isPrimitive()) {
            out.append("    check-cast v0, ")
                    .append(Node24ClassGenerator.typeDescriptor(returnType)).append('\n');
            out.append("    return-object v0\n");
            return;
        }
        String wrapper = Node24ClassGenerator.wrapperInternalName(returnType);
        // JS 回调可能返回 undefined → dispatcher 给回 null。基本类型返回值的接口方法
        // 直接拆箱会 NPE（"Expected to unbox a 'boolean' primitive type but was returned null"），
        // 这里补 null 分支返回 Java 默认值（0 / false / '\0'）。
        String nullLabel = "n24def" + RETURN_DEFAULT_LABEL.incrementAndGet();
        out.append("    if-nez v0, :").append(nullLabel).append('\n');
        out.append("    check-cast v0, L").append(wrapper).append(";\n");
        out.append("    invoke-virtual {v0}, L").append(wrapper).append(";->")
                .append(Node24ClassGenerator.unboxMethodName(returnType)).append("()")
                .append(Node24ClassGenerator.typeDescriptor(returnType)).append('\n');
        emitMoveResult(out, returnType, 0);
        out.append("    return").append(returnSuffix(returnType)).append(" v0\n");
        out.append("    :").append(nullLabel).append('\n');
        out.append(Node24ClassGenerator.words(returnType) == 2
                ? "    const-wide/16 v0, 0x0\n" : "    const/4 v0, 0x0\n");
        out.append("    return").append(returnSuffix(returnType)).append(" v0\n");
    }

    private static void emitBoxArgument(StringBuilder out, Class<?> type, int parameterRegister,
                                        String arrayRegister, String indexRegister,
                                        String tempRegister, int locals) {
        if (!type.isPrimitive()) {
            String source = "p" + parameterRegister;
            if (parameterRegister + locals > 255) {
                out.append("    move-object/from16 ").append(tempRegister).append(", ").append(source).append('\n');
                source = tempRegister;
            }
            out.append("    aput-object ").append(source).append(", ")
                    .append(arrayRegister).append(", ").append(indexRegister).append('\n');
            return;
        }
        String wrapper = Node24ClassGenerator.wrapperInternalName(type);
        int words = Node24ClassGenerator.words(type);
        if (parameterRegister + locals + words - 1 > 15) {
            out.append("    invoke-static/range {p").append(parameterRegister)
                    .append(" .. p").append(parameterRegister + words - 1);
        } else {
            out.append("    invoke-static {p").append(parameterRegister);
            if (words == 2) out.append(", p").append(parameterRegister + 1);
        }
        out.append("}, L").append(wrapper).append(";->valueOf(")
                .append(Node24ClassGenerator.typeDescriptor(type)).append(")L")
                .append(wrapper).append(";\n");
        out.append("    move-result-object ").append(tempRegister).append('\n');
        out.append("    aput-object ").append(tempRegister).append(", ")
                .append(arrayRegister).append(", ").append(indexRegister).append('\n');
    }

    private static void emitConstant(StringBuilder out, String register, int value) {
        if (value >= -8 && value <= 7) {
            out.append("    const/4 ").append(register).append(", 0x")
                    .append(Integer.toHexString(value & 0xf)).append('\n');
        } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
            out.append("    const/16 ").append(register).append(", 0x")
                    .append(Integer.toHexString(value & 0xffff)).append('\n');
        } else {
            out.append("    const ").append(register).append(", 0x")
                    .append(Integer.toHexString(value)).append('\n');
        }
    }

    private static void emitMoveResult(StringBuilder out, Class<?> type, int register) {
        if (type == void.class) {
            return;
        }
        if (type == long.class || type == double.class) {
            out.append("    move-result-wide v").append(register).append('\n');
        } else if (type.isPrimitive()) {
            out.append("    move-result v").append(register).append('\n');
        } else {
            out.append("    move-result-object v").append(register).append('\n');
        }
    }

    private static void emitInvoke(StringBuilder out, String opcode, String target,
                                   Class<?>[] parameters) {
        int registerWords = 1;
        for (Class<?> parameter : parameters) {
            registerWords += Node24ClassGenerator.words(parameter);
        }
        if (registerWords <= 5) {
            out.append("    ").append(opcode).append(" {");
            for (int i = 0; i < registerWords; i++) {
                if (i > 0) {
                    out.append(", ");
                }
                out.append('p').append(i);
            }
            out.append("}, ").append(target).append('\n');
        } else {
            out.append("    ").append(opcode).append("/range {p0 .. p")
                    .append(registerWords - 1).append("}, ").append(target).append('\n');
        }
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

    private static String returnSuffix(Class<?> type) {
        if (type == void.class) {
            return "-void";
        }
        if (type == long.class || type == double.class) {
            return "-wide";
        }
        if (!type.isPrimitive()) {
            return "-object";
        }
        return "";
    }

    private static String escapeSmaliString(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\':
                    escaped.append("\\\\");
                    break;
                case '"':
                    escaped.append("\\\"");
                    break;
                case '\n':
                    escaped.append("\\n");
                    break;
                case '\r':
                    escaped.append("\\r");
                    break;
                case '\t':
                    escaped.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        escaped.append("\\u");
                        String hex = Integer.toHexString(c);
                        for (int pad = hex.length(); pad < 4; pad++) {
                            escaped.append('0');
                        }
                        escaped.append(hex);
                    } else {
                        escaped.append(c);
                    }
            }
        }
        return escaped.toString();
    }
}
