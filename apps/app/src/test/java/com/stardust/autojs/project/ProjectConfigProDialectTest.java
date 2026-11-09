package com.stardust.autojs.project;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.GsonBuilder;

import org.junit.Test;

/**
 * A1-4（task-70）：project.json **读兼容** Pro 方言的回归测试。
 *
 * <p>夹具来源说明（重要）：</p>
 * <ul>
 *   <li>真样例片段来自 output/hz_ybzj_src/hz_ybzj/project.json（Pro 打包产物，scripts 是**空的**）；</li>
 *   <li>「非空**字符串值** scripts」是**合成夹具**（依据 Pro 的 Map&lt;String,String&gt; schema）——
 *       仓库与 device4 的真 Pro 工程 scripts 都是空的（.artifacts/AutoJsPro源码树-2026-11-09 下 0 个 project.json）；</li>
 *   <li>JVM 夹具里**没有**放「对象值 scripts」：ScriptConfig 没有无参构造，JDK17 的 Gson 会走 Unsafe 失败
 *       （Android 上正常）。对象值分支的代码路径本轮未改（仍 GSON.fromJson(value, ScriptConfig.class)），
 *       由类型级编译 + 代码审阅保证，真机由打包页读工程覆盖。</li>
 * </ul>
 */
public class ProjectConfigProDialectTest {

    private static final String REAL_SAMPLE = "{"
            + "\"name\":\"华仔部落_佣兵战纪\",\"versionName\":\"1.3.2\",\"versionCode\":1302,"
            + "\"packageName\":\"com.zjh336.cn.autojs.ybzj\",\"main\":\"main.js\","
            + "\"abis\":[\"arm64-v8a\",\"armeabi-v7a\"],"
            + "\"features\":{\"activityIntentTasks\":false,\"nodejs\":\"disabled\"},"
            + "\"launchConfig\":{\"displaySplash\":true,\"hideLogs\":false,\"splashText\":\"欢迎\",\"stableMode\":false},"
            + "\"optimization\":{\"removeOpenCv\":false},"
            + "\"plugins\":{\"org.autojs.autojspro.plugin.mlkit.ocr\":\"1.1\"},"
            + "\"signingConfig\":{\"alias\":\"zjh336\"},"
            + "\"scripts\":{},\"useFeatures\":[]}";

    /** 合成夹具：strings 值（Pro 钩子）+ 非法值；无对象值（见类注释）。 */
    private static final String HOOKS_SAMPLE = "{"
            + "\"name\":\"pro-ish\",\"versionName\":\"1.0\",\"versionCode\":1,"
            + "\"packageName\":\"com.example.proish\",\"main\":\"main.js\","
            + "\"scripts\":{\"onStart\":\"handler.js\",\"bad\":123}}";

    private static final String TOP_LEVEL_SPLASH_SAMPLE = "{"
            + "\"name\":\"old\",\"versionName\":\"1.0\",\"versionCode\":1,"
            + "\"packageName\":\"com.example.old\",\"main\":\"main.js\",\"displaySplash\":false}";

    private static final String PRECEDENCE_SAMPLE = "{"
            + "\"name\":\"both\",\"versionName\":\"1.0\",\"versionCode\":1,"
            + "\"packageName\":\"com.example.both\",\"main\":\"main.js\",\"displaySplash\":true,"
            + "\"launchConfig\":{\"displaySplash\":false}}";

    @Test
    public void proSampleParsesWithWarningsAndFeatures() {
        ProjectConfig config = ProjectConfig.fromJson(REAL_SAMPLE);
        assertNotNull("Pro 工程必须能解析", config);
        assertEquals("features.nodejs 要映射到我们的语义", "disabled", config.getNodeJsMode());
        assertTrue("launchConfig.displaySplash=true 必须映射到 showSplash",
                config.getLaunchConfig().shouldShowSplash());
        String warnings = config.getWarnings().toString();
        assertTrue("abis 必须进 warnings（有意不读）", warnings.contains("abis"));
        assertTrue("optimization 必须进 warnings", warnings.contains("optimization"));
        assertTrue("plugins 必须进 warnings", warnings.contains("plugins"));
        assertTrue("signingConfig 必须进 warnings", warnings.contains("signingConfig"));
        assertTrue("空 scripts 不该有钩子", config.getScriptHooks().isEmpty());
    }

    @Test
    public void proHooksAreKeptAndIllegalEntriesWarnWithoutLosingTheRest() {
        ProjectConfig config = ProjectConfig.fromJson(HOOKS_SAMPLE);
        assertNotNull("字符串值/非法值都不能让整份解析失败", config);
        assertEquals("字符串值必须进 scriptsHooks（不静默丢）", "handler.js", config.getScriptHooks().get("onStart"));
        assertTrue("非法类型要记 warning", config.getWarnings().toString().contains("scripts.bad"));
        assertEquals("其余键照常解析", "pro-ish", config.getName());
        assertEquals(1, config.getScriptHooks().size());
    }

    @Test
    public void displaySplashIsDoubleRecognizedWithDocumentedPrecedence() {
        ProjectConfig old = ProjectConfig.fromJson(TOP_LEVEL_SPLASH_SAMPLE);
        assertNotNull(old);
        assertFalse("顶层旧键 displaySplash 也必须认", old.getLaunchConfig().shouldShowSplash());

        ProjectConfig both = ProjectConfig.fromJson(PRECEDENCE_SAMPLE);
        assertNotNull(both);
        assertFalse("launchConfig.displaySplash 优先于顶层 displaySplash",
                both.getLaunchConfig().shouldShowSplash());

        ProjectConfig none = ProjectConfig.fromJson("{"
                + "\"name\":\"n\",\"versionName\":\"1\",\"versionCode\":1,"
                + "\"packageName\":\"p\",\"main\":\"m.js\"}");
        assertNotNull(none);
        assertNull("没有 features 时 nodejs 模式为 null", none.getNodeJsMode());
    }

    /** 写路径保护：容错字段绝不能进 toJson，且输出与「纯 Gson」逐字一致。 */
    @Test
    public void writePathIsUnaffectedByTheCompatibilityLayer() {
        ProjectConfig config = ProjectConfig.fromJson(REAL_SAMPLE);
        assertNotNull(config);
        String written = config.toJson();
        String plain = new GsonBuilder().setPrettyPrinting().create().toJson(config);
        assertEquals("toJson 必须与纯 Gson 逐字一致（无额外键/格式变化）", plain, written);
        assertFalse(written.contains("scriptsHooks"));
        assertFalse(written.contains("proFeatures"));
        assertFalse(written.contains("warnings"));
        assertFalse("Pro 键不该被写回（避免第二份真值）", written.contains("\"abis\""));
        assertTrue("我们的字段仍在写路径里", written.contains("\"useFeatures\""));
        assertTrue(written.contains("\"scripts\""));
    }

    @Test
    public void unknownKeysAndIllegalTypesNeverBreakParsing() {
        ProjectConfig config = ProjectConfig.fromJson("{"
                + "\"name\":\"x\",\"versionName\":\"1\",\"versionCode\":1,\"packageName\":\"p\",\"main\":\"m.js\","
                + "\"features\":[\"not-an-object\"],\"scripts\":123,\"totallyUnknown\":{\"a\":1}}");
        assertNotNull("未知键/非法类型也不能让整份解析失败", config);
        assertTrue(config.getWarnings().toString().contains("features"));
        assertTrue(config.getWarnings().toString().contains("scripts"));
        assertNull(config.getNodeJsMode());
        assertTrue(config.getScriptHooks().isEmpty());
    }
}
