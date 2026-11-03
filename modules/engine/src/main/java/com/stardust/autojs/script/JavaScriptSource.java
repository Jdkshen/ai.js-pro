package com.stardust.autojs.script;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.stardust.util.MapBuilder;

import java.io.Reader;
import java.io.StringReader;
import java.util.Map;

/**
 * Created by Stardust on 2017/8/2.
 */

public abstract class JavaScriptSource extends ScriptSource {

    public static final String ENGINE = "com.stardust.autojs.script.JavaScriptSource.Engine";
    public static final String ENGINE_RHINO = ENGINE;
    public static final String ENGINE_QUICKJS = ENGINE + ".QuickJS";
    public static final String ENGINE_NODE24 = ENGINE + ".Node24";

    /**
     * 脚本首行写上这个指令可以显式指定引擎。没有任何配置时保持旧行为（Rhino 兼容引擎），
     * 新建脚本/项目由编辑器与模板写入本指令来使用 QuickJS，不做静默切换；
     * 选择规则见 docs/architecture/ENGINE_FLAVORS.md。
     */
    public static final String QUICKJS_ENGINE_DIRECTIVE = "// @engine quickjs";
    public static final String RHINO_ENGINE_DIRECTIVE = "// @engine rhino";
    public static final String NODE24_ENGINE_DIRECTIVE = "// @engine node24";

    public static final String EXECUTION_MODE_UI_PREFIX = "\"ui\";";

    public static final int EXECUTION_MODE_NORMAL = 0;
    public static final int EXECUTION_MODE_UI = 0x00000001;
    public static final int EXECUTION_MODE_AUTO = 0x00000002;

    private static final Map<String, Integer> EXECUTION_MODES = new MapBuilder<String, Integer>()
            .put("ui", EXECUTION_MODE_UI)
            .put("auto", EXECUTION_MODE_AUTO)
            .build();
    private static final int PARSING_MAX_TOKEN = 300;

    private int mExecutionMode = -1;
    private String mPreferredEngine;
    /** 见 {@link #setForcedEngine}：只有解析兜底会设它。 */
    private String mForcedEngine;

    public JavaScriptSource(String name) {
        super(name);
    }

    @NonNull
    public abstract String getScript();

    @Nullable
    public abstract Reader getScriptReader();

    @NonNull
    public Reader getNonNullScriptReader() {
        Reader reader = getScriptReader();
        if (reader == null) {
            return new StringReader(getScript());
        }
        return reader;
    }

    public String toString() {
        return getName() + ".js";
    }


    public int getExecutionMode() {
        if (mExecutionMode == -1) {
            mExecutionMode = parseExecutionMode();
        }
        return mExecutionMode;
    }

    protected int parseExecutionMode() {
        return readDirectives(getScript()).mode;
    }

    private static int parseExecutionMode(String[] modeStrings) {
        int mode = 0;
        for (String modeString : modeStrings) {
            Integer i = EXECUTION_MODES.get(modeString);
            if (i != null) {
                mode |= i;
            }
        }
        return mode;
    }

    @Override
    public String getEngineName() {
        if (mForcedEngine != null) {
            return mForcedEngine;
        }
        String directiveEngine = engineFromDirective(getScript());
        // 选择优先级（见 docs/architecture/ENGINE_FLAVORS.md 与 QUICKJS_MIGRATION_MATRIX.md）：
        //   1. 脚本首行指令 → 2. 工程 scripts.<路径>.engine → 3. 工程顶层 engine
        //   4. 没有任何配置的旧脚本继续用 Rhino 兼容引擎；新建脚本由编辑器写入
        //      // @engine quickjs 显式选择新引擎，不做静默切换。
        return directiveEngine != null
                ? directiveEngine
                : (mPreferredEngine == null ? getDefaultEngine() : mPreferredEngine);
    }

    protected String getDefaultEngine() { return ENGINE_RHINO; }

    public static boolean requestsQuickJs(String script) {
        return ENGINE_QUICKJS.equals(engineFromDirective(script));
    }

    /**
     * Auto.js Pro 风格的首行字符串引擎指令：
     * {@code "nodejs";}、{@code "nodejs ui";}、{@code "quickjs";}。
     * 只认「整行就是一个字符串表达式」；{@code "ui";}/{@code "auto";} 返回 null 交给调用方继续扫。
     */
    @Nullable
    private static String engineFromProDirective(String line) {
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
        if (!rest.isEmpty() && !rest.equals(";")) {
            return null;
        }
        String content = line.substring(1, end).trim();
        if (content.isEmpty()) {
            return null;
        }
        boolean node = false;
        boolean quickjs = false;
        boolean rhino = false;
        for (String word : content.split("\\s+")) {
            if ("nodejs".equalsIgnoreCase(word) || "node".equalsIgnoreCase(word)) {
                node = true;
            } else if ("quickjs".equalsIgnoreCase(word)) {
                quickjs = true;
            } else if ("rhino".equalsIgnoreCase(word) || "rhinojs".equalsIgnoreCase(word)) {
                rhino = true;
            }
        }
        if (node) {
            return ENGINE_NODE24;
        }
        if (quickjs) {
            return ENGINE_QUICKJS;
        }
        if (rhino) {
            return ENGINE_RHINO;
        }
        return null;
    }

