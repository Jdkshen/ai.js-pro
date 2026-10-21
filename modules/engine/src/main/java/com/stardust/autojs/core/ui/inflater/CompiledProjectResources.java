package com.stardust.autojs.core.ui.inflater;

import android.content.Context;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.util.DisplayMetrics;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 工程模式编译出来的资源包（{@code <工程>/build/project-res.apk}，由 {@code tools/build-project-res.ps1} 生成）。
 *
 * <p><b>为什么是 PC 编译</b>：Google 只发布 x86_64 的 aapt2（linux/windows/osx），
 * <b>没有 arm64/Android 版本</b>（dl.google.com 上 {@code *-linux-arm64.jar} / {@code *-android.jar} 全是 404），
 * 设备上跑不了 aapt2；Android 10+ 也禁止从 app data 目录 exec。所以工程 {@code res/} 在电脑上编好，
 * 产物推到设备后由这里挂进宿主进程。
 *
 * <p><b>怎么挂</b>：新建一个 {@link AssetManager}，先 {@code addAssetPath(app.apk)} 再叠上资源包，
     * 宿主的 {@code 0x7f} 与工程的 {@code 0x7e} 独立包同时可见，避免资源 id 互相覆盖。
 * 这份 {@link Resources} 会交给 UI 模式的宿主 Activity（见 {@code ScriptExecuteActivity.getResources()}），
 * 所以 {@code AnimationUtils.loadAnimation(activity, ui.R.anim.ball)}、
 * {@code resources.getColor(ui.R.color.x)} 这些**只认真 resource id** 的 framework 调用点原生可用。
 *
 * <p>没有这份产物时一切照旧：引擎用自己的名字表 + {@code 0x7e} 段合成 id（能跑布局，但上面那几个调用点不行）。
 */
public class CompiledProjectResources {

    private static final String LOG_TAG = "CompiledProjectRes";
    private static final String SIDECAR_NAME = "project-res.json";
    /** 资源包在资源表里的包名（aapt2 对独立包 id 会命名成 {@code <app 包名>.feature}）。 */
    private static volatile String sResourcePackageName;

    /** 宿主 Activity 读它（{@code ScriptExecuteActivity.getResources()}）。 */
    private static volatile Resources sOverlay;

    /** 宿主 APK 的 {@code resources.arsc} 摘要只算一次（进程内所有工程资源包共用）。 */
    private static String sHostResourcesDigestPath;
    private static long sHostResourcesDigestLength;
    private static long sHostResourcesDigestModified;
    private static String sHostResourcesDigest;

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final Resources mResources;
    private final File mApk;
    /** 本实例对应资源包的包名；并发加载多个工程时各自按自己的包名查 id。 */
    private final String mResourcePackageName;

    private CompiledProjectResources(Resources resources, File apk, String resourcePackageName) {
        mResources = resources;
        mApk = apk;
        mResourcePackageName = resourcePackageName;
    }

    /** 当前已挂载的资源表；没挂载（或已被忽略）时为 {@code null}。 */
    public static Resources overlay() {
        return sOverlay;
    }

    /**
     * 工程资源包在资源表里的包名；没挂载时为 {@code null}。
     *
     * <p>按名字查资源（见 {@link com.stardust.autojs.core.ui.inflater.util.ResIds}）要连它一起试：
     * 工程资源用独立包 id（避免与 app 的 0x7f 撞 id），aapt2 会把它命名成 {@code <app 包名>.feature}。
     */
    public static String resourcePackageName() {
        return sResourcePackageName;
    }

    public Resources getResources() {
        return mResources;
    }

    public File getApk() {
        return mApk;
    }

    /** 本实例资源包的包名（不读全局静态字段，避免并发工程互相串包）。 */
    public String getResourcePackageName() {
        return mResourcePackageName;
    }

