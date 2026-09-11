package com.jdkshen.aijspro.packaging;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.graphics.Bitmap;
import android.os.Environment;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import com.jdkshen.aijspro.Pref;
import com.jdkshen.aijspro.R;
import com.jdkshen.aijspro.autojs.build.ApkBuilder;
import com.jdkshen.aijspro.autojs.build.sign.ApkSignatureReader;
import com.jdkshen.aijspro.autojs.build.sign.AutoSigningIdentity;
import com.jdkshen.aijspro.autojs.build.sign.KeyStoreApkSigner;
import com.jdkshen.aijspro.autojs.build.sign.KeyStoreGenerator;
import com.jdkshen.aijspro.autojs.build.sign.SigningKey;
import com.jdkshen.aijspro.autojs.build.sign.SigningOptions;
import com.jdkshen.aijspro.build.ApkBuilderPluginHelper;
import com.stardust.autojs.apkbuilder.Signer;
import com.stardust.autojs.engine.encryption.ScriptEncryption;
import com.stardust.autojs.project.ScriptProtection;
import com.stardust.autojs.rhino.AndroidClassLoader;
import com.stardust.autojs.script.CompiledScriptPayload;
import com.stardust.autojs.script.EncryptedScriptFileHeader;
import com.stardust.util.MD5;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 真机端到端验证（instrumentation）：在应用进程里用真实的 ApkBuilder 打包一个脚本，
 * 断言工作区中的入口脚本已经按约定加密（8 字节文件头 + AES/CBC/PKCS5 密文），
 * 且能用 inrt 运行时相同的密钥派生方式解密还原。
 */
public class ApkBuilderEncryptionTest {

    /** tiny-sign 内置测试证书（CN=Test）的 SHA-256：全世界共用，绝不能再出现在默认产物里。 */
    private static final String SHARED_TEST_CERTIFICATE_SHA256 =
            "e1f6edb5a65a79e69f9f41a44b3b5d5bb95954b34d320a52d3f7b63e647b0a43";

    private static final String TEST_SCRIPT_DIR_NAME = "aijspro-test-scripts";

    private static boolean scriptDirIsolated = false;
    private static String previousScriptDir = null;

    /**
     * 把脚本目录指到临时位置。签名身份就在 `<脚本目录>/.keyStore/` 里，
     * 不隔离的话测试会往用户真实的脚本目录里写东西。
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

    /**
     * 测试脚本目录在外部存储上，而重新安装会把运行时权限重置成未授权，
     * 于是打包测试会以 ENOENT（建不出目录）的形式集体失败。
     * 用 UiAutomation 直接授权，让整套用例自己就能跑通。
     */
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

    @After
    public void restoreScriptDir() {
        if (!scriptDirIsolated) return;
        Pref.setPrefString(InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getString(R.string.key_script_dir_path), previousScriptDir);
        scriptDirIsolated = false;
        previousScriptDir = null;
        deleteRecursively(testScriptDir());
    }

    @Test
    public void packagedWorkspaceContainsEncryptedEntryScript() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        isolateScriptDir(context);

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

    /**
     * 自定义签名：默认产物用的是 tiny-sign 内嵌的公共测试证书（全世界共用一份身份），
     * 选了签名后必须换成开发者自己的证书，而且平台要能验通 v1 + v2 两套签名。
     */
    @Test
    public void packagedApkUsesTheProvidedSigningKey() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

        File workDir = new File(context.getCacheDir(), "apk-builder-signing-test");
        deleteRecursively(workDir);
        File workspace = new File(workDir, "workspace");
        File outApk = new File(workDir, "signed.apk");
        File script = new File(workDir, "probe.js");
        File keyStore = new File(workDir, "identity.p12");
        //noinspection ResultOfMethodCallIgnored
        workDir.mkdirs();
        writeText(script, "console.log('signing probe');\n");

