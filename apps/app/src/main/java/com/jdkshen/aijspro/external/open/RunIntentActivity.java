package com.jdkshen.aijspro.external.open;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import com.stardust.autojs.execution.ScriptExecution;
import com.stardust.autojs.execution.ScriptExecutionListener;
import com.stardust.autojs.execution.SimpleScriptExecutionListener;
import com.stardust.autojs.script.StringScriptSource;
import com.stardust.pio.PFiles;
import com.jdkshen.aijspro.autojs.AutoJs;
import com.jdkshen.aijspro.external.ScriptIntents;
import com.jdkshen.aijspro.R;
import com.jdkshen.aijspro.model.script.Scripts;

import java.io.FileNotFoundException;
import java.io.InputStream;

/**
 * Runs a script from an external intent. A translucent window stays in the
 * foreground until the execution finishes so dialogs keep a valid window token.
 */
public class RunIntentActivity extends Activity {

    private static final long MAX_HOLD_MS = 30 * 60 * 1000L;

    private ScriptExecutionListener mWatcher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            hold(handleIntent(getIntent()));
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, R.string.edit_and_run_handle_intent_error, Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private ScriptExecution handleIntent(Intent intent) throws FileNotFoundException {
        Uri uri = intent.getData();
        if (uri != null && "content".equals(uri.getScheme())) {
            InputStream stream = getContentResolver().openInputStream(uri);
            return Scripts.INSTANCE.run(new StringScriptSource(PFiles.read(stream)));
        }
        return ScriptIntents.execute(this, intent);
    }

    private void hold(ScriptExecution execution) {
        if (execution == null) {
            finish();
            return;
        }
        final int id = execution.getId();
        mWatcher = new SimpleScriptExecutionListener() {
            @Override
            public void onSuccess(ScriptExecution e, Object result) {
                maybeFinish(e);
            }

            @Override
            public void onException(ScriptExecution e, Throwable t) {
                maybeFinish(e);
            }

            private void maybeFinish(ScriptExecution e) {
                if (e.getId() != id) {
                    return;
                }
                AutoJs.getInstance().getScriptEngineService()
                        .unregisterGlobalScriptExecutionListener(mWatcher);
                runOnUiThread(() -> finish());
            }
        };
        AutoJs.getInstance().getScriptEngineService()
                .registerGlobalScriptExecutionListener(mWatcher);
        // Safety net: never keep the process foreground forever.
        getWindow().getDecorView().postDelayed(() -> {
            if (!isFinishing()) {
                AutoJs.getInstance().getScriptEngineService()
                        .unregisterGlobalScriptExecutionListener(mWatcher);
                finish();
            }
        }, MAX_HOLD_MS);
    }
}
