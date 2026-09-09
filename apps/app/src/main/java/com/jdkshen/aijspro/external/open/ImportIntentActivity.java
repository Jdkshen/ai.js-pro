package com.jdkshen.aijspro.external.open;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import androidx.annotation.Nullable;
import android.text.TextUtils;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import com.stardust.pio.PFiles;
import com.jdkshen.aijspro.ui.BaseActivity;
import com.jdkshen.aijspro.ui.common.ScriptOperations;
import com.jdkshen.aijspro.R;

import java.io.FileNotFoundException;
import java.io.InputStream;

import io.reactivex.Observable;
import io.reactivex.disposables.CompositeDisposable;

/**
 * Created by Stardust on 2017/2/2.
 */

public class ImportIntentActivity extends BaseActivity {

    private final CompositeDisposable mDisposables = new CompositeDisposable();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        onNewIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        try {
            handleIntent(intent);
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, R.string.edit_and_run_handle_intent_error, Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void handleIntent(Intent intent) throws FileNotFoundException {
        Uri uri = intent.getData();
        if (uri == null) {
            throw new FileNotFoundException("Missing import URI");
        }
        if ("content".equals(uri.getScheme())) {
            String displayName = queryDisplayName(uri);
            String ext = PFiles.getExtension(displayName);
            if (TextUtils.isEmpty(ext)) {
                ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(
                        getContentResolver().getType(uri));
            }
            if (TextUtils.isEmpty(ext)) ext = "js";
            InputStream stream = getContentResolver().openInputStream(uri);
            if (stream == null) throw new FileNotFoundException(uri.toString());
            subscribeImport(new ScriptOperations(this, null)
                    .importFile(displayName, stream, ext)
                    .doFinally(stream::close));
        } else {
            final String path = uri.getPath();
            if (!TextUtils.isEmpty(path)) {
                subscribeImport(new ScriptOperations(this, null).importFile(path));
            } else {
                throw new FileNotFoundException(uri.toString());
            }
        }
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0) return cursor.getString(column);
            }
        }
        return "";
    }

    private void subscribeImport(Observable<?> operation) {
        mDisposables.add(operation.subscribe(ignored -> finish(), error -> {
            Toast.makeText(this, R.string.edit_and_run_handle_intent_error, Toast.LENGTH_LONG).show();
            finish();
        }));
    }

    @Override
    protected void onDestroy() {
        mDisposables.clear();
        super.onDestroy();
    }

}
