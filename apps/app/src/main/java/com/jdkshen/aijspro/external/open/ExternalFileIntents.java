package com.jdkshen.aijspro.external.open;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.text.TextUtils;

/** File managers use data URIs; share sheets normally use EXTRA_STREAM or ClipData. */
public final class ExternalFileIntents {
    public static Uri getFileUri(Intent intent) {
        if (intent == null) return null;
        if (isFileUri(intent.getData())) return intent.getData();
        Object stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (stream instanceof Uri && isFileUri((Uri) stream)) return (Uri) stream;
        ClipData clip = intent.getClipData();
        if (clip != null && clip.getItemCount() == 1 && isFileUri(clip.getItemAt(0).getUri())) {
            return clip.getItemAt(0).getUri();
        }
        return null;
    }

    private static boolean isFileUri(Uri uri) {
        return uri != null && ("content".equals(uri.getScheme()) || "file".equals(uri.getScheme()));
    }

    public static String displayName(Context context, Uri uri) {
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = context.getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    String name = column < 0 ? null : cursor.getString(column);
                    if (!TextUtils.isEmpty(name)) return name;
                }
            } catch (RuntimeException ignored) {
                // Some senders allow stream reads but do not implement metadata queries.
            }
        }
        String name = uri.getLastPathSegment();
        if (name != null) name = name.substring(name.lastIndexOf('/') + 1);
        return TextUtils.isEmpty(name) ? "外部文件" : name;
    }

    private ExternalFileIntents() { }
}
