package com.jdkshen.aijspro.autojs.build;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Node 入口脚本在「保护等级 2（编译 + 加密）」下的载荷生成。
 *
 * <h2>为什么 Node 没有真正的「编译后加密」</h2>
 *
 * <p>另外两个引擎能在打包机上真的把源码编译掉：Rhino 用 JVM 里的 {@code ScriptCompiler}
 * 产出 Java class（载荷类型 {@code PAYLOAD_TYPE_RHINO_CLASS}），QuickJS 用随包的原生
 * {@code JS_WriteObject(..., JS_WRITE_OBJ_BYTECODE)} 产出字节码（载荷类型
 * {@code PAYLOAD_TYPE_QUICKJS_BYTECODE}）。Node 用的是 V8，**V8 没有稳定可移植的
 * 字节码格式**：{@code vm.Script#createCachedData} / {@code NODE_COMPILE_CACHE} 产出的
 * code cache 绑定「V8 版本 + 构建标志 + 平台 + 地址随机化」，官方文档明确说不能跨版本或
 * 跨平台搬运；而且生成它需要在目标 V8 运行时里真的跑一次脚本——打包机上只有
 * arm64 的 {@code libnode.so} 镜像，装不出 Android 版 Node 来执行。
 *
 * <p>所以这里不去伪造字节码，而是给出一个**诚实且可执行的等价物**：把源码压成
 * 「自解包加载器」，运行时先还原再执行。
 * <ul>
 *   <li>产物里没有可读源码（载荷是 AES 密文，解出来也只有 base64 数据）；</li>
 *   <li>不改执行语义：解码后的文本仍然是入口脚本自身，{@code var} 提升、{@code require}、
 *       {@code __dirname}/{@code module.exports} 都按原样工作；</li>
 *   <li>不需要新载荷类型：文件头仍写 {@code PAYLOAD_TYPE_TEXT}，旧运行端也能读，
 *       避免「引擎常量是 Kotlin const val（编译期内联）」带来的版本耦合。</li>
 * </ul>
 *
 * <p>安全性说明（不要把它说成比实际更强）：等级 1 与等级 2 的差别是**产物可读性**，
 * 不是密码学强度——两者的 AES 密钥都来自 {@code project.json}（新方案再叠加随机盐 +
 * 打包签名指纹）。真正的密钥硬化在 {@code ScriptKeyDerivation}，与本类无关。
 *
 * <h2>为什么加载器前面要保留源码序言</h2>
 *
 * <p>运行端用文本判断两件事，都在源码最前面：
 * <ul>
 *   <li>{@code JavaScriptSource.engineFromDirective}：首行 {@code // @engine node24}
 *       或 {@code "nodejs";} 决定用哪个引擎；</li>
 *   <li>{@code JavaScriptSource.parseExecutionMode} 与
 *       {@code Node24JavaScriptEngine.parseFeatures}：首行的 {@code "ui";}/{@code "auto";}
 *       决定 UI 执行模式与保活策略。</li>
 * </ul>
 * 这些判断读的是**文本**而不是文件头（{@code StringScriptSource} 也没把文件头里的模式位
 * 传下去），所以加载器必须原样保留源码里这段序言，否则等级 2 的 Node 产物会退化成
 * 「跑起来但不是 UI 模式 / 选不回 Node 引擎」。
 *
 * <p>本类刻意不依赖任何 Android API，便于 :app 的 JVM 单测直接覆盖。
 */
final class NodeScriptPayload {

    /** 加载器正文前的说明注释（也保证第一行不是空白，便于阅读与排错）。 */
    private static final String HEADER =
            "// [aijs-node-payload] 该文件不包含可读源码；已按保护等级 2 处理，运行时自解包后执行。\n";

    /** 首行能安全改写成赋值绑定（保留名字）的包名；其余包名保守地不建绑定。 */
    private static final Pattern SIMPLE_PACKAGE_NAME =
            Pattern.compile("^[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*$");

    /** 单次 base64 解码的码元数：一次最多产出 3KB，避免大脚本一次申请过多内存。 */
    private static final int DECODE_CHUNK = 4096;

    /** 每个 base64 分片的前缀（生成与回读共用，避免两边各写一份字符串）。 */
    private static final String PAYLOAD_CHUNK_PREFIX = "s+=u(\"";

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private NodeScriptPayload() {
    }

    /**
     * 把入口脚本源码转成「源码不可读、行为等价」的加载器文本。
     *
     * @param sourceText 入口脚本原文（UTF-8 解码后）
     * @param scriptName 脚本名；写进 {@code sourceURL}，让运行时异常回到真实文件名
     */
    static String wrap(String sourceText, String scriptName) {
        if (sourceText == null) {
            throw new IllegalArgumentException("sourceText == null");
        }
        String prologue = extractPrologue(sourceText);
        String base64 = base64Of(sourceText.getBytes(UTF8));
        String safeName = scriptName == null || scriptName.isEmpty() ? "main.js" : scriptName;

        StringBuilder out = new StringBuilder(base64.length() + prologue.length() + 640);
        out.append(prologue);
        out.append(HEADER);
        out.append(bindingsFromPrologue(prologue));
        out.append("(function(){");
        out.append("var s=\"\";");
        // 用构造器本身解码（Buffer.from），不要先 Buffer.from("") 再取 .from —— 那拿到的是
        // 一个 Buffer 实例，实例上没有 from()，会在运行时抛 "d.from is not a function"。
        out.append("var B=typeof Buffer!=='undefined'?Buffer:null;");
        out.append("function u(t){");
        out.append("if(!B){throw new Error('[aijs-node-payload] Buffer 不可用，无法还原入口脚本');}");
        out.append("return B.from(t,'base64').toString('utf8');}");
        out.append("for(var i=0;i<");
        out.append(base64.length());
        out.append(";i+=");
        out.append(DECODE_CHUNK);
        out.append("){");
        out.append(PAYLOAD_CHUNK_PREFIX);
        for (int i = 0; i < base64.length(); i += DECODE_CHUNK) {
            if (i > 0) {
                out.append("\");").append(PAYLOAD_CHUNK_PREFIX);
            }
            out.append(base64, i, Math.min(base64.length(), i + DECODE_CHUNK));
        }
        out.append("\");}");
        // 用直接 eval（不是 (0,eval)）：直接 eval 继承 IIFE 所在的作用域，因此入口脚本里的
        // require/module/exports 与文件模块一致；间接 eval 只能看到全局作用域，require 会变成
        // "require is not defined"。这条在真机 Node 24 上验证过。
        out.append("eval(s+'\\n//# sourceURL='+");
        out.append(quote(safeName));
        out.append(");");
        out.append("})();\n");
        return out.toString();
    }

    /**
     * 取源码开头必须保持「文本可见」的序言：{@code "ui";} / {@code "auto";} 这类执行模式声明，
     * 以及注释形式的引擎指令（{@code // @engine ...}）。
     *
     * <p>与 {@code JavaScriptSource.engineFromDirective} 的规则保持一致：遇到第一行
     * 其它内容就停（所以空行会被跳过、代码之前的整段声明都会保留）。
     */
    static String extractPrologue(String sourceText) {
        StringBuilder prologue = new StringBuilder();
        int offset = 0;
        while (offset < sourceText.length()) {
            int lineEnd = sourceText.indexOf('\n', offset);
            if (lineEnd < 0) {
                lineEnd = sourceText.length();
            }
            // 首行的 UTF-8 BOM 必须先剥掉再判定，否则「BOM + "nodejs";」会被当成
            // 普通代码行，整段序言（引擎指令/执行模式）跟着丢失。
            String trimmed = stripBom(sourceText.substring(offset, lineEnd)).trim();
            if (!trimmed.isEmpty() && !isPrologueLine(trimmed)) {
                break;
            }
            prologue.append(trimmed).append('\n');
            offset = lineEnd + 1;
        }
        return prologue.toString();
    }

    /** 去掉行首的 UTF-8 BOM（序言行可能带着它）。 */
    private static String stripBom(String line) {
        int start = 0;
        while (start < line.length() && line.charAt(start) == '\ufeff') {
            start++;
        }
        return start == 0 ? line : line.substring(start);
    }

    /** 这一行是否属于「必须保持原样」的序言。 */
    private static boolean isPrologueLine(String trimmed) {
        if ("\"ui\";".equals(trimmed) || "'ui';".equals(trimmed)
                || "\"auto\";".equals(trimmed) || "'auto';".equals(trimmed)) {
            return true;
        }
        // Auto.js Pro 的首行字符串声明（"nodejs"; / "nodejs ui"; / "quickjs";）同样必须保留：
        // JavaScriptSource.engineFromDirective 靠它选引擎。
        if (stringStatementContent(trimmed) != null) {
            return true;
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        return lower.equals("// @engine node24") || lower.equals("// @engine node")
                || lower.equals("// @engine nodejs") || lower.equals("// @engine quickjs")
                || lower.equals("// @engine rhino");
    }

    /**
     * {@code "nodejs";} 这类「首行字符串前置声明」只有作为整行时才是 Pro 风格指令；
     * 原样保留会变成一条无副作用的字符串表达式语句——这里顺手把它改写成一个赋值绑定，
     * 名字就叫 {@code nodejs}（或 {@code quickjs}），与 Pro 的「前置声明等价于全局标记」
     * 语义一致，也避免被 linter 当成无意义语句。
     *
     * <p>包名不是简单标识符（含 {@code -}、空格）时不生成绑定，避免造出非法语法。
     */
    private static String bindingsFromPrologue(String prologue) {
        StringBuilder bindings = new StringBuilder();
        for (String raw : prologue.split("\n")) {
            String line = raw.trim();
            String content = stringStatementContent(line);
            if (content == null) {
                continue;
            }
            if (content.isEmpty() || "ui".equals(content) || "auto".equals(content)) {
                continue;
            }
            if (!SIMPLE_PACKAGE_NAME.matcher(content).matches()) {
                continue;
            }
            String literal = content.indexOf('\'') >= 0
                    ? "\"" + content + "\"" : "'" + content + "'";
            bindings.append(content).append('=').append(literal).append(';');
        }
        return bindings.length() == 0 ? "" : bindings.append('\n').toString();
    }

    /**
     * 整行就是一条字符串表达式语句时返回它的内容，否则返回 null。
     *
     * <p>两种写法都认：双引号 {@code "nodejs";} 与单引号 {@code 'nodejs';}。
     */
    private static String stringStatementContent(String line) {
        if (line.length() < 3) {
            return null;
        }
        char quote = line.charAt(0);
        if (quote != '"' && quote != '\'') {
            return null;
        }
        int end = line.indexOf(quote, 1);
        if (end < 0) {
            return null;
        }
        String rest = line.substring(end + 1).trim();
        if (!rest.isEmpty() && !";".equals(rest)) {
            return null;
        }
        return line.substring(1, end).trim();
    }

    /** 标准 base64（含 {@code +}、{@code /}、{@code =} 补位），与 Node 的 {@code base64} 解码一致。 */
    static String base64Of(byte[] bytes) {
        final char[] table =
                "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();
        StringBuilder out = new StringBuilder(((bytes.length + 2) / 3) * 4);
        int i = 0;
        for (; i + 2 < bytes.length; i += 3) {
            int value = (bytes[i] & 0xff) << 16 | (bytes[i + 1] & 0xff) << 8 | (bytes[i + 2] & 0xff);
            out.append(table[(value >>> 18) & 0x3f]).append(table[(value >>> 12) & 0x3f])
                    .append(table[(value >>> 6) & 0x3f]).append(table[value & 0x3f]);
        }
        int remaining = bytes.length - i;
        if (remaining == 1) {
            int value = (bytes[i] & 0xff) << 16;
            out.append(table[(value >>> 18) & 0x3f]).append(table[(value >>> 12) & 0x3f]).append("==");
        } else if (remaining == 2) {
            int value = (bytes[i] & 0xff) << 16 | (bytes[i + 1] & 0xff) << 8;
            out.append(table[(value >>> 18) & 0x3f]).append(table[(value >>> 12) & 0x3f])
                    .append(table[(value >>> 6) & 0x3f]).append('=');
        }
        return out.toString();
    }

    /** JS 单引号字符串字面量转义。 */
    private static String quote(String text) {
        StringBuilder out = new StringBuilder(text.length() + 2);
        out.append('\'');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\':
                    out.append("\\\\");
                    break;
                case '\'':
                    out.append("\\'");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\u2028':
                    out.append("\\u2028");
                    break;
                case '\u2029':
                    out.append("\\u2029");
                    break;
                default:
                    if (c < 0x20) {
                        out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.append('\'').toString();
    }

    /**
     * 从加载器文本里取回 base64 数据段拼出的原码元（仅供测试/诊断核对可逆性）。
     *
     * <p>必须用 {@code s+=u("} 这个完整前缀定位：只找 {@code u("} 会先命中
     * {@code function u(t){} 里的 {@code u(t}，或者更早的 {@code Buffer.from("")}。
     */
    static String embeddedBase64(String wrapped) {
        List<String> chunks = new ArrayList<>();
        int from = 0;
        int position = 0;
        while (true) {
            int start = wrapped.indexOf(PAYLOAD_CHUNK_PREFIX, from);
            if (start < 0) {
                break;
            }
            position = start + PAYLOAD_CHUNK_PREFIX.length();
            int end = wrapped.indexOf("\"", position);
            if (end < 0) {
                break;
            }
            chunks.add(wrapped.substring(position, end));
            from = end + 1;
        }
        if (chunks.isEmpty()) {
            return "";
        }
        int firstDataChunk = dataChunkStart(chunks);
        StringBuilder out = new StringBuilder();
        for (int i = firstDataChunk; i < chunks.size(); i++) {
            out.append(chunks.get(i));
        }
        return out.toString();
    }

    /**
     * 找到「真正的 base64 数据」从第几个片段开始。
     *
     * <p>只有 {@code s+=u("} 这个前缀也会命中函数体里的 {@code .toString('utf8');}...}：
     * 单分片时它是列表里唯一的一项（所以数据从 0 开始），多分片时它排在最前面（数据从 1 开始）。
     * 判据是「这个片段是不是纯 base64 字符」——函数体里有括号、引号、分号，一定不是。
     */
    private static int dataChunkStart(List<String> chunks) {
        if (chunks.size() == 1) {
            return 0;
        }
        return isBase64(chunks.get(0)) ? 0 : 1;
    }

    private static boolean isBase64(String text) {
        if (text.isEmpty()) {
            return true;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '+' || c == '/' || c == '=';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /** 用于日志：源码里是否还有可读的原始行（诊断用，不做安全承诺）。 */
    static boolean containsReadableSource(String wrapped, String sourceText) {
        if (sourceText == null || sourceText.isEmpty()) {
            return false;
        }
        Matcher matcher = Pattern.compile("\\S{16,}").matcher(sourceText);
        while (matcher.find()) {
            String sample = matcher.group();
            if (!sample.startsWith("//") && wrapped.contains(sample)) {
                return true;
            }
        }
        return false;
    }
}
