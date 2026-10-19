package com.jdkshen.aijspro.ui.update;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import androidx.annotation.NonNull;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.afollestad.materialdialogs.MaterialDialog;
import com.stardust.util.IntentUtil;

import com.jdkshen.aijspro.BuildConfig;
import com.jdkshen.aijspro.R;
import com.jdkshen.aijspro.external.fileprovider.AppFileProvider;
import com.jdkshen.aijspro.network.download.DownloadManager;
import com.jdkshen.aijspro.network.GitHubUpdateProxy;
import com.jdkshen.aijspro.network.entity.VersionInfo;
import com.jdkshen.aijspro.tool.IntentTool;
import com.jdkshen.aijspro.ui.widget.CommonMarkdownView;

import java.io.File;
import java.util.List;

import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.Observable;
import io.reactivex.schedulers.Schedulers;

/**
 * Created by Stardust on 2017/4/9.
 */

public class UpdateInfoDialogBuilder extends MaterialDialog.Builder {

    private static final String KEY_DO_NOT_ASK_AGAIN_FOR_VERSION = "I cannot forget you...cannot help missing you...";
    private View mView;
    private SharedPreferences mSharedPreferences;
    private VersionInfo mVersionInfo;
    private boolean mShowDoNotAskAgain;

    public UpdateInfoDialogBuilder(@NonNull Context context, VersionInfo info) {
        super(context);
        mSharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        // 不要在这里构建自定义视图：CommonMarkdownView 内部是 WebView，首次构造要初始化
        // Chromium，耗时可到秒级。而 VersionGuard 在主界面每次 onResume 都会 new 本 Builder，
        // show() 里又可能因「不再提示」或走 Miuix(Compose) 路径直接跳过 —— 构造阶段先建
        // WebView 会白白堵死主线程（实测脚本结束返回主界面时 Skipped 366 帧）。
        // 视图改为 show() 确认需要走 Material 路径时再惰性构建（见 buildView()）。
        mVersionInfo = info;
    }

    /** 立即构建/重建自定义视图（保留旧行为，供需要即时取视图的调用方使用）。 */
    public UpdateInfoDialogBuilder updateInfo(VersionInfo info) {
        mVersionInfo = info;
        buildView();
        return this;
    }

    /** 只在确定要显示 Material 对话框时才构建视图（含 WebView）。 */
    private void buildView() {
        mView = View.inflate(context, R.layout.dialog_update_info, null);
        setReleaseNotes(mView, mVersionInfo);
        setUpdateHistory(mView, mVersionInfo);
        setCurrentVersionIssues(mView, mVersionInfo);
        setUpdateDownloadButtons(mView, mVersionInfo);
        title(context.getString(R.string.text_new_version) + " " + mVersionInfo.versionName);
        customView(mView, false);
        if (mShowDoNotAskAgain) {
            applyDoNotAskAgain();
        }
    }

    public UpdateInfoDialogBuilder showDoNotAskAgain() {
        mShowDoNotAskAgain = true;
        if (mView != null) {
            applyDoNotAskAgain();
        }
        return this;
    }

