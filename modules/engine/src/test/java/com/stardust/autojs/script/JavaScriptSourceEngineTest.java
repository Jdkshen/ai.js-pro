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
}
