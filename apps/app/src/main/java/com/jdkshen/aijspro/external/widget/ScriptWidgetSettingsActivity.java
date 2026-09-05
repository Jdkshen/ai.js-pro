package com.jdkshen.aijspro.external.widget;

import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import androidx.annotation.Nullable;
import android.view.Menu;
import android.view.MenuItem;

import com.jdkshen.aijspro.R;
import com.jdkshen.aijspro.model.explorer.Explorer;
import com.jdkshen.aijspro.model.explorer.ExplorerDirPage;
import com.jdkshen.aijspro.model.explorer.ExplorerFileProvider;
import com.jdkshen.aijspro.model.script.Scripts;
import com.jdkshen.aijspro.ui.BaseActivity;
import com.jdkshen.aijspro.ui.explorer.ExplorerView;

/**
 * Created by Stardust on 2017/7/11.
 */
public class ScriptWidgetSettingsActivity extends BaseActivity {

    private String mSelectedScriptFilePath;
    private Explorer mExplorer;
    private int mAppWidgetId;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mAppWidgetId = getIntent().getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
        setContentView(R.layout.activity_script_widget_settings);
        setUpViews();
    }

    void setUpViews() {
        BaseActivity.setToolbarAsBack(this, R.id.toolbar, getString(R.string.text_please_choose_a_script));
        initScriptListRecyclerView();
    }


    private void initScriptListRecyclerView() {
        mExplorer = new Explorer(new ExplorerFileProvider(Scripts.INSTANCE.getFILE_FILTER()), 0);
        ExplorerView explorerView = findViewById(R.id.script_list);
        explorerView.setExplorer(mExplorer, ExplorerDirPage.createRoot(Environment.getExternalStorageDirectory()));
        explorerView.setOnItemClickListener((view, file) -> {
            mSelectedScriptFilePath = file.getPath();
            finish();
        });
    }

    @Override
    public void onBackPressed() {
        // 逐层返回目录，而不是直接退出页面
        ExplorerView explorerView = findViewById(R.id.script_list);
        if (explorerView != null && explorerView.canGoBack()) {
            explorerView.goBack();
            return;
        }
        super.onBackPressed();
    }


    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_refresh) {
            mExplorer.refreshAll();
        } else if (item.getItemId() == R.id.action_clear_file_selection) {
            mSelectedScriptFilePath = null;
        }
        return true;
    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.script_widget_settings_menu, menu);
        return true;
    }

    @Override
    public void finish() {
        if (ScriptWidget.updateWidget(this, mAppWidgetId, mSelectedScriptFilePath)) {
            setResult(RESULT_OK, new Intent()
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, mAppWidgetId));

        } else {
            setResult(RESULT_CANCELED, new Intent()
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, mAppWidgetId));
        }
        super.finish();
    }


}