    @Nullable
    public static String engineFromDirective(String script) {
        return readDirectives(script).engine;
    }

    /** Read the opening directive statements, rather than assuming one statement per line. */
    private static Directives readDirectives(String script) {
        Directives result = new Directives();
        if (script == null || script.isEmpty()) return result;
        int offset = script.charAt(0) == '\ufeff' ? 1 : 0;
        for (int count = 0; count < PARSING_MAX_TOKEN && offset < script.length(); count++) {
            char ch = script.charAt(offset);
            if (Character.isWhitespace(ch)) { offset++; count--; continue; }
            if (script.startsWith("//", offset)) {
                int end = script.indexOf('\n', offset);
                if (end < 0) end = script.length();
                String comment = script.substring(offset, end).trim();
                if (result.engine == null) {
                    if (QUICKJS_ENGINE_DIRECTIVE.equalsIgnoreCase(comment)) result.engine = ENGINE_QUICKJS;
                    else if (RHINO_ENGINE_DIRECTIVE.equalsIgnoreCase(comment)) result.engine = ENGINE_RHINO;
                    else if (NODE24_ENGINE_DIRECTIVE.equalsIgnoreCase(comment)
                            || "// @engine node".equalsIgnoreCase(comment)
                            || "// @engine nodejs".equalsIgnoreCase(comment)) result.engine = ENGINE_NODE24;
                }
                offset = end;
                continue;
            }
            if (script.startsWith("/*", offset)) {
                int end = script.indexOf("*/", offset + 2);
                if (end < 0) break;
                offset = end + 2;
                continue;
            }
            if (ch != '\'' && ch != '"') break;
            int end = offset + 1;
            while (end < script.length() && script.charAt(end) != ch
                    && script.charAt(end) != '\\' && script.charAt(end) != '\n'
                    && script.charAt(end) != '\r') end++;
            if (end == script.length() || script.charAt(end) != ch) break;
            int after = end + 1;
            while (after < script.length() && (script.charAt(after) == ' ' || script.charAt(after) == '\t')) after++;
            // A string used as an operand/callee is not an engine declaration.
            if (after < script.length() && script.charAt(after) != ';'
                    && script.charAt(after) != '\n' && script.charAt(after) != '\r') break;
            String content = script.substring(offset + 1, end);
            if (result.engine == null) {
                result.engine = engineFromProDirective(script.substring(offset, end + 1));
            }
            result.mode |= parseExecutionMode(content.trim().split("\\s+"));
            offset = after < script.length() && script.charAt(after) == ';' ? after + 1 : after;
        }
        return result;
    }

    private static final class Directives {
        String engine;
        int mode;
    }

    public void setPreferredEngine(@Nullable String engine) {
        mPreferredEngine = normalizeEngine(engine);
    }

    /**
     * 强制指定引擎，**优先于脚本首行指令**。
     *
     * <p>只给「当前引擎连解析都过不去」的兜底用（见 {@code EngineFallbacks}）：那种情况下
     * 脚本在指定引擎上注定跑不起来，换引擎是救它。正常路径请用
     * {@link #setPreferredEngine(String)}（它不会压过首行指令）。
     */
    public void setForcedEngine(@Nullable String engine) {
        mForcedEngine = normalizeEngine(engine);
    }

    @Nullable
    private static String normalizeEngine(@Nullable String engine) {
        if (engine == null || engine.trim().isEmpty()) {
            return null;
        }
        String normalized = engine.trim();
        if ("quickjs".equalsIgnoreCase(normalized) || ENGINE_QUICKJS.equals(normalized)) {
            return ENGINE_QUICKJS;
        }
        if ("rhino".equalsIgnoreCase(normalized) || ENGINE_RHINO.equals(normalized)) {
            return ENGINE_RHINO;
        }
        if ("node24".equalsIgnoreCase(normalized) || "node".equalsIgnoreCase(normalized)
                || "nodejs".equalsIgnoreCase(normalized) || ENGINE_NODE24.equals(normalized)) {
            return ENGINE_NODE24;
        }
        throw new IllegalArgumentException("Unsupported JavaScript engine: " + engine);
    }


}