        // 生成密钥 → 存成 PKCS#12 → 再按 UI 的路径读回来，把整条链路都跑一遍。
        String password = KeyStoreGenerator.randomPassword();
        SigningKey generated = KeyStoreGenerator.generate("SigningProbe", "AI.js Pro", "CN");
        KeyStoreGenerator.save(generated, keyStore, password.toCharArray(), "aijspro");
        assertTrue("keystore must be written", keyStore.length() > 0);
        SigningKey key = SigningKey.load(keyStore, null, password.toCharArray(), null,
                password.toCharArray());
        assertEquals("reloaded key must be the generated one",
                generated.getCertificateFingerprint(), key.getCertificateFingerprint());

        ApkBuilder.AppConfig config = new ApkBuilder.AppConfig()
                .setAppName("SigningProbe")
                .setPackageName("com.example.signingprobe")
                .setVersionName("1.0.0")
                .setVersionCode(1)
                .setSourcePath(script.getAbsolutePath())
                .setEngine("rhino")
                .setIncludeAccessibility(false)
                .setIncludeImageModule(false)
                .setSigner(new KeyStoreApkSigner(key, "aijspro"));

        new ApkBuilder(ApkBuilderPluginHelper.openTemplateApk(context), outApk, workspace.getPath())
                .prepare()
                .withConfig(config)
                .build()
                .sign()
                .cleanWorkspace();

        // 1) 平台自己的校验器必须接受这个包，而且报出来的签名者就是我们的证书。
        PackageInfo info = context.getPackageManager()
                .getPackageArchiveInfo(outApk.getPath(), PackageManager.GET_SIGNING_CERTIFICATES);
        assertNotNull("platform must accept the custom signed apk", info);
        android.content.pm.Signature[] signers = signersOf(info);
        assertNotNull("signature info should be available", signers);
        assertEquals("exactly one signer expected", 1, signers.length);
        assertEquals("signer certificate must be the provided one",
                generated.getCertificateFingerprint(), sha256Fingerprint(signers[0].toByteArray()));

