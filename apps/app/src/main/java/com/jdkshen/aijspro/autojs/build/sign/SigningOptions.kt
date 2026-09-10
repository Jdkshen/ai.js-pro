package com.jdkshen.aijspro.autojs.build.sign

import com.stardust.autojs.apkbuilder.Signer

/**
 * 打包页的签名选择逻辑。
 *
 * 单独抽成纯函数是因为这里踩过一次坑：只判断了「使用已有密钥库」，导致「新建签名」
 * 生成出来的密钥库根本没有参与打包，产物依旧是内置公共证书 —— 用户以为换了身份，
 * 实际什么都没变。现在这段决策有回归测试盯着（见 ApkBuilderEncryptionTest）。
 */
object SigningOptions {

    /** 沿用 tiny-sign 内置的公共测试证书，保持历史行为（旧包可覆盖升级）。 */
    const val MODE_DEFAULT = 0

    /** 使用已有的密钥库。 */
    const val MODE_EXISTING = 1

    /** 在本机新建密钥库（生成后和 MODE_EXISTING 等价，区别只在界面引导）。 */
    const val MODE_NEW = 2

    /**
     * @return 要交给打包器的签名器；null 表示继续用内置公共证书。
     *         密钥没验证通过时一律返回 null，避免拿错口令签出无法升级的产物。
     */
    fun signerFor(mode: Int, key: SigningKey?, alias: String): Signer? =
        if (mode != MODE_DEFAULT && key != null) KeyStoreApkSigner(key, alias.trim()) else null
}
