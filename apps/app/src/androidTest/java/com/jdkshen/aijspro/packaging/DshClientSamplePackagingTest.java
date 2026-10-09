package com.jdkshen.aijspro.packaging;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Environment;

import androidx.test.platform.app.InstrumentationRegistry;

import com.jdkshen.aijspro.Pref;
import com.jdkshen.aijspro.R;
import com.jdkshen.aijspro.autojs.build.ApkBuilder;
import com.jdkshen.aijspro.build.ApkBuilderPluginHelper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 真机端到端：把内置示例「dsh 手机客户端」（QuickJS + ui + 全屏 WebView 外壳）
 * 打成独立 APK，断言产物可被系统解析、身份与权限正确，并导出到应用私有目录
 * （{@code adb exec-out run-as com.jdkshen.aijspro cat files/dsh-phone-client-quickjs.apk}
 * 拉出来做最终装机验证）。
 *
 * <p>打包走的是真实 ApkBuilder + 「APK 打包插件」里的运行时模板，和打包页同一条链路；
 * 不依赖网络，只验证「示例还能被正确打成 App」这条回归。</p>
 */
public class DshClientSamplePackagingTest {

    private static final String SAMPLE_ASSET_DIR = "sample/QuickJS 新引擎/项目与打包/dsh 手机客户端";
    private static final String OUT_APK_NAME = "dsh-phone-client-quickjs.apk";
    private static final String PACKAGE_NAME = "com.dsh.aijsclient.quickjs";

    private static final String TEST_SCRIPT_DIR_NAME = "aijspro-test-scripts";

    /**
     * 产物期望声明的权限：三个业务权限 + {@code POST_NOTIFICATIONS}。
     * 最后一个是 AndroidX 库清单合并注入的，打包器既不主动添加也无法移除，
     * {@code targetSdk 28} 下系统不会弹通知授权，故按现状固定，避免「数字对不上」的假失败。
     */
    private static final List<String> EXPECTED_PERMISSIONS = java.util.Collections.unmodifiableList(
            Arrays.asList(
                    "android.permission.INTERNET",
                    "android.permission.ACCESS_NETWORK_STATE",
                    "android.permission.CAMERA",
                    "android.permission.POST_NOTIFICATIONS"));

    private static boolean scriptDirIsolated = false;
    private static String previousScriptDir = null;

    /**
     * 把脚本目录指到临时位置：签名身份就在 {@code <脚本目录>/.keyStore/} 里，
     * 不隔离的话测试会往用户真实的脚本目录里写东西（与原打包用例保持一致）。
     */
    private static void isolateScriptDir(Context context) {
        grantStoragePermissions(context);
        String key = context.getString(R.string.key_script_dir_path);
        if (!scriptDirIsolated) {
            previousScriptDir = Pref.getPrefString(key, null);
            scriptDirIsolated = true;
        }
        Pref.setPrefString(key, TEST_SCRIPT_DIR_NAME);
    }

    private static void grantStoragePermissions(Context context) {
        android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        String packageName = context.getPackageName();
        String[] permissions = {
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        };
        for (String permission : permissions) {
            try {
                instrumentation.getUiAutomation().grantRuntimePermission(packageName, permission);
            } catch (Exception ignored) {
                // 已授权或系统拒绝时忽略：真出问题后面的断言会给出更具体的原因。
            }
        }
    }

    private static File testScriptDir() {
        return new File(Environment.getExternalStorageDirectory(), TEST_SCRIPT_DIR_NAME);
    }