    /** 真 resource id；找不到返回 0。 */
    public int identifier(String type, String name) {
        try {
            return mResources.getIdentifier(name, type, mResourcePackageName);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 加载并挂载资源包。
     *
     * @return 加载成功返回实例；文件不存在、宿主版本不匹配、或反射被拒时返回 {@code null}（调用方回落）
     */
    public static CompiledProjectResources load(Context context, File apk) {
        if (apk == null || !apk.isFile()) {
            return null;
        }
        String appApkPath = context.getApplicationInfo().sourceDir;
        Sidecar sidecar = Sidecar.read(apk);
        if (!sidecar.matchesAppApk(appApkPath)) {
            return null;
        }
        try {
            AssetManager assets = newAssetManager();
            if (assets == null) {
                Log.w(LOG_TAG, "拿不到 AssetManager 实例，忽略工程资源包");
                return null;
            }
            if (!addAssetPath(assets, appApkPath) || !addAssetPath(assets, apk.getAbsolutePath())) {
                Log.w(LOG_TAG, "addAssetPath 失败，忽略工程资源包：" + apk);
                return null;
            }
            Resources appResources = context.getResources();
            DisplayMetrics metrics = appResources.getDisplayMetrics();
            Configuration configuration = appResources.getConfiguration();
            Resources merged = new Resources(assets, metrics, configuration);
            String resourcePackageName = sidecar.resourcePackageName(context.getPackageName());
            CompiledProjectResources compiled = new CompiledProjectResources(merged, apk, resourcePackageName);
            // 静态字段只为兼容旧的调用点（如 ResIds）；实例内的 identifier 用 mResourcePackageName。
            sResourcePackageName = resourcePackageName;
            // 挂给宿主 Activity：之后 activity.getResources() 与 framework 内部的资源查找都能看到工程资源。
            sOverlay = merged;
            Log.i(LOG_TAG, "已挂载工程资源包：" + apk + "（资源包名 " + resourcePackageName + "）");
            return compiled;
        } catch (Throwable e) {
            Log.w(LOG_TAG, "加载工程资源包失败，忽略：" + apk, e);
            return null;
        }
    }

    /** 工具写在资源包旁边的 {@code project-res.json}。 */
    private static final class Sidecar {

        private final String mResourcePackageName;
        private final long mAppApkSize;
        /** 宿主 APK 的 {@code resources.arsc}（未压缩内容）SHA-256；新 sidecar 才有。 */
        private final String mAppResourcesSha256;

        private Sidecar(String resourcePackageName, long appApkSize, String appResourcesSha256) {
            mResourcePackageName = resourcePackageName;
            mAppApkSize = appApkSize;
            String digest = appResourcesSha256 == null ? null : appResourcesSha256.trim().toLowerCase(Locale.US);
            mAppResourcesSha256 = digest == null || digest.isEmpty() ? null : digest;
        }

        static Sidecar read(File apk) {
            File file = new File(apk.getParentFile(), SIDECAR_NAME);
            if (!file.isFile()) {
                return new Sidecar(null, 0, null);
            }
            try {
                JSONObject json = new JSONObject(readText(file));
                return new Sidecar(json.optString("resourcePackageName", null),
                        json.optLong("appApkSize", 0),
                        json.optString("appResourcesSha256", null));
            } catch (Exception e) {
                Log.w(LOG_TAG, "读不了 " + file + "，按默认处理", e);
                return new Sidecar(null, 0, null);
            }
        }

        /**
         * 资源包里的引用是「按编译时那份宿主 APK 的资源 id」写死的（aapt2 的 {@code -I} 导入），
         * 宿主换了版本后 app 侧的资源 id 可能已经漂移 —— 那时继续用会串资源，所以直接忽略并提示重编。
         *
         * <p>新 sidecar 带 {@code appResourcesSha256} 时只比宿主 APK 里的资源表摘要，APK 大小变化
         * （签名/压缩/原生库等与资源 id 无关的差异）不再误判；摘要不存在时退回旧的 appApkSize 比较。
         */
        boolean matchesAppApk(String currentAppApkPath) {
            if (mAppResourcesSha256 != null) {
                String actual;
                try {
                    actual = hostResourcesSha256(currentAppApkPath);
                } catch (Exception e) {
                    Log.w(LOG_TAG, "算不了当前宿主 resources.arsc 的 SHA-256，忽略工程资源包："
                            + currentAppApkPath, e);
                    return false;
                }
                if (mAppResourcesSha256.equals(actual)) {
                    return true;
                }
                Log.w(LOG_TAG, "工程资源包的宿主资源表摘要不匹配（resources.arsc "
                        + mAppResourcesSha256 + " → " + actual
                        + "），已忽略；请重跑资源编译工具"
                        + "（tools/build-project-res.ps1 或 tools/build-bundled-project-res.py）");
                return false;
            }
            if (mAppApkSize <= 0) {
                return true;
            }
            long actual = new File(currentAppApkPath).length();
            if (mAppApkSize == actual) {
                return true;
            }
            Log.w(LOG_TAG, "工程资源包是给另一版宿主编的（app.apk " + mAppApkSize + " → " + actual
                    + "），已忽略；请重跑资源编译工具"
                    + "（tools/build-project-res.ps1 或 tools/build-bundled-project-res.py）");
            return false;
        }

        String resourcePackageName(String appPackage) {
            if (mResourcePackageName != null && !mResourcePackageName.isEmpty()) {
                return mResourcePackageName;
            }
            // 没有 sidecar 时的兼顾猜测：aapt2 对独立包 id 的命名规则。
            return appPackage + ".feature";
        }
    }

    /** 当前宿主 APK 的 {@code resources.arsc} 摘要；同一 APK 在进程内只算一次。 */
    private static String hostResourcesSha256(String appApkPath) throws Exception {
        File appApk = new File(appApkPath);
        long length = appApk.length();
        long modified = appApk.lastModified();
        synchronized (CompiledProjectResources.class) {
            if (sHostResourcesDigest != null
                    && appApkPath.equals(sHostResourcesDigestPath)
                    && length == sHostResourcesDigestLength
                    && modified == sHostResourcesDigestModified) {
                return sHostResourcesDigest;
            }
            String digest = computeResourcesSha256(appApk);
            sHostResourcesDigestPath = appApkPath;
            sHostResourcesDigestLength = length;
            sHostResourcesDigestModified = modified;
            sHostResourcesDigest = digest;
            return digest;
        }
    }

    /** 只读 ZIP 里的 {@code resources.arsc} 条目；ZIP 内容解压后算，等价于资源表本身的摘要。 */
    private static String computeResourcesSha256(File appApk) throws Exception {
        try (ZipFile zip = new ZipFile(appApk)) {
            ZipEntry entry = zip.getEntry("resources.arsc");
            if (entry == null) {
                throw new IOException("APK 里没有 resources.arsc：" + appApk);
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = zip.getInputStream(entry)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return toHex(digest.digest());
        }
    }

    private static String toHex(byte[] bytes) {
        char[] chars = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xff;
            chars[i * 2] = HEX[value >>> 4];
            chars[i * 2 + 1] = HEX[value & 0x0f];
        }
        return new String(chars);
    }

    private static String readText(File file) throws Exception {
        try (java.io.InputStream input = new java.io.FileInputStream(file)) {
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) > 0) {
                output.write(buffer, 0, read);
            }
            return output.toString("UTF-8");
        }
    }

    private static AssetManager newAssetManager() throws Exception {
        try {
            return (AssetManager) AssetManager.class.getDeclaredConstructor().newInstance();
        } catch (NoSuchMethodException e) {
            // 低版本上没有公开的无参构造声明，退回 newInstance()
            return (AssetManager) AssetManager.class.newInstance();
        }
    }

    private static boolean addAssetPath(AssetManager assets, String path) throws Exception {
        Method addAssetPath = AssetManager.class.getDeclaredMethod("addAssetPath", String.class);
        addAssetPath.setAccessible(true);
        Object result = addAssetPath.invoke(assets, path);
        return result instanceof Integer && ((Integer) result) != 0;
    }
}
