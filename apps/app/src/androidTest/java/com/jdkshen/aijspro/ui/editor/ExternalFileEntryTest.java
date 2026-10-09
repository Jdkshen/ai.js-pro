package com.jdkshen.aijspro.ui.editor;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import androidx.test.platform.app.InstrumentationRegistry;
import com.jdkshen.aijspro.external.open.EditIntentActivity;
import com.jdkshen.aijspro.external.open.ExternalFileIntents;
import com.jdkshen.aijspro.external.open.ImportIntentActivity;
import com.jdkshen.aijspro.external.open.RunIntentActivity;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

/** Resolve implicit intents before launching: explicit Activity tests cannot catch missing QQ entries. */
public class ExternalFileEntryTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private List<ResolveInfo> resolve(Intent intent) {
        return context.getPackageManager().queryIntentActivities(intent.setPackage(context.getPackageName()), PackageManager.MATCH_DEFAULT_ONLY);
    }
    private void hasBothEntries(Intent intent) {
        List<ResolveInfo> entries = resolve(intent);
        assertTrue(intent.toString(), entries.stream().anyMatch(i -> i.activityInfo.name.equals(EditIntentActivity.class.getName())));
        assertTrue(intent.toString(), entries.stream().anyMatch(i -> i.activityInfo.name.equals(ImportIntentActivity.class.getName())));
        for (ResolveInfo entry : entries) {
            if (entry.activityInfo.name.equals(EditIntentActivity.class.getName()))
                assertEquals("AI.js Pro · 打开文件", entry.loadLabel(context.getPackageManager()).toString());
            if (entry.activityInfo.name.equals(ImportIntentActivity.class.getName()))
                assertEquals("AI.js Pro · 导入文件", entry.loadLabel(context.getPackageManager()).toString());
        }
    }
    @Test public void openAndEditResolveForGenericAndTextFileTypes() {
        String[] types = {"application/octet-stream", "application/x-javascript", "application/javascript",
                "text/javascript", "text/plain", "text/markdown", "text/x-markdown", "application/json", "application/xml", "application/unknown"};
        for (String action : new String[]{Intent.ACTION_VIEW, Intent.ACTION_EDIT}) {
            for (String scheme : new String[]{"file", "content"}) {
                for (String type : types) hasBothEntries(new Intent(action).setDataAndType(
                        Uri.parse(scheme + ":///qq/文件.MD"), type));
            }
        }
        hasBothEntries(new Intent(Intent.ACTION_VIEW).setData(Uri.parse("file:///sdcard/Download/example.js")));
    }
    @Test public void shareResolvesWithoutDataUriAndKeepsAttachment() {
        Uri uri = EditorDocumentTestProvider.READ_ONLY;
        for (String type : new String[]{"application/octet-stream", "text/plain", "text/javascript", "text/markdown"}) {
            Intent intent = new Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uri);
            hasBothEntries(intent);
            assertEquals(uri, ExternalFileIntents.getFileUri(intent));
        }
        Intent clip = new Intent(Intent.ACTION_SEND).setType("application/octet-stream");
        clip.setClipData(ClipData.newRawUri("file", uri));
        hasBothEntries(clip);
        assertEquals(uri, ExternalFileIntents.getFileUri(clip));
    }
    @Test public void genericFilesAreNotRegisteredForImmediateExecution() {
        List<ResolveInfo> entries = resolve(new Intent(Intent.ACTION_VIEW).setDataAndType(
                EditorDocumentTestProvider.READ_ONLY, "application/octet-stream"));
        assertFalse(entries.stream().anyMatch(i -> i.activityInfo.name.equals(RunIntentActivity.class.getName())));
        assertNull(ExternalFileIntents.getFileUri(new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "https://example.com/text-only-share")));
    }
}
