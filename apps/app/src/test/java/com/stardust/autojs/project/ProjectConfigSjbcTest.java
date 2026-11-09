package com.stardust.autojs.project;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.GsonBuilder;

import org.junit.Test;

/**
 * task-81：SJBC 容器字段（sjbc.assetsDir / sjbc.certSha）的读写回归 + 「未用 SJBC 的工程 toJson 逐字不变」。
 *
 * <p>SJBC 的 .sjbc 自带 AES-256-GCM + HKDF ⇒ 用了 SJBC 的工程推荐 encryptLevel=0（不加密）；
 * 本类只钉住 project.json 字段的**读兼容**与**写路径不变**（目录收集/manifest/certSha 实算归打包侧）。</p>
 */
public class ProjectConfigSjbcTest {

    private static final String BASE = "\"name\":\"sjbc-proj\",\"versionName\":\"1.0\",\"versionCode\":7,"
            + "\"packageName\":\"com.example.sjbc\",\"main\":\"main.js\"";

    private static final String WITH_SJBC = "{" + BASE + ","
            + "\"encryptLevel\":0,"
            + "\"sjbc\":{\"assetsDir\":\"assets/sjbc\",\"certSha\":\"AB:CD:EF:01:23:45\"}}";

    private static final String WITHOUT_SJBC = "{" + BASE + ",\"encryptLevel\":0}";

    @Test
    public void sjbcFieldsRoundTripAndCertShaIsNormalized() {
        ProjectConfig config = ProjectConfig.fromJson(WITH_SJBC);
        assertNotNull(config);
        assertTrue("必须认得 sjbc", config.usesSjbc());
        assertEquals("assets/sjbc", config.getSjbc().getAssetsDir());
        assertEquals("certSha 规范化：去冒号 + 小写", "abcdef012345", config.getSjbc().getCertSha());
        String written = config.toJson();
        assertTrue("写了 SJBC 的工程必须写回 sjbc 键", written.contains("\"sjbc\""));
        assertTrue(written.contains("\"assetsDir\": \"assets/sjbc\""));
        assertTrue(written.contains("\"certSha\": \"abcdef012345\""));
        assertEquals("加密等级仍是工程自己的选择（推荐 0）", 0, config.getEncryptLevel());
    }

    @Test
    public void sjbcDefaultsWhenOnlyCertShaIsGiven() {
        ProjectConfig config = ProjectConfig.fromJson("{" + BASE
                + ",\"sjbc\":{\"certSha\":\"00112233445566778899aabbccddeeff\"}}");
        assertNotNull(config);
        assertNotNull(config.getSjbc());
        assertEquals("只给 certSha 时目录用默认值", ProjectConfig.SjbcConfig.DEFAULT_ASSETS_DIR,
                config.getSjbc().getAssetsDir());
        assertEquals("00112233445566778899aabbccddeeff", config.getSjbc().getCertSha());
    }

    /** 写路径保护：没用 SJBC 的工程，toJson 与「纯 Gson」逐字一致，且**不出现** sjbc 键。 */
    @Test
    public void projectWithoutSjbcKeepsToJsonByteIdentical() {
        ProjectConfig config = ProjectConfig.fromJson(WITHOUT_SJBC);
        assertNotNull(config);
        assertFalse(config.usesSjbc());
        String written = config.toJson();
        String plain = new GsonBuilder().setPrettyPrinting().create().toJson(config);
        assertEquals("toJson 必须与纯 Gson 逐字一致", plain, written);
        assertFalse("未用 SJBC 的工程不能冒出 sjbc 键（null 字段 Gson 不输出）", written.contains("\"sjbc\""));
    }

    @Test
    public void illegalSjbcValuesWarnWithoutBreakingTheRest() {
        ProjectConfig wrongType = ProjectConfig.fromJson("{" + BASE + ",\"sjbc\":123}");
        assertNotNull("sjbc 类型不对也不能让整份解析失败", wrongType);
        assertFalse(wrongType.usesSjbc());
        assertTrue(wrongType.getWarnings().toString().contains("sjbc"));

        ProjectConfig brokenObject = ProjectConfig.fromJson("{" + BASE + ",\"sjbc\":{\"assetsDir\":{}}}");
        assertNotNull(brokenObject);
        assertFalse("解析不出就整体忽略，不半信半疑", brokenObject.usesSjbc());
        assertTrue(brokenObject.getWarnings().toString().contains("sjbc"));

        ProjectConfig emptyDir = ProjectConfig.fromJson("{" + BASE + ",\"sjbc\":{\"assetsDir\":\"  \"}}");
        assertNotNull(emptyDir);
        assertTrue(emptyDir.usesSjbc());
        assertEquals(ProjectConfig.SjbcConfig.DEFAULT_ASSETS_DIR, emptyDir.getSjbc().getAssetsDir());
        assertTrue(emptyDir.getWarnings().toString().contains("assetsDir"));
        assertNull("空 certSha 视为没给（不比较）", emptyDir.getSjbc().getCertSha());
    }
}
