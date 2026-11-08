package com.stardust.autojs.script;

import com.stardust.autojs.engine.encryption.ScriptEncryption;
import com.stardust.util.AdvancedEncryptionStandard;
import com.stardust.util.MD5;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * `.4` 的 require 报错在**加密/编译产物**上仍复现，原因是打包版入口走 EncryptedScripts.toSource(file)，
 * 得到的源（文本载荷 → StringScriptSource；编译载荷 → CompiledJavaScriptSource）**没有文件路径**，
 * 引擎因此无法在「执行前用脚本自身目录刷新 jvm-npm 的 require 根」。
 * 本测试锁住修复：from file 的加密源必须带上原始路径。
 */
public class EncryptedSourcePathTest {

    private static void installKey(String key, String vector) throws Exception {
        Field keyField = ScriptEncryption.class.getDeclaredField("mKey");
        keyField.setAccessible(true);
        keyField.set(null, key);
        Field vectorField = ScriptEncryption.class.getDeclaredField("mInitVector");
        vectorField.setAccessible(true);
        vectorField.set(null, vector);
    }

    private static File writeEncryptedTextScript(String script, String key, String vector) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        EncryptedScriptFileHeader.INSTANCE.writeHeader(out, (short) 0); // 文本载荷
        out.write(new AdvancedEncryptionStandard(key.getBytes("UTF-8"), vector).encrypt(script.getBytes("UTF-8")));
        File file = Files.createTempFile("encrypted-entry", ".js").toFile();
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(out.toByteArray());
        }
        return file;
    }

    @Test
    public void encryptedTextPayloadCarriesOriginalScriptPath() throws Exception {
        String packageName = "xx.xx";
        String versionName = "1.0.0";
        String mainScriptFile = "sub/main.js";
        String name = "打包测试";
        String buildId = "B883A485-9";
        String key = MD5.md5(packageName + versionName + mainScriptFile);
        String vector = MD5.md5(buildId + name).substring(0, 16);
        installKey(key, vector);

        File entry = writeEncryptedTextScript("require(\"./脚本.js\");\n", key, vector);
        JavaScriptSource source = EncryptedScripts.toSource(entry, "main");
        assertNotNull("加密文件应能还原成脚本源", source);
        assertTrue("文本载荷应是 StringScriptSource", source instanceof StringScriptSource);
        assertEquals("加密源必须带上原始文件路径，引擎才能刷新 require 根",
                entry.getAbsolutePath(), ((StringScriptSource) source).getScriptPath());
    }

    @Test
    public void plainFileSourceStillHasNoScriptPathOnStringSource() throws Exception {
        // 反例：内存脚本（无文件）没有路径，引擎应退回 cwd（旧行为）。
        StringScriptSource memory = new StringScriptSource("mem", "console.log(1);");
        assertEquals(null, memory.getScriptPath());
        assertEquals("mem", memory.getName());
    }
}
