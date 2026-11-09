package com.stardust.autojs.sjbc;

import org.junit.Test;

import java.util.Base64;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * task-79 片1：v5 解容器对**用户 PC 工具真产出的向量**（device4 demo `com.aijs.sjbc.demo` 的 4 个 assets + pc-tools 的 hello）。
 *
 * <p>三方对拍锚点：这些 .sjbc 由 {@code pc-tools/pack_v5.js} 生成、{@code m8/native/sjbc_v5.c} 能解、
 * 证书指纹取自 demo 自己的 {@code assets/sjbc/manifest.json}：
 * {@code cert=80b26b41168e41fc6add6e863f9dc8770956e99ab6b830320fb360ceb9ad40ea}；
 * manifest 还给了每个模块的**明文字节数**（184/388/260/272）与密文 sha256 前缀 ⇒ 这里用它当**期望值断言**。
 */
public class SjbcV5ContainerTest {

    private static final String CERT_SHA256 = "80b26b41168e41fc6add6e863f9dc8770956e99ab6b830320fb360ceb9ad40ea";

    /** {模块名, manifest 里的明文字节数, 密文 base64}。 */
    private static final Object[][] VECTORS = {
        { "数列与递归", 184,
            "U0pCRQUBAQAkz4hCGARFC5xjUP2x4Hv/j9Mf6vUpH9V85qqIAAAASImLh2vjr8vp+j3ovWMl1A8UsvF6Hj55ji8fG7RJRpSn0sNvRdOkjMo+yEHmkNI7FeTZIYt1bNISdLI9xxRU4h3LSy+x0lQVh9av3xBlqqfa3AdjIcyQM722su8HklBRkrzcrvILxml1NSsKciyBOie09voBM9SjifGwE5jSGa4LtzGSo70GTJzgjtaAm6zYZPXwVFE8ukqTd9+CsyIauCe2rtQDEJmC//Qoyp0HN9yiqxlzo1Pz4wXJedOmrmTJhZS8XHxESY5jZ0D3J+Xe996VARVSjb4pzUVUptP2yJO2fOl1eSxevCRZ9s/d4VHE7j2W2wRfsmgWK6jEoGYGXHJJgiw5nHtF7xwGW33lLLyJwsrBptmoVbsvS9Zz" },
        { "数组与统计", 388,
            "U0pCRQUBAQAXXZ0ZvO6bQ8GxHFO4oI9cCzMMAsDzdiyjRWveAAAAisMsJIs7tk6hGXySgZjVyYO6Zs50Z2n1RE3qjryx0NCJDadyQdYGhIx7w8V+ihraIX0S9rAIA7pqs2mSBhceXBCJECPKMDGuPgHJHS7eCSeAoBtzCOH49bmdS6x6DAtz6MC48b2Gf3AOLJxmjuD+H5jgmIftnNOuKRTQzOuBOcQ7oYBbJKfkKT8FjLSRhN2pcuhh7ZyD8jWB+Yz77KC4ei5RPBGiiL+SbeUMXHJiQJfpu0KqXJerhtnm81OYgGxI1+zomUEZOPLPE+Q/yk6QzTpNvaw9UXBvRg6HYB9sEHBnIcTj5qUcdLNAImfy3dwk0oHC3B4rFMthLQRYNVoZ1XBkIzkr6kXkFSzd7CSIquyh85d84/HUBiley6zaMp1w7hkjJ1wgQ0tEyl5JFDq2RfgUAzOf+Iwe8cmv31IBGqVasnsSCgLMTxCStKFqgF+vkj8tw5fTFSDWUO7yXzJzI2/7FjV6+cK7vlD0cJIBjU8CqXdylE71P/XbCNEKLpVcJyt/M9ngfKkhMJv8Rnt7u/evBFopJMWHCm5UukzowRlv1QtPumHhFeOEy6f629v0XhRvW6/jtUXsHGxWmkaRXO63xZj+6EeS1tqpSBpxCUJnaBeJO8Zkq2jVtcJ9UWsGiTRkKiQTHEnevuSxR/wZPOyjurvXzG87LMBlGSUkXdfbMfZubsFFWf/RWeMT1zbIJG1VVMiKE8WFu2CYYz2EA7hlXSztaxL65feb" },
        { "闭包与异常", 260,
            "U0pCRQUBAQAftb/3KVwLc18nVAuVC4x2fkLBiyNEpLCEt8UEAAAATOyIpLA2JFC1u2q0mfUp0A88QRxC2BjbjFNppvgvDPHtd1Ivlfxb7PkzQcOJOkHRDzt4XVbfHca4ri6riT4/OWEjn9ci9SohiwFMn4lufY9zxDDwi+j0Xtn+3aerWmlTRs+EtpUp27sYMX+BcLD1ga1SzKrF50H5839G7JDKbAoWODkCSsPcZtZC4ZOPdpOOEfqtDnOe6KydCoKG+Qsg+BIJa/mzfnS0dJJiNK7pg9LHIQNG3wMfMGypapbDgOOoqZdRxkQHrrFFGEzm79RoIgrllLMif1ivArDzpA+88IUbEFVb0hJy7eE08YGQJ4JrLpQE09aj/A4os6P+c2bxqZNLEsJ+ZKpIoM8IQAbuwAmZD7Ty8T0Ywp3JfTAj4Bzo+Nzrby+VGS/XQ+vgIX5ILy2rgbVLILkuxC5fxD0/EfBLtQQvE1SBDk2EHrKuBXkKPdbxh7uvG2ARDOLaetwPWKCgf//mSvrO298VsTU=" },
        { "字符串处理", 272,
            "U0pCRQUBAQDRcibz/VPCd4RPLL0CP5Ir850bsdWfzFKEjUOtAAAAWN0/Ou9PAbtPeBNYszDBpPt3UZ5nliOl6cdWA8Sxf4UoPZIsHmtWjH7TREJuzkHVpbIS4LXCXpQpVbhY2nTciQtuiti2paaDK4iPFEs0I09KskNsRb3qXG0E7MV2UkB9ISis9aG9Ev1ZV566IIrvMF1/Tql4JwDp18fpMCUvKxN6i2/0ekVXKFzmRdmH6clk53/ALbfpkI2/rmY7SRcT6/1brIb/s//5TCOs/ZmcW+d1+gARU5848jNTFWZojOb9pwFVcAkz9J00ocaezHNZvzje1xbgOOkNKxofH5212Xtx2I2qjdnac+wdy4zw+yrGuGpEV6BA+GYWFm1cZ3YxgLgT2e+7cvNKl2ieLWZ2goqDA6/CqS5DR3K+TNJT1tRVYWg0vrDa76znVLfX/IFfy6v1d8+dh3UUqv5uzWE/YnDgtGM6seTeIwNjEO/xv7B+q6eOmts5DDBXTjgS/Z66qfLeaIJHB1wfIQfaPgeEO8vY5In+SWVGkT8R2p9KHlPH6dVcEYE=" },
    };

