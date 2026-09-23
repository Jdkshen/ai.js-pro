package com.jdkshen.aijspro.ui.main.scripts;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.widget.Toast;
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
import com.jdkshen.aijspro.model.explorer.ExplorerPage;
import com.jdkshen.aijspro.model.explorer.Explorers;
import com.jdkshen.aijspro.model.script.ScriptDescriptor;
import com.jdkshen.aijspro.model.script.ScriptRepository;
import com.jdkshen.aijspro.model.script.Scripts;
import com.jdkshen.aijspro.theme.dialog.ThemeColorMaterialDialogBuilder;
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
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import io.reactivex.android.schedulers.AndroidSchedulers;

/**
 * Created by Stardust on 2017/3/13.
 */
public class MyScriptListFragment extends ViewPagerFragment implements FloatingActionMenu.OnFloatingActionButtonClickListener {

    private static final String TAG = "MyScriptListFragment";

    /** 实验开关：开启后文件页改用 Compose 版列表（S2）。默认关闭，S3 补齐操作后默认开启并删旧实现。 */
    public static final String PREF_MIUIX_FILE_LIST = "aijspro.experimental.miuix_file_list";

    public MyScriptListFragment() {
        super(0);
    }

    ExplorerView mExplorerView;

    private com.jdkshen.aijspro.ui.explorer.MiuixScriptListHost mMiuixHost;
    private static final String STATE_MIUIX_FILE_LIST = "miuix_file_list_state";
    private Bundle mMiuixSavedState;
    private static final String STATE_LEGACY_FILE_LIST = "legacy_file_list_state";
    private Bundle mLegacySavedState;

    private boolean useMiuixFileList() {
        return getContext() != null && PreferenceManager.getDefaultSharedPreferences(getContext())
                .getBoolean(PREF_MIUIX_FILE_LIST, false);
    }

