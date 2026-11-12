package com.jdkshen.aijspro.mcp

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P3-1 契约 v1 的源码级钉子（离线可跑）：
 *  · ok()   必须写 error=null（成功时 error 不携带原因）；
 *  · fail() 必须**显式**写 error=message（不依赖「ok() 反正写了」这种会静默过期的假设）；
 *  · empty()必须写 ok=true + empty=true（唯一形态：判据只看 empty）；
 *  · fail() 必须同时写 reasonCode 与 message（消费方未知码时回退显示 message）。
 * 行为级三态断言（构造真实调用）在 1c 样板里补，本文件只锁「形状」。
 */
class ToolkitIoContractPinTest {
    private fun io(): String {
        val file = File("src/main/java/com/jdkshen/aijspro/mcp/ToolkitIo.kt")
        assertTrue("找不到 " + file.absolutePath, file.isFile)
        return file.readText()
    }

    @Test fun okWritesNullError() {
        val text = io()
        val okBody = text.substringAfter("fun ok(data: JsonElement").substringBefore("fun fail(")
        assertTrue("ok() 必须写 ok=true", okBody.contains("\"ok\" to true"))
        assertTrue("ok() 必须写 error=null（成功不带原因）", okBody.contains("\"error\" to null"))
    }

    @Test fun failMirrorsMessageIntoErrorExplicitly() {
        val text = io()
        val failBody = text.substringAfter("fun fail(reasonCode: String").substringBefore("fun empty(")
        assertTrue("fail() 必须写 ok=false", failBody.contains("\"ok\" to false"))
        assertTrue("fail() 必须写 reasonCode", failBody.contains("\"reasonCode\" to reasonCode"))
        assertTrue("fail() 必须写 message（权威字段）", failBody.contains("\"message\" to message"))
        assertTrue("fail() 必须**显式**写 error=message（兼容镜像，不许依赖隐含假设）",
            failBody.contains("\"error\" to message"))
    }

    @Test fun emptyIsTheOnlyShapeForNoResult() {
        val text = io()
        val emptyBody = text.substringAfter("fun empty(actions: JsonArray").substringBefore("fun ensureActive(")
        assertTrue("empty() 必须 ok=true", emptyBody.contains("\"ok\" to true"))
        assertTrue("empty() 必须 empty=true（唯一判据）", emptyBody.contains("\"empty\" to true"))
        assertTrue("empty() 不得用 error 表达失败", emptyBody.contains("\"error\" to null"))
    }
}
