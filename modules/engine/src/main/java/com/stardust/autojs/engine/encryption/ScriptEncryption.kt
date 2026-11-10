package com.stardust.autojs.engine.encryption

import com.stardust.util.AdvancedEncryptionStandard

object ScriptEncryption {

    private var mKey = ""
    private var mInitVector = ""
    /** task-126：v2（AES-256-GCM）密钥（真 32 字节）。由运行端用 ScriptPayloadV2.deriveKeyV2 注入，**同一份派生**。 */
    private var mKeyV2: ByteArray = ByteArray(0)

    /** 注入 v2 密钥（32 字节）。v2 载荷只走 JVM 路径（原生库不含 GCM）。 */
    fun setKeyV2(key: ByteArray) {
        require(key.size == ScriptPayloadV2.KEY_LENGTH) { "v2 密钥必须 32 字节（当前 ${key.size}）" }
        mKeyV2 = key
    }

    fun decrypt(bytes: ByteArray, start: Int = 0, end: Int = bytes.size): ByteArray {
        // task-126：先按三级结构判定 v2（魔数 + 第 9 字节 0x02 + 3 个保留字节为 0）。
        val slice = if (start == 0 && end == bytes.size) bytes else bytes.copyOfRange(start, end)
        if (ScriptPayloadV2.isV2(slice)) {
            if (mKeyV2.size != ScriptPayloadV2.KEY_LENGTH) {
                throw IllegalStateException("v2 载荷但未注入 v2 密钥（运行端应先 setKeyV2(deriveKeyV2(...))）")
            }
            return ScriptPayloadV2.decrypt(slice, mKeyV2)
        }
        // v1 读取窗口：**临时**，下轮删除（只服务「新派生」的 v1 产物；旧 MD5 产物已在 task-122 被点名拒绝）。
        return AdvancedEncryptionStandard(mKey.toByteArray(), mInitVector).decrypt(bytes, start, end)
    }

}