package com.stardust.autojs.project;

import com.google.gson.Gson;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class ProjectConfigEngineTest {
    @Test public void missingEnginePreservesLegacyRhinoSelection() {
        assertNull(new ProjectConfig().getEngine("main.js"));
    }

    @Test public void scriptEngineOverridesProjectEngine() {
        ProjectConfig config = new ProjectConfig();
        config.setEngine("quickjs");
        ScriptConfig legacy = new ScriptConfig();
        legacy.setEngine("rhino");
        config.getScriptConfigs().put("legacy.js", legacy);
        assertEquals("quickjs", config.getEngine("main.js"));
        assertEquals("rhino", config.getEngine("legacy.js"));
    }

    @Test public void nodeProjectTypeSelectsNodeWhenEngineIsAbsent() {
        ProjectConfig config = new Gson().fromJson("{\"type\":\"node\"}", ProjectConfig.class);
        assertEquals("node", config.getEngine());
        assertEquals("node", config.getEngine("main.js"));
    }

    @Test public void explicitEngineStillOverridesProjectType() {
        ProjectConfig config = new Gson().fromJson("{\"type\":\"node\",\"engine\":\"quickjs\"}", ProjectConfig.class);
        assertEquals("quickjs", config.getEngine("main.js"));
        assertNull(new Gson().fromJson("{}", ProjectConfig.class).getEngine());
    }
}
