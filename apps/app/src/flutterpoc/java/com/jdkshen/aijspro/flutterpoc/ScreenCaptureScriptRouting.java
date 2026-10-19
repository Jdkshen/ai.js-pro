package com.jdkshen.aijspro.flutterpoc;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 识别需要本机截图/投屏能力的脚本，供 {@link PocRuntime} 在进入隔离进程前决定是否留在主进程。
 *
 * <p>为什么需要：{@code :script} 进程拿不到本地 MediaProjection 帧，
 * {@code requestScreenCapture}/{@code captureScreen}/{@code captureScreenNative} 只能走主进程 PNG
 * 回传或直接报「隔离进程暂不支持投屏截图」。图色性能脚本必须保留真实投屏帧和本机零拷贝路径，
 * 不能降级成慢速 IPC PNG，因此这类脚本要在分发前回主进程执行。
 *
 * <p>这里只做轻量词法级能力探测：跳过行注释、块注释、字符串和模板字符串，再识别已知 API 的
 * 直接调用；字符串只在 {@code images/$images.on("screen_capture", ...)} 的参数位置用于确认
 * 异步截图事件。它不解析变量别名、动态属性名和模板插值，但足以覆盖内置图色示例，也不会把
 * 注释/文档/普通字符串里的 API 名称误判成真实调用。
 */
final class ScreenCaptureScriptRouting {

