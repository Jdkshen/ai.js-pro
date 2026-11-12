package com.jdkshen.aijspro.mcp

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P3-1 契约 v1 的**行为级**三态测试（纯 JVM：ToolkitIo 只依赖 gson + JDK）。
 *
 * 三态唯一口径：失败 = ok=false + reasonCode + message；空结果 = ok=true + empty=true（判据只看 empty）；
 * 成功 = ok=true + data。另外钉住「同一语义双字段」：reasonCode 与 errorCode 必须相等、error 镜像 message。
 */
class ToolkitIoThreeStateTest {

    @Test fun successCarriesDataAndNullError() {
        val payload = JsonObject().apply { addProperty("type", "missing") }
        val result = ToolkitIo.ok(payload)
        assertEquals(true, result.get("ok").asBoolean)
        assertTrue("成功必须带 data", result.has("data") && result.get("data").isJsonObject)
        assertTrue("成功时 error 必须是 null", result.get("error").isJsonNull)
        assertFalse("成功不得带 empty 判据", result.has("empty"))
    }

    @Test fun failureCarriesBothCodesAndMirrorsMessage() {
        val result = ToolkitIo.fail("invalidArg", "mode 必须是 fastest、fast、balanced 或 quality")
        assertEquals(false, result.get("ok").asBoolean)
        assertEquals("invalidArg", result.get("reasonCode").asString)
        assertEquals("同一语义双字段必须相等", result.get("reasonCode").asString, result.get("errorCode").asString)
        assertEquals("message 是权威字段", "mode 必须是 fastest、fast、balanced 或 quality", result.get("message").asString)
        assertEquals("error 必须镜像 message", result.get("message").asString, result.get("error").asString)
        assertFalse("失败不得用 empty 表达", result.has("empty"))
    }

    @Test fun emptyIsTheOnlyShapeForNoResult() {
        val result = ToolkitIo.empty()
        assertEquals("空结果仍是成功", true, result.get("ok").asBoolean)
        assertEquals("判据只看 empty", true, result.get("empty").asBoolean)
        assertTrue("data 可空或缺席", !result.has("data") || result.get("data").isJsonNull)
        assertTrue("empty 不得携带 error", result.get("error").isJsonNull)
    }
}
