package com.stardust.autojs.script;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class JavaScriptSourceEngineTest {

    @Test
    public void nodeFileExtensionRoutesOriginalUiEntryWithoutEditingItsHeader() {
        JavaScriptFileSource source = new JavaScriptFileSource(new java.io.File("main.node.js")) {
            @Override public String getScript() { return "\"ui\";\nclass WebActivity {}"; }
        };
        assertEquals(JavaScriptSource.ENGINE_NODE24, source.getEngineName());
        source.setPreferredEngine("quickjs");
        assertEquals(JavaScriptSource.ENGINE_QUICKJS, source.getEngineName());
        source.setForcedEngine("rhino");
        assertEquals(JavaScriptSource.ENGINE_RHINO, source.getEngineName());
    }

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
    public void firstNonEmptyLineCanExplicitlySelectRhino() {
        StringScriptSource source = new StringScriptSource(
                "// @engine rhino\nconsole.log(__engine__.name);"
        );
        source.setPreferredEngine("quickjs");
        assertEquals(JavaScriptSource.ENGINE_RHINO, source.getEngineName());
    }

    @Test
    public void projectEngineAppliesWhenScriptHasNoDirective() {
        StringScriptSource source = new StringScriptSource("console.log('project default');");
        source.setPreferredEngine("quickjs");
        assertEquals(JavaScriptSource.ENGINE_QUICKJS, source.getEngineName());
    }

    @Test
    public void nodejsAliasWorksForProjectAndForcedEngine() {
        StringScriptSource source = new StringScriptSource("console.log(process.version);");
        source.setPreferredEngine(" NodeJS ");
        assertEquals(JavaScriptSource.ENGINE_NODE24, source.getEngineName());
        source.setForcedEngine("nodejs");
        assertEquals(JavaScriptSource.ENGINE_NODE24, source.getEngineName());
    }

    @Test
    public void nodejsCommentDirectiveSelectsNode() {
        StringScriptSource source = new StringScriptSource("// @engine nodejs\nconsole.log(process.version);");
        assertEquals(JavaScriptSource.ENGINE_NODE24, source.getEngineName());
    }

    @Test
    public void proNodeStringDirectiveSelectsNode24() {
        StringScriptSource source = new StringScriptSource(
                "\"nodejs\";\nconsole.log(process.version);"
        );
        assertEquals(JavaScriptSource.ENGINE_NODE24, source.getEngineName());
    }

    @Test
    public void proNodeUiCombinedStringDirectiveSelectsNode24() {
        StringScriptSource source = new StringScriptSource(
                "\"nodejs ui\";\nrequire('fs');"
        );
        assertEquals(JavaScriptSource.ENGINE_NODE24, source.getEngineName());
    }

    @Test
    public void proRhinoStringDirectiveSelectsRhino() {
        StringScriptSource source = new StringScriptSource(
                "\"rhino\";\nconsole.log(Packages);"
        );
        source.setPreferredEngine("quickjs");
        assertEquals(JavaScriptSource.ENGINE_RHINO, source.getEngineName());
    }

    @Test
    public void proQuickJsStringDirectiveSelectsQuickJs() {
        StringScriptSource source = new StringScriptSource(
                "\"quickjs\";\nconsole.log(Java);"
        );
        assertEquals(JavaScriptSource.ENGINE_QUICKJS, source.getEngineName());
    }

    @Test
    public void uiModeLineBeforeEngineMarkerKeepsUiMode() {
        StringScriptSource source = new StringScriptSource(
                "\"ui\";\n\"rhino\";\nui.layout(\"console\");"
        );
        assertEquals(JavaScriptSource.ENGINE_RHINO, source.getEngineName());
    }

    @Test
    public void laterDirectiveDoesNotSilentlyChangeEngine() {
        StringScriptSource source = new StringScriptSource(
                "console.log('still Rhino');\n// @engine quickjs"
        );
        assertEquals(JavaScriptSource.ENGINE, source.getEngineName());
    }
    @Test
    public void sameLineUiAndNodeDirectivesSelectNodeFromFileEntry() {
        StringScriptSource source = new StringScriptSource("\"ui\"; \"nodejs\"; const ui = require('ui');");
        assertEquals(JavaScriptSource.ENGINE_NODE24, source.getEngineName());
        assertEquals(JavaScriptSource.EXECUTION_MODE_UI, source.getExecutionMode());
    }

    @Test
    public void engineFirstStillReadsFollowingUiAndAutoModes() {
        StringScriptSource source = new StringScriptSource("'nodejs'; 'ui'; 'auto'; console.log(1);");
        assertEquals(JavaScriptSource.ENGINE_NODE24, source.getEngineName());
        assertEquals(JavaScriptSource.EXECUTION_MODE_UI | JavaScriptSource.EXECUTION_MODE_AUTO,
                source.getExecutionMode());
    }

    @Test
    public void commentsBomAndStrictPrologueCanPrecedeEngineAndMode() {
        StringScriptSource source = new StringScriptSource(
                "\ufeff/* Widget example */\n'use strict'; // header\n'ui'; /* engine */ 'nodejs'; class Widget {}");
        assertEquals(JavaScriptSource.ENGINE_NODE24, source.getEngineName());
        assertEquals(JavaScriptSource.EXECUTION_MODE_UI, source.getExecutionMode());
    }

    @Test
    public void threeEngineDirectivesAllowSameLineCode() {
        for (String[] entry : new String[][]{{"nodejs", JavaScriptSource.ENGINE_NODE24},
                {"quickjs", JavaScriptSource.ENGINE_QUICKJS}, {"rhino", JavaScriptSource.ENGINE_RHINO}}) {
            StringScriptSource source = new StringScriptSource("'ui'; '" + entry[0] + "'; console.log(1);");
            assertEquals(entry[1], source.getEngineName());
            assertEquals(JavaScriptSource.EXECUTION_MODE_UI, source.getExecutionMode());
        }
    }

    @Test
    public void executableCodeAndStringExpressionsNeverSelectLaterEngine() {
        for (String code : new String[]{"'ui'; run(); 'nodejs';", "'nodejs' + value;",
                "'nodejs'.toUpperCase();", "const text = 'nodejs';", "/* 'nodejs'; */ run();"}) {
            StringScriptSource source = new StringScriptSource(code);
            assertEquals(code, JavaScriptSource.ENGINE_RHINO, source.getEngineName());
        }
    }

    @Test
    public void explicitForcedEngineStillWinsOverInlinePrologue() {
        StringScriptSource source = new StringScriptSource("'ui'; 'nodejs'; class Widget {}");
        source.setForcedEngine("quickjs");
        assertEquals(JavaScriptSource.ENGINE_QUICKJS, source.getEngineName());
    }

}