    /** HKDF-SHA256 与 node/OpenSSL 的 crypto.hkdfSync 同值（RFC 5869 TC1）。 */
    @Test
    public void hkdfMatchesRfc5869TestCase1() {
        byte[] ikm = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b");
        byte[] salt = hex("000102030405060708090a0b0c");
        byte[] info = hex("f0f1f2f3f4f5f6f7f8f9");
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
                toHex(SjbcV5Container.hkdfSha256(ikm, salt, info, 42)));
    }

    /** 4 个真向量都能解开，且**明文字节数与 manifest 一致**（期望值断言，不只对拍）。 */
    @Test
    public void opensEveryDemoVectorAndCodeLengthMatchesManifest() {
        byte[] master = SjbcV5Container.masterFromCertSha256Hex(CERT_SHA256);
        for (Object[] vector : VECTORS) {
            String name = (String) vector[0];
            int expectedCodeBytes = (Integer) vector[1];
            byte[] blob = Base64.getDecoder().decode((String) vector[2]);
            SjbcV5Container.Opened opened = SjbcV5Container.open(blob, master);
            assertEquals(name + "：明文码长度应与 manifest 一致", expectedCodeBytes, opened.code.length);
            assertTrue(name + "：池首行应为 SJBC5", opened.poolText.startsWith("SJBC5\n"));
            assertTrue(name + "：entries 不应为空", opened.entries.length > 0);
            assertNotNull(name + "：池应有序", opened.pool);
            // ENTRIES 是「每个函数 id → 入口 PC」（见 pc-tools/ajs2sjbc.js:461-462），不是池下标。
            for (int entry : opened.entries) {
                assertTrue(name + "：入口 PC 越界 " + entry, entry >= 0 && entry < opened.code.length);
            }
        }
    }

    /** 换证书 ⇒ AEAD 直接拒绝（「换证书必解不开」的 JVM 证据）。 */
    @Test
    public void aDifferentCertificateCannotOpenAnyVector() {
        byte[] wrongMaster = SjbcV5Container.masterFromCertSha256Hex("11".repeat(32));
        for (Object[] vector : VECTORS) {
            byte[] blob = Base64.getDecoder().decode((String) vector[2]);
            try {
                SjbcV5Container.open(blob, wrongMaster);
                fail(vector[0] + "：换了证书竟然还能解开");
            } catch (SjbcException expected) {
                assertTrue(vector[0] + "：错误信息应点名是哪一层：" + expected.getMessage(),
                        expected.getMessage().contains("池层") || expected.getMessage().contains("码层"));
            }
        }
    }

    /** 篡改：改池区 ⇒ 池层拒绝；改码区 ⇒ 码层拒绝（两层各自认证）。 */
    @Test
    public void tamperingEitherLayerIsRejected() {
        byte[] master = SjbcV5Container.masterFromCertSha256Hex(CERT_SHA256);
        for (Object[] vector : VECTORS) {
            byte[] blob = Base64.getDecoder().decode((String) vector[2]);
            int poolLen = ((blob[8 + 12 + 16] & 0xFF) << 24) | ((blob[8 + 12 + 16 + 1] & 0xFF) << 16)
                    | ((blob[8 + 12 + 16 + 2] & 0xFF) << 8) | (blob[8 + 12 + 16 + 3] & 0xFF);
            int poolStart = 8 + 12 + 16 + 4;
            int codeStart = poolStart + poolLen + 12 + 16;
            byte[] poolTampered = blob.clone();
            poolTampered[poolStart] ^= 0x01;
            assertLayerRejected(vector[0] + "（池被改）", poolTampered, master, "池层");
            byte[] codeTampered = blob.clone();
            codeTampered[codeStart] ^= 0x01;
            assertLayerRejected(vector[0] + "（码被改）", codeTampered, master, "码层");
        }
    }

    /** 非容器输入给具名错误（不是随机异常）。 */
    @Test
    public void rejectsNonContainerInputWithNamedErrors() {
        byte[] master = SjbcV5Container.masterFromCertSha256Hex(CERT_SHA256);
        try {
            SjbcV5Container.open("not-sjbc-at-all".getBytes(), master);
            fail("magic 不对应当抛");
        } catch (SjbcException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("magic"));
        }
        try {
            SjbcV5Container.open(new byte[] { 'S', 'J', 'B', 'E' }, master);
            fail("截断应当抛");
        } catch (SjbcException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("太短") || expected.getMessage().contains("截断"));
        }
        try {
            SjbcV5Container.masterFromCertSha256Hex("abcd");
            fail("指纹长度不对应当抛");
        } catch (SjbcException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("64"));
        }
    }

    /** 池转义反转与 pack_v5.js 的 esc 互逆（\\n → 换行、\\\\ → 单反斜杠）。 */
    @Test
    public void unescapeMatchesPackEscape() {
        assertEquals("a\nb", SjbcV5Container.unescape("a\\nb"));
        assertEquals("a\\b", SjbcV5Container.unescape("a\\\\b"));
        assertEquals("\n", SjbcV5Container.unescape("\\n"));
        assertEquals("x", SjbcV5Container.unescape("x"));
    }

    private static void assertLayerRejected(String what, byte[] blob, byte[] master, String layer) {
        try {
            SjbcV5Container.open(blob, master);
            fail(what + "：篡改未被检出");
        } catch (SjbcException expected) {
            assertTrue(what + "：应报 " + layer + "，实际 " + expected.getMessage(),
                    expected.getMessage().contains(layer));
        }
    }

    private static byte[] hex(String value) {
        byte[] out = new byte[value.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static String toHex(byte[] value) {
        StringBuilder sb = new StringBuilder(value.length * 2);
        for (byte b : value) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}