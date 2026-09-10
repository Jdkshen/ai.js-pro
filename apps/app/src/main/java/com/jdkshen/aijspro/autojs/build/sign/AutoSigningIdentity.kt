package com.jdkshen.aijspro.autojs.build.sign

import android.util.Log
import com.jdkshen.aijspro.Pref
import com.stardust.autojs.apkbuilder.Signer
import java.io.File
import java.io.IOException

/**
 * 打包时**没有显式选择签名**所用的身份：`<脚本目录>/.keyStore/aijspro.keystore`，**本机一份**。
 *
 * 对齐 AutoX.js 的做法（它把密钥库集中在 `<脚本目录>/.keyStore/`，创建时默认文件就叫
 * `AutoX.keystore`）：一份身份服务所有打包应用 —— 产物目录不会每打一个应用就多一个 p12，
 * 改了应用名也还能覆盖升级。
 *
 * 为什么不用 tiny-sign 内嵌的那份证书：它是全世界共用的，安全软件直接据此把所有产物
 * 归成同一「家族」（检测名里的 `crt`）。
 */
object AutoSigningIdentity {

    private const val TAG = "AutoSigningIdentity"
    private const val ALIAS = "aijspro"
    private const val ORGANIZATION = "AI.js Pro"
    private const val COUNTRY = "CN"
    private const val DEFAULT_COMMON_NAME = "AI.js Pro"

    /**
     * 上一次打包时被兑掉的旧密钥库文件名（口令丢了、打不开），非 null 时界面会提醒用户。
     * 被兑掉的文件只改名不删除，用的是旧身份、已经装在手机上的包需要先卸载。
     */
    @Volatile
    var lastOrphanedKeyStore: String? = null
        private set

    /** 身份文件的路径（无论是否已存在），界面用它告诉用户签名放在哪。 */
    fun identityFile(): File {
        val dir = SigningOptions.keyStoreDir(Pref.getScriptDirPath())
        if (!dir.exists()) dir.mkdirs()
        return File(dir, SigningOptions.DEFAULT_KEYSTORE_NAME)
    }

    /**
     * 取得（必要时生成）本机签名身份，交给打包器使用。
     *
     * @param outputDir 产物目录；@param appName 应用名。两者只用于兼容旧布局：
     *        早期版本会在产物旁边放一个 `<应用名>-signing.p12`，能打开的就搬进 `.keyStore/`。
     */
    @Synchronized
    @Throws(Exception::class)
    fun signer(outputDir: File?, appName: String?): Signer {
        lastOrphanedKeyStore = null
        migrateLegacy(outputDir, appName)
        val key = resolve() ?: throw IOException("无法准备应用签名身份")
        return KeyStoreApkSigner(key, ALIAS)
    }

    /** 已有身份的主题名；还没有生成过就返回 null（只读，用于界面提示）。 */
    @Synchronized
    fun existingSubject(): String? = try {
        resolve(generateIfMissing = false)?.subjectName
    } catch (error: Exception) {
        Log.w(TAG, "Cannot inspect the auto signing identity", error)
        null
    }

    @Synchronized
    private fun resolve(generateIfMissing: Boolean = true): SigningKey? {
        val keyStore = identityFile()
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
        val generated = KeyStoreGenerator.generate(DEFAULT_COMMON_NAME, ORGANIZATION, COUNTRY)
        KeyStoreGenerator.save(generated, keyStore, password.toCharArray(), ALIAS)
        Pref.setPrefString(SigningOptions.passwordPrefKey(keyStore.path), password)
        Log.i(TAG, "Generated the app signing identity: " + keyStore.path)
        return generated
    }

    /**
     * 兼容旧布局：早期版本在产物旁边放 `<应用名>-signing.p12`。
     * 能打开（本机记得它的口令）就搬进 `.keyStore/` 当本机身份，保住覆盖升级能力；
     * 打不开的留在原地不动（别把用不了的垃圾导入成默认身份）。
     */
    private fun migrateLegacy(outputDir: File?, appName: String?) {
        if (outputDir == null || appName.isNullOrBlank()) return
        val target = identityFile()
        if (target.isFile) return
        val legacy = File(outputDir, SigningOptions.keystoreBaseName(appName) + "-signing.p12")
        if (!legacy.isFile) return
        val password = passwordCandidates(legacy).firstOrNull { canOpen(legacy, it) } ?: return
        if (move(legacy, target)) {
            Pref.setPrefString(SigningOptions.passwordPrefKey(target.path), password)
            Log.i(TAG, "Migrated the signing identity into .keyStore: " + target.path)
        }
    }

    /**
     * 搬文件。`renameTo` 在跨文件系统时会失败（产物可能在 /data，密钥库在 /sdcard），
     * 所以失败后退回「复制再删」。
     */
    private fun move(source: File, target: File): Boolean {
        if (source.renameTo(target)) return true
        return try {
            source.inputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            source.delete()
            true
        } catch (error: IOException) {
            Log.w(TAG, "Cannot move " + source.path + " to " + target.path, error)
            target.delete()
            false
        }
    }

    /** 本机记得的候选口令：先按路径记的，再旧版本只记在「当前密钥库口令」上的那个。 */
    private fun passwordCandidates(keyStore: File): List<String> = listOf(
        Pref.getPrefString(SigningOptions.passwordPrefKey(keyStore.path), ""),
        Pref.getPrefString(SigningOptions.CURRENT_STORE_PASSWORD_PREF, "")
    ).filter { it.isNotEmpty() }.distinct()

    private fun canOpen(keyStore: File, password: String): Boolean = try {
        SigningKey.load(keyStore, null, password.toCharArray(), ALIAS, password.toCharArray())
        true
    } catch (error: Exception) {
        false
    }

    /**
     * 试着用本机记得的口令打开密钥库；命中后补记账，之后再打包直接命中。
     */
    private fun openExisting(keyStore: File): SigningKey? {
        for (password in passwordCandidates(keyStore)) {
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
