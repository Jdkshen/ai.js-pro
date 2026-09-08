package com.jdkshen.aijspro.ui.edit;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;

import com.google.android.material.snackbar.Snackbar;

import androidx.appcompat.app.AppCompatActivity;

import android.util.SparseArray;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import com.stardust.app.OnActivityResultDelegate;
import com.stardust.autojs.engine.JavaScriptEngine;
import com.stardust.autojs.execution.ScriptExecution;

import com.jdkshen.aijspro.R;
import com.jdkshen.aijspro.autojs.AutoJs;
import com.jdkshen.aijspro.model.sample.SampleFile;
import com.jdkshen.aijspro.model.script.Scripts;
import com.jdkshen.aijspro.ui.BaseActivity;
import com.jdkshen.aijspro.ui.common.ScriptOperations;
import com.jdkshen.aijspro.ui.edit.editor.CodeEditor;
import com.jdkshen.aijspro.ui.edit.theme.Theme;
import com.jdkshen.aijspro.ui.editor.ProCodeEditorActivity;

import com.stardust.theme.ThemeColorManager;
import com.stardust.util.SparseArrayEntries;

import com.jdkshen.aijspro.ui.widget.ToolbarMenuItem;

import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.annotations.NonNull;
import io.reactivex.functions.Consumer;

import com.stardust.pio.PFiles;

import static com.jdkshen.aijspro.model.script.Scripts.ACTION_ON_EXECUTION_FINISHED;
import static com.jdkshen.aijspro.model.script.Scripts.EXTRA_EXCEPTION_MESSAGE;


/**
 * Created by Stardust on 2017/4/29.
 */
public class ViewSampleActivity extends AppCompatActivity implements OnActivityResultDelegate.DelegateHost {


    public static void view(Context context, SampleFile sample) {
        context.startActivity(new Intent(context, ViewSampleActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("sample_path", sample.getPath()));
    }

    private View mView;
    private SampleFile mSample;
    private CodeEditor mEditor;
    private ScriptExecution mScriptExecution;
    private SparseArray<ToolbarMenuItem> mMenuMap;
    private OnActivityResultDelegate.Mediator mMediator = new OnActivityResultDelegate.Mediator();
    private BroadcastReceiver mOnRunFinishedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(ACTION_ON_EXECUTION_FINISHED)) {
                mScriptExecution = null;
                String msg = intent.getStringExtra(EXTRA_EXCEPTION_MESSAGE);
                if (msg != null) {
                    Snackbar.make(mView, getString(R.string.text_error) + ": " + msg, Snackbar.LENGTH_LONG).show();
                }
            }
        }
    };

    public void onCreate(Bundle b) {
        super.onCreate(b);
        mView = View.inflate(this, R.layout.activity_view_sample, null);
        setContentView(mView);
        handleIntent(getIntent());
        setUpUI();
        registerReceiver(mOnRunFinishedReceiver, new IntentFilter(ACTION_ON_EXECUTION_FINISHED));
    }

    private void handleIntent(Intent intent) {
        mSample = new SampleFile(intent.getStringExtra("sample_path"), getAssets());
    }

    private void setUpUI() {
        ThemeColorManager.addActivityStatusBar(this);
        setUpToolbar();
        initMenuItem();
        findViewById(R.id.run).setOnClickListener(v -> run());
        findViewById(R.id.edit).setOnClickListener(v -> edit());
        mEditor = findViewById(R.id.editor);
        try {
            mEditor.setTheme(Theme.getDefault(this));
            mEditor.setInitialText(PFiles.read(mSample.openInputStream()));
            mEditor.setReadOnly(true);
        } catch (Exception e) {
            Snackbar.make(mView, getString(R.string.text_error) + ": " + e.getMessage(), Snackbar.LENGTH_LONG).show();
        }
    }

    private void setUpToolbar() {
        BaseActivity.setToolbarAsBack(this, R.id.toolbar, mSample.getSimplifiedName());
    }

    void run() {
        Snackbar.make(mView, R.string.text_start_running, Snackbar.LENGTH_SHORT).show();
        mScriptExecution = Scripts.INSTANCE.run(mSample.toSource());
    }

    void edit() {
        new ScriptOperations(this, mView)
                .importSample(mSample)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(path -> {
                    startActivity(ProCodeEditorActivity.intent(
                            ViewSampleActivity.this, new java.io.File(path)));
                    finish();
                });
    }

    private void initMenuItem() {
        mMenuMap = new SparseArrayEntries<ToolbarMenuItem>()
                .entry(R.id.run, (ToolbarMenuItem) findViewById(R.id.run))
                .sparseArray();
    }

    public void setMenuStatus(int menuResId, int status) {
        ToolbarMenuItem menuItem = mMenuMap.get(menuResId);
        if (menuItem == null)
            return;
    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_view_sample, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_console:
                showConsole();
                return true;
            case R.id.action_log:
                showLog();
                return true;
            case R.id.action_help:

                return true;
            case R.id.action_import:
                new ScriptOperations(this, mView)
                        .importSample(mSample)
                        .subscribe();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }


    private void showLog() {
        AutoJs.getInstance().getScriptEngineService().getGlobalConsole().show();
    }

    private void showConsole() {
        if (mScriptExecution != null) {
            ((JavaScriptEngine) mScriptExecution.getEngine()).getRuntime().console.show();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(mOnRunFinishedReceiver);
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        try {
            super.onRestoreInstanceState(savedInstanceState);
        } catch (RuntimeException e) {
            // FIXME: 2017/3/20
            e.printStackTrace();
        }
    }

    @Override
    public OnActivityResultDelegate.Mediator getOnActivityResultDelegateMediator() {
        return mMediator;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        mMediator.onActivityResult(requestCode, resultCode, data);
    }
}
