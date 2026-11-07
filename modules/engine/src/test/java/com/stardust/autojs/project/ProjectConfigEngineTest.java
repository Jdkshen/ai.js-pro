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
    }    /**
     * 打包版日志桥开关（project.json -> launchConfig.logBridge）。
     * 默认必须是 false：没写这个字段的老工程/老产物绝不打开调试端口。
     */
    @Test public void logBridgeDefaultsOffAndRoundTripsThroughProjectJson() {
        Gson gson = new Gson();
        ProjectConfig legacy = gson.fromJson("{\"launchConfig\":{\"hideLogs\":false}}", ProjectConfig.class);
        assertEquals(false, legacy.getLaunchConfig().shouldLogBridge());
        // 没有 launchConfig 的工程：getLaunchConfig() 会懒建一个，但开关必须仍是 false。
        assertEquals(false, gson.fromJson("{}", ProjectConfig.class).getLaunchConfig().shouldLogBridge());

        ProjectConfig enabled = gson.fromJson(
                "{\"launchConfig\":{\"logBridge\":true}}", ProjectConfig.class);
        assertEquals(true, enabled.getLaunchConfig().shouldLogBridge());

        enabled.getLaunchConfig().setLogBridge(false);
        assertEquals(false, gson.fromJson(gson.toJson(enabled), ProjectConfig.class)
                .getLaunchConfig().shouldLogBridge());
    }

    /** 打开后序列化必须带上 logBridge=true，打包器读的就是这份 JSON。 */
    @Test public void logBridgeSerializesWhenEnabled() {
        ProjectConfig config = new ProjectConfig();
        config.getLaunchConfig().setLogBridge(true);
        String json = new Gson().toJson(config);
        assertEquals(true, json.contains("\"logBridge\":true"));
    }
}
