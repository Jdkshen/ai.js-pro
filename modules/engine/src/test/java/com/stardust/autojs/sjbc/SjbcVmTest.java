package com.stardust.autojs.sjbc;

import org.json.JSONArray;
import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * task-79 片2：Java VM 对**用户参考实现** `pc-tools/vm.js` 的对拍（结果 + **步数**）+ 期望值断言。
 *
 * <p><b>步数口径（从 oracle `vm.js:12` 读出，Java 侧同口径）</b>：`while (pc<code.length){ if(++steps>5e7)… }`
 * ⇒ steps = 指令**派发次数**：每条指令 +1，**`LOADF`（6 字节）算 1 步**（长度只用于推进 pc，见 {@link SjbcVm#LEN}）；
 * `RET`/`RETV`(栈空)/未捕获 `THROW` 返回时不再加；`THROW` 弹 handler 不计步。浮点用例 57 步就是这条口径的钉子。
 *
 * <p><b>entries 语义</b>：`entries[fnId] = 函数入口 PC`（不是池下标）⇒ 断言 `entries.length == 模块函数数`
 * （demo `manifest.json` 的 funcs：数列 3 / 数组 2 / 闭包 3 / 字符串 2）且入口 PC 落在码内；对拍按 entryIndex 逐个跑。
 *
 * <p><b>m18 那组</b>：材料里**没有**那份 36B 自举字节码（`m18/` 只有 README+meta18.src、产物目录空；现版 pc-tools 编译
 * meta18.src 报 `未定义变量 src`）⇒ 用 frameArray / floats+loops / closure+exc 三个等价用例覆盖同一批指令
 * （帧数组 / LOADF 变长与跳转真实长度 / 隐式返回 / 闭包 / 异常），**不声称跑过 m18**。
 */
public class SjbcVmTest {

    private static final String CERT_SHA256 =
            "80b26b41168e41fc6add6e863f9dc8770956e99ab6b830320fb360ceb9ad40ea";

    /** {模块名, manifest funcs, 密文 base64, [[entryIndex, steps, [prints…]]…]（值由 pc-tools/vm.js 产出）}。 */
    private static final Object[][] MODULES = {
        { "数列与递归", 3,
          "U0pCRQUBAQAkz4hCGARFC5xjUP2x4Hv/j9Mf6vUpH9V85qqIAAAASImLh2vjr8vp+j3ovWMl1A8UsvF6Hj55ji8fG7RJRpSn0sNvRdOkjMo+yEHmkNI7FeTZIYt1bNISdLI9xxRU4h3LSy+x0lQVh9av3xBlqqfa3AdjIcyQM722su8HklBRkrzcrvILxml1NSsKciyBOie09voBM9SjifGwE5jSGa4LtzGSo70GTJzgjtaAm6zYZPXwVFE8ukqTd9+CsyIauCe2rtQDEJmC//Qoyp0HN9yiqxlzo1Pz4wXJedOmrmTJhZS8XHxESY5jZ0D3J+Xe996VARVSjb4pzUVUptP2yJO2fOl1eSxevCRZ9s/d4VHE7j2W2wRfsmgWK6jEoGYGXHJJgiw5nHtF7xwGW33lLLyJwsrBptmoVbsvS9Zz",
          "[[0,20864,[\"fib(15)=610\",\"12!=479001600\"]],[1,5,[]],[2,6,[]]]" },
        { "数组与统计", 2,
          "U0pCRQUBAQAXXZ0ZvO6bQ8GxHFO4oI9cCzMMAsDzdiyjRWveAAAAisMsJIs7tk6hGXySgZjVyYO6Zs50Z2n1RE3qjryx0NCJDadyQdYGhIx7w8V+ihraIX0S9rAIA7pqs2mSBhceXBCJECPKMDGuPgHJHS7eCSeAoBtzCOH49bmdS6x6DAtz6MC48b2Gf3AOLJxmjuD+H5jgmIftnNOuKRTQzOuBOcQ7oYBbJKfkKT8FjLSRhN2pcuhh7ZyD8jWB+Yz77KC4ei5RPBGiiL+SbeUMXHJiQJfpu0KqXJerhtnm81OYgGxI1+zomUEZOPLPE+Q/yk6QzTpNvaw9UXBvRg6HYB9sEHBnIcTj5qUcdLNAImfy3dwk0oHC3B4rFMthLQRYNVoZ1XBkIzkr6kXkFSzd7CSIquyh85d84/HUBiley6zaMp1w7hkjJ1wgQ0tEyl5JFDq2RfgUAzOf+Iwe8cmv31IBGqVasnsSCgLMTxCStKFqgF+vkj8tw5fTFSDWUO7yXzJzI2/7FjV6+cK7vlD0cJIBjU8CqXdylE71P/XbCNEKLpVcJyt/M9ngfKkhMJv8Rnt7u/evBFopJMWHCm5UukzowRlv1QtPumHhFeOEy6f629v0XhRvW6/jtUXsHGxWmkaRXO63xZj+6EeS1tqpSBpxCUJnaBeJO8Zkq2jVtcJ9UWsGiTRkKiQTHEnevuSxR/wZPOyjurvXzG87LMBlGSUkXdfbMfZubsFFWf/RWeMT1zbIJG1VVMiKE8WFu2CYYz2EA7hlXSztaxL65feb",
          "[[0,220,[\"sum/max/min=963305\",\"n=6\"]],[1,28,[]]]" },
        { "闭包与异常", 3,
          "U0pCRQUBAQAftb/3KVwLc18nVAuVC4x2fkLBiyNEpLCEt8UEAAAATOyIpLA2JFC1u2q0mfUp0A88QRxC2BjbjFNppvgvDPHtd1Ivlfxb7PkzQcOJOkHRDzt4XVbfHca4ri6riT4/OWEjn9ci9SohiwFMn4lufY9zxDDwi+j0Xtn+3aerWmlTRs+EtpUp27sYMX+BcLD1ga1SzKrF50H5839G7JDKbAoWODkCSsPcZtZC4ZOPdpOOEfqtDnOe6KydCoKG+Qsg+BIJa/mzfnS0dJJiNK7pg9LHIQNG3wMfMGypapbDgOOoqZdRxkQHrrFFGEzm79RoIgrllLMif1ivArDzpA+88IUbEFVb0hJy7eE08YGQJ4JrLpQE09aj/A4os6P+c2bxqZNLEsJ+ZKpIoM8IQAbuwAmZD7Ty8T0Ywp3JfTAj4Bzo+Nzrby+VGS/XQ+vgIX5ILy2rgbVLILkuxC5fxD0/EfBLtQQvE1SBDk2EHrKuBXkKPdbxh7uvG2ARDOLaetwPWKCgf//mSvrO298VsTU=",
          "[[0,198,[\"1,2,3,E:over,E:over,\"]],[1,9,[]],[2,15,[]]]" },
        { "字符串处理", 2,
          "U0pCRQUBAQDRcibz/VPCd4RPLL0CP5Ir850bsdWfzFKEjUOtAAAAWN0/Ou9PAbtPeBNYszDBpPt3UZ5nliOl6cdWA8Sxf4UoPZIsHmtWjH7TREJuzkHVpbIS4LXCXpQpVbhY2nTciQtuiti2paaDK4iPFEs0I09KskNsRb3qXG0E7MV2UkB9ISis9aG9Ev1ZV566IIrvMF1/Tql4JwDp18fpMCUvKxN6i2/0ekVXKFzmRdmH6clk53/ALbfpkI2/rmY7SRcT6/1brIb/s//5TCOs/ZmcW+d1+gARU5848jNTFWZojOb9pwFVcAkz9J00ocaezHNZvzje1xbgOOkNKxofH5212Xtx2I2qjdnac+wdy4zw+yrGuGpEV6BA+GYWFm1cZ3YxgLgT2e+7cvNKl2ieLWZ2goqDA6/CqS5DR3K+TNJT1tRVYWg0vrDa76znVLfX/IFfy6v1d8+dh3UUqv5uzWE/YnDgtGM6seTeIwNjEO/xv7B+q6eOmts5DDBXTjgS/Z66qfLeaIJHB1wfIQfaPgeEO8vY5In+SWVGkT8R2p9KHlPH6dVcEYE=",
          "[[0,153,[\"CBJS\",\"alpha/beta/gamma/\"]],[1,12,[]]]" },
    };

    /** {用例名, 字节码 hex, 池 JSON, entries JSON, 期望步数, 期望 prints JSON}。 */
    private static final Object[][] CASES = {
        { "frameArray",
          "52900100038900005080000151000000407000030180000003080000018000010318000001800002032800000180000303380000018000040348000001800005035800000180000603680000018000070190000003a7000003b8000043a90000018000080190000103a7000003b8000043a90000018000090190000203a7000003b8000043a90000038000001088100010882000108830001088400010885000108860000190000042a790001088a00001a0000142b7a0001088b00001b0000203c7000003d8000043cb000001800002429780005309000053000000",
          "[1,2,3,4,5,6,7,8,9,0]",
          "[0,16]", 54L,
          "[\"45\"]" },
        { "floats+loops",
          "01800000030800000180000103180000018000020328000001800002033800000383000001900003228890003208005803820000039000001299100010889000032800000383000001900004108890000338000030000020038200005080000151000000",
          "[1.5,2.5,0,3,1]",
          "[0]", 57L,
          "[\"11.25\"]" },
        { "closure+exc",
          "528001000308000003900000549000000389000050800001039000005490000003890000508000017000003c0480000072080000710000003000005473800000031800000490000110991000038900005080000151000000018000020798000003090000060000000580020153080000530000000a8000000898000001a000031099a0000aa0000009a900000a800000089800005309000053000000",
          "[\"E\",\"got:\",0,1]",
          "[0,88,116]", 44L,
          "[\"1\",\"2\",\"got:E\"]" },
    };

    /** 4 个真模块：解密 → 逐 entryIndex 跑 → 步数与 print 都对 oracle。 */
    @Test
    public void everyDemoModuleMatchesVmJsStepsAndPrints() throws Exception {
        byte[] master = SjbcV5Container.masterFromCertSha256Hex(CERT_SHA256);
        for (Object[] module : MODULES) {
            String name = (String) module[0];
            int funcs = (Integer) module[1];
            SjbcV5Container.Opened opened = SjbcV5Container.open(
                    Base64.getDecoder().decode((String) module[2]), master);
            assertEquals(name + "：entries.length 必须等于函数数（entries[fnId]=入口 PC）",
                    funcs, opened.entries.length);
            for (int entry : opened.entries) {
                assertTrue(name + "：入口 PC 必须落在码内 " + entry, entry >= 0 && entry < opened.code.length);
            }
            JSONArray expectations = new JSONArray((String) module[3]);
            assertEquals(name + "：对拍条目数应等于函数数", funcs, expectations.length());
            for (int i = 0; i < expectations.length(); i++) {
                JSONArray row = expectations.getJSONArray(i);
                int entryIndex = row.getInt(0);
                long expectedSteps = row.getLong(1);
                List<String> expectedPrints = strings(row.getJSONArray(2));
                List<Object> prints = new ArrayList<>();
                SjbcVm.Result result = SjbcVm.run(opened.code, opened.pool, SjbcVm.defaultHost(prints),
                        opened.entries, entryIndex);
                assertEquals(name + " e" + entryIndex + "：步数应与 vm.js 一致", expectedSteps, result.steps);
                assertEquals(name + " e" + entryIndex + "：print 应与 vm.js 一致",
                        expectedPrints, displayAll(prints));
            }
        }
    }

    /** 等价用例：帧数组(≥8 局部) / LOADF+跳转+隐式返回 / 闭包+异常。 */
    @Test
    public void focusedCasesMatchVmJsStepsAndPrints() throws Exception {
        for (Object[] testCase : CASES) {
            String name = (String) testCase[0];
            List<Object> prints = new ArrayList<>();
            SjbcVm.Result result = SjbcVm.run(hex((String) testCase[1]), jsonValues(new JSONArray((String) testCase[2])),
                    SjbcVm.defaultHost(prints), ints(new JSONArray((String) testCase[3])), 0);
            assertEquals(name + "：步数", ((Long) testCase[4]).longValue(), result.steps);
            assertEquals(name + "：print", strings(new JSONArray((String) testCase[5])), displayAll(prints));
        }
    }

    /** 宿主函数表一一对应（少一个就会把宿主调用当未定义函数）+ push/slice0 纯函数语义。 */
    @Test
    public void hostTableMatchesPcToolsOrder() throws Exception {
        assertEquals("print", SjbcHostFunctions.NAMES[0]);
        assertEquals("len", SjbcHostFunctions.NAMES[1]);
        assertEquals("push", SjbcHostFunctions.NAMES[2]);
        assertEquals("slice0", SjbcHostFunctions.NAMES[3]);
        SjbcVm.Host host = SjbcHostFunctions.host(new ArrayList<>());
        Object len = host.call(1, arg(SjbcValues.array(SjbcValues.number(1), SjbcValues.number(2), SjbcValues.number(3))));
        assertEquals(3.0, ((Double) len).doubleValue(), 1e-9);
        Object pushed = host.call(2, args(SjbcValues.array(SjbcValues.number(1), SjbcValues.number(2)), SjbcValues.number(3)));
        assertEquals("push 应返回新数组（长度 +1）", 3, ((List<?>) pushed).size());
        List<Object> source = SjbcValues.array(SjbcValues.number(1), SjbcValues.number(2));
        host.call(2, args(source, SjbcValues.number(3)));
        assertEquals("push 不能改原数组（纯函数）", 2, source.size());
        assertEquals("ab", host.call(3, args("abcd", SjbcValues.number(2))));
    }

    /** 有 node 时用 `pc-tools/vm.js` 重算并逐字比对 digest；缺 node/资源 ⇒ Assume skip（绝不静默变绿）。 */
    @Test
    public void nodeOracleReproducesTheSameDigest() throws Exception {
        File runner = new File("src/test/resources/sjbc/oracle.mjs");
        Assume.assumeTrue("缺 node 或 pc-tools 资源 ⇒ 跳过对拍", hasNode() && runner.isFile());
        Process process = new ProcessBuilder("node", runner.getAbsolutePath())
                .redirectErrorStream(true).start();
        String output = new String(readAll(process), StandardCharsets.UTF_8);
        assertEquals("node runner 退出码非 0：" + output, 0, process.waitFor());
        List<String> nodeRows = new ArrayList<>();
        for (String line : output.split("\n")) {
            if (line.startsWith("ORACLE-ROW ")) {
                nodeRows.add(line.substring("ORACLE-ROW ".length()).trim());
            }
        }
        assertTrue("runner 没输出 ORACLE-ROW：" + output, !nodeRows.isEmpty());
        assertEquals("node 重算的 digest 必须与 Java VM 相同", digestOf(nodeRows), javaDigest());
    }

    /**
     * 接受集第三组：`--cert self`（32 个 0 指纹）的 `pc-tools/out/hello.sjbc` ——
     * **不同证书的容器各自可解**：self 指纹能解、真签名指纹（80b26b41…）解不开；再跑一遍 VM 对 oracle 的步数/print。
     */
    @Test
    public void selfCertHelloOpensWithItsOwnKeyOnlyAndRunsToOracleNumbers() throws Exception {
        byte[] blob = Base64.getDecoder().decode(HELLO_SELF_B64);
        byte[] selfMaster = SjbcV5Container.masterFromCertSha256Hex("00".repeat(32));
        SjbcV5Container.Opened opened = SjbcV5Container.open(blob, selfMaster);
        assertTrue("hello(self)：明文码非空", opened.code.length > 0);
        List<Object> prints = new ArrayList<>();
        SjbcVm.Result result = SjbcVm.run(opened.code, opened.pool, SjbcVm.defaultHost(prints),
                opened.entries, 0);
        assertEquals("hello(self) 步数应与 vm.js 一致",1990L, result.steps);
        assertEquals("hello(self) print 应与 vm.js 一致",
                java.util.Arrays.asList("fib(10)=55", "counter=1,2", "sum=12", "caught:boom", "done"),
                displayAll(prints));
        try {
            SjbcV5Container.open(blob, SjbcV5Container.masterFromCertSha256Hex(CERT_SHA256));
            org.junit.Assert.fail("hello 是 self 证书签的，真签名指纹不该解得开");
        } catch (SjbcException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("池层") || expected.getMessage().contains("码层"));
        }
    }

    /** `pc-tools/out/hello.sjbc`（695B，`--cert self` 产物）。 */
    private static final String HELLO_SELF_B64 =
            "U0pCRQUBAQAGUfcjirOsnMCPJLGGWD8Wi44G371JXt5PKmOUAAAAlxxiYCljUTSVBlK+9sTddPIMRi+iR1UibeeLZH9W9KUSuW0+iDDnC997zgDm6keeEuOl00GE8T6TpxKwvvoju4UOKBGWJTeEGja3DMDu6RCmSzY8V8sTWaT/LMj+NOZLjjspz5WrpN3kV2WEd5NfeLauC0iYhSgDnt+fNQI/RkLrQOE7GvSskgglQ95o0hdrpL14CL6NWmg/E/pmsZbjX33bmwJiUTmPov8d0Z92zh1JGjwXTxSdZAPMTv+9jXIquIvKFixXOZb4g85oBbz1ZcJIZ69K5+dU82Y7W850X9eSNk6U4P124GxWu7sgHqVG7Py+IAx6C6uq1eH47AitD5U1EPbGJ4wsVWUccJtOxuu/rff3m5JeTW9N+4TMzNBWfOfOVriiXITovBw0yhIwt4/wZ72xaupK9oZYrwCOLXRZ3QuMnS5tWv1BiJhUACGGBhQjChtEQjZyvpALfdpKcjMQgZvf0ZbRhajFrUnjNbzhPv4SMwOIi0S+VQUuQqiWU5yKKnYo4TXrN2jcCuWw0xrDjmJYWXXP3AC6s6oF2Aqja9F0cqcDJZMhNDdDuAI/C8wSYDDhGz91Cmv/pNPkUdmWqOmYQnwAym/75Z398WrEcXWgpxwfFbDPVOkx+BwUaERG6lv4k0mt0CGXZpXf8bNcZxxZwhNjVRCIB+ZektZIDS9WgA9a3EeTSzuHEvANCo+WD+fBhNBUmztH2bcajwY5oBXA/knM+LIFZUe2tUqHLKr3EMuPX2d3bJ3KWEzCOaUICtnyFZzcdMQYL09O0CeGVhnz3c03IAMlckg5VF1vdccZ/SNpA5GJpWYG1PuxFrNbDEBSJZCUcRZrmAd3lr8QhOEgp8kGHSNHgh8VV1A=";

    private static String javaDigest() throws Exception {
        List<String> rows = new ArrayList<>();
        byte[] master = SjbcV5Container.masterFromCertSha256Hex(CERT_SHA256);
        for (Object[] module : MODULES) {
            SjbcV5Container.Opened opened = SjbcV5Container.open(
                    Base64.getDecoder().decode((String) module[2]), master);
            for (int entryIndex = 0; entryIndex < opened.entries.length; entryIndex++) {
                List<Object> prints = new ArrayList<>();
                SjbcVm.Result result = SjbcVm.run(opened.code, opened.pool, SjbcVm.defaultHost(prints),
                        opened.entries, entryIndex);
                rows.add(digestRow((String) module[0], entryIndex, result.steps, displayAll(prints)));
            }
        }
        for (Object[] testCase : CASES) {
            List<Object> prints = new ArrayList<>();
            SjbcVm.Result result = SjbcVm.run(hex((String) testCase[1]), jsonValues(new JSONArray((String) testCase[2])),
                    SjbcVm.defaultHost(prints), ints(new JSONArray((String) testCase[3])), 0);
            rows.add(digestRow((String) testCase[0], 0, result.steps, displayAll(prints)));
        }
        return digestOf(rows);
    }

    static String digestRow(String name, int entryIndex, long steps, List<String> prints) {
        StringBuilder sb = new StringBuilder();
        sb.append(name).append('|').append(entryIndex).append('|').append(steps);
        for (String print : prints) {
            sb.append("|<").append(print.length()).append(':').append(print).append('>');
        }
        return sb.toString();
    }

    static String digestOf(List<String> rows) {
        List<String> sorted = new ArrayList<>(rows);
        Collections.sort(sorted);
        StringBuilder sb = new StringBuilder();
        for (String row : sorted) {
            sb.append(row).append('\n');
        }
        return sb.toString().trim();
    }

    private static boolean hasNode() {
        try {
            Process process = new ProcessBuilder("node", "--version").redirectErrorStream(true).start();
            process.waitFor();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static byte[] readAll(Process process) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = process.getInputStream().read(buffer)) > 0) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static List<Object> arg(Object value) {
        List<Object> args = new ArrayList<>();
        args.add(value);
        return args;
    }

    private static List<Object> args(Object first, Object second) {
        List<Object> args = arg(first);
        args.add(second);
        return args;
    }

    private static List<String> strings(JSONArray array) throws Exception {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            out.add(String.valueOf(array.get(i)));
        }
        return out;
    }

    private static List<Object> jsonValues(JSONArray array) throws Exception {
        List<Object> out = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            Object value = array.get(i);
            out.add(value instanceof Number ? SjbcValues.number(((Number) value).doubleValue())
                    : value instanceof String ? value : SjbcVm.UNDEFINED);
        }
        return out;
    }

    private static List<String> displayAll(List<Object> values) {
        List<String> out = new ArrayList<>();
        for (Object value : values) {
            out.add(SjbcValues.toDisplayString(value));
        }
        return out;
    }

    private static byte[] hex(String value) {
        byte[] out = new byte[value.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static int[] ints(JSONArray array) throws Exception {
        int[] out = new int[array.length()];
        for (int i = 0; i < out.length; i++) {
            out[i] = array.getInt(i);
        }
        return out;
    }
}