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

    /**
     * 脚本首行写上这个指令可以显式指定引擎；不写时默认走 QuickJS，
     * 需要回到旧引擎的脚本在首行加 {@code // @engine rhino} 即可回退。
     */
    public static final String QUICKJS_ENGINE_DIRECTIVE = "// @engine quickjs";
    public static final String RHINO_ENGINE_DIRECTIVE = "// @engine rhino";

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
        String directiveEngine = engineFromDirective(getScript());
        // 无指令、无工程配置时默认走 QuickJS（新引擎）；需要旧引擎的脚本
        // 在首行加 // @engine rhino 就能回退。
        return directiveEngine != null
                ? directiveEngine
                : (mPreferredEngine == null ? ENGINE_QUICKJS : mPreferredEngine);
    }

    public static boolean requestsQuickJs(String script) {
        return ENGINE_QUICKJS.equals(engineFromDirective(script));
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
                // 「"ui";」「"auto";」这类执行模式声明按惯例写在脚本最前面，
                // 可以出现在引擎指令之前——跳过它继续找引擎指令。
                if (line.equals("\"ui\";") || line.equals("'ui';")
                        || line.equals("\"auto\";") || line.equals("'auto';")) {
                    offset = lineEnd + 1;
                    continue;
                }
                return null;
            }
            offset = lineEnd + 1;
        }
        return null;
    }

    public void setPreferredEngine(@Nullable String engine) {
        if (engine == null || engine.trim().isEmpty()) {
            mPreferredEngine = null;
            return;
        }
        String normalized = engine.trim();
        if ("quickjs".equalsIgnoreCase(normalized) || ENGINE_QUICKJS.equals(normalized)) {
            mPreferredEngine = ENGINE_QUICKJS;
        } else if ("rhino".equalsIgnoreCase(normalized) || ENGINE_RHINO.equals(normalized)) {
            mPreferredEngine = ENGINE_RHINO;
        } else {
            throw new IllegalArgumentException("Unsupported JavaScript engine: " + engine);
        }
    }


}
