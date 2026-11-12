package com.stardust.autojs.project;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * task-153【①】：**密钥派生交叉对拍**（Java 实现 vs C 实现）+ 两侧常量冻结。
 *
 * <p>同一组 input 的三方证据（全部 32 个 hex 字符逐字节相等）：
 * <pre>
 * input  : com.example.app | 0123456789abcdef0123456789abcdef | 38166a4b51f1a3992c96512324345a994c8e7331a531e6e51ce3c9d91691cc70
 * C      : 66d4a151b1fb1cdf57c2b9ac2b0a6e35   （执行方式见下，实测 EXIT=0）
 * 独立算  : 66d4a151b1fb1cdf57c2b9ac2b0a6e35   （node crypto sha256，与 C 逐字节相等）
 * Java   : 由本测试断言（ScriptKeyDerivation.deriveKey）
 * </pre>
 *
 * <p>C 侧的执行方式（可复跑，test-only、不碰生产路径）：
 * {@code output/task-153-2026-11-12/selftest.c} 直接 {@code #include "script_crypto.c"}，
 * 用 mingw gcc + JDK include + 一个只声明 dlfcn 原型的 stub 目录编译，跑出来就是上面的 C 值：
 * <pre>
 * gcc -O0 -w -I output/task-153-2026-11-12/stubs -I modules/engine/src/main/cpp \
 *     -I "$JAVA_HOME/include" -I "$JAVA_HOME/include/win32" \
 *     -o output/task-153-2026-11-12/selftest.exe output/task-153-2026-11-12/selftest.c
 * output/task-153-2026-11-12/selftest.exe  =>  66d4a151b1fb1cdf57c2b9ac2b0a6e35
 * </pre>
 */
public class ScriptKeyDerivationCrossCheckTest {

    private static final String PKG = "com.example.app";
    private static final String SALT = "0123456789abcdef0123456789abcdef";
    private static final String FP =
            "38166a4b51f1a3992c96512324345a994c8e7331a531e6e51ce3c9d91691cc70";

    /** C 实现实测值（同一 input）——Java 必须逐字节相同。 */
    private static final String C_KEY_HEX = "66d4a151b1fb1cdf57c2b9ac2b0a6e35";

    /** ① 交叉对拍：Java 派生 == C 派生（32 字节 key 的 hex 前 32 字符，逐字节相等）。 */
    @Test
    public void javaDerivationMatchesTheCImplementationByteForByte() {
        String javaKey = ScriptKeyDerivation.deriveKey(PKG, SALT, FP);
        assertEquals("Java 与 C 派生必须逐字节相等（C 侧输出见类注释）", C_KEY_HEX, javaKey);
        assertEquals(32, javaKey.length());
    }

    /** ② 常量冻结：Java 侧前缀必须与钉死值一致（有人改一侧 ⇒ 本测试红，而不是线上静默解不开）。 */
    @Test
    public void javaPrefixesAreFrozen() throws Exception {
        String source = read("src/main/java/com/stardust/autojs/project/ScriptKeyDerivation.java");
        assertTrue("Java KEY 前缀被改动了", source.contains("\"aijspro-script-key|\""));
        assertTrue("Java VECTOR 前缀被改动了", source.contains("\"aijspro-script-vec|\""));
    }

    /** ③ 常量冻结：C 侧前缀必须与 Java 侧逐字一致（跨语言漂移守卫）。 */
    @Test
    public void cPrefixesMatchTheJavaSideExactly() throws Exception {
        String c = read("src/main/cpp/script_crypto.c");
        assertTrue("C 侧 kKeyPrefix 与 Java 不一致（改一侧就会静默解不开）",
                c.contains("static const char kKeyPrefix[] = \"aijspro-script-key|\";"));
        assertTrue("C 侧 kVectorPrefix 与 Java 不一致",
                c.contains("static const char kVectorPrefix[] = \"aijspro-script-vec|\";"));
    }

    private static String read(String relativePath) throws Exception {
        return new String(Files.readAllBytes(Paths.get(relativePath)), StandardCharsets.UTF_8);
    }
}