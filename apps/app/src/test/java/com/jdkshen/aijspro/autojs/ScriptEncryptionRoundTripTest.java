package com.jdkshen.aijspro.autojs;

import com.stardust.autojs.engine.encryption.ScriptEncryption;
import com.stardust.autojs.script.EncryptedScriptFileHeader;
import com.stardust.util.AdvancedEncryptionStandard;
import com.stardust.util.MD5;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 打包端（ApkBuilder.syncProjectJsonAndDeriveKeys / encryptBytes）与 inrt 运行时
 * （AssetsProjectLauncher.initKey + XJavaScriptEngine.execute）两侧的加密约定必须一致：
 * 派生公式、文件头格式、AES 参数。本测试按两端相同的公式与顺序复现各自行为，
 * 验证打包端产出的加密文件可以被运行端约定解密。
 */
public class ScriptEncryptionRoundTripTest {

    /** 与两端约定一致：key = MD5(packageName + versionName + mainScriptFile)。 */
    private static String deriveKey(String packageName, String versionName, String mainScriptFile) {
        return MD5.md5(packageName + versionName + mainScriptFile);
    }

    /** 与两端约定一致：vector = MD5(buildId + name).substring(0, 16)。 */
    private static String deriveVector(String buildId, String name) {
        return MD5.md5(buildId + name).substring(0, 16);
    }

    @Test
    public void packagedScriptCanBeDecryptedByRuntimeConvention() throws Exception {
        String packageName = "com.example.packaged";
        String versionName = "1.0.0";
        String mainScriptFile = "main.js";
        String name = "Packaging Test";
        String buildId = "B883A485-9";

        String key = deriveKey(packageName, versionName, mainScriptFile);
        String vector = deriveVector(buildId, name);

        String script = "console.log('hello encrypted world');\nvar x = 1 + 2;\n";
        byte[] plain = script.getBytes("UTF-8");

        // 打包端 ApkBuilder.encryptBytes() 的等价序列：文件头 + AES/CBC/PKCS5。
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        EncryptedScriptFileHeader.INSTANCE.writeHeader(out, (short) 0);
        out.write(new AdvancedEncryptionStandard(key.getBytes("UTF-8"), vector).encrypt(plain));
        byte[] packaged = out.toByteArray();

        // 运行端 XJavaScriptEngine.execute()：先校验文件头。
        assertTrue(EncryptedScriptFileHeader.INSTANCE.isValidFile(packaged));

        // 运行端 AssetsProjectLauncher.initKey()：反射注入派生出的 key/vector。
        Field keyField = ScriptEncryption.class.getDeclaredField("mKey");
        keyField.setAccessible(true);
        keyField.set(null, key);
        Field vectorField = ScriptEncryption.class.getDeclaredField("mInitVector");
        vectorField.setAccessible(true);
        vectorField.set(null, vector);

        byte[] decrypted = ScriptEncryption.INSTANCE.decrypt(
                packaged, EncryptedScriptFileHeader.BLOCK_SIZE, packaged.length);
        assertEquals(script, new String(decrypted, "UTF-8"));
    }

    @Test
    public void wrongKeyProducesDifferentCipherText() throws Exception {
        String vector = deriveVector("B883A485-9", "Packaging Test");
        byte[] plain = "plain-script".getBytes("UTF-8");
        byte[] cipherA = new AdvancedEncryptionStandard(
                deriveKey("com.example.a", "1.0.0", "main.js").getBytes("UTF-8"), vector).encrypt(plain);
        byte[] cipherB = new AdvancedEncryptionStandard(
                deriveKey("com.example.b", "1.0.0", "main.js").getBytes("UTF-8"), vector).encrypt(plain);
        assertTrue(java.util.Arrays.equals(cipherA, cipherB) == false);
    }
}
