package com.stardust.autojs.sjbc;

import org.junit.Test;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * task-79 片3：只读入口 `sjbc.run(path)` 的 JVM 可测部分（容器→VM→{prints,steps,entries}）+ manifest 冲突裁决。
 *
 * <p>`runBlob` 不碰 Android ⇒ 可以用 demo 真向量（证书 `80b26b41…40ea`）直接断言；
 * `run(path)` 的 assets/文件读取与「签名证书取指纹」在真机验（K40/device4）。
 */
public class SjbcApiTest {

    private static final String CERT_SHA256 =
            "80b26b41168e41fc6add6e863f9dc8770956e99ab6b830320fb360ceb9ad40ea";

    /** demo 真向量（数列与递归，与 SjbcVmTest 同源：pc-tools/pack_v5.js 产出）。 */
    private static final String VECTOR_B64 =
            "U0pCRQUBAQAkz4hCGARFC5xjUP2x4Hv/j9Mf6vUpH9V85qqIAAAASImLh2vjr8vp+j3ovWMl1A8UsvF6Hj55ji8fG7RJRpSn0sNvRdOkjMo+yEHmkNI7FeTZIYt1bNISdLI9xxRU4h3LSy+x0lQVh9av3xBlqqfa3AdjIcyQM722su8HklBRkrzcrvILxml1NSsKciyBOie09voBM9SjifGwE5jSGa4LtzGSo70GTJzgjtaAm6zYZPXwVFE8ukqTd9+CsyIauCe2rtQDEJmC//Qoyp0HN9yiqxlzo1Pz4wXJedOmrmTJhZS8XHxESY5jZ0D3J+Xe996VARVSjb4pzUVUptP2yJO2fOl1eSxevCRZ9s/d4VHE7j2W2wRfsmgWK6jEoGYGXHJJgiw5nHtF7xwGW33lLLyJwsrBptmoVbsvS9Zz";

    /** 只读入口返回的字段与 vm.js 的 steps/prints 一致（oracle 值：20864 / 两条 print）。 */
    @Test
    public void runBlobReturnsPrintsStepsAndEntries() {
        byte[] master = SjbcV5Container.masterFromCertSha256Hex(CERT_SHA256);
        Map<String, Object> result = SjbcApi.runBlob(Base64.getDecoder().decode(VECTOR_B64), master, 0);
        assertEquals(Boolean.TRUE, result.get("ok"));
        assertEquals(java.util.Arrays.asList("fib(15)=610", "12!=479001600"), result.get("prints"));
        assertEquals(20864.0, ((Double) result.get("steps")).doubleValue(), 1e-9);
        assertEquals(184.0, ((Double) result.get("codeBytes")).doubleValue(), 1e-9);
        List<?> entries = (List<?>) result.get("entries");
        assertEquals("entries.length == 函数数（manifest funcs=3）", 3, entries.size());
        assertEquals(0.0, ((Double) entries.get(0)).doubleValue(), 1e-9);
        assertFalse("正常结束不应有 uncaught", result.containsKey("uncaught"));
    }

    /** entryIndex 选择入口 PC：e1/e2 是模块内部函数，直接跑不打印（与 vm.js 一致）。 */
    @Test
    public void runBlobHonoursEntryIndex() {
        byte[] master = SjbcV5Container.masterFromCertSha256Hex(CERT_SHA256);
        Map<String, Object> e1 = SjbcApi.runBlob(Base64.getDecoder().decode(VECTOR_B64), master, 1);
        assertEquals(Boolean.TRUE, e1.get("ok"));
        assertTrue("e1 不应打印", ((List<?>) e1.get("prints")).isEmpty());
        assertEquals(5.0, ((Double) e1.get("steps")).doubleValue(), 1e-9);
    }

    /** 空/非法路径与错证书都给具名错误（不静默）。 */
    @Test
    public void invalidInputsFailLoudly() {
        byte[] master = SjbcV5Container.masterFromCertSha256Hex(CERT_SHA256);
        try {
            SjbcApi.runBlob(new byte[0], master, 0);
            org.junit.Assert.fail("空容器应当抛");
        } catch (SjbcException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("太短"));
        }
        try {
            SjbcApi.runBlob(Base64.getDecoder().decode(VECTOR_B64),
                    SjbcV5Container.masterFromCertSha256Hex("11".repeat(32)), 0);
            org.junit.Assert.fail("错证书应当抛");
        } catch (SjbcException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("层"));
        }
        try {
            SjbcApi.runBlob(Base64.getDecoder().decode(VECTOR_B64), master, 99);
            org.junit.Assert.fail("entryIndex 越界应当抛");
        } catch (SjbcException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("entryIndex") || expected.getMessage().contains("执行参数"));
        }
    }

    /** manifest 冲突裁决：不一致 ⇒ 以签名证书为准并 warning（纯函数）。 */
    @Test
    public void manifestMismatchIsDetected() {
        assertFalse(SjbcKeySource.shouldWarnManifestMismatch("aa", null));
        assertFalse(SjbcKeySource.shouldWarnManifestMismatch("aa", "AA"));
        assertTrue(SjbcKeySource.shouldWarnManifestMismatch("aa", "bb"));
        assertTrue(SjbcKeySource.shouldWarnManifestMismatch(null, "bb"));
        assertEquals("00ff", SjbcKeySource.toHex(new byte[] { 0, (byte) 0xff }));
    }
}