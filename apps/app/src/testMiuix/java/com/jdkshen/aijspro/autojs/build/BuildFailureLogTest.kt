package com.jdkshen.aijspro.autojs.build

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/**
 * [BuildFailureLog.describe] 是打包失败弹窗与日志摘要的来源。
 *
 * 用户反馈过「只显示外层消息、真实原因看不到」：这里锁住行为——沿 cause 链取信息、
 * 空 message 退化为类名、重复消息去重、异常环不死循环。
 */
class BuildFailureLogTest {

    /** 模拟“包装层自己没带消息”的常见异常（如某些库的包装异常）。 */
    private class WrapperWithoutMessage(cause: Throwable) : RuntimeException(null as String?, cause)

    @Test
    fun keepsWholeCauseChainForWrappedFailures() {
        val error = IOException(
            "Failed to sync assets/project/project.json",
            IllegalStateException("project.json 里的 engine 不认识：quickjss"))

        assertEquals(
            "Failed to sync assets/project/project.json" +
                "\nproject.json 里的 engine 不认识：quickjss",
            BuildFailureLog.describe(error))
    }

    @Test
    fun dedupesMessagesRepeatedAlongTheChain() {
        val error = IOException("加密失败", IllegalStateException("加密失败"))

        assertEquals("加密失败", BuildFailureLog.describe(error))
    }

    @Test
    fun fallsBackToClassNameWhenMessageIsMissing() {
        val error = WrapperWithoutMessage(IOException("真实原因"))

        assertEquals("WrapperWithoutMessage\n真实原因", BuildFailureLog.describe(error))
    }

    @Test
    fun handlesNullError() {
        assertEquals("未知错误", BuildFailureLog.describe(null))
    }

    @Test
    fun stopsAtCauseCycles() {
        val a = RuntimeException("A")
        val b = RuntimeException("B")
        a.initCause(b)
        b.initCause(a)

        assertEquals("A\nB", BuildFailureLog.describe(a))
    }
}
