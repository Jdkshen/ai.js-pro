package com.jdkshen.aijspro.flutterpoc;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Looper;

import com.jdkshen.aijspro.BuildConfig;
import com.jdkshen.aijspro.Pref;
import com.jdkshen.aijspro.R;
import com.jdkshen.aijspro.network.VersionService;
import com.jdkshen.aijspro.tool.IntentTool;
import com.jdkshen.aijspro.ui.update.UpdateCheckDialog;

import org.json.JSONObject;

import java.io.IOException;

/**
 * 应用信息类桥：检查更新 / 更新源 / 问题反馈 / 关于 / 许可 / Ace 编辑器字号。
 *
 * <p>配套 {@link PocBridge}：这里只返回 {@code data} 部分，参数非法一律抛 {@link IOException}，
 * 由 PocBridge 统一包成 {@code {ok:true,data:...}} / {@code {ok:false,error:<message>}}。
 *
 * <p>方法契约（Flutter 侧 method 名）：
 * <ul>
 *   <li>{@code checkUpdate}：打开原生更新检查（无参）→ {@code true}。</li>
 *   <li>{@code updateSource}：无 {@code url} 参数读当前更新源；带 {@code url} 写入
 *       （{@code ""} 表示清空恢复内置源，非空只接受 http:// 或 https:// 开头）
 *       → {@code {"url":"..."}}（写入后回读的生效值）。</li>
 *   <li>{@code openIssueReport}：浏览器打开仓库 issues 页（无参）→ {@code true}。</li>
 *   <li>{@code openAbout} / {@code openLicenses}：task-177 去 Miuix 后由 Flutter 自己展示
 *       AboutDialog / LicensePage，桥方法保留但只回 {@code true}（兼容旧 AOT，不启动原生页）。</li>
 *   <li>{@code editorSettings}：无 {@code textSize} 参数读字号；带 {@code textSize}
 *       （8..40，JSON number 即可）写入并回读 → {@code {"textSize":14.0}}。</li>
 * </ul>
 *
 * <p>task-177「去掉 Miuix，统一 Flutter 界面」：Miuix 的关于/许可两个 Activity 已从构建
 * 排除，这里不再持有它们的类名；关于/许可的展示移到 Flutter 设置页。
 */
final class PocAppInfo {

    /** 与 {@code ProCodeEditorActivity} 读写的是同一份偏好：文件名 + 键名必须逐字一致。 */
    private static final String EDITOR_PREFS = "pro_code_editor";
    private static final String PREF_TEXT_SIZE = "text_size_sp";
    private static final float DEFAULT_TEXT_SIZE_SP = 14f;
    private static final float MIN_TEXT_SIZE_SP = 8f;
    private static final float MAX_TEXT_SIZE_SP = 40f;

    private PocAppInfo() {
    }

    static Object execute(Activity activity, String method, JSONObject args) throws Exception {
        switch (method) {
            case "checkUpdate":
                return checkUpdate(activity);
            case "updateSource":
                return updateSource(activity, args);
            case "openIssueReport":
                return openIssueReport(activity);
            case "openAbout":
            case "openLicenses":
                // Flutter 设置页自己展示 AboutDialog / LicensePage（task-177 去 Miuix）。
                return Boolean.TRUE;
            case "aboutInfo":
                // 关于页展示用：真实安装版本 + 作者 + 最近一次构建对应的 git 提交。
                return aboutInfo(activity);
            case "editorSettings":
                return editorSettings(activity, args);
            default:
                throw new UnsupportedOperationException("POC 尚未实现应用信息桥方法：" + method);
        }
    }

    /**
     * 打开原生「检查更新」：UpdateCheckDialog 内部自己起 RxJava 后台请求、回主线程弹结果，
     * 所以这里只负责保证在 UI 线程 new + show，并立即返回。
     */
    private static Object checkUpdate(Activity activity) {
        Runnable show = () -> new UpdateCheckDialog(activity).show();
        if (Looper.myLooper() == Looper.getMainLooper()) {
            show.run();
        } else {
            activity.runOnUiThread(show);
        }
        return Boolean.TRUE;
    }

