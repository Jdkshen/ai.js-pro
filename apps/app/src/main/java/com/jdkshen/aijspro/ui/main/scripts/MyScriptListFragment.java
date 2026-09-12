package com.jdkshen.aijspro.ui.main.scripts;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.preference.PreferenceManager;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.stardust.app.GlobalAppContext;
import com.stardust.util.IntentUtil;

import com.jdkshen.aijspro.Pref;
import com.jdkshen.aijspro.R;
import com.jdkshen.aijspro.external.fileprovider.AppFileProvider;
import com.jdkshen.aijspro.model.explorer.ExplorerDirPage;
import com.jdkshen.aijspro.model.explorer.Explorers;
import com.jdkshen.aijspro.model.script.Scripts;
import com.jdkshen.aijspro.tool.SimpleObserver;
import com.jdkshen.aijspro.ui.common.ScriptOperations;
import com.jdkshen.aijspro.ui.explorer.ExplorerView;
import com.jdkshen.aijspro.ui.main.FloatingActionMenu;
import com.jdkshen.aijspro.ui.main.MainActivity;
import com.jdkshen.aijspro.ui.main.QueryEvent;
import com.jdkshen.aijspro.ui.main.ViewPagerFragment;
import com.jdkshen.aijspro.ui.project.ProjectConfigActivity;
import com.jdkshen.aijspro.ui.viewmodel.ExplorerItemList;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;

import java.io.File;

import io.reactivex.android.schedulers.AndroidSchedulers;

/**
 * Created by Stardust on 2017/3/13.
 */
public class MyScriptListFragment extends ViewPagerFragment implements FloatingActionMenu.OnFloatingActionButtonClickListener {

    private static final String TAG = "MyScriptListFragment";

    public MyScriptListFragment() {
        super(0);
    }

    ExplorerView mExplorerView;

    private FloatingActionMenu mFloatingActionMenu;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EventBus.getDefault().register(this);
    }

    @NonNull
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_my_script_list, container, false);
        mExplorerView = view.findViewById(R.id.script_file_list);
        setUpViews();
        return view;
    }

    void setUpViews() {
        ExplorerItemList.SortConfig sortConfig = ExplorerItemList.SortConfig.from(PreferenceManager.getDefaultSharedPreferences(getContext()));
        mExplorerView.setSortConfig(sortConfig);
        File storageDirectory = Environment.getExternalStorageDirectory();
        ExplorerDirPage storageRoot = ExplorerDirPage.createRoot(storageDirectory.getPath());
        ExplorerDirPage scriptDirectory = new ExplorerDirPage(Pref.getScriptDirPath(), storageRoot);
        mExplorerView.setExplorer(Explorers.workspace(), storageRoot, scriptDirectory);
        // 滚动时收起主界面悬浮按钮（Miuix FAB 不在 CoordinatorLayout 里，需要手动联动）。
        mExplorerView.setOnScrollStateChangedCallback(scrolling -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).onMainListScrollStateChanged(scrolling);
            }
        });
        mExplorerView.setOnItemClickListener((view, item) -> {
            if (item.isEditable()) {
                Scripts.INSTANCE.edit(getActivity(), item.toScriptFile());
            } else {
                IntentUtil.viewFile(GlobalAppContext.get(), item.getPath(), AppFileProvider.AUTHORITY);
            }
        });
    }

    @Override
    protected void onFabClick(FloatingActionButton fab) {
        initFloatingActionMenuIfNeeded(fab);
        if (mFloatingActionMenu.isExpanded()) {
            mFloatingActionMenu.collapse();
        } else {
            mFloatingActionMenu.expand();

        }
    }

    private void initFloatingActionMenuIfNeeded(final FloatingActionButton fab) {
        if (mFloatingActionMenu != null)
            return;
        mFloatingActionMenu = getActivity().findViewById(R.id.floating_action_menu);
        mFloatingActionMenu.getState()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new SimpleObserver<Boolean>() {
                    @Override
                    public void onNext(@io.reactivex.annotations.NonNull Boolean expanding) {
                        fab.animate().cancel();
                        fab.animate()
                                .scaleX(0.82f)
                                .scaleY(0.82f)
                                .setDuration(90)
                                .withEndAction(() -> {
                                    fab.setImageResource(expanding
                                            ? R.drawable.ic_close_white_48dp
                                            : R.drawable.ic_menu);
                                    fab.animate()
                                            .scaleX(1f)
                                            .scaleY(1f)
                                            .setDuration(120)
                                            .start();
                                })
                                .start();
                    }
                });
        mFloatingActionMenu.setOnFloatingActionButtonClickListener(this);
    }

    @Override
    public boolean onBackPressed(Activity activity) {
        if (mFloatingActionMenu != null && mFloatingActionMenu.isExpanded()) {
            mFloatingActionMenu.collapse();
            return true;
        }
        if (mExplorerView.canGoBack()) {
            mExplorerView.goBack();
            return true;
        }
        return false;
    }

    @Override
    public void onPageHide() {
        // 滚动可能把主 FAB 隐去了：离开页面前先恢复，之后 super 会按页面状态重算显隐。
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).onMainListScrollStateChanged(false);
        }
        super.onPageHide();
        if (mFloatingActionMenu != null && mFloatingActionMenu.isExpanded()) {
            mFloatingActionMenu.collapse();
        }
    }

    /** Used by the Miuix search overlay to return to and reveal a selected file or directory. */
    public boolean revealFileFromSearch(String path) {
        return mExplorerView != null && mExplorerView.revealFile(path);
    }

    @Subscribe
    public void onQuerySummit(QueryEvent event) {
        if (!isShown()) {
            return;
        }
        if (event == QueryEvent.CLEAR) {
            mExplorerView.setFilter(null);
            return;
        }
        String query = event.getQuery();
        mExplorerView.setFilter((item -> item.getName().contains(query)));
    }

    @Override
    public void onStop() {
        super.onStop();
        mExplorerView.getSortConfig().saveInto(PreferenceManager.getDefaultSharedPreferences(getContext()));
    }

    @Override
    public void onDetach() {
        super.onDetach();
        if (mFloatingActionMenu != null)
            mFloatingActionMenu.setOnFloatingActionButtonClickListener(null);
    }


    @Override
    public void onDestroy() {
        super.onDestroy();
        EventBus.getDefault().unregister(this);
    }

    @Override
    public void onClick(FloatingActionButton button, int pos) {
        if (mExplorerView == null)
            return;
        switch (pos) {
            case 0:
                new ScriptOperations(getContext(), mExplorerView, mExplorerView.getCurrentPage())
                        .newDirectory();
                break;
            case 1:
                new ScriptOperations(getContext(), mExplorerView, mExplorerView.getCurrentPage())
                        .newFile();
                break;
            case 2:
                new ScriptOperations(getContext(), mExplorerView, mExplorerView.getCurrentPage())
                        .importFile();
                break;
            case 3:
                Intent intent = new Intent(getContext(), ProjectConfigActivity.class);
                intent.putExtra(ProjectConfigActivity.EXTRA_PARENT_DIRECTORY, mExplorerView.getCurrentPage().getPath());
                intent.putExtra(ProjectConfigActivity.EXTRA_NEW_PROJECT, true);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(intent);
                break;

        }
    }
}
