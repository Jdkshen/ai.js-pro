package com.jdkshen.aijspro.packaging;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.graphics.Bitmap;

import androidx.test.platform.app.InstrumentationRegistry;

import com.jdkshen.aijspro.autojs.build.ApkBuilder;
import com.jdkshen.aijspro.build.ApkBuilderPluginHelper;
import com.stardust.autojs.engine.encryption.ScriptEncryption;
import com.stardust.autojs.script.EncryptedScriptFileHeader;
import com.stardust.util.MD5;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 真机端到端验证（instrumentation）：在应用进程里用真实的 ApkBuilder 打包一个脚本，
 * 断言工作区中的入口脚本已经按约定加密（8 字节文件头 + AES/CBC/PKCS5 密文），
 * 且能用 inrt 运行时相同的密钥派生方式解密还原。
 */
public class ApkBuilderEncryptionTest {

    @Test
    public void packagedWorkspaceContainsEncryptedEntryScript() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

        File workDir = new File(context.getCacheDir(), "apk-builder-encryption-test");
        deleteRecursively(workDir);
        File workspace = new File(workDir, "workspace");
        File outApk = new File(workDir, "out.apk");
        File script = new File(workDir, "probe.js");
        //noinspection ResultOfMethodCallIgnored
        workDir.mkdirs();
        writeText(script, "console.log('encryption probe');\nvar v = 1 + 1;\n");

        InputStream template = ApkBuilderPluginHelper.openTemplateApk(context);
        File iconFile = new File(workDir, "icon.png");
        writePng(iconFile, 0xFF3366FF);
        File splashFile = new File(workDir, "splash-icon.png");
        writePng(splashFile, 0xFF993366);

        ApkBuilder.AppConfig config = new ApkBuilder.AppConfig()
                .setAppName("EncryptionProbe")
                .setPackageName("com.example.encryptionprobe")
                .setVersionName("1.0.0")
                .setVersionCode(1)
                .setSourcePath(script.getAbsolutePath())
                .setIcon(iconFile.getAbsolutePath())
                // 打包页的“权限配置”：一个新增、一个从模板移除，其余保持默认。
                .setPermissionsToAdd(Arrays.asList("android.permission.CAMERA"))
                .setPermissionsToRemove(Arrays.asList("android.permission.WRITE_SECURE_SETTINGS"))
                // 打包页的“运行配置”。
                .setHideLogs(true)
                .setShowSplash(true)
                .setSplashText("打包测试")
                .setSplashIcon(splashFile.getAbsolutePath())
                // 打包页的“启动时自动申请权限”。
                .setRequestPermissions(Arrays.asList(
                        "android.permission.WRITE_EXTERNAL_STORAGE",
                        "android.permission.CAMERA"))
                // 打包页的“特性”：引擎 + 先掉无障碍与图色模块。
                .setEngine("quickjs")
                .setIncludeAccessibility(false)
                .setIncludeImageModule(false);
        // sign() repackages the workspace into out.apk, so it has to run before the
        // packaged artifact can be parsed below.
        new ApkBuilder(template, outApk, workspace.getPath())
                .prepare()
                .withConfig(config)
                .build()
                .sign();

        // 0) APK 身份（包名/版本/authorities）必须来自 AppConfig，不能保留模板身份。
        //    GET_PERMISSIONS 才会填充 requestedPermissions（权限配置断言依赖它）。
        PackageInfo archiveInfo = context.getPackageManager()
                .getPackageArchiveInfo(outApk.getPath(),
                        PackageManager.GET_PROVIDERS | PackageManager.GET_PERMISSIONS
                                | PackageManager.GET_SERVICES);
        assertNotNull("packaged apk should be parseable", archiveInfo);
        assertEquals("com.example.encryptionprobe", archiveInfo.packageName);
        assertEquals("1.0.0", archiveInfo.versionName);
        assertEquals(1L, archiveInfo.getLongVersionCode());
        assertNotNull(archiveInfo.providers);
        for (ProviderInfo provider : archiveInfo.providers) {
            assertNotNull(provider.authority);
            assertFalse("provider authority must follow the packaged package name, got "
                            + provider.authority,
                    provider.authority.contains("com.jdkshen.aijspro.inrt"));
        }

        // 1) project.json 应与打包身份同步，并带新的 buildInfo。
        File jsonFile = new File(workspace, "assets/project/project.json");
        assertTrue("project.json should exist", jsonFile.exists());
        JSONObject json = new JSONObject(readText(jsonFile));
        assertEquals("EncryptionProbe", json.getString("name"));
        assertEquals("com.example.encryptionprobe", json.getString("packageName"));
        assertEquals("1.0.0", json.getString("versionName"));
        assertEquals("main.js", json.getString("main"));
        String buildId = json.getJSONObject("build").getString("build_id");
        assertNotNull(buildId);

        // 2) 入口脚本必须是“加密头 + 密文”，不能再是明文。
        File mainFile = new File(workspace, "assets/project/main.js");
        byte[] packaged = readBytes(mainFile);
        assertTrue("entry script must carry the encryption header",
                EncryptedScriptFileHeader.INSTANCE.isValidFile(packaged));
        assertTrue("entry script must contain cipher text",
                packaged.length > EncryptedScriptFileHeader.BLOCK_SIZE);

