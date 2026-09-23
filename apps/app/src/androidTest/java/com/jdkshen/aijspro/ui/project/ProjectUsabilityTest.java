package com.jdkshen.aijspro.ui.project;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import java.io.File;
import java.lang.reflect.Method;
import static org.junit.Assert.*;

public class ProjectUsabilityTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private Activity launch() {
        Intent intent = new Intent(instrumentation.getTargetContext(), MiuixProjectConfigActivity.class)
            .putExtra("new_project", true)
            .putExtra("parent_directory", instrumentation.getTargetContext().getCacheDir().getPath())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return instrumentation.startActivitySync(intent);
    }
    private Object call(Activity activity, String name, Class<?>[] types, Object... args) {
        try { Method method = activity.getClass().getDeclaredMethod(name, types);
            method.setAccessible(true); return method.invoke(activity, args); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    private void set(Activity activity, String name, String value) {
        call(activity, name, new Class<?>[]{String.class}, value);
    }
    private boolean contains(AccessibilityNodeInfo node, String text) {
        if (node == null) return false;
        if (node.getText() != null && node.getText().toString().contains(text)) return true;
        for (int i = 0; i < node.getChildCount(); i++) if (contains(node.getChild(i), text)) return true;
        return false;
    }
    private void awaitText(String text) {
        long deadline = SystemClock.uptimeMillis() + 8000;
        while (SystemClock.uptimeMillis() < deadline) {
            if (contains(instrumentation.getUiAutomation().getRootInActiveWindow(), text)) return;
            SystemClock.sleep(100);
        }
        fail("Missing UI text: " + text);
    }
    @Test public void rejectsExistingAndRelativeLocationsWithoutWriting() {
        Activity activity = launch();
        try {
            instrumentation.runOnMainSync(() -> {
                set(activity, "setAppName", "QA");
                set(activity, "setPackageNameInput", "com.example.qa");
                set(activity, "setLocation", activity.getCacheDir().getPath());
                assertEquals(false, call(activity, "validate", new Class<?>[0]));
                assertTrue(String.valueOf(call(activity, "getErrorLocation", new Class<?>[0])).contains("已经存在"));
                set(activity, "setLocation", "relative-project");
                assertEquals(false, call(activity, "validate", new Class<?>[0]));
                assertTrue(String.valueOf(call(activity, "getErrorLocation", new Class<?>[0])).contains("绝对路径"));
            });
        } finally { instrumentation.runOnMainSync(activity::finish); }
    }
    @Test public void dirtySwitchProvidesSaveDiscardCancel() {
        Activity activity = launch();
        try {
            instrumentation.runOnMainSync(() -> {
                set(activity, "setAppName", "changed");
                call(activity, "requestSwitch", new Class<?>[]{String.class}, activity.getCacheDir().getPath());
            });
            awaitText("保存后切换"); awaitText("放弃修改并切换"); awaitText("取消");
        } finally { instrumentation.runOnMainSync(activity::finish); }
    }
    @Test public void unreadableFolderIsNotDisplayedAsEmpty() {
        Activity activity = launch();
        try {
            instrumentation.runOnMainSync(() -> {
                File missing = new File(activity.getCacheDir(), "missing-ui-qa-" + SystemClock.uptimeMillis());
                com.jdkshen.aijspro.ui.common.MiuixFileChooserDialog.showDirectory(activity,
                    missing.getPath(), path -> fail("Should not confirm unreadable folder"));
            });
            awaitText("读取失败");
        } finally { instrumentation.runOnMainSync(activity::finish); }
    }
    @Test public void busySaveDoesNotStartAnotherSubmission() {
        Activity activity = launch();
        File target = new File(activity.getCacheDir(), "duplicate-ui-qa-" + SystemClock.uptimeMillis());
        try {
            instrumentation.runOnMainSync(() -> {
                set(activity, "setAppName", "QA");
                set(activity, "setPackageNameInput", "com.example.qa");
                set(activity, "setLocation", target.getPath());
                call(activity, "setSaving", new Class<?>[]{boolean.class}, true);
                call(activity, "commit", new Class<?>[0]);
                call(activity, "commit", new Class<?>[0]);
                assertFalse(target.exists());
            });
        } finally { instrumentation.runOnMainSync(activity::finish); }
    }
    @Test public void deletionRemovesFileAndNestedFolderFromDisk() throws Exception {
        Activity activity = launch();
        File root = new File(activity.getCacheDir(), "delete-ui-qa-" + SystemClock.uptimeMillis());
        File file = new File(root, "file.js"), folder = new File(root, "folder"), child = new File(folder, "nested.js");
        try {
            assertTrue(folder.mkdirs()); assertTrue(file.createNewFile()); assertTrue(child.createNewFile());
            com.jdkshen.aijspro.ui.common.ScriptOperations operations = new com.jdkshen.aijspro.ui.common.ScriptOperations(
                activity, null, new com.jdkshen.aijspro.model.script.ScriptFile(root.getPath()));
            instrumentation.runOnMainSync(() -> operations.deleteWithoutConfirm(
                new com.jdkshen.aijspro.model.script.ScriptFile(file.getPath())));
            long deadline = SystemClock.uptimeMillis() + 5000;
            while (file.exists() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50);
            assertFalse(file.exists());
            instrumentation.runOnMainSync(() -> operations.deleteWithoutConfirm(
                new com.jdkshen.aijspro.model.script.ScriptFile(folder.getPath())));
            deadline = SystemClock.uptimeMillis() + 5000;
            while (folder.exists() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50);
            assertFalse(folder.exists()); assertFalse(child.exists());
        } finally {
            child.delete(); folder.delete(); file.delete(); root.delete();
            instrumentation.runOnMainSync(activity::finish);
        }
    }

}
