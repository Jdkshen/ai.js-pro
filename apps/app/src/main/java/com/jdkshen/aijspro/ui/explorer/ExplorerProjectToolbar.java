package com.jdkshen.aijspro.ui.explorer;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import androidx.annotation.Nullable;
import androidx.cardview.widget.CardView;
import android.util.AttributeSet;
import android.widget.TextView;
import android.widget.Toast;

import com.stardust.autojs.project.ProjectConfig;
import com.stardust.autojs.project.ProjectLauncher;
import com.stardust.pio.PFile;

import com.jdkshen.aijspro.R;
import com.jdkshen.aijspro.autojs.AutoJs;
import com.jdkshen.aijspro.model.explorer.ExplorerChangeEvent;
import com.jdkshen.aijspro.model.explorer.ExplorerItem;
import com.jdkshen.aijspro.model.explorer.Explorers;
import com.jdkshen.aijspro.ui.project.BuildActivity;
import com.jdkshen.aijspro.ui.project.ProjectConfigActivity;
import org.greenrobot.eventbus.Subscribe;

public class ExplorerProjectToolbar extends CardView {

    private ProjectConfig mProjectConfig;
    private PFile mDirectory;

    TextView mProjectName;

    public ExplorerProjectToolbar(Context context) {
        super(context);
        init();
    }

    public ExplorerProjectToolbar(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public ExplorerProjectToolbar(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        inflate(getContext(), R.layout.explorer_project_toolbar, this);
        mProjectName = findViewById(R.id.project_name);
        findViewById(R.id.run).setOnClickListener(v -> run());
        findViewById(R.id.build).setOnClickListener(v -> build());
        findViewById(R.id.sync).setOnClickListener(v -> sync());
        setOnClickListener(view -> edit());
    }

    public void setProject(PFile dir) {
        mProjectConfig = ProjectConfig.fromProjectDir(dir.getPath());
        if(mProjectConfig == null){
            setVisibility(GONE);
            return;
        }
        mDirectory = dir;
        mProjectName.setText(mProjectConfig.getName());
    }

    public void refresh() {
        if (mDirectory != null) {
            setProject(mDirectory);
        }
    }

    void run() {
        try {
            new ProjectLauncher(mDirectory.getPath())
                    .launch(AutoJs.getInstance().getScriptEngineService());
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(getContext(), e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    void build() {
        Intent intent = new Intent(getContext(), BuildActivity.class);
        intent.putExtra(BuildActivity.EXTRA_SOURCE, mDirectory.getPath());
        if (!(getContext() instanceof Activity)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        getContext().startActivity(intent);
    }

    void sync() {

    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        Explorers.workspace().registerChangeListener(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        Explorers.workspace().unregisterChangeListener(this);
    }

    @Subscribe
    public void onExplorerChange(ExplorerChangeEvent event) {
        if (mDirectory == null) {
            return;
        }
        ExplorerItem item = event.getItem();
        if ((event.getAction() == ExplorerChangeEvent.ALL)
                || (item != null && mDirectory.getPath().equals(item.getPath()))) {
            refresh();
        }
    }

    void edit() {
        Intent intent = new Intent(getContext(), ProjectConfigActivity.class);
        intent.putExtra(ProjectConfigActivity.EXTRA_DIRECTORY, mDirectory.getPath());
        if (!(getContext() instanceof Activity)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        getContext().startActivity(intent);
    }

}