    /**
     * 读写自建更新源（{@code update.json} 的网址）。
     *
     * <p>键与读法都复用 {@link VersionService#updateSourceUrl(Context)}：读出来的是 trim 过的值，
     * 未配置时为空串（= 内置 GitHub 加速源）。写入前 trim；空串表示清空；
     * 校验规则与 MiuixSettingsActivity 的设置弹窗一致（只允许 http(s)）。
     */
    private static Object updateSource(Activity activity, JSONObject args) throws Exception {
        if (args.has("url") && !args.isNull("url")) {
            String url = args.optString("url", "").trim();
            if (!url.isEmpty() && !url.startsWith("http://") && !url.startsWith("https://")) {
                throw new IOException("更新源需要 http(s) 地址");
            }
            Pref.setPrefString(activity.getString(R.string.key_update_source_url), url);
        }
        return new JSONObject().put("url", VersionService.updateSourceUrl(activity));
    }

    /** 问题反馈：用系统浏览器打开仓库 issues 页（my_github + "/issues"）。 */
    private static Object openIssueReport(Activity activity) {
        IntentTool.browse(activity, activity.getString(R.string.my_github) + "/issues");
        return Boolean.TRUE;
    }

    /**
     * Ace 编辑器字号：{@code ProCodeEditorActivity} 真正读取的偏好
     * （SharedPreferences {@value #EDITOR_PREFS} 的 {@value #PREF_TEXT_SIZE}，float）。
     *
     * <p>无 textSize 参数 → 读当前值；带 textSize → 校验 8..40 后写入并回读。
     * 之后打开/重启原生工作区即按新字号显示（正在打开的编辑器需重进生效）。
     */
    private static Object editorSettings(Activity activity, JSONObject args) throws Exception {
        SharedPreferences prefs = activity.getSharedPreferences(EDITOR_PREFS, Context.MODE_PRIVATE);
        if (args.has("textSize") && !args.isNull("textSize")) {
            double textSize = textSizeArg(args);
            if (Double.isNaN(textSize)
                    || textSize < MIN_TEXT_SIZE_SP || textSize > MAX_TEXT_SIZE_SP) {
                throw new IOException("编辑器字号需在 8-40 之间");
            }
            prefs.edit().putFloat(PREF_TEXT_SIZE, (float) textSize).apply();
        }
        return new JSONObject()
                .put("textSize", (double) prefs.getFloat(PREF_TEXT_SIZE, DEFAULT_TEXT_SIZE_SP));
    }

    /** 字号参数：JSON number（double/float/int 都走 Number），兼容数字字符串。 */
    private static double textSizeArg(JSONObject args) throws IOException {
        Object raw = args.opt("textSize");
        if (raw instanceof Number) {
            return ((Number) raw).doubleValue();
        }
        if (raw instanceof String) {
            try {
                return Double.parseDouble(((String) raw).trim());
            } catch (NumberFormatException ignored) {
                // 落到下面的统一报错
            }
        }
        throw new IOException("编辑器字号需在 8-40 之间");
    }

    /**
     * 关于页展示数据：真实安装版本 + 作者 + 本次构建对应的 git 提交。
     *
     * <p>git 信息由 {@code apps/app/build.gradle} 在构建时注入 {@link BuildConfig}；
     * 无 git 环境时为 "unknown"，Flutter 侧按空/unknown 降级显示。
     */
    private static Object aboutInfo(Activity activity) throws Exception {
        JSONObject info = new JSONObject();
        try {
            PackageInfo packageInfo = activity.getPackageManager()
                    .getPackageInfo(activity.getPackageName(), 0);
            info.put("versionName", packageInfo.versionName == null ? "" : packageInfo.versionName);
            info.put("versionCode", packageInfo.versionCode);
        } catch (Exception e) {
            info.put("versionName", "");
            info.put("versionCode", 0);
        }
        info.put("author", "Jdkshen");
        info.put("gitCommit", BuildConfig.GIT_COMMIT);
        info.put("gitAuthor", BuildConfig.GIT_AUTHOR);
        info.put("gitDate", BuildConfig.GIT_DATE);
        info.put("gitSubject", BuildConfig.GIT_SUBJECT);
        info.put("flutterUi", true);
        return info;
    }
}