    /**
     * 打包模板（~43MB）现在随「APK 打包插件」分发，主程序不再带 assets/template.apk。
     * 测试机装了插件、或手动放了 /sdcard/template.apk 才跑；否则跳过并说明原因。
     */
    @Before
    public void requirePackagingTemplate() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        org.junit.Assume.assumeTrue(
                "打包模板不在位：安装 apkbuilder-plugin，或把 template.apk 放到 "
                        + ApkBuilderPluginHelper.manualTemplateFile().getPath(),
                ApkBuilderPluginHelper.isTemplateAvailable(context));
    }

    @After
    public void restoreScriptDir() {
        if (!scriptDirIsolated) {
            return;
        }
        Pref.setPrefString(InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getString(R.string.key_script_dir_path), previousScriptDir);
        scriptDirIsolated = false;
        previousScriptDir = null;
        deleteRecursively(testScriptDir());
    }

    @Test
    public void bundledDshClientSamplePackagesIntoAnInstallableApk() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        isolateScriptDir(context);

        File workDir = new File(context.getCacheDir(), "dsh-client-packaging");
        deleteRecursively(workDir);
        File projectDir = new File(workDir, "project");
        File workspace = new File(workDir, "workspace");
        File outApk = new File(workDir, OUT_APK_NAME);

        copyAssetTree(context, SAMPLE_ASSET_DIR, projectDir);

        File appIcon = new File(projectDir, "icon.png");
        if (!appIcon.isFile()) {
            appIcon = new File(workDir, "icon.png");
            writePng(appIcon, 0xFF1F6FEB);
        }

        ApkBuilder.AppConfig config = new ApkBuilder.AppConfig()
                .setAppName("dsh 手机客户端")
                .setPackageName(PACKAGE_NAME)
                .setVersionName("1.1.0")
                .setVersionCode(3)
                // 工程目录：name/packageName/main/engine/launchConfig 等来自示例的 project.json
                .setSourcePath(projectDir.getAbsolutePath())
                .setIcon(appIcon.getAbsolutePath())
                .setEngine("quickjs")
                .setHideLogs(true)
                .setShowSplash(true)
                .setSplashText("dsh 手机客户端")
                .setSplashIcon(appIcon.getAbsolutePath())
                // 网络客户端只需要联网权限；扫码功能需要相机；去掉无障碍；QuickJS JNI 依赖 OpenCV，保留图色库
                .setPermissionsToAdd(Arrays.asList(
                        "android.permission.INTERNET",
                        "android.permission.ACCESS_NETWORK_STATE",
                        "android.permission.CAMERA"))
                .setIncludeAccessibility(false)
                .setIncludeImageModule(true);

        // 按本项目的权限配置移除模板里其他脚本功能所需的权限。
        File templateSnapshot = new File(workDir, "template.apk");
        try (InputStream input = ApkBuilderPluginHelper.openTemplateApk(context)) {
            java.nio.file.Files.copy(input, templateSnapshot.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        PackageInfo templateInfo = context.getPackageManager().getPackageArchiveInfo(
                templateSnapshot.getPath(), PackageManager.GET_PERMISSIONS);
        assertNotNull(templateInfo);
        java.util.ArrayList<String> unwanted = new java.util.ArrayList<>();
        if (templateInfo.requestedPermissions != null) {
            unwanted.addAll(Arrays.asList(templateInfo.requestedPermissions));
        }
        unwanted.removeAll(Arrays.asList("android.permission.INTERNET",
                "android.permission.ACCESS_NETWORK_STATE", "android.permission.CAMERA"));
        config.setPermissionsToRemove(unwanted);

        ApkBuilder builder = new ApkBuilder(ApkBuilderPluginHelper.openTemplateApk(context),
                outApk, workspace.getPath())
                .prepare()
                .withConfig(config)
                .build()
                .sign();

        assertTrue("产物 APK 应当存在: " + outApk,
                outApk.isFile() && outApk.length() > 1_000_000L);

        // 先把产物导出到应用私有目录，再做权限断言。
        // 放在断言之前，是为了在断言失败时产物依然留在设备上可被
        // `adb exec-out run-as ... cat files/...` 拉出来核对，而不是随工作区一起被清掉。
        copyToPrivateDir(context, outApk, OUT_APK_NAME);
        // 再额外放到外部存储：测试收尾会卸载被测应用，私有目录会随之消失，
        // 而装机验证只需要一个能 `adb pull` 到的文件。
        copyToPublicDownloads(context, outApk, OUT_APK_NAME);

        PackageInfo archive = context.getPackageManager().getPackageArchiveInfo(
                outApk.getPath(), PackageManager.GET_PERMISSIONS | PackageManager.GET_ACTIVITIES);
        assertNotNull("产物应当能被系统解析", archive);
        assertEquals(PACKAGE_NAME, archive.packageName);
        assertEquals("1.1.0", archive.versionName);
        assertEquals(3, archive.versionCode);
        List<String> requested = archive.requestedPermissions == null
                ? java.util.Collections.<String>emptyList()
                : Arrays.asList(archive.requestedPermissions);
        assertTrue("产物必须声明 INTERNET: " + requested,
                requested.contains("android.permission.INTERNET"));
        assertTrue("扫码需要 CAMERA 权限: " + requested,
                requested.contains("android.permission.CAMERA"));

        assertTrue("扫码必须声明 CAMERA", requested.contains("android.permission.CAMERA"));
        // 期望 4 个：三个业务权限 + POST_NOTIFICATIONS。
        // 后者是 AndroidX 库清单合并自动注入的（targetSdk 28 下不生效、无运行时风险），
        // 打包器不主动添加权限，也无法把它去掉，因此断言按真实产物固定下来。
        // 断言里带上真实清单：模板或库清单合并进来的权限会让「不多不少正好 3 个」失效，
        // 失败时必须能一眼看出多出来的是哪一个，而不是只看到一个数字。
        assertTrue("产物必须只声明预期权限，实际: " + requested,
                requested.containsAll(EXPECTED_PERMISSIONS) && requested.size() == EXPECTED_PERMISSIONS.size());
        try (java.util.zip.ZipFile apk = new java.util.zip.ZipFile(outApk)) {
            assertNotNull("扫码识别库必须随项目进入 APK",
                    apk.getEntry("assets/project/client-runtime.jar"));
            assertNotNull("QuickJS 界面 XML 必须随项目进入 APK",
                    apk.getEntry("assets/project/layout.xml"));
            try (InputStream json = apk.getInputStream(apk.getEntry("assets/project/project.json"))) {
                org.json.JSONObject project = new org.json.JSONObject(
                        new String(readAll(json), java.nio.charset.StandardCharsets.UTF_8));
                assertEquals("quickjs", project.getString("engine"));
                assertEquals("quickjs", project.getJSONObject("scripts")
                        .getJSONObject("main.js").getString("engine"));
                assertTrue("QuickJS JNI 依赖 OpenCV，必须保留",
                        !project.getJSONObject("optimization").getBoolean("removeOpenCv"));
            }
        }
        // 入口脚本与工程配置进了产物
        File packagedJson = new File(workspace, "assets/project/project.json");
        File packagedMain = new File(workspace, "assets/project/main.js");
        assertTrue("产物里应当有 project.json", packagedJson.isFile());
        assertTrue("产物里应当有入口脚本", packagedMain.isFile());

        builder.cleanWorkspace();
    }

    private static byte[] readAll(InputStream input) throws Exception {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] block = new byte[4096];
        int count;
        while ((count = input.read(block)) != -1) buffer.write(block, 0, count);
        return buffer.toByteArray();
    }

    /**
     * 把打包产物写到应用私有目录，方便用
     * {@code adb exec-out run-as com.jdkshen.aijspro cat files/xxx.apk} 拉出去安装验证。
     */
    private void copyToPrivateDir(Context context, File apk, String name) throws Exception {
        File target = new File(context.getFilesDir(), name);
        FileOutputStream out = new FileOutputStream(target);
        try {
            InputStream in = new FileInputStream(apk);
            try {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                }
            } finally {
                in.close();
            }
        } finally {
            out.close();
        }
        assertEquals("导出的产物大小应当与工作区一致", apk.length(), target.length());
    }

    /**
     * 再导出一份到外部存储的 {@code Download/}：测试收尾会卸载被测应用，
     * 应用私有目录随之消失，而装机只需要一个 {@code adb pull} 得到的文件。
     * 写失败不影响测试结论（私有目录那份仍在），只是少一条取件通道。
     */
    private void copyToPublicDownloads(Context context, File apk, String name) {
        try {
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (dir != null && (dir.isDirectory() || dir.mkdirs())) {
                FileOutputStream out = new FileOutputStream(new File(dir, name));
                try {
                    InputStream in = new FileInputStream(apk);
                    try {
                        byte[] buffer = new byte[16 * 1024];
                        int read;
                        while ((read = in.read(buffer)) > 0) {
                            out.write(buffer, 0, read);
                        }
                    } finally {
                        in.close();
                    }
                } finally {
                    out.close();
                }
            }
        } catch (Exception ignored) {
            // 外部存储不可写时忽略：私有目录已有一份，不影响打包结论。
        }
    }

    /** 把 assets 下的一棵小目录树复制到磁盘（示例目录名含空格与中文，逐层 list）。 */
    private static void copyAssetTree(Context context, String assetDir, File target)
            throws Exception {
        String[] names = context.getAssets().list(assetDir);
        assertNotNull(names);
        assertTrue("示例目录应当存在: " + assetDir, names.length > 0);
        assertTrue(target.isDirectory() || target.mkdirs());
        for (String name : names) {
            String assetPath = assetDir + "/" + name;
            File targetFile = new File(target, name);
            try {
                InputStream in = context.getAssets().open(assetPath);
                try {
                    FileOutputStream out = new FileOutputStream(targetFile);
                    try {
                        byte[] buffer = new byte[16 * 1024];
                        int read;
                        while ((read = in.read(buffer)) != -1) {
                            out.write(buffer, 0, read);
                        }
                    } finally {
                        out.close();
                    }
                } finally {
                    in.close();
                }
            } catch (java.io.IOException notAFile) {
                copyAssetTree(context, assetPath, targetFile);
            }
        }
    }

    private static void writePng(File file, int color) throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(color);
        FileOutputStream out = new FileOutputStream(file);
        try {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
        } finally {
            out.close();
        }
    }

    private static void deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
