package com.jdkshen.aijspro.ui.editor;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.platform.app.InstrumentationRegistry;
import com.jdkshen.aijspro.external.open.ImportIntentActivity;
import org.junit.Test;
import static org.junit.Assert.*;

public class ExternalImportCancellationTest {
    private AccessibilityNodeInfo cancelButton(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.getText() != null && node.getText().toString().equals("取消")) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = cancelButton(node.getChild(i));
            if (found != null) return found;
        }
        return null;
    }
    @Test public void cancellingExternalImportClosesItsActivity() {
        checkCancel(new Intent(Intent.ACTION_VIEW).setDataAndType(EditorDocumentTestProvider.READ_ONLY, "text/markdown"));
    }
    @Test public void qqStyleShareImportsAndCanBeCancelled() {
        checkCancel(new Intent(Intent.ACTION_SEND).setType("application/octet-stream")
                .putExtra(Intent.EXTRA_STREAM, EditorDocumentTestProvider.READ_ONLY));
    }
    private void checkCancel(Intent intent) {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        android.content.Context context = instrumentation.getTargetContext();
        android.content.pm.ResolveInfo entry = context.getPackageManager().queryIntentActivities(
                intent.setPackage(context.getPackageName()), android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
                .stream().filter(i -> i.activityInfo.name.equals(ImportIntentActivity.class.getName())).findFirst().orElse(null);
        assertNotNull("Import entry must be discoverable before selecting it", entry);
        Activity activity = instrumentation.startActivitySync(intent
                .setComponent(new android.content.ComponentName(entry.activityInfo.packageName, entry.activityInfo.name))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION));
        try {
            long end = SystemClock.uptimeMillis() + 8000;
            AccessibilityNodeInfo cancel = null;
            while (cancel == null && SystemClock.uptimeMillis() < end) {
                cancel = cancelButton(instrumentation.getUiAutomation().getRootInActiveWindow());
                if (cancel == null) SystemClock.sleep(100);
            }
            assertNotNull("Unified import name dialog must be visible", cancel);
            while (cancel != null && !cancel.isClickable()) cancel = cancel.getParent();
            assertNotNull(cancel);
            assertTrue(cancel.performAction(AccessibilityNodeInfo.ACTION_CLICK));
            end = SystemClock.uptimeMillis() + 5000;
            while (!activity.isFinishing() && SystemClock.uptimeMillis() < end) SystemClock.sleep(100);
            assertTrue("Cancel must finish the external entry instead of leaving a blank page", activity.isFinishing());
        } finally { instrumentation.runOnMainSync(activity::finish); }
    }
}
