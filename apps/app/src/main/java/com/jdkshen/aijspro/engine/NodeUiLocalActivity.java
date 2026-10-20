package com.jdkshen.aijspro.engine;

import android.annotation.SuppressLint;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.os.Bundle;
import android.view.View;
import androidx.appcompat.app.AppCompatActivity;
import com.stardust.autojs.core.ui.inflater.ProjectResourceBundle;
import com.stardust.autojs.engine.node24.NodeUiHost;
import com.stardust.autojs.engine.node24.NodeUiLocalSession;
import org.autojs.source.engine.android.Node24GeneratedClasses;

/** Activity components reuse the three Node service processes, with no additional runtime. */
public class NodeUiLocalActivity extends AppCompatActivity {
    private Resources projectResources;
    private Resources.Theme projectThemeValues;
    private ClassLoader runtimeClasses;
    private String token;
    public static final class Slot1 extends NodeUiLocalActivity {}
    public static final class Slot2 extends NodeUiLocalActivity {}
    public static final class Slot3 extends NodeUiLocalActivity {}
    @Override public Resources getResources() {
        return projectResources != null ? projectResources : super.getResources();
    }
    @Override public AssetManager getAssets() {
        return projectResources != null ? projectResources.getAssets() : super.getAssets();
    }
    @Override public Resources.Theme getTheme() {
        return projectThemeValues != null ? projectThemeValues : super.getTheme();
    }
    @Override public ClassLoader getClassLoader() {
        return runtimeClasses != null ? runtimeClasses : super.getClassLoader();
    }
    @Override protected void onCreate(Bundle state) {
        token = getIntent().getStringExtra("token");
        String directory = getIntent().getStringExtra("projectDir");
        ProjectResourceBundle bundle = ProjectResourceBundle.load(getApplicationContext(), new java.io.File(directory));
        if (bundle.isCompiled()) projectResources = bundle.getCompiled().getResources();
        runtimeClasses = Node24GeneratedClasses.loader(getIntent().getStringExtra("javaClassOwner"), super.getClassLoader());
        int projectTheme = bundle.isCompiled() ? bundle.idOf("style", "MainTheme") : 0;
        if (projectResources != null) {
            // Activity.attach may already have created a Theme with the old AssetManager.
            // A theme for the merged table must be created by that table, not reused.
            projectThemeValues = projectResources.newTheme();
            int base = projectResources.getIdentifier("Theme.Material3.DayNight.NoActionBar", "style", getPackageName());
            if (base != 0) projectThemeValues.applyStyle(base, true);
            if (projectTheme != 0) projectThemeValues.applyStyle(projectTheme, true);
        }
        if (projectTheme != 0) setTheme(projectTheme);
        super.onCreate(state);
        try {
            String xml = getIntent().getStringExtra("layoutXml");
            View root;
            if (xml != null && !xml.trim().isEmpty()) {
                root = NodeUiHost.inflateInlineLayout(this, xml, runtimeClasses);
            } else {
                int layoutId = getIntent().getIntExtra("layoutId", 0);
                if (!bundle.isCompiled()) {
                    throw new IllegalStateException("原生 Java UI 需要 build/project-res.apk；先用 tools/build-project-res.ps1 编译工程资源");
                }
                root = getLayoutInflater().inflate(layoutId, null);
            }
            setContentView(root);
            org.json.JSONObject ids = new org.json.JSONObject(bundle.getRJson()).optJSONObject("id");
            if (ids == null) ids = NodeUiHost.buildViewsForScale(root, bundle);
            if (!NodeUiLocalSession.attach(this, token, root, ids)) finish();
        } catch (Throwable failure) {
            android.util.Log.e("NodeUiLocalActivity", "inflate failed", failure);
            NodeUiLocalSession.failed(token, failure);
            finish();
        }
    }
    /** 返回键直接关闭 Node 原生 UI（不进系统默认派发，避免残留 overlay）。 */
    @SuppressLint("MissingSuperCall")
    @Override public void onBackPressed() { finish(); }
    @Override public boolean onCreateOptionsMenu(android.view.Menu menu) {
        return NodeUiLocalSession.createOptionsMenu(menu) || super.onCreateOptionsMenu(menu);
    }
    @Override public boolean onOptionsItemSelected(android.view.MenuItem item) {
        return NodeUiLocalSession.optionsItemSelected(item) || super.onOptionsItemSelected(item);
    }
    @Override protected void onDestroy() {
        NodeUiLocalSession.destroyed(this);
        super.onDestroy();
    }
}
