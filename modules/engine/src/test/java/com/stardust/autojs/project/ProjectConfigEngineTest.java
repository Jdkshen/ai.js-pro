package com.stardust.autojs.project;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class ProjectConfigEngineTest {

    @Test
    public void missingEnginePreservesLegacyRhinoSelection() {
        ProjectConfig config = new ProjectConfig();
        assertNull(config.getEngine("main.js"));
    }

    @Test
    public void scriptEngineOverridesProjectEngine() {
        ProjectConfig config = new ProjectConfig();
        config.setEngine("quickjs");
        ScriptConfig legacy = new ScriptConfig();
        legacy.setEngine("rhino");
        config.getScriptConfigs().put("legacy.js", legacy);

        assertEquals("quickjs", config.getEngine("main.js"));
        assertEquals("rhino", config.getEngine("legacy.js"));
    }
}
