package com.jdkshen.aijspro.flutterpoc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** task-115：模板来源的**桥侧钉子**（只读字段 + 只认打包侧常量、认不出返回空）。 */
public class TemplateSourceSurfacePinTest {

    private static String read(String relative) throws Exception {
        Path path = Paths.get(relative);
        if (!Files.exists(path)) path = Paths.get("apps/app/" + relative);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    @Test
    public void statusCarriesTemplateSourceAndLabel() throws Exception {
        String source = read("src/flutterpoc/java/com/jdkshen/aijspro/flutterpoc/PocPackaging.java");
        assertTrue(source.contains("value.put(\"templateSource\", templateSource);"));
        assertTrue(source.contains("value.put(\"templateLabel\", templateLabel());"));
        assertTrue("来源要来自打包侧同一份真值", source.contains("job.templateSource(templateStatus.source);"));
    }

    @Test
    public void labelUsesThePackagingConstantsOnly() throws Exception {
        String source = read("src/flutterpoc/java/com/jdkshen/aijspro/flutterpoc/PocPackaging.java");
        for (String constant : new String[]{"SOURCE_BUNDLED", "SOURCE_HOST_CACHE", "SOURCE_SDCARD"}) {
            assertTrue("必须按打包侧常量判：" + constant, source.contains(constant));
        }
        assertTrue("认不出要返回空串（页面不显示，不猜）", source.contains("return \"\";"));
        assertTrue("不许出现「必须装插件」这类没证据的文案", !source.contains("必须装插件"));
    }

    @Test
    public void inspectorConstantsStillExist() throws Exception {
        String source = read("src/main/java/com/jdkshen/aijspro/build/ApkTemplateInspector.java");
        for (String constant : new String[]{"SOURCE_SDCARD", "SOURCE_HOST_CACHE", "SOURCE_BUNDLED", "SOURCE_NONE"}) {
            assertTrue("常量不能被改名：" + constant, source.contains(constant));
        }
    }
}