    private void applyDoNotAskAgain() {
        mView.findViewById(R.id.do_not_ask_again_container).setVisibility(View.VISIBLE);
        CheckBox checkBox = (CheckBox) mView.findViewById(R.id.do_not_ask_again);
        checkBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                mSharedPreferences.edit().putBoolean(KEY_DO_NOT_ASK_AGAIN_FOR_VERSION + mVersionInfo.versionCode, isChecked).apply();
            }
        });
    }

    @Override
    public MaterialDialog show() {
        if (mSharedPreferences.getBoolean(KEY_DO_NOT_ASK_AGAIN_FOR_VERSION + mVersionInfo.versionCode, false)) {
            return null;
        }
        // task-177：Miuix 版「发现新版本」对话框已随 miuix 源集退役，统一走 Material 实现。
        if (mView == null) {
            buildView();
        }
        return super.show();
    }

    private void setCurrentVersionIssues(View view, VersionInfo info) {
        TextView issues = (TextView) view.findViewById(R.id.issues);
        VersionInfo.OldVersion currentVersion = info.getOldVersion(BuildConfig.VERSION_CODE);
        if (currentVersion == null) {
            issues.setVisibility(View.GONE);
        } else {
            issues.setText(currentVersion.issues);
        }
    }

    private void setUpdateDownloadButtons(View view, VersionInfo info) {
        LinearLayout downloads = (LinearLayout) view.findViewById(R.id.downloads);
        setDirectlyDownloadButton(downloads, info);
        for (final VersionInfo.Download download : info.downloads) {
            Button button = (Button) View.inflate(getContext(), R.layout.dialog_update_info_btn, null);
            button.setText(download.name);
            downloads.addView(button);
            button.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    IntentTool.browse(v.getContext(), download.url);
                }
            });
        }
    }

    private void setDirectlyDownloadButton(LinearLayout container, final VersionInfo info) {
        if (TextUtils.isEmpty(info.downloadUrl)) {
            return;
        }
        Button button = (Button) View.inflate(getContext(), R.layout.dialog_update_info_btn, null);
        button.setText(R.string.text_directly_download);
        button.setOnClickListener(v -> directlyDownload(info));
        container.addView(button);
    }

    @SuppressLint("CheckResult")
    private void directlyDownload(VersionInfo info) {
        final File updateDirectory = new File(getContext().getCacheDir(), "updates");
        final String path = new File(updateDirectory, "AI-js-Pro.apk").getPath();
        GitHubUpdateProxy.downloadWithFallback(info.downloadUrl,
                        url -> DownloadManager.getInstance().downloadWithProgress(getContext(), url, path))
                .subscribeOn(AndroidSchedulers.mainThread())
                .flatMap(file -> Observable.fromCallable(() -> UpdatePackageVerifier.verify(
                                getContext().getApplicationContext(), file, info.versionCode,
                                info.downloadDigest))
                        .subscribeOn(Schedulers.io()))
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(file -> IntentUtil.installApkOrToast(getContext(), file.getPath(), AppFileProvider.AUTHORITY),
                        error -> {
                            error.printStackTrace();
                            String detail = TextUtils.isEmpty(error.getMessage())
                                    ? getContext().getString(R.string.text_download_failed)
                                    : error.getMessage();
                            Toast.makeText(getContext(), getContext().getString(
                                    R.string.text_update_package_rejected, detail), Toast.LENGTH_LONG).show();
                        });

    }


    private void setReleaseNotes(View view, VersionInfo info) {
        CommonMarkdownView markdownView = view.findViewById(R.id.release_notes);
        markdownView.loadMarkdown(info.releaseNotes);
    }

    /**
     * 「更新历史」：默认收起，点标题展开；每个历史版本一条（版本号 · 日期 + 更新说明）。
     * 正在安装的这个版本不列在这里，它的说明已作为「更新日志」单独展示。
     */
    private void setUpdateHistory(View view, VersionInfo info) {
        final TextView toggle = view.findViewById(R.id.update_history_toggle);
        final LinearLayout container = view.findViewById(R.id.update_history_container);
        final List<VersionInfo.OldVersion> history = info.historyForDialog();
        if (history.isEmpty()) {
            toggle.setVisibility(View.GONE);
            container.setVisibility(View.GONE);
            return;
        }
        final String label = context.getString(R.string.text_update_history_count, history.size());
        toggle.setText(label + "  ·  " + context.getString(R.string.text_expand));
        toggle.setVisibility(View.VISIBLE);
        container.setVisibility(View.GONE);
        // 历史更新默认收起，而每条说明都是一个 CommonMarkdownView（WebView）：全部预建
        // 会让对话框打开时连续构造多个 WebView，历史越多卡得越久。改为展开时才构建。
        final boolean[] built = {false};
        toggle.setOnClickListener(v -> {
            boolean expanded = container.getVisibility() != View.VISIBLE;
            if (expanded && !built[0]) {
                built[0] = true;
                buildUpdateHistoryViews(container, history);
            }
            container.setVisibility(expanded ? View.VISIBLE : View.GONE);
            toggle.setText(label + "  ·  " + context.getString(
                    expanded ? R.string.text_collapse : R.string.text_expand));
        });
    }

    private void buildUpdateHistoryViews(LinearLayout container, List<VersionInfo.OldVersion> history) {
        int padding = (int) (8 * context.getResources().getDisplayMetrics().density);
        for (VersionInfo.OldVersion entry : history) {
            TextView header = new TextView(context);
            header.setText(entry.displayTitle());
            header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            header.setPadding(0, padding, 0, 0);
            container.addView(header);
            if (!TextUtils.isEmpty(entry.issues)) {
                CommonMarkdownView notes = new CommonMarkdownView(context);
                notes.loadMarkdown(entry.issues);
                container.addView(notes);
            }
        }
    }
}
