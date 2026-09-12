package com.jdkshen.aijspro.ui.update;

import android.app.Activity;
import android.content.Context;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import com.afollestad.materialdialogs.MaterialDialog;
import com.stardust.app.GlobalAppContext;
import com.jdkshen.aijspro.BuildConfig;
import com.jdkshen.aijspro.R;
import com.jdkshen.aijspro.network.VersionService;
import com.jdkshen.aijspro.network.entity.VersionInfo;
import com.jdkshen.aijspro.tool.SimpleObserver;

import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.annotations.NonNull;

/**
 * Created by Stardust on 2017/9/20.
 */

public class UpdateCheckDialog {

    private static final String TAG = "UpdateCheckDialog";
    private static final String MIUIX_DIALOG_CLASS =
            "com.jdkshen.aijspro.ui.update.MiuixUpdateCheckDialog";

    /** 「正在检查更新」提示框的最短显示时间：本地/自建更新源响应极快时，
     *  不设下限就会出现「弹出来立刻被关掉」的一闪而过。 */
    private static final long MIN_SHOW_DURATION_MS = 400;

    private MaterialDialog mProgress;
    private Object mMiuixHandle;
    private Context mContext;
    private long mShownAtMs;
    private final android.os.Handler mHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());

    public UpdateCheckDialog(Context context) {
        mContext = context;
    }

    public void show() {
        mShownAtMs = android.os.SystemClock.uptimeMillis();
        // Miuix（pilot）下用同风格的 Compose 提示框：旧 Material 进度框在网络很快时
        // 会一闪而过且风格不搭。反射失败时自动回退。
        if (!showWithMiuix()) {
            mProgress = new MaterialDialog.Builder(mContext)
                    .progress(true, 0)
                    .content(R.string.text_checking_update)
                    .cancelable(false)
                    .build();
            mProgress.show();
        }
        VersionService.getInstance()
                .checkForUpdates(mContext)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new SimpleObserver<VersionInfo>() {
                    @Override
                    public void onNext(@NonNull VersionInfo versionInfo) {
                        // 等提示框走完最短显示时间再出结果，观感上「检查完 → 出结果」更连贯。
                        dismissProgress(() -> {
                            if (versionInfo.isNewer()) {
                                new UpdateInfoDialogBuilder(mContext, versionInfo)
                                        .show();
                            } else {
                                Toast.makeText(GlobalAppContext.get(), R.string.text_is_latest_version, Toast.LENGTH_SHORT).show();
                            }
                        });
                    }

                    @Override
                    public void onError(@NonNull Throwable e) {
                        e.printStackTrace();
                        // 自建更新源时把原因说清楚：自己搭的源出问题（路径写错、没开服务）
                        // 只报「检查更新失败」根本定位不了。
                        final String detail = e.getMessage();
                        dismissProgress(() -> {
                            if (!TextUtils.isEmpty(VersionService.updateSourceUrl(mContext))) {
                                Toast.makeText(GlobalAppContext.get(), mContext.getString(
                                        R.string.text_check_update_error_detail,
                                        TextUtils.isEmpty(detail) ? e.getClass().getSimpleName() : detail),
                                        Toast.LENGTH_LONG).show();
                            } else {
                                Toast.makeText(GlobalAppContext.get(), R.string.text_check_update_error, Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                });
    }

    private boolean showWithMiuix() {
        if (!BuildConfig.MIUIX_PILOT || !(mContext instanceof Activity)) {
            return false;
        }
        try {
            Class<?> host = Class.forName(MIUIX_DIALOG_CLASS);
            mMiuixHandle = host.getMethod("show", Activity.class, String.class)
                    .invoke(null, mContext, mContext.getString(R.string.text_checking_update));
            return mMiuixHandle != null;
        } catch (Throwable error) {
            Log.e(TAG, "Miuix update check dialog failed, fallback to Material", error);
            return false;
        }
    }

    private void dismissProgress(Runnable andThen) {
        long elapsed = android.os.SystemClock.uptimeMillis() - mShownAtMs;
        long remain = MIN_SHOW_DURATION_MS - elapsed;
        if (remain > 20) {
            // 检查太快（自建本地源毫秒级返回）时补足最短显示时间，
            // 否则提示框一闪而过，观感像「闪一下」。
            mHandler.postDelayed(() -> {
                dismissProgressNow();
                andThen.run();
            }, remain);
        } else {
            dismissProgressNow();
            andThen.run();
        }
    }

    private void dismissProgressNow() {
        if (mMiuixHandle != null) {
            try {
                Class.forName(MIUIX_DIALOG_CLASS).getMethod("dismiss", Object.class)
                        .invoke(null, mMiuixHandle);
            } catch (Throwable ignored) {
            }
            mMiuixHandle = null;
        } else if (mProgress != null) {
            mProgress.dismiss();
            mProgress = null;
        }
    }
}
