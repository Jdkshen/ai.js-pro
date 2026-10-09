package com.jdkshen.aijspro.ui.project;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.platform.app.InstrumentationRegistry;
import com.jdkshen.aijspro.ui.common.MiuixFileChooserDialog;
import com.jdkshen.aijspro.theme.AijsMiuixThemeState;
import org.junit.Test;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class UnifiedFileChooserTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private Activity launch(File root) {
        return instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(), MiuixProjectConfigActivity.class)
                .putExtra("new_project", true).putExtra("parent_directory", root.getPath()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
    private AccessibilityNodeInfo find(AccessibilityNodeInfo node, String text) {
        if (node == null) return null;
        if (node.getText() != null && node.getText().toString().contains(text)) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo result = find(node.getChild(i), text);
            if (result != null) return result;
        }
        return null;
    }
    private AccessibilityNodeInfo await(String text) {
        long end = SystemClock.uptimeMillis() + 8000;
        while (SystemClock.uptimeMillis() < end) {
            AccessibilityNodeInfo node = find(instrumentation.getUiAutomation().getRootInActiveWindow(), text);
            if (node != null) return node;
            SystemClock.sleep(100);
        }
        try {
            Bitmap bitmap = instrumentation.getUiAutomation().takeScreenshot();
            try (FileOutputStream out = new FileOutputStream(new File(instrumentation.getTargetContext().getCacheDir(), "chooser-failure.png"))) {
                if (bitmap != null) bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
        } catch (Exception ignored) { }
        dump(instrumentation.getUiAutomation().getRootInActiveWindow());
        throw new AssertionError("Missing chooser text: " + text);
    }
    private void dump(AccessibilityNodeInfo node) {
        if (node == null) return;
        System.out.println("CHOOSER_NODE " + node.getText() + " / " + node.getContentDescription());
        for (int i = 0; i < node.getChildCount(); i++) dump(node.getChild(i));
    }
    private void click(String text) {
        AccessibilityNodeInfo node = findClickText(instrumentation.getUiAutomation().getRootInActiveWindow(), text);
        while (node != null && !node.isClickable()) node = node.getParent();
        assertNotNull(node);
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK));
    }
    private AccessibilityNodeInfo findClickText(AccessibilityNodeInfo node, String text) {
        if (node == null) return null;
        String value = node.getText() == null ? "" : node.getText().toString();
        if (value.equals(text) || value.startsWith(text + "\n")) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findClickText(node.getChild(i), text);
            if (found != null) return found;
        }
        return null;
    }
    private void screenshot(Activity activity, String name) throws Exception {
        instrumentation.waitForIdleSync();
        SystemClock.sleep(600); // Allow the window enter/theme animation to finish before visual QA.
        Bitmap bitmap = instrumentation.getUiAutomation().takeScreenshot();
        try (FileOutputStream out = new FileOutputStream(new File(activity.getCacheDir(), name))) {
            assertNotNull(bitmap); bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }
    @Test public void importStartsAtCurrentDirectoryAndSelectsMarkdownInBothThemes() throws Exception {
        File root = new File(instrumentation.getTargetContext().getCacheDir(), "chooser-qa-" + SystemClock.uptimeMillis());
        File folder = new File(root, "项目文档"), markdown = new File(folder, "说明.MD");
        assertTrue(folder.mkdirs()); assertTrue(markdown.createNewFile());
        Activity activity = launch(root);
        AtomicReference<String> selected = new AtomicReference<>();
        AijsMiuixThemeState theme = AijsMiuixThemeState.INSTANCE;
        boolean dark = theme.getDark().getValue(), follow = theme.getFollowSystem().getValue();
        try {
            instrumentation.runOnMainSync(() -> {
                theme.getFollowSystem().setValue(false); theme.getDark().setValue(false);
                assertTrue(MiuixFileChooserDialog.showImport(activity, root.getPath(), folder.getPath(), selected::set));
            });
            await("说明.MD"); await("搜索当前文件夹");
            screenshot(activity, "unified-import-light.png");
            instrumentation.runOnMainSync(() -> theme.getDark().setValue(true));
            SystemClock.sleep(300);
            screenshot(activity, "unified-import-dark.png");
            click("说明.MD"); await("已选择：说明.MD"); click("导入");
            instrumentation.waitForIdleSync();
            assertEquals(markdown.getPath(), selected.get());
        } finally {
            instrumentation.runOnMainSync(() -> { theme.getDark().setValue(dark); theme.getFollowSystem().setValue(follow); activity.finish(); });
            markdown.delete(); folder.delete(); root.delete();
        }
    }
    @Test public void projectChooserExplainsInvalidDirectoryThenOpensValidProject() throws Exception {
        File root = new File(instrumentation.getTargetContext().getCacheDir(), "project-chooser-qa-" + SystemClock.uptimeMillis());
        File project = new File(root, "测试项目"), config = new File(project, "project.json");
        assertTrue(project.mkdirs());
        Files.write(config.toPath(), "{\"name\":\"测试项目\",\"main\":\"main.js\",\"versionCode\":1,\"versionName\":\"1.0\",\"packageName\":\"test.chooser.project\"}".getBytes(StandardCharsets.UTF_8));
        assertNotNull(com.stardust.autojs.project.ProjectConfig.fromProjectDir(project.getPath()));
        Activity activity = launch(root);
        AtomicReference<String> selected = new AtomicReference<>();
        try {
            instrumentation.runOnMainSync(() -> MiuixFileChooserDialog.showProject(activity, root.getPath(), root.getPath(), selected::set));
            await("没有有效的 project.json");
            assertNull(selected.get());
            click("测试项目"); await("将打开当前目录的项目配置");
            screenshot(activity, "unified-project.png");
            click("打开此项目"); instrumentation.waitForIdleSync();
            assertEquals(project.getPath(), selected.get());
        } finally { instrumentation.runOnMainSync(activity::finish); config.delete(); project.delete(); root.delete(); }
    }
}
