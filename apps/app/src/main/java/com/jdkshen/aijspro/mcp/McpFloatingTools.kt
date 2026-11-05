package com.jdkshen.aijspro.mcp

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.gson.JsonObject
import com.jdkshen.aijspro.ui.floating.FloatyWindowManger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 应用自身悬浮球（CircularMenu）的状态/显示/关闭入口。
 *
 * 为什么需要它：用户的悬浮球一旦关不掉（见 CircularMenuWindow.close 的历史缺陷：三步移除串在一个
 * try 里、异常被吞），在设备上没有任何可靠的关闭手段。这里给出一个**确定能关**的入口，
 * 同时把真实状态（showing/suppressed/windowType）暴露出来，避免「看起来关了其实还在」。
 */
internal object McpFloatingTools {
    val names = setOf("floating_menu")

    fun call(context: Context, name: String, args: JsonObject): JsonObject {
        val action = args.tString("action", "state").lowercase()
        return when (action) {
            "state" -> ToolkitIo.ok(state())
            "show" -> onMain {
                FloatyWindowManger.showCircularMenu()
                state()
            }
            "close", "hide" -> onMain {
                FloatyWindowManger.hideCircularMenu()
                state()
            }
            else -> throw ToolkitException("INVALID_ARGUMENT", "action 只能是 state / show / close", "action")
        }
    }

    private fun state(): JsonObject = ToolkitIo.json(
        "showing" to FloatyWindowManger.isCircularMenuShowing(),
        "suppressed" to FloatyWindowManger.isCircularMenuSuppressed(),
        "windowType" to FloatyWindowManger.getWindowType(),
        "note" to "showing 来自主进程内存引用；列表里的窗口才是真实存在（见 list_overlays / dumpsys window）"
    )

    /** 悬浮球必须在主线程操作（WindowManager + GlobalAppContext）。 */
    private fun onMain(block: () -> JsonObject): JsonObject {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return block()
        }
        val result = AtomicReference<JsonObject>()
        val failure = AtomicReference<Throwable>()
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            try {
                result.set(block())
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(5, TimeUnit.SECONDS)) {
            throw ToolkitException("TIMEOUT", "主线程 5 秒内没有完成悬浮球操作", "action")
        }
        failure.get()?.let { throw it }
        return result.get() ?: ToolkitIo.json("showing" to FloatyWindowManger.isCircularMenuShowing())
    }
}
