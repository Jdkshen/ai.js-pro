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
        updateInfo(info);
    }

    public UpdateInfoDialogBuilder updateInfo(VersionInfo info) {
        mVersionInfo = info;
        mView = View.inflate(context, R.layout.dialog_update_info, null);
        setReleaseNotes(mView, info);
        setUpdateHistory(mView, info);
        setCurrentVersionIssues(mView, info);
        setUpdateDownloadButtons(mView, info);
        title(context.getString(R.string.text_new_version) + " " + info.versionName);
        customView(mView, false);
        return this;
    }

    public UpdateInfoDialogBuilder showDoNotAskAgain() {
        mShowDoNotAskAgain = true;
        mView.findViewById(R.id.do_not_ask_again_container).setVisibility(View.VISIBLE);
        CheckBox checkBox = (CheckBox) mView.findViewById(R.id.do_not_ask_again);
        checkBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                mSharedPreferences.edit().putBoolean(KEY_DO_NOT_ASK_AGAIN_FOR_VERSION + mVersionInfo.versionCode, isChecked).apply();
            }
        });
        return this;
    }

    @Override
    public MaterialDialog show() {
        if (mSharedPreferences.getBoolean(KEY_DO_NOT_ASK_AGAIN_FOR_VERSION + mVersionInfo.versionCode, false)) {
            return null;
        }
        if (BuildConfig.MIUIX_PILOT && showWithMiuix()) {
            return null;
        }
        return super.show();
    }

    /** Miuix 版（pilot）走独立的 Compose 对话框；失败时回退到 Material 实现。 */
    private boolean showWithMiuix() {
        if (!(getContext() instanceof android.app.Activity)) {
            return false;
        }
        // 历史更新：只传展示用的字符串，main 源集不需要依赖 miuix 的任何类型。
        List<VersionInfo.OldVersion> history = mVersionInfo.historyForDialog();
        String[] historyTitles = new String[history.size()];
        String[] historyNotes = new String[history.size()];
        for (int i = 0; i < history.size(); i++) {
            VersionInfo.OldVersion entry = history.get(i);
            historyTitles[i] = entry.displayTitle();
            historyNotes[i] = entry.issues == null ? "" : entry.issues;
        }
        try {
            Class<?> host = Class.forName("com.jdkshen.aijspro.ui.update.MiuixUpdateDialog");
            host.getMethod("show", android.app.Activity.class, String.class, String.class,
                            int.class, boolean.class, String.class, Runnable.class,
                            String[].class, String[].class)
                    .invoke(null, getContext(),
                            getContext().getString(R.string.text_new_version) + " " + mVersionInfo.versionName,
                            mVersionInfo.releaseNotes,
                            mVersionInfo.versionCode,
                            mShowDoNotAskAgain,
                            KEY_DO_NOT_ASK_AGAIN_FOR_VERSION,
                            (Runnable) () -> directlyDownload(mVersionInfo),
                            historyTitles,
                            historyNotes);
            return true;
        } catch (Throwable error) {
            android.util.Log.e("UpdateInfoDialogBuilder", "Miuix update dialog failed, fallback to Material", error);
            return false;
        }
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
        DownloadManager.getInstance().downloadWithProgress(getContext(), info.downloadUrl, path)
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
        toggle.setOnClickListener(v -> {
            boolean expanded = container.getVisibility() != View.VISIBLE;
            container.setVisibility(expanded ? View.VISIBLE : View.GONE);
            toggle.setText(label + "  ·  " + context.getString(
                    expanded ? R.string.text_collapse : R.string.text_expand));
        });
    }
}
