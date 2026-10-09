package com.jdkshen.aijspro.packaging;

import android.content.Context;

import androidx.test.platform.app.InstrumentationRegistry;

import com.jdkshen.aijspro.build.ApkBuilderPluginHelper;

import org.junit.Assume;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 打包模板不再随主程序分发，所以「没装打包插件」必须给出可操作的提示，
 * 而不是让 ApkBuilder 抛一个看不懂的异常。
 *
 * <p>本机已经装了插件（或手动放了模板）时用例自动跳过：它验证的是缺失路径。
 */
public class ApkBuilderTemplateAvailabilityTest {

    @Test
    public void missingTemplateExplainsHowToInstallIt() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Assume.assumeFalse("本机已有打包模板，跳过缺失提示用例",
                ApkBuilderPluginHelper.isTemplateAvailable(context));

        assertFalse(ApkBuilderPluginHelper.templateStatus(context).available);
        try {
            ApkBuilderPluginHelper.openTemplateApk(context);
            fail("模板不在位时 openTemplateApk 必须抛出可读异常");
        } catch (IOException expected) {
            String message = expected.getMessage();
            assertTrue(message, message != null && message.contains("APK 打包插件"));
            assertTrue(message, message.contains(ApkBuilderPluginHelper.manualTemplateFile().getPath()));
        }
    }
}
