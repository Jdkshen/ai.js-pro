package com.jdkshen.aijspro.ui.editor;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import com.jdkshen.aijspro.external.open.EditIntentActivity;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class ExternalEditorTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context context = instrumentation.getTargetContext();
    private Object call(ProCodeEditorActivity activity, String name, Class<?>[] types, Object... args) {
        try {
            Method method = activity.getClass().getDeclaredMethod(name, types);
            method.setAccessible(true);
            return method.invoke(activity, args);
        } catch (Exception error) { throw new AssertionError(error); }
    }
    private ProCodeEditorActivity open(Uri uri) {
        return open(new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "text/markdown"));
    }
    private ProCodeEditorActivity open(Intent intent) {
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(ProCodeEditorActivity.class.getName(), null, false);
        try {
            android.content.pm.ResolveInfo entry = context.getPackageManager().queryIntentActivities(
                    intent.setPackage(context.getPackageName()), android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
                    .stream().filter(i -> i.activityInfo.name.equals(EditIntentActivity.class.getName())).findFirst().orElse(null);
            assertNotNull("Open entry must be discoverable before selecting it", entry);
            context.startActivity(intent.setComponent(new android.content.ComponentName(entry.activityInfo.packageName, entry.activityInfo.name))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
            ProCodeEditorActivity activity = (ProCodeEditorActivity) monitor.waitForActivityWithTimeout(10000);
            assertNotNull("External URI must use the unified editor", activity);
            long end = SystemClock.uptimeMillis() + 10000;
            while (SystemClock.uptimeMillis() < end) {
                boolean[] ready = {false};
                instrumentation.runOnMainSync(() -> ready[0] = call(activity, "activeEditor", new Class<?>[0]) != null);
                if (ready[0]) { awaitSnapshot(activity); return activity; }
                SystemClock.sleep(100);
            }
            fail("External document did not load");
            return activity;
        } finally { instrumentation.removeMonitor(monitor); }
    }
    private String read(Uri uri) throws Exception {
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            return new String(org.apache.commons.io.IOUtils.toByteArray(input), StandardCharsets.UTF_8);
        }
    }
    private void awaitSnapshot(ProCodeEditorActivity activity) {
        CountDownLatch complete = new CountDownLatch(1);
        instrumentation.runOnMainSync(() -> ((AceCodeEditor) call(activity, "activeEditor", new Class<?>[0]))
                .withSnapshot(complete::countDown));
        try { assertTrue("Snapshot timed out", complete.await(15, TimeUnit.SECONDS)); }
        catch (InterruptedException error) { throw new AssertionError(error); }
        instrumentation.waitForIdleSync();
    }
    @Test public void externalMarkdownOpensInUnifiedEditorAndSavesTruncatedToOriginal() throws Exception {
        Uri uri = EditorDocumentTestProvider.WRITABLE;
        ExternalEditorDocument.write(context.getContentResolver(), uri, "# original long text with suffix");
        ProCodeEditorActivity activity = open(uri);
        try {
            instrumentation.runOnMainSync(() -> {
                AceCodeEditor editor = (AceCodeEditor) call(activity, "activeEditor", new Class<?>[0]);
                assertEquals("# original long text with suffix", editor.getText());
                editor.setText("# 新内容");
                call(activity, "saveActive", new Class<?>[]{boolean.class}, false);
            });
            awaitSnapshot(activity);
            instrumentation.runOnMainSync(() -> assertEquals(false, call(activity, "hasAnyUnsavedChanges", new Class<?>[0])));
            assertEquals("# 新内容", read(uri));
        } finally { instrumentation.runOnMainSync(activity::finish); }
    }
    @Test public void qqStyleShareStreamOpensInUnifiedEditor() throws Exception {
        ExternalEditorDocument.write(context.getContentResolver(), EditorDocumentTestProvider.WRITABLE, "# QQ attachment");
        ProCodeEditorActivity activity = open(new Intent(Intent.ACTION_SEND).setType("application/octet-stream")
                .putExtra(Intent.EXTRA_STREAM, EditorDocumentTestProvider.READ_ONLY));
        try {
            instrumentation.runOnMainSync(() -> assertEquals("# QQ attachment",
                    ((AceCodeEditor) call(activity, "activeEditor", new Class<?>[0])).getText()));
        } finally { instrumentation.runOnMainSync(activity::finish); }
    }
    @Test public void rejectedExternalSaveKeepsDirtyTextAndOriginal() throws Exception {
        ExternalEditorDocument.write(context.getContentResolver(), EditorDocumentTestProvider.WRITABLE, "original");
        ProCodeEditorActivity activity = open(EditorDocumentTestProvider.READ_ONLY);
        try {
            instrumentation.runOnMainSync(() -> {
                AceCodeEditor editor = (AceCodeEditor) call(activity, "activeEditor", new Class<?>[0]);
                editor.setText("unsaved changes");
                call(activity, "saveActive", new Class<?>[]{boolean.class}, false);
            });
            awaitSnapshot(activity);
            instrumentation.runOnMainSync(() -> {
                AceCodeEditor editor = (AceCodeEditor) call(activity, "activeEditor", new Class<?>[0]);
                assertEquals(true, call(activity, "hasAnyUnsavedChanges", new Class<?>[0]));
                assertEquals("unsaved changes", editor.getText());
            });
            assertEquals("original", read(EditorDocumentTestProvider.READ_ONLY));
        } finally { instrumentation.runOnMainSync(activity::finish); }
    }
    @Test public void savesExternalDraftStateWithoutWritingToReadOnlySource() throws Exception {
        ExternalEditorDocument.write(context.getContentResolver(), EditorDocumentTestProvider.WRITABLE, "original");
        ProCodeEditorActivity activity = open(EditorDocumentTestProvider.READ_ONLY);
        android.os.Bundle state = new android.os.Bundle();
        try {
            instrumentation.runOnMainSync(() -> {
                AceCodeEditor editor = (AceCodeEditor) call(activity, "activeEditor", new Class<?>[0]);
                editor.setText("draft after recreation");
            });
            awaitSnapshot(activity);
            instrumentation.runOnMainSync(() -> {
                call(activity, "onSaveInstanceState", new Class<?>[]{android.os.Bundle.class}, state);
            });
            assertTrue(state.getBoolean("external_editor_dirty"));
            assertEquals("draft after recreation", org.apache.commons.io.FileUtils.readFileToString(
                    new java.io.File(state.getString("external_editor_path")), "UTF-8"));
            assertEquals("original", read(EditorDocumentTestProvider.READ_ONLY));
        } finally { instrumentation.runOnMainSync(activity::finish); }
    }
}