    /** 实际会申请、读取或共享本机截图会话的 JS API（同步/异步/共享/窗口）。 */
    private static final Set<String> LOCAL_CAPTURE_CALLS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "requestScreenCapture",
                    "captureScreen",
                    "shareScreenCapture",
                    "useSharedScreenCapture",
                    "stopScreenCapture",
                    "getScreenCaptureOptions",
                    "isSharedScreenCapture",
                    "setImageCaptureCallback",
                    "captureWindow",
                    "listCaptureWindows",
                    "isWindowCaptureAvailable"
            )));

    private static final String IMAGES = "images";
    private static final String DOLLAR_IMAGES = "$images";
    private static final String SCREEN_CAPTURE_EVENT = "screen_capture";

    private ScreenCaptureScriptRouting() {
    }

    /**
     * 返回脚本中命中的本机截图能力 API（按首次出现顺序去重）；空列表表示可以继续走原隔离路由。
     *
     * @throws IOException 脚本读取失败；调用方按原有路由继续，不能因为探测失败阻断运行
     */
    static List<String> localCaptureApis(File scriptFile) throws IOException {
        if (scriptFile == null || !scriptFile.isFile()) {
            return Collections.emptyList();
        }
        String source = readUtf8(scriptFile);
        boolean candidate = source.contains(SCREEN_CAPTURE_EVENT);
        for (String api : LOCAL_CAPTURE_CALLS) {
            if (source.contains(api)) {
                candidate = true;
                break;
            }
        }
        if (!candidate) return Collections.emptyList();
        List<Token> tokens = tokenize(source);
        LinkedHashSet<String> apis = new LinkedHashSet<>();
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.type != Token.IDENTIFIER) {
                continue;
            }
            if (LOCAL_CAPTURE_CALLS.contains(token.text) && isCaptureCall(tokens, i)) {
                apis.add(token.text);
                continue;
            }
            if ("on".equals(token.text)) {
                String receiver = screenCaptureEventReceiver(tokens, i);
                if (receiver != null) {
                    apis.add(receiver + ".on(\"" + SCREEN_CAPTURE_EVENT + "\")");
                }
            }
        }
        return new ArrayList<>(apis);
    }

    private static String readUtf8(File file) throws IOException {
        StringBuilder source = new StringBuilder((int) Math.min(file.length(), 256 * 1024L));
        try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            char[] buffer = new char[8192];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                source.append(buffer, 0, read);
            }
        }
        return source.toString();
    }

    /**
     * 轻量词法扫描：只保留标识符、字符串值和标点，跳过注释/模板字符串。
     *
     * <p>这不是完整 JS 解析器：例如模板插值中的代码不会展开（宁可漏判，不把模板文本当代码）。
     */
    private static List<Token> tokenize(String source) {
        List<Token> tokens = new ArrayList<>();
        int length = source.length();
        int index = 0;
        while (index < length) {
            char c = source.charAt(index);
            if (Character.isWhitespace(c)) {
                index++;
                continue;
            }
            if (c == '/' && index + 1 < length) {
                char next = source.charAt(index + 1);
                if (next == '/') {
                    index += 2;
                    while (index < length) {
                        char commentChar = source.charAt(index);
                        if (commentChar == '\n' || commentChar == '\r') {
                            break;
                        }
                        index++;
                    }
                    continue;
                }
                if (next == '*') {
                    index = skipBlockComment(source, index);
                    continue;
                }
            }
            if (c == '\'' || c == '"') {
                StringBuilder value = new StringBuilder();
                index = readQuotedString(source, index, c, value);
                tokens.add(Token.string(value.toString()));
                continue;
            }
            if (c == '`') {
                index = skipTemplateLiteral(source, index);
                continue;
            }
            if (isIdentifierStart(c)) {
                int start = index++;
                while (index < length && isIdentifierPart(source.charAt(index))) {
                    index++;
                }
                tokens.add(Token.identifier(source.substring(start, index)));
                continue;
            }
            if (c == '?' && index + 1 < length && source.charAt(index + 1) == '.') {
                tokens.add(Token.punctuation("?."));
                index += 2;
                continue;
            }
            tokens.add(Token.punctuation(String.valueOf(c)));
            index++;
        }
        return tokens;
    }

    private static int skipBlockComment(String source, int start) {
        int index = start + 2;
        while (index + 1 < source.length()) {
            if (source.charAt(index) == '*' && source.charAt(index + 1) == '/') {
                return index + 2;
            }
            index++;
        }
        return source.length();
    }

    private static int readQuotedString(String source, int start, char quote, StringBuilder value) {
        int index = start + 1;
        while (index < source.length()) {
            char c = source.charAt(index);
            if (c == quote) {
                return index + 1;
            }
            if (c == '\\') {
                if (index + 1 < source.length()) {
                    char escaped = source.charAt(index + 1);
                    if (escaped != '\n' && escaped != '\r') {
                        value.append(escaped);
                    }
                    index += 2;
                    continue;
                }
                return source.length();
            }
            if (c == '\n' || c == '\r') {
                return index;
            }
            value.append(c);
            index++;
        }
        return index;
    }

    private static int skipTemplateLiteral(String source, int start) {
        int index = start + 1;
        while (index < source.length()) {
            char c = source.charAt(index);
            if (c == '\\') {
                index = Math.min(source.length(), index + 2);
                continue;
            }
            if (c == '`') {
                return index + 1;
            }
            index++;
        }
        return source.length();
    }

    private static boolean isIdentifierStart(char c) {
        return c == '_' || c == '$' || Character.isLetter(c);
    }

    private static boolean isIdentifierPart(char c) {
        return c == '_' || c == '$' || Character.isLetterOrDigit(c);
    }

    /** 方法名后紧跟调用（允许 {@code ?.()}，token 已忽略空白和注释）。 */
    private static boolean isCall(List<Token> tokens, int identifierIndex) {
        int next = identifierIndex + 1;
        if (next >= tokens.size()) {
            return false;
        }
        Token token = tokens.get(next);
        if (token.isPunctuation("?.")) {
            next++;
            if (next >= tokens.size()) {
                return false;
            }
            token = tokens.get(next);
        }
        return token.isPunctuation("(");
    }

    private static boolean isCaptureCall(List<Token> tokens, int index) {
        if (!isCall(tokens, index)) return false;
        if (index == 0) return true;
        Token previous = tokens.get(index - 1);
        if (previous.isIdentifier("function") || previous.isIdentifier("new")) return false;
        if (previous.isPunctuation(".") || previous.isPunctuation("?.")) {
            if (index < 2) return false;
            Token receiver = tokens.get(index - 2);
            return receiver.isIdentifier(IMAGES) || receiver.isIdentifier(DOLLAR_IMAGES)
                    || receiver.isIdentifier("globalThis") || receiver.isIdentifier("global");
        }
        return true;
    }

    /**
     * 识别 {@code images.on("screen_capture", fn)} / {@code $images.on(...)}。
     *
     * <p>只认这两个截图对象上的 {@code on}，并且第一个参数必须是字符串字面量
     * {@code screen_capture}；普通字符串里的同名文本已在词法阶段被排除。
     */
    private static String screenCaptureEventReceiver(List<Token> tokens, int onIndex) {
        if (!isCall(tokens, onIndex)) {
            return null;
        }
        int separator = onIndex - 1;
        if (separator < 0) {
            return null;
        }
        Token separatorToken = tokens.get(separator);
        if (!separatorToken.isPunctuation(".") && !separatorToken.isPunctuation("?.")) {
            return null;
        }
        int receiverIndex = separator - 1;
        if (receiverIndex < 0) {
            return null;
        }
        Token receiver = tokens.get(receiverIndex);
        if (!receiver.isIdentifier(IMAGES) && !receiver.isIdentifier(DOLLAR_IMAGES)) {
            return null;
        }
        int openParen = onIndex + 1;
        if (tokens.get(openParen).isPunctuation("?.")) {
            openParen++;
        }
        int firstArgument = openParen + 1;
        if (firstArgument >= tokens.size()) {
            return null;
        }
        Token eventName = tokens.get(firstArgument);
        if (eventName.type != Token.STRING || !SCREEN_CAPTURE_EVENT.equals(eventName.text)) {
            return null;
        }
        return receiver.text;
    }

    private static final class Token {

        static final int IDENTIFIER = 1;
        static final int STRING = 2;
        static final int PUNCTUATION = 3;

        final int type;
        final String text;

        private Token(int type, String text) {
            this.type = type;
            this.text = text;
        }

        static Token identifier(String text) {
            return new Token(IDENTIFIER, text);
        }

        static Token string(String text) {
            return new Token(STRING, text);
        }

        static Token punctuation(String text) {
            return new Token(PUNCTUATION, text);
        }

        boolean isIdentifier(String value) {
            return type == IDENTIFIER && value.equals(text);
        }

        boolean isPunctuation(String value) {
            return type == PUNCTUATION && value.equals(text);
        }
    }
}
