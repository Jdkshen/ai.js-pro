package com.jdkshen.aijspro.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ToolkitFridaSessionTest {
    private fun rejects(block: () -> Unit) {
        try {
            block()
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    /**
     * W4 task-21：pid 是可选覆盖 —— 省略 pid 时按 packageName 解析（pid=null），
     * 显式给时必须是 >=2 的真实进程号；pid=1（init）与 pid=0 明确拒绝。
     */
    @Test
    fun pidIsOptionalButNeverDefaultsToInit() {
        val sessions = ToolkitFridaSession({ 1_000L }, ttlMillis = 60_000, maxSessions = 4)

        // 省略 pid：会话按 packageName 解析，pid 为 null（不再是「默认 1」）
        val byPackage = sessions.open(ToolkitFridaSession.Target("com.example.app", null, "127.0.0.1:27042", null), "")
        assertEquals("com.example.app", byPackage.tString("packageName"))
        assertTrue("省略 pid 时应为 JSON null", byPackage.get("pid").isJsonNull)

        // 显式 pid 仍生效
        val byPid = sessions.open(ToolkitFridaSession.Target("com.example.other", 4321, "127.0.0.1:27042", null), "")
        assertEquals(4321, byPid.get("pid").asInt)

        // pid 1 = init / pid 0：明确拒绝（不静默绑 init）
        rejects { sessions.open(ToolkitFridaSession.Target("com.example.init", 1, "127.0.0.1:27042", null), "") }
        rejects { sessions.open(ToolkitFridaSession.Target("com.example.zero", 0, "127.0.0.1:27042", null), "") }
    }

    @Test
    fun openBindsTargetAndMasksSecrets() {
        var now = 1_000L
        val sessions = ToolkitFridaSession({ now }, ttlMillis = 60_000, maxSessions = 2)
        val result = sessions.open(
            ToolkitFridaSession.Target("com.example.app", 1234, "127.0.0.1:27042", "tok-12345678"),
            "send('hello')",
        )
        val sessionId = result.tString("sessionId")
        assertTrue(sessionId.isNotEmpty())
        assertEquals("open", result.tString("state"))
        assertEquals("com.example.app", result.tString("packageName"))
        assertEquals(1234, result.get("pid").asInt)
        assertEquals("1***1:27042", result.tString("endpoint"))
        assertEquals("tok-****5678", result.tString("token"))
        assertEquals("com.example.app", result.getAsJsonObject("target").tString("packageName"))
        assertFalse(result.toString().contains("tok-12345678"))
        assertFalse(result.toString().contains("127.0.0.1:27042"))
        assertFalse(result.toString().contains("send('hello')"))
        assertEquals(1, result.get("sessions").asInt)
        assertEquals(60_000L, result.get("expiresAt").asLong - result.get("createdAt").asLong)
        val listed = sessions.list()
        assertEquals(1, listed.get("count").asInt)
        assertEquals(1, listed.getAsJsonArray("items").size())
        assertEquals("tok-****5678", listed.getAsJsonArray("items")[0].asJsonObject.tString("token"))
        assertFalse(listed.toString().contains("tok-12345678"))
    }

    @Test
    fun duplicateOpenIsRejectedUntilStopped() {
        val sessions = ToolkitFridaSession(maxSessions = 2)
        val first = sessions.open(ToolkitFridaSession.Target("com.example.app", 7, null, null), "1")
        rejects {
            sessions.open(ToolkitFridaSession.Target("com.example.app", 7, null, null), "2")
        }
        val otherPid = sessions.open(ToolkitFridaSession.Target("com.example.app", 8, null, null), "3")
        assertTrue(otherPid.tString("sessionId").isNotEmpty())
        rejects {
            sessions.open(ToolkitFridaSession.Target("com.example.app", 8, null, null), "4")
        }
        sessions.stop(otherPid.tString("sessionId"))
        assertTrue(first.tString("sessionId").isNotEmpty())
        sessions.stop(first.tString("sessionId"))
        val second = sessions.open(ToolkitFridaSession.Target("com.example.app", 7, null, null), "2")
        assertNotEquals(first.tString("sessionId"), second.tString("sessionId"))
    }

    @Test
    fun appendReadPagesAndTruncatesLongMessages() {
        var now = 10L
        val sessions = ToolkitFridaSession({ now }, maxMessages = 5, maxMessageChars = 5)
        val id = sessions.open(ToolkitFridaSession.Target("com.example.app", null, null, null), "x").tString("sessionId")
        val first = sessions.append(id, "INFO", "hello")
        assertEquals(1L, first.get("seq").asLong)
        assertFalse(first.get("truncated").asBoolean)
        now = 20L
        val second = sessions.append(id, "ERROR", "123456789")
        assertEquals(2L, second.get("seq").asLong)
        assertTrue(second.get("truncated").asBoolean)
        assertEquals(5, second.get("storedChars").asInt)
        assertEquals(9, second.get("originalChars").asInt)
        assertEquals("12345", second.tString("text"))
        val page = sessions.read(id, 0, 1)
        assertEquals(1, page.getAsJsonArray("messages").size())
        assertEquals(1, page.get("nextOffset").asInt)
        assertTrue(page.get("hasMore").asBoolean)
        assertTrue(page.get("truncated").asBoolean)
        assertTrue(page.get("pageLimited").asBoolean)
        assertFalse(page.get("pageTruncated").asBoolean)
        val rest = sessions.read(id, 1, 10)
        assertEquals(1, rest.getAsJsonArray("messages").size())
        assertEquals(2, rest.get("nextOffset").asInt)
        assertFalse(rest.get("hasMore").asBoolean)
        assertTrue(rest.get("pageTruncated").asBoolean)
        val last = sessions.read(id, 2, 1)
        assertEquals(0, last.getAsJsonArray("messages").size())
        assertEquals(2, last.get("nextOffset").asInt)
        assertFalse(last.get("hasMore").asBoolean)
        rejects { sessions.read(id, -1, 1) }
        rejects { sessions.read(id, 0, 0) }
        rejects { sessions.read(id, 3, 1) }
    }

    @Test
    fun messageLimitRejectsInsteadOfDropping() {
        val sessions = ToolkitFridaSession(maxMessages = 2)
        val id = sessions.open(ToolkitFridaSession.Target("com.example.app", 4242, null, null), "x").tString("sessionId")
        sessions.append(id, "info", "a")
        sessions.append(id, "info", "b")
        rejects { sessions.append(id, "info", "c") }
        assertEquals(2, sessions.read(id, 0, 10).get("total").asInt)
        rejects { sessions.append(id, "", "blank-level") }
    }

    @Test
    fun ttlExpiresOnReadAndFreesSessionSlot() {
        var now = 0L
        val sessions = ToolkitFridaSession({ now }, ttlMillis = 100, maxSessions = 1)
        val id = sessions.open(ToolkitFridaSession.Target("com.example.app", 4242, null, null), "x").tString("sessionId")
        sessions.append(id, "info", "before")
        now = 99L
        assertEquals(1, sessions.read(id, 0, 10).get("total").asInt)
        now = 100L
        rejects { sessions.read(id, 0, 10) }
        assertEquals(0, sessions.list().get("count").asInt)
        val replacement = sessions.open(ToolkitFridaSession.Target("com.example.app", 4242, null, null), "x")
        assertTrue(replacement.tString("sessionId").isNotEmpty())
    }

    @Test
    fun stopReportsReleasedCountsAndExpiredState() {
        var now = 5L
        val sessions = ToolkitFridaSession({ now }, ttlMillis = 50, maxMessages = 10)
        val id = sessions.open(ToolkitFridaSession.Target("com.example.app", 4242, null, "tok-abcd"), "x").tString("sessionId")
        sessions.append(id, "info", "one")
        now = 6L
        sessions.append(id, "info", "two")
        val stopped = sessions.stop(id)
        assertTrue(stopped.get("stopped").asBoolean)
        assertEquals("stopped", stopped.tString("state"))
        assertEquals(2, stopped.get("releasedMessages").asInt)
        assertEquals(1, stopped.get("releasedSessions").asInt)
        assertEquals(0, stopped.get("remainingSessions").asInt)
        assertEquals("tok-****abcd", stopped.getAsJsonObject("target").tString("token"))
        assertFalse(stopped.toString().contains("tok-abcd"))
        val missing = sessions.stop(id)
        assertFalse(missing.get("stopped").asBoolean)
        assertEquals("not_found", missing.tString("state"))
        assertEquals(0, missing.get("releasedMessages").asInt)

        val expiredId = sessions.open(ToolkitFridaSession.Target("com.example.two", 2, null, null), "x").tString("sessionId")
        sessions.append(expiredId, "info", "expired")
        now = 1_000L
        val expired = sessions.stop(expiredId)
        assertFalse(expired.get("stopped").asBoolean)
        assertEquals("expired", expired.tString("state"))
        assertEquals(1, expired.get("releasedMessages").asInt)
        assertEquals(1, expired.get("releasedSessions").asInt)
    }

    @Test
    fun sessionLimitRejectsAndExpiredSlotIsReusable() {
        var now = 0L
        val sessions = ToolkitFridaSession({ now }, ttlMillis = 10, maxSessions = 1)
        sessions.open(ToolkitFridaSession.Target("com.example.one", 4242, null, null), "x")
        rejects { sessions.open(ToolkitFridaSession.Target("com.example.two", 2, null, null), "x") }
        now = 11L
        val second = sessions.open(ToolkitFridaSession.Target("com.example.two", 2, null, null), "x")
        assertTrue(second.tString("sessionId").isNotEmpty())
    }

    @Test
    fun javaBridgeStatusDistinguishesMissingLoadErrorAndPresent() {
        val sessions = ToolkitFridaSession()
        val missing = sessions.javaBridgeStatus(false, null)
        assertFalse(missing.get("componentPresent").asBoolean)
        assertFalse(missing.get("javaPerformAvailable").asBoolean)
        assertFalse(missing.get("javaPerformVerified").asBoolean)
        assertEquals("missing", missing.tString("status"))
        assertTrue(missing.tString("message").contains("不能声称 Java.perform 可用"))
        assertTrue(missing.tString("claim").contains("不能声称 Java.perform 可用"))

        val failed = sessions.javaBridgeStatus(true, "java bridge load failed")
        assertFalse(failed.get("javaPerformAvailable").asBoolean)
        assertEquals("load_error", failed.tString("status"))
        assertTrue(failed.tString("message").contains("加载失败"))
        assertTrue(failed.tString("message").contains("不能声称 Java.perform 可用"))

        val present = sessions.javaBridgeStatus(true, null)
        assertTrue(present.get("componentPresent").asBoolean)
        assertTrue(present.get("javaPerformAvailable").asBoolean)
        assertFalse(present.get("javaPerformVerified").asBoolean)
        assertEquals("present", present.tString("status"))
        assertTrue(present.tString("message").contains("不能仅凭组件存在声称已验证"))
    }

    @Test
    fun invalidTargetsAndScriptsAreRejected() {
        val sessions = ToolkitFridaSession()
        rejects { sessions.open(ToolkitFridaSession.Target("", 4242, null, null), "x") }
        rejects { sessions.open(ToolkitFridaSession.Target("not a package", 4242, null, null), "x") }
        rejects { sessions.open(ToolkitFridaSession.Target("com.example.app", 0, null, null), "x") }
        rejects { sessions.open(ToolkitFridaSession.Target("com.example.app", 4242, "bad-endpoint", null), "x") }
        rejects { sessions.open(ToolkitFridaSession.Target("com.example.app", 4242, null, null), "x".repeat(65_537)) }
        rejects { sessions.append("missing-session", "info", "x") }
    }
}
