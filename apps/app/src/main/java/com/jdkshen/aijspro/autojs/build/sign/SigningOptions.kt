package com.jdkshen.aijspro.autojs.build.sign

import com.jdkshen.aijspro.Pref
import com.stardust.autojs.apkbuilder.Signer
import java.io.File

/**
 * 打包页的签名选择逻辑。
 *
 * 单独抽成纯函数是因为这里踩过一次坑：只判断了「使用已有密钥库」，导致「新建签名」
 * 生成出来的密钥库根本没有参与打包，产物依旧是内置公共证书 —— 用户以为换了身份，
 * 实际什么都没变。现在这段决策有回归测试盯着（见 ApkBuilderEncryptionTest）。
 */
object SigningOptions {

    /**
     * 自动：不显式指定签名器，交给 [com.jdkshen.aijspro.autojs.build.ApkBuilder] 用
     * `.keyStore/` 里那份**本机身份**（没有就生成一份）。**不再**退回 tiny-sign 那份
     * 全世界共用的测试证书，也不会每个应用单独生成一份（那样产物目录会越来越乱）。
     */
    const val MODE_AUTO = 0

    /** 使用已有的密钥库。 */
    const val MODE_EXISTING = 1

    /** 在本机新建密钥库（生成后和 [MODE_EXISTING] 等价，区别只在界面引导）。 */
    const val MODE_NEW = 2

    private const val PASSWORD_PREFIX = "aijspro.build.signing.password."

    /**
     * 早期版本没有按路径记账，只把「当前密钥库口令」存在这一个键上。
     * 自动签名复用旧密钥库时要拿它当备选口令，否则用户升上来第一次打包就会报
     * 「已存在密钥库但没有口令记录」。
     */
    const val CURRENT_STORE_PASSWORD_PREF = "aijspro.build.signing.storePassword"

    /**
     * 自动生成的密钥库口令按「路径」记账：重新打开打包页、或者换个会话进来，
     * 都要能拿着同一份身份继续签，否则每打一次包就换一次证书、旧包再也升级不了。
     */
    fun passwordPrefKey(keyStorePath: String): String = PASSWORD_PREFIX + keyStorePath

    /**
     * 本机记着的候选口令：先按密钥库路径记账的，再旧版本只存在「当前密钥库口令」里的那个。
     *
     * 自动生成的身份用的是随机口令，用户不可能手敲 —— 「选择签名」选回它、或者自动签名
     * 复用它时，都得靠这个列表自动填。
     */
    fun recordedPasswords(keyStore: File): List<String> = listOf(
        Pref.getPrefString(passwordPrefKey(keyStore.path), ""),
        Pref.getPrefString(CURRENT_STORE_PASSWORD_PREF, "")
    ).filter { it.isNotEmpty() }.distinct()

    /** 密钥库统一放在脚本目录下的这个隐藏目录里（对齐 AutoX.js 的 `.keyStore/`）。 */
    const val KEYSTORE_DIR_NAME = ".keyStore"

    /**
     * 本机身份的固定文件名：一份身份服务所有打包应用。
     * 不按应用名生成，是为了让产物目录保持干净、改应用名也还能覆盖升级。
     */
    const val DEFAULT_KEYSTORE_NAME = "aijspro.keystore"

    private val KEYSTORE_EXTENSIONS = setOf("jks", "keystore", "p12", "pfx", "bks")

    fun keyStoreDir(scriptDirPath: String): File = File(scriptDirPath, KEYSTORE_DIR_NAME)

    /** 打包页「选择签名」列出来的候选：`.keyStore/` 里所有的密钥库文件。 */
    fun listKeyStores(dir: File): List<File> =
        dir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in KEYSTORE_EXTENSIONS }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()

    /**
     * 密钥库文件名的基础部分（只用于兼容、迁移旧版本的按应用命名）。
     */
    fun keystoreBaseName(appName: String?): String {
        val raw = appName.orEmpty().trim()
        val cleaned = raw.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        if (cleaned.any { it.isLetterOrDigit() }) return cleaned
        return "app-" + Integer.toHexString(raw.hashCode())
    }

    /**
     * @return 用户显式选择的签名器；null 表示「自动」——由打包器注入本机专属身份。
     *         密钥没验证通过时也返回 null，调用方必须拦住这次打包，
     *         避免拿错口令签出无法升级的产物。
     */
    fun signerFor(mode: Int, key: SigningKey?, alias: String): Signer? =
        if (mode != MODE_AUTO && key != null) KeyStoreApkSigner(key, alias.trim()) else null
}
