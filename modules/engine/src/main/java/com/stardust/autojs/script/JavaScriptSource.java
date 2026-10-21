package com.stardust.autojs.script;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.stardust.autojs.rhino.TokenStream;
import com.stardust.util.MapBuilder;

import org.mozilla.javascript.Token;

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

    private static final String LOG_TAG = "JavaScriptSource";

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
        String script = getScript();
        TokenStream ts = new TokenStream(new StringReader(script), null, 1);
        int token;
        int count = 0;
        try {
            while (count <= PARSING_MAX_TOKEN && (token = ts.getToken()) != Token.EOF) {
                count++;
                if (token == Token.EOL || token == Token.COMMENT) {
                    continue;
                }
                if (token == Token.STRING && ts.getTokenLength() > 2) {
                    String tokenString = script.substring(ts.getTokenBeg() + 1, ts.getTokenEnd() - 1);
                    if (ts.getToken() != Token.SEMI) {
                        break;
                    }
                    Log.d(LOG_TAG, "string = " + tokenString);
                    return parseExecutionMode(tokenString.split(" "));
                }
                break;
            }
        } catch (Exception e) {
            e.printStackTrace();
            return EXECUTION_MODE_NORMAL;
        }
        return EXECUTION_MODE_NORMAL;

    }

    private int parseExecutionMode(String[] modeStrings) {
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
        if (script == null || script.isEmpty()) {
            return null;
        }
        int offset = 0;
        if (script.charAt(0) == '\ufeff') {
            offset = 1;
        }
        while (offset < script.length()) {
            int lineEnd = script.indexOf('\n', offset);
            if (lineEnd < 0) {
                lineEnd = script.length();
            }
            String line = script.substring(offset, lineEnd).trim();
            if (!line.isEmpty()) {
                if (QUICKJS_ENGINE_DIRECTIVE.equalsIgnoreCase(line)) {
                    return ENGINE_QUICKJS;
                }
                if (RHINO_ENGINE_DIRECTIVE.equalsIgnoreCase(line)) {
                    return ENGINE_RHINO;
                }
                if (NODE24_ENGINE_DIRECTIVE.equalsIgnoreCase(line) || "// @engine node".equalsIgnoreCase(line)) {
                    return ENGINE_NODE24;
                }
                // 「"ui";」「"auto";」这类执行模式声明按惯例写在脚本最前面，
                // 可以出现在引擎指令之前——跳过它继续找引擎指令。
                if (line.equals("\"ui\";") || line.equals("'ui';")
                        || line.equals("\"auto\";") || line.equals("'auto';")) {
                    offset = lineEnd + 1;
                    continue;
                }
                // Auto.js Pro 的写法：首行字符串指令，如 "nodejs"; / "nodejs ui"; / "quickjs";
                // 没写注释指令时按它选引擎（只有 ui/auto 的继续往下找引擎指令）。
                String proEngine = engineFromProDirective(line);
                if (proEngine != null) {
                    return proEngine;
                }
                return null;
            }
            offset = lineEnd + 1;
        }
        return null;
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
        if ("node24".equalsIgnoreCase(normalized) || "node".equalsIgnoreCase(normalized) || ENGINE_NODE24.equals(normalized)) {
            return ENGINE_NODE24;
        }
        throw new IllegalArgumentException("Unsupported JavaScript engine: " + engine);
    }


}