        // 2) v1（JAR）签名：JarFile 在 verify 模式会校验 MANIFEST.MF / CERT.SF / CERT.RSA
        //    的一致性；读完条目才能拿到证书，缺签名或摘要对不上都会在这里露出来。
        JarFile jar = new JarFile(outApk, true);
        try {
            Enumeration<? extends ZipEntry> entries = jar.entries();
            byte[] buffer = new byte[1 << 16];
            int checked = 0;
            while (entries.hasMoreElements()) {
                JarEntry entry = (JarEntry) entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || name.startsWith("META-INF/")) {
                    continue;
                }
                InputStream in = jar.getInputStream(entry);
                try {
                    while (in.read(buffer) != -1) {
                        // 读取全部内容以完成条目校验
                    }
                } finally {
                    in.close();
                }
                Certificate[] certificates = entry.getCertificates();
                assertNotNull("entry " + name + " must be signed", certificates);
                assertEquals("entry " + name + " must carry our certificate",
                        generated.getCertificate(), certificates[0]);
                checked++;
            }
            assertTrue("expected a fully signed apk, checked " + checked, checked > 100);
        } finally {
            jar.close();
        }

        // 3) v2 签名块必须插在「中央目录」前面，并携带同一份证书。
        byte[] apk = readBytes(outApk);
        int blockStart = findSigningBlockStart(apk);
        assertTrue("APK Signing Block must sit in front of the central directory", blockStart > 0);
        assertTrue("v2 block must carry the custom certificate",
                indexOf(apk, generated.getCertificate().getEncoded(), blockStart,
                        centralDirOffset(apk)) >= 0);

        // 4) 打包页要靠 ApkSignatureReader 把「产物到底是谁签的」写在成功提示里，
        //    它读出来的指纹必须和真实证书一致，否则那个提示就是假的。
        ApkSignatureReader.Signer artifactSigner = ApkSignatureReader.INSTANCE.read(outApk);
        assertNotNull("signer must be readable from the built apk", artifactSigner);
        assertEquals("artifact signer must be the generated certificate",
                generated.getCertificateFingerprint().replace(":", "").toLowerCase(Locale.US),
                artifactSigner.getSha256());
    }

    /**
     * 回归：签名模式的判定。
     *
     * 之前这里只判断了「使用已有密钥库」，于是「新建签名」生成出来的密钥库压根没参与打包，
     * 产物依旧是 tiny-sign 的内置公共证书 —— 用户以为换了身份，实际什么都没变，
     * 再去谈“换了证书还报毒”就是无效结论。
     */
    @Test
    public void signingModeDecidesWhetherTheCustomKeyIsUsed() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File workDir = new File(context.getCacheDir(), "apk-builder-signing-mode-test");
        deleteRecursively(workDir);
        //noinspection ResultOfMethodCallIgnored
        workDir.mkdirs();

        File keyStore = new File(workDir, "identity.p12");
        String password = KeyStoreGenerator.randomPassword();
        KeyStoreGenerator.save(KeyStoreGenerator.generate("ModeProbe", "AI.js Pro", "CN"),
                keyStore, password.toCharArray(), "aijspro");
        SigningKey key = SigningKey.load(keyStore, null, password.toCharArray(), null,
                password.toCharArray());

        assertNull("自动模式不显式指定签名器，交给打包器注入本机专属身份",
                SigningOptions.INSTANCE.signerFor(SigningOptions.MODE_AUTO, key, "aijspro"));
        assertNotNull("选择已有密钥库要生效",
                SigningOptions.INSTANCE.signerFor(SigningOptions.MODE_EXISTING, key, "aijspro"));
        assertNotNull("新建密钥后也必须生效（回归点）",
                SigningOptions.INSTANCE.signerFor(SigningOptions.MODE_NEW, key, "aijspro"));
        assertNull("密钥没验证通过时不得拿去签，否则会签出无法升级的产物",
                SigningOptions.INSTANCE.signerFor(SigningOptions.MODE_NEW, null, "aijspro"));
    }

    /**
     * 回归：「新建签名」里填的名字 → 文件名。
     *
     * 默认名 `aijspro.keystore` 和本机身份（自动签名用的那份）同名属于常态，
     * 所以这里必须稳定地算出同一个名字 —— 打包页据此判定「已存在 = 同一份身份」并直接载入，
     * 而不是把用户堵在「已存在同名密钥库」的错误上（真实用户反馈：新建签名又不行了）。
     */
    @Test
    public void newKeyStoreNameKeepsTheDefaultIdentityNameAndStaysAFileName() {
        assertEquals("留空要用默认名，否则和本机身份对不上",
                SigningOptions.DEFAULT_KEYSTORE_NAME,
                SigningOptions.INSTANCE.newKeyStoreFileName(""));
        assertEquals("照抄默认名也要落在同一份身份上",
                SigningOptions.DEFAULT_KEYSTORE_NAME,
                SigningOptions.INSTANCE.newKeyStoreFileName("aijspro.keystore"));
        assertEquals("没写扩展名要补上",
                "mykey.keystore", SigningOptions.INSTANCE.newKeyStoreFileName(" mykey "));
        assertEquals("路径分隔符不能当目录用",
                "a_b.keystore", SigningOptions.INSTANCE.newKeyStoreFileName("a/b"));
        assertFalse("名字不能带着路径溜出 .keyStore/",
                SigningOptions.INSTANCE.newKeyStoreFileName("../evil").contains("/"));
    }

    /**
     * 默认签名不得落到 tiny-sign 那份全世界共用的测试证书（CN=Test），也不能每个应用
     * 在产物旁边生成一个密钥库：统一用 `<脚本目录>/.keyStore/aijspro.keystore` 一份身份，
     * 而且重复打包要复用同一份（否则旧包永远升不了级）。
     */
    @Test
    public void defaultSigningUsesTheDeviceIdentityOutOfKeyStoreDirectory() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        isolateScriptDir(context);
        File workDir = new File(context.getCacheDir(), "apk-builder-default-signing-test");
        deleteRecursively(workDir);
        File outDir = new File(workDir, "out");
        //noinspection ResultOfMethodCallIgnored
        outDir.mkdirs();
        File script = new File(workDir, "probe.js");
        writeText(script, "console.log('default signing probe');\n");

        ApkBuilder.AppConfig config = new ApkBuilder.AppConfig()
                .setAppName("DefaultSigningProbe")
                .setPackageName("com.example.defaultsigningprobe")
                .setVersionName("1.0.0")
                .setVersionCode(1)
                .setSourcePath(script.getAbsolutePath())
                .setEngine("rhino")
                .setIncludeAccessibility(false)
                .setIncludeImageModule(false);

        ApkSignatureReader.Signer first =
                buildWithConfig(context, outDir, "probe-1.apk", workDir, config);
        assertNotNull("产物必须能读出签名者", first);
        assertNotEquals("默认签名不得再是全世界共用的 tiny-sign 测试证书",
                SHARED_TEST_CERTIFICATE_SHA256, first.getSha256());

        File identity = new File(testScriptDir(), ".keyStore/aijspro.keystore");
        assertTrue("身份应当收在 .keyStore/ 里: " + identity, identity.length() > 0);
        assertFalse("产物旁边不应该再散落 p12",
                new File(outDir, "DefaultSigningProbe-signing.p12").exists());

        ApkSignatureReader.Signer second =
                buildWithConfig(context, outDir, "probe-2.apk", workDir, config);
        assertNotNull(second);
        assertEquals("重复打包必须复用同一份身份", first.getSha256(), second.getSha256());
    }

    /**
     * 兼容旧布局：早期版本在产物旁边放 `<应用名>-signing.p12`，口令只记在
     * 「当前密钥库口令」一个键上。自动签名应当把它搬进 `.keyStore/` 继续用，
     * 而不是换一份身份、逼用户卸载重装已有应用。
     */
    @Test
    public void autoSigningMigratesAnOlderPerAppKeyStore() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        isolateScriptDir(context);
        File workDir = new File(context.getCacheDir(), "apk-builder-legacy-identity-test");
        deleteRecursively(workDir);
        //noinspection ResultOfMethodCallIgnored
        workDir.mkdirs();

        String appName = "LegacyIdentityProbe";
        String password = KeyStoreGenerator.randomPassword();
        SigningKey generated = KeyStoreGenerator.generate(appName, "AI.js Pro", "CN");
        File legacy = new File(workDir, appName + "-signing.p12");
        KeyStoreGenerator.save(generated, legacy, password.toCharArray(), "aijspro");

        // 这里会写应用的偏好，测试跑完必须恢复：这些键是 App 真实使用的。
        String legacyPref = Pref.getPrefString(SigningOptions.CURRENT_STORE_PASSWORD_PREF, null);
        try {
            Pref.setPrefString(SigningOptions.CURRENT_STORE_PASSWORD_PREF, password);

            Signer signer = AutoSigningIdentity.INSTANCE.signer(workDir, appName);
            assertNotNull("应当拿旧密钥库继续签", signer);

            File identity = new File(testScriptDir(), ".keyStore/aijspro.keystore");
            assertTrue("旧密钥库应当被搬进 .keyStore/: " + identity, identity.isFile());
            assertFalse("旧位置的文件应当已经搬走", legacy.exists());
            SigningKey migrated = SigningKey.load(identity, null,
                    password.toCharArray(), "aijspro", password.toCharArray());
            assertEquals("搬过来的必须是同一份身份", generated.getCertificateFingerprint(),
                    migrated.getCertificateFingerprint());
        } finally {
            Pref.setPrefString(SigningOptions.CURRENT_STORE_PASSWORD_PREF, legacyPref);
        }
    }

    /**
     * 口令丢失、打不开的本机身份不能把用户彻底堵死：应当把旧文件改名备份、
     * 生成新身份继续打包，并把这件事留给界面提示。
     */
    @Test
    public void autoSigningRecoversFromAnUnreadableKeyStore() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        isolateScriptDir(context);

        File identityDir = new File(testScriptDir(), ".keyStore");
        //noinspection ResultOfMethodCallIgnored
        identityDir.mkdirs();
        File identity = new File(identityDir, "aijspro.keystore");
        KeyStoreGenerator.save(KeyStoreGenerator.generate("BrokenIdentity", "AI.js Pro", "CN"),
                identity, KeyStoreGenerator.randomPassword().toCharArray(), "aijspro");

        String legacyPref = Pref.getPrefString(SigningOptions.CURRENT_STORE_PASSWORD_PREF, null);
        String pathKey = SigningOptions.INSTANCE.passwordPrefKey(identity.getPath());
        String pathPref = Pref.getPrefString(pathKey, null);
        try {
            Pref.setPrefString(pathKey, "");
            Pref.setPrefString(SigningOptions.CURRENT_STORE_PASSWORD_PREF, "not-the-password");

            Signer signer = AutoSigningIdentity.INSTANCE.signer(null, null);
            assertNotNull("打不开的旧身份应当被换掉，而不是把打包堵死", signer);
            File backup = new File(identityDir, "aijspro.keystore.unreadable");
            assertTrue("旧文件应当被改名备份: " + backup, backup.isFile());
            assertTrue("原路径上应当生成了新身份", identity.isFile());
            assertEquals("应当留下给用户看的提示", backup.getName(),
                    AutoSigningIdentity.INSTANCE.getLastOrphanedKeyStore());
            assertNotNull("新身份应当可用", AutoSigningIdentity.INSTANCE.existingSubject());
        } finally {
            Pref.setPrefString(SigningOptions.CURRENT_STORE_PASSWORD_PREF, legacyPref);
            Pref.setPrefString(pathKey, pathPref);
        }
    }

    /**
     * encryptLevel = 0（Auto.js / Auto.js Pro 工程格式里的「不加密」）：
     * 入口脚本必须原样明文写入，不能再带上加密头 —— 否则「关掉加密」只是说说而已。
     */
    @Test
    public void encryptLevelZeroKeepsScriptPlainText() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        isolateScriptDir(context);

        File workDir = new File(context.getCacheDir(), "apk-builder-plaintext-test");
        deleteRecursively(workDir);
        File workspace = new File(workDir, "workspace");
        File outApk = new File(workDir, "plain.apk");
        File script = new File(workDir, "probe.js");
        //noinspection ResultOfMethodCallIgnored
        workDir.mkdirs();
        writeText(script, "console.log('plain probe');\n");

        ApkBuilder.AppConfig config = new ApkBuilder.AppConfig()
                .setAppName("PlainProbe")
                .setPackageName("com.example.plainprobe")
                .setVersionName("1.0.0")
                .setVersionCode(1)
                .setSourcePath(script.getAbsolutePath())
                .setEncryptLevel(ScriptProtection.LEVEL_NONE);

        ApkBuilder builder = new ApkBuilder(ApkBuilderPluginHelper.openTemplateApk(context),
                outApk, workspace.getPath()).prepare().withConfig(config).build().sign();

        assertEquals("页面选择的等级要生效", ScriptProtection.LEVEL_NONE, builder.getEncryptLevel());
        JSONObject json = new JSONObject(readText(new File(workspace, "assets/project/project.json")));
        assertEquals("产物 project.json 必须记录真实等级", 0, json.getInt("encryptLevel"));
        byte[] packaged = readBytes(new File(workspace, "assets/project/main.js"));
        assertFalse("等级 0 时不能再有加密头",
                EncryptedScriptFileHeader.INSTANCE.isValidFile(packaged));
        assertEquals("等级 0 时入口脚本就是原脚本", readText(script), new String(packaged, "UTF-8"));
    }

    /**
     * 工程 project.json 里的 encryptLevel 必须被采纳（页面没有显式指定时）：
     * Auto.js / Pro 的工程拿到这里打包，不能把它的「不加密」静默改成加密。
     */
    @Test
    public void projectJsonEncryptLevelIsHonoured() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        isolateScriptDir(context);

        File workDir = new File(context.getCacheDir(), "apk-builder-project-level-test");
        deleteRecursively(workDir);
        File projectDir = new File(workDir, "project");
        File workspace = new File(workDir, "workspace");
        File outApk = new File(workDir, "project.apk");
        //noinspection ResultOfMethodCallIgnored
        projectDir.mkdirs();
        writeText(new File(projectDir, "main.js"), "console.log('project probe');\n");
        writeText(new File(projectDir, "project.json"),
                "{\n"
                        + "  \"name\": \"ProjectLevelProbe\",\n"
                        + "  \"packageName\": \"com.example.projectlevelprobe\",\n"
                        + "  \"versionName\": \"1.0.0\",\n"
                        + "  \"versionCode\": 1,\n"
                        + "  \"main\": \"main.js\",\n"
                        + "  \"encryptLevel\": 0\n"
                        + "}\n");

        ApkBuilder.AppConfig config = new ApkBuilder.AppConfig()
                .setAppName("ProjectLevelProbe")
                .setPackageName("com.example.projectlevelprobe")
                .setVersionName("1.0.0")
                .setVersionCode(1)
                .setSourcePath(projectDir.getAbsolutePath());

        ApkBuilder builder = new ApkBuilder(ApkBuilderPluginHelper.openTemplateApk(context),
                outApk, workspace.getPath()).prepare().withConfig(config).build().sign();

        assertEquals("工程里写了 0 就应当是 0", ScriptProtection.LEVEL_NONE, builder.getEncryptLevel());
        JSONObject json = new JSONObject(readText(new File(workspace, "assets/project/project.json")));
        assertEquals(0, json.getInt("encryptLevel"));
        byte[] packaged = readBytes(new File(workspace, "assets/project/main.js"));
        assertFalse("工程要求不加密时不能再有加密头",
                EncryptedScriptFileHeader.INSTANCE.isValidFile(packaged));
        assertEquals("console.log('project probe');\n", new String(packaged, "UTF-8"));
    }

    /**
     * 默认路径（工程没写、页面没选）继续加密：新增等级字段不能改变老工程的打包结果。
     */
    @Test
    public void defaultPackagingStillEncrypts() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        isolateScriptDir(context);

        File workDir = new File(context.getCacheDir(), "apk-builder-default-level-test");
        deleteRecursively(workDir);
        File workspace = new File(workDir, "workspace");
        File outApk = new File(workDir, "default.apk");
        File script = new File(workDir, "probe.js");
        //noinspection ResultOfMethodCallIgnored
        workDir.mkdirs();
        writeText(script, "console.log('default probe');\n");

        ApkBuilder.AppConfig config = new ApkBuilder.AppConfig()
                .setAppName("DefaultLevelProbe")
                .setPackageName("com.example.defaultlevelprobe")
                .setVersionName("1.0.0")
                .setVersionCode(1)
                .setSourcePath(script.getAbsolutePath());

        ApkBuilder builder = new ApkBuilder(ApkBuilderPluginHelper.openTemplateApk(context),
                outApk, workspace.getPath()).prepare().withConfig(config).build().sign();

        assertEquals(ScriptProtection.DEFAULT_LEVEL, builder.getEncryptLevel());
        JSONObject json = new JSONObject(readText(new File(workspace, "assets/project/project.json")));
        assertEquals(ScriptProtection.LEVEL_ENCRYPT, json.getInt("encryptLevel"));
        assertTrue("默认仍然是加密头 + 密文",
                EncryptedScriptFileHeader.INSTANCE.isValidFile(
                        readBytes(new File(workspace, "assets/project/main.js"))));
    }

    /**
     * 编译等级（encryptLevel ≥ 2）：产物里应当**没有脚本源码**，而是编译好的 class 字节，
     * 且这些字节在设备上能真正加载执行（dx → DexClassLoader → Rhino）。
     */
    @Test
    public void compileLevelProducesRunnableClassWithoutSource() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        isolateScriptDir(context);

        File workDir = new File(context.getCacheDir(), "apk-builder-compile-test");
        deleteRecursively(workDir);
        File workspace = new File(workDir, "workspace");
        File outApk = new File(workDir, "compiled.apk");
        File script = new File(workDir, "probe.js");
        //noinspection ResultOfMethodCallIgnored
        workDir.mkdirs();
        // 开头写一个标记文件（用于安装后的端到端验证）；结尾是一个表达式，执行结果 = 3。
        // 用 typeof 兜底：同一个脚本既要在打包出的 App 里跑（有 context/files），
        // 也要在测试进程的裸 Rhino 上下文里跑（没有这些全局）。
        String source = "if (typeof context !== 'undefined' && typeof files !== 'undefined') {\n"
                + "  var dir = context.getExternalFilesDir(null);\n"
                + "  if (dir) { files.write(new java.io.File(dir, 'compiled-ran.txt').getAbsolutePath(), 'COMPILED_OK'); }\n"
                + "}\n"
                + "var UNIQUE_MARKER_VARIABLE_7788 = 0;\n"
                + "for (var i = 0; i < 3; i++) { UNIQUE_MARKER_VARIABLE_7788 += i; }\n"
                + "UNIQUE_MARKER_VARIABLE_7788;\n";
        writeText(script, source);

        ApkBuilder.AppConfig config = new ApkBuilder.AppConfig()
                .setAppName("CompiledProbe")
                .setPackageName("com.example.compiledprobe")
                .setVersionName("1.0.0")
                .setVersionCode(1)
                .setSourcePath(script.getAbsolutePath())
                .setEngine("rhino")
                .setEncryptLevel(ScriptProtection.LEVEL_COMPILE);

        ApkBuilder builder = new ApkBuilder(ApkBuilderPluginHelper.openTemplateApk(context),
                outApk, workspace.getPath()).prepare().withConfig(config).build().sign();

        assertEquals(ScriptProtection.LEVEL_COMPILE, builder.getEncryptLevel());
        JSONObject json = new JSONObject(readText(new File(workspace, "assets/project/project.json")));
        assertEquals(ScriptProtection.LEVEL_COMPILE, json.getInt("encryptLevel"));

        byte[] packaged = readBytes(new File(workspace, "assets/project/main.js"));
        assertTrue("编译模式产物仍然是加密的（只是载荷换成了编译结果）",
                EncryptedScriptFileHeader.INSTANCE.isValidFile(packaged));
        short flags = EncryptedScriptFileHeader.INSTANCE.readFlags(packaged);
        assertEquals("文件头必须标记载荷是 Rhino 编译产物",
                EncryptedScriptFileHeader.PAYLOAD_TYPE_RHINO_CLASS,
                EncryptedScriptFileHeader.INSTANCE.payloadTypeOf(flags));

        // 产物里不能出现脚本源码（这是「编译」相对于「加密」的关键差别）。
        String asText = new String(packaged, "ISO-8859-1");
        assertFalse("产物里不该出现脚本原文", asText.contains(source));
        assertFalse("产物里不该出现脚本里的标识符原文",
                asText.contains("UNIQUE_MARKER_VARIABLE_7788"));

        // 用运行时相同的密钥派生解密，解析出编译载荷。
        String key = MD5.md5(json.getString("packageName") + json.getString("versionName")
                + json.getString("main"));
        String vector = MD5.md5(json.getJSONObject("build").getString("build_id")
                + json.getString("name")).substring(0, 16);
        Field keyField = ScriptEncryption.class.getDeclaredField("mKey");
        keyField.setAccessible(true);
        keyField.set(null, key);
        Field vectorField = ScriptEncryption.class.getDeclaredField("mInitVector");
        vectorField.setAccessible(true);
        vectorField.set(null, vector);
        byte[] plain = ScriptEncryption.INSTANCE.decrypt(
                packaged, EncryptedScriptFileHeader.BLOCK_SIZE, packaged.length);

        CompiledScriptPayload payload = CompiledScriptPayload.read(plain);
        assertTrue("载荷里应当带类名", payload.className != null && payload.className.length() > 0);
        assertEquals("编译产物必须是 class 文件", (byte) 0xCA, payload.classBytes[0]);
        assertEquals((byte) 0xFE, payload.classBytes[1]);
        assertEquals((byte) 0xBA, payload.classBytes[2]);
        assertEquals((byte) 0xBE, payload.classBytes[3]);

        // 设备侧执行：AndroidClassLoader（dx → DexClassLoader）加载后交给 Rhino 运行。
        org.mozilla.javascript.Context rhino = org.mozilla.javascript.Context.enter();
        try {
            rhino.setOptimizationLevel(9);
            org.mozilla.javascript.Scriptable scope = rhino.initStandardObjects();
            AndroidClassLoader loader = new AndroidClassLoader(getClass().getClassLoader(),
                    new File(workDir, "compiled-classes"));
            Class<?> clazz = loader.defineClass(payload.className, payload.classBytes);
            Object instance = clazz.newInstance();
            assertTrue("加载出来的类必须实现 Rhino 的 Script",
                    instance instanceof org.mozilla.javascript.Script);
            Object result = ((org.mozilla.javascript.Script) instance).exec(rhino, scope);
            assertEquals("编译产物在设备上的执行结果必须与源码一致", 3.0,
                    Double.parseDouble(String.valueOf(result)), 0.0001);
        } finally {
            org.mozilla.javascript.Context.exit();
        }

        // 把产物导出到公共目录：安装后可以跑一遍真正的「编译模式打包应用」。
        copyToSharedStorage(outApk, "aijs-compiled-probe.apk");
    }

    /** 把打包产物拷到 /sdcard/Download，方便用 adb 拉出去安装做端到端验证。 */
    private static void copyToSharedStorage(File apk, String name) throws Exception {
        File downloads = new File(Environment.getExternalStorageDirectory(), "Download");
        if (!downloads.isDirectory() && !downloads.mkdirs()) {
            return;
        }
        File target = new File(downloads, name);
        java.io.OutputStream out = new FileOutputStream(target);
        try {
            java.io.InputStream in = new java.io.FileInputStream(apk);
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
    }

    private static ApkSignatureReader.Signer buildWithConfig(Context context, File outDir, String apkName,
            File workDir, ApkBuilder.AppConfig config) throws Exception {        File outApk = new File(outDir, apkName);        File workspace = new File(workDir, "workspace-" + apkName);
        new ApkBuilder(ApkBuilderPluginHelper.openTemplateApk(context), outApk, workspace.getPath())
                .prepare()
                .withConfig(config)
                .build()
                .sign()
                // 解包出来的工作区有上百 MB，不清理的话同一进程里连跑几次就会把内存吃爆。
                .cleanWorkspace();
        return ApkSignatureReader.INSTANCE.read(outApk);
    }

    /** 中央目录偏移量（= 签名块结束位置）。 */
    private static int centralDirOffset(byte[] apk) {
        return readInt(apk, eocdOffset(apk) + 16);
    }

    private static int eocdOffset(byte[] apk) {
        for (int i = apk.length - 22; i >= 0 && i >= apk.length - 22 - 0xFFFF; i--) {
            if (apk[i] == 'P' && apk[i + 1] == 'K' && apk[i + 2] == 0x05 && apk[i + 3] == 0x06) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 通过“中央目录前的 "APK Sig Block 42" 魔术 + 尾部长度字段”定位签名块，
     * 与校验方找块的方式一致；找不到返回 -1。
     */
    private static int findSigningBlockStart(byte[] apk) throws Exception {
        int centralDir = centralDirOffset(apk);
        if (centralDir < 32) {
            return -1;
        }
        int magic = centralDir - 16;
        byte[] expected = "APK Sig Block 42".getBytes("US-ASCII");
        for (int i = 0; i < expected.length; i++) {
            if (apk[magic + i] != expected[i]) {
                return -1;
            }
        }
        long sizeField = readLong(apk, magic - 8);
        return (int) (magic - 8 - sizeField);
    }

    private static android.content.pm.Signature[] signersOf(PackageInfo info) {
        // GET_SIGNING_CERTIFICATES 需要 API 28+，更低版本回落到旧的 signatures 字段。
        if (info.signingInfo != null) {
            return info.signingInfo.getApkContentsSigners();
        }
        return info.signatures;
    }

    private static String sha256Fingerprint(byte[] data) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder builder = new StringBuilder();
        for (byte b : digest) {
            if (builder.length() > 0) {
                builder.append(':');
            }
            builder.append(String.format(Locale.US, "%02X", b));
        }
        return builder.toString();
    }

    private static int indexOf(byte[] data, byte[] needle, int from, int to) {
        outer:
        for (int i = from; i + needle.length <= to; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static int readInt(byte[] data, int offset) {
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24);
    }

    private static long readLong(byte[] data, int offset) {
        long value = 0;
        for (int i = 7; i >= 0; i--) {
            value = (value << 8) | (data[offset + i] & 0xFFL);
        }
        return value;
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
