package com.stardust.autojs.script;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class JavaScriptSourceEngineTest {

    @Test
    public void oldScriptsUseRhinoByDefault() {
        StringScriptSource source = new StringScriptSource("console.log('legacy');");
        assertEquals(JavaScriptSource.ENGINE, source.getEngineName());
    }

    @Test
    public void firstNonEmptyLineCanSelectQuickJs() {
        StringScriptSource source = new StringScriptSource(
                "\n  // @engine quickjs  \nconsole.log(__engine__.name);"
        );
        assertEquals(JavaScriptSource.ENGINE_QUICKJS, source.getEngineName());
    }

    @Test
    public void laterDirectiveDoesNotSilentlyChangeEngine() {
        StringScriptSource source = new StringScriptSource(
                "console.log('still Rhino');\n// @engine quickjs"
        );
        assertEquals(JavaScriptSource.ENGINE, source.getEngineName());
    }
}
