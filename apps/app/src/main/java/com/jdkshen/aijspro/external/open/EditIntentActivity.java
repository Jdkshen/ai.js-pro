package com.jdkshen.aijspro.external.open;

import android.content.ContentResolver;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.Nullable;

import android.widget.Toast;


import com.jdkshen.aijspro.ui.BaseActivity;
import com.jdkshen.aijspro.ui.editor.ProCodeEditorActivity;
import com.jdkshen.aijspro.R;

import java.io.File;

/**
 * Created by Stardust on 2017/2/2.
 */

public class EditIntentActivity extends BaseActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            handleIntent();
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, R.string.edit_and_run_handle_intent_error, Toast.LENGTH_LONG).show();
        }
        finish();
    }

    private void handleIntent() {
        Intent intent = getIntent();
        Uri uri = ExternalFileIntents.getFileUri(intent);
        if (uri == null) throw new IllegalArgumentException("没有要打开的文件");
        if (ContentResolver.SCHEME_FILE.equals(uri.getScheme())) {
            startActivity(ProCodeEditorActivity.intent(this, new File(uri.getPath())));
        } else if (ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
            // Forward the actual grant. Never guess filesystem paths from a provider's URI.
            Intent editor = new Intent(this, ProCodeEditorActivity.class).setData(uri)
                    .addFlags(intent.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
            editor.setClipData(android.content.ClipData.newRawUri("document", uri));
            startActivity(editor);
        } else {
            throw new IllegalArgumentException("不支持的文件来源");
        }
    }
}