        // 3) 用运行时相同的派生方式解密，内容应与原脚本一致。
        String key = MD5.md5(json.getString("packageName") + json.getString("versionName")
                + json.getString("main"));
        String vector = MD5.md5(buildId + json.getString("name")).substring(0, 16);
        Field keyField = ScriptEncryption.class.getDeclaredField("mKey");
        keyField.setAccessible(true);
        keyField.set(null, key);
        Field vectorField = ScriptEncryption.class.getDeclaredField("mInitVector");
        vectorField.setAccessible(true);
        vectorField.set(null, vector);
        byte[] decrypted = ScriptEncryption.INSTANCE.decrypt(
                packaged, EncryptedScriptFileHeader.BLOCK_SIZE, packaged.length);
        assertEquals(readText(script), new String(decrypted, "UTF-8"));

        // 4) 权限配置：新增的权限要进产物清单，取消的模板权限要被移除。
        List<String> requestedPermissions = archiveInfo.requestedPermissions == null
                ? new ArrayList<String>()
                : Arrays.asList(archiveInfo.requestedPermissions);
        assertTrue("CAMERA should be declared, got " + requestedPermissions,
                requestedPermissions.contains("android.permission.CAMERA"));
        assertFalse("WRITE_SECURE_SETTINGS should be removed, got " + requestedPermissions,
                requestedPermissions.contains("android.permission.WRITE_SECURE_SETTINGS"));
        assertTrue("untouched template permissions must survive, got " + requestedPermissions,
                requestedPermissions.contains("android.permission.INTERNET"));

        // 5) 运行配置：inrt 启动器读回这个 launchConfig 决定日志界面与启动屏。
        JSONObject launchConfig = json.getJSONObject("launchConfig");
        assertTrue("hideLogs should be persisted", launchConfig.getBoolean("hideLogs"));
        assertTrue("showSplash should be persisted", launchConfig.getBoolean("showSplash"));
        assertEquals("打包测试", launchConfig.getString("splashText"));
        // 启动时自动申请权限：inrt SplashActivity 读回该列表发请求，不能多也不能少。
        JSONArray requestPermissions = launchConfig.getJSONArray("requestPermissions");
        assertEquals(2, requestPermissions.length());
        assertEquals("android.permission.WRITE_EXTERNAL_STORAGE",
                requestPermissions.getString(0));
        assertEquals("android.permission.CAMERA", requestPermissions.getString(1));

        // 6) 启动界面图片应作为资源写进 assets/project/splash.png。
        assertArrayEquals(readBytes(splashFile),
                readBytes(new File(workspace, "assets/project/splash.png")));

        // 7) 图标替换：产物里真正的 launcher icon 必须是用户选的图（旧实现写死了
        //    res/mipmap-mdpi/ic_launcher.png，在资源名被缩短的模板上根本不生效）。
        byte[] packagedIcon = readZipEntry(outApk, "ic_launcher", ".png");
        assertNotNull("packaged apk should contain a launcher icon", packagedIcon);
        assertArrayEquals("launcher icon must be the user icon",
                readBytes(iconFile), packagedIcon);

        // 8) 特性开关：引擎写入 project.json，无障碍服务从清单移除，图库从产物删除。
        assertEquals("quickjs", json.getString("engine"));
        assertFalse("accessibility service must be dropped from the manifest",
                hasService(archiveInfo, "com.stardust.autojs.core.accessibility.AccessibilityService"));
        assertTrue("unrelated services must stay",
                hasService(archiveInfo, "com.stardust.notification.NotificationListenerService"));
        assertNull("opencv libraries must be removed",
                readZipEntry(outApk, "libopencv_", ".so"));
        assertNotNull("quickjs libraries must stay",
                readZipEntry(outApk, "libquickjs", ".so"));
    }

    private static boolean hasService(PackageInfo info, String name) {
        if (info.services == null) {
            return false;
        }
        for (android.content.pm.ServiceInfo service : info.services) {
            if (name.equals(service.name)) {
                return true;
            }
        }
        return false;
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

    /** First zip entry whose name contains {@code contains} and ends with {@code suffix}. */
    private static byte[] readZipEntry(File zipFile, String contains, String suffix) throws Exception {
        ZipFile zip = new ZipFile(zipFile);
        try {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.contains(contains) && name.endsWith(suffix)) {
                    return readStream(zip.getInputStream(entry));
                }
            }
        } finally {
            zip.close();
        }
        return null;
    }

    private static byte[] readStream(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
            }
        } finally {
            in.close();
        }
        return out.toByteArray();
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

    private static void writeText(File file, String text) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        FileOutputStream fos = new FileOutputStream(file);
        try {
            fos.write(text.getBytes("UTF-8"));
        } finally {
            fos.close();
        }
    }

    private static String readText(File file) throws Exception {
        return new String(readBytes(file), "UTF-8");
    }

    private static byte[] readBytes(File file) throws Exception {
        byte[] buffer = new byte[(int) file.length()];
        java.io.FileInputStream fis = new java.io.FileInputStream(file);
        try {
            int offset = 0;
            while (offset < buffer.length) {
                int read = fis.read(buffer, offset, buffer.length - offset);
                if (read < 0) {
                    break;
                }
                offset += read;
            }
        } finally {
            fis.close();
        }
        return buffer;
    }
}
