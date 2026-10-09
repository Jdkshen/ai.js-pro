package com.jdkshen.aijspro.ui.editor;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Installed only in the test APK; never accepts user paths. */
public class EditorDocumentTestProvider extends ContentProvider {
    public static final Uri WRITABLE = Uri.parse("content://com.jdkshen.aijspro.test.editor.documents/writable");
    public static final Uri READ_ONLY = Uri.parse("content://com.jdkshen.aijspro.test.editor.documents/readonly");
    private File file;
    @Override public boolean onCreate() {
        file = new File(getContext().getCacheDir(), "external-editor-test.md");
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write("# external document original long text\n中文内容".getBytes(StandardCharsets.UTF_8));
        } catch (Exception error) { throw new IllegalStateException(error); }
        return true;
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        MatrixCursor cursor = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME});
        cursor.addRow(new Object[]{"来自外部.MD"});
        return cursor;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (uri.equals(READ_ONLY) && !mode.equals("r")) throw new SecurityException("read only test document");
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode));
    }
    @Override public String getType(Uri uri) { return "text/markdown"; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
