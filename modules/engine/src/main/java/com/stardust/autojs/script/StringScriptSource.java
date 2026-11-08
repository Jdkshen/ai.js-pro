package com.stardust.autojs.script;


import androidx.annotation.Nullable;

import java.io.Reader;

/**
 * Created by Stardust on 2017/4/2.
 */

public class StringScriptSource extends JavaScriptSource {

    private String mScript;
    /** 原始脚本文件路径（加密文本载荷也走这里；Rhino 执行前要用它刷新 require 根）。 */
    private String mScriptPath;

    public StringScriptSource(String script) {
        super("Tmp");
        mScript = script;
    }

    public StringScriptSource(String name, String script) {
        this(name, script, null);
    }

    public StringScriptSource(String name, String script, String scriptPath) {
        super(name);
        mScript = script;
        mScriptPath = scriptPath;
    }

    public String getScriptPath() {
        return mScriptPath;
    }

    @Override
    public String getScript() {
        return mScript;
    }

    @Nullable
    @Override
    public Reader getScriptReader() {
        return null;
    }

}
