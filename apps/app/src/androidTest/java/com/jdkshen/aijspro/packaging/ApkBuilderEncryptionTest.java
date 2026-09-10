package com.jdkshen.aijspro.packaging;

import android.content.Context;

import androidx.test.platform.app.InstrumentationRegistry;

import com.jdkshen.aijspro.autojs.build.ApkBuilder;
import com.jdkshen.aijspro.build.ApkBuilderPluginHelper;
import com.stardust.autojs.engine.encryption.ScriptEncryption;
import com.stardust.autojs.script.EncryptedScriptFileHeader;
import com.stardust.util.MD5;

import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
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
        ApkBuilder.AppConfig config = new ApkBuilder.AppConfig()
                .setAppName("EncryptionProbe")
                .setPackageName("com.example.encryptionprobe")
                .setVersionName("1.0.0")
                .setVersionCode(1)
                .setSourcePath(script.getAbsolutePath());
        new ApkBuilder(template, outApk, workspace.getPath())
                .prepare()
                .withConfig(config)
                .build();

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