    private FloatingActionMenu mFloatingActionMenu;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            mMiuixSavedState = savedInstanceState.getBundle(STATE_MIUIX_FILE_LIST);
            mLegacySavedState = savedInstanceState.getBundle(STATE_LEGACY_FILE_LIST);
        }
        EventBus.getDefault().register(this);
    }

    @NonNull
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        if (useMiuixFileList()) {
            mMiuixHost = new com.jdkshen.aijspro.ui.explorer.MiuixScriptListHost(this, mMiuixSavedState);
            View view = mMiuixHost.createView();
            mMiuixHost.reload();
            return view;
        }
        View view = inflater.inflate(R.layout.fragment_my_script_list, container, false);
        mExplorerView = view.findViewById(R.id.script_file_list);
        setUpViews();
        mExplorerView.restoreBrowserState(mLegacySavedState);
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

    public void onAppThemeChanged() {
        if (mExplorerView != null) mExplorerView.applyThemeColors();
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
        if (mMiuixHost != null) {
            if (mMiuixHost.canGoBack()) {
                mMiuixHost.goBack();
                return true;
            }
            return false;
        }
        if (mFloatingActionMenu != null && mFloatingActionMenu.isExpanded()) {
            mFloatingActionMenu.collapse();
            return true;
        }
        if (mExplorerView != null && mExplorerView.canGoBack()) {
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
        if (mMiuixHost != null) {
            return mMiuixHost.revealFile(path);
        }
        return mExplorerView != null && mExplorerView.revealFile(path);
    }

    /** 资源页「打开文件夹」：进入该目录本身（不是只高亮它所在的行）。 */
    public boolean openDirectoryFromSearch(String path) {
        if (mMiuixHost != null) {
            return mMiuixHost.openDirectory(path);
        }
        return mExplorerView != null && mExplorerView.openDirectory(path);
    }

    @Subscribe
    public void onQuerySummit(QueryEvent event) {
        if (!isShown()) {
            return;
        }
        if (mMiuixHost != null) {
            mMiuixHost.setQuery(event == QueryEvent.CLEAR ? null : event.getQuery());
            return;
        }
        if (event == QueryEvent.CLEAR) {
            if (mExplorerView != null) mExplorerView.setFilter(null);
            return;
        }
        String query = event.getQuery();
        if (mExplorerView != null) mExplorerView.setFilter((item -> item.getName().contains(query)));
    }

    @Override
    public void onStop() {
        super.onStop();
        if (mExplorerView != null) {
            mExplorerView.getSortConfig().saveInto(PreferenceManager.getDefaultSharedPreferences(getContext()));
        }
    }

    @Override
    public void onDetach() {
        super.onDetach();
        if (mFloatingActionMenu != null)
            mFloatingActionMenu.setOnFloatingActionButtonClickListener(null);
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        Bundle state = mMiuixHost == null ? mMiuixSavedState : mMiuixHost.saveState();
        if (state != null) outState.putBundle(STATE_MIUIX_FILE_LIST, state);
        Bundle legacy = mExplorerView == null ? mLegacySavedState : mExplorerView.saveBrowserState();
        if (legacy != null) outState.putBundle(STATE_LEGACY_FILE_LIST, legacy);
    }

    @Override
    public void onDestroyView() {
        if (mMiuixHost != null) {
            mMiuixSavedState = mMiuixHost.saveState();
            mMiuixHost.dispose();
            mMiuixHost = null;
        }
        if (mExplorerView != null) {
            mLegacySavedState = mExplorerView.saveBrowserState();
            mExplorerView.setOnScrollStateChangedCallback(null);
            mExplorerView.setOnItemClickListener(null);
            mExplorerView = null;
        }
        if (mFloatingActionMenu != null) {
            mFloatingActionMenu.setOnFloatingActionButtonClickListener(null);
            mFloatingActionMenu = null;
        }
        super.onDestroyView();
    }


    @Override
    public void onDestroy() {
        super.onDestroy();
        EventBus.getDefault().unregister(this);
    }

    @Override
    public void onClick(FloatingActionButton button, int pos) {
        ExplorerPage page = mMiuixHost != null ? mMiuixHost.getCurrentPage()
                : mExplorerView != null ? mExplorerView.getCurrentPage() : null;
        if (page == null || getContext() == null)
            return;
        switch (pos) {
            case 0:
                new ScriptOperations(getContext(), mExplorerView, page)
                        .newDirectory();
                break;
            case 1:
                new ScriptOperations(getContext(), mExplorerView, page)
                        .newFile();
                break;
            case 2:
                new ScriptOperations(getContext(), mExplorerView, page)
                        .importFile();
                break;
            case 3:
                Intent intent = new Intent(getContext(), ProjectConfigActivity.class);
                intent.putExtra(ProjectConfigActivity.EXTRA_PARENT_DIRECTORY, page.getPath());
                intent.putExtra(ProjectConfigActivity.EXTRA_NEW_PROJECT, true);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(intent);
                break;
            case 4:
                showRecentDeletions();
                break;

        }
    }

    /** 「最近删除」：列出备份区条目 → 选择 → 恢复到原位置（不覆盖同名）。 */
    private void showRecentDeletions() {
        if (getContext() == null)
            return;
        final File scriptRoot = new File(Pref.getScriptDirPath());
        final List<ScriptDescriptor> backups = ScriptRepository.listBackups(scriptRoot);
        if (backups.isEmpty()) {
            Toast.makeText(getContext(), "没有可恢复的删除记录", Toast.LENGTH_SHORT).show();
            return;
        }
        final SimpleDateFormat format = new SimpleDateFormat("MM-dd HH:mm", Locale.US);
        List<String> labels = new ArrayList<>();
        for (ScriptDescriptor backup : backups) {
            labels.add(ScriptRepository.backupDisplayName(backup.toFile(), scriptRoot)
                    + " · " + format.format(new Date(backup.getLastModified())));
        }
        new ThemeColorMaterialDialogBuilder(getContext())
                .title("最近删除（" + backups.size() + " 条）")
                .items(labels)
                .itemsCallback((dialog, itemView, position, text) ->
                        restoreBackupItem(backups.get(position), scriptRoot))
                .build()
                .show();
    }

    private void restoreBackupItem(ScriptDescriptor backup, File scriptRoot) {
        if (getContext() == null)
            return;
        File restored = ScriptRepository.restoreBackup(backup.toFile(), scriptRoot);
        if (restored == null) {
            Toast.makeText(getContext(), "恢复失败：原位置已有同名项目（不会覆盖），或备份已不可用",
                    Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(getContext(), "已恢复：" + restored.getPath(), Toast.LENGTH_SHORT).show();
        File parent = restored.getParentFile();
        if (parent != null) {
            Explorers.workspace().notifyChildrenChanged(new ExplorerDirPage(parent.getPath(), null));
        }
    }
}
