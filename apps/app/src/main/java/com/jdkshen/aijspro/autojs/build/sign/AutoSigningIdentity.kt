package com.jdkshen.aijspro.autojs.build.sign

import android.util.Log
import com.jdkshen.aijspro.Pref
import com.stardust.autojs.apkbuilder.Signer
import java.io.File
import java.io.IOException

/**
 * 打包时**没有显式选择签名**所用的身份：本机为该应用自动生成并复用。
 *
 * 以前这种情况会退回 tiny-sign 内嵌的测试证书（`CN=Test`）：全世界所有用同款打包器的产物
 * 共用同一份私钥，安全软件可以直接把不同人的应用归成同一「家族」（检测名里的 `crt`）。
 * 现在改成按应用生成专属身份：
 *
 * - 密钥库放在产物旁边，命名 `<应用名>-signing.p12`（和打包页「新建签名」是同一个文件）
 * - 口令记在应用私有偏好里，按密钥库路径记账；卸载应用或清数据前请自行备份
 * - 同一个应用再次打包复用同一份身份，能覆盖升级；**改了应用名会得到新身份，需要先卸载旧包**
 */
object AutoSigningIdentity {

    private const val TAG = "AutoSigningIdentity"
    private const val ALIAS = "aijspro"
    private const val ORGANIZATION = "AI.js Pro"
    private const val COUNTRY = "CN"

    /**
     * 上一次打包时被兑掉的旧密钥库文件名（口令丢了、打不开），非 null 时界面会提醒用户。
     * 被兑掉的文件只改名不删除，用的是旧身份、已经装在手机上的包需要先卸载。
     */
    @Volatile
    var lastOrphanedKeyStore: String? = null
        private set

    /** 取得（必要时生成）该应用的签名身份，交给打包器使用。 */
    @Synchronized
    @Throws(Exception::class)
    fun forApp(outputDir: File, appName: String?): Signer {
        lastOrphanedKeyStore = null
        val key = resolve(outputDir, appName)
            ?: throw IOException("无法准备应用签名身份：$outputDir")
        return KeyStoreApkSigner(key, ALIAS)
    }

    /** 已有身份的主题名；还没有生成过就返回 null（只读，用于界面提示）。 */
    @Synchronized
    fun existingSubject(outputDir: File, appName: String?): String? = try {
        resolve(outputDir, appName, generateIfMissing = false)?.subjectName
    } catch (error: Exception) {
        Log.w(TAG, "Cannot inspect the auto signing identity", error)
        null
    }

    /** 身份文件的路径（无论是否已存在），界面用它告诉用户签名放在哪。 */
    fun identityFile(outputDir: File, appName: String?): File =
        File(outputDir, SigningOptions.keystoreBaseName(appName) + "-signing.p12")

    @Synchronized
    private fun resolve(
        outputDir: File,
        appName: String?,
        generateIfMissing: Boolean = true
    ): SigningKey? {
        val keyStore = identityFile(outputDir, appName)
        if (keyStore.isFile) {
            val key = openExisting(keyStore)
            if (key != null) return key
            // 只做只读查看（existingSubject）时不要动文件，交给调用方显示“尚未生成”。
            if (!generateIfMissing) return null
            // 打不开（口令丢了、或者不是本机生成的）：硬报错会把用户彻底堵死，
            // 改成把旧文件改名留个备份，再生成一份新身份。
            val backup = File(keyStore.parentFile, keyStore.name + ".unreadable")
            backup.delete()
            if (keyStore.renameTo(backup)) {
                lastOrphanedKeyStore = backup.name
                Log.w(TAG, "Moved the unreadable keystore aside: " + backup.path)
            } else {
                Log.w(TAG, "Cannot move the unreadable keystore aside: " + keyStore.path)
            }
        }
        if (!generateIfMissing) return null
        val password = KeyStoreGenerator.randomPassword()
        val generated = KeyStoreGenerator.generate(
            SigningOptions.keystoreBaseName(appName).trim('_').ifBlank { "AI.js Pro" },
            ORGANIZATION, COUNTRY)
        KeyStoreGenerator.save(generated, keyStore, password.toCharArray(), ALIAS)
        Pref.setPrefString(SigningOptions.passwordPrefKey(keyStore.path), password)
        Log.i(TAG, "Generated the app signing identity: " + keyStore.path)
        return generated
    }

    /**
     * 试着用本机记得的口令打开密钥库：先试按路径记的，再试旧版本只记在
     * 「当前密钥库口令」上的那个；命中后补记账，之后再打包直接命中。
     */
    private fun openExisting(keyStore: File): SigningKey? {
        val candidates = listOf(
            Pref.getPrefString(SigningOptions.passwordPrefKey(keyStore.path), ""),
            Pref.getPrefString(SigningOptions.CURRENT_STORE_PASSWORD_PREF, "")
        ).filter { it.isNotEmpty() }.distinct()
        for (password in candidates) {
            val key = try {
                SigningKey.load(keyStore, null,
                    password.toCharArray(), ALIAS, password.toCharArray())
            } catch (error: Exception) {
                Log.w(TAG, "Password candidate rejected for " + keyStore.path, error)
                null
            }
            if (key != null) {
                Pref.setPrefString(SigningOptions.passwordPrefKey(keyStore.path), password)
                return key
            }
        }
        return null
    }
}
