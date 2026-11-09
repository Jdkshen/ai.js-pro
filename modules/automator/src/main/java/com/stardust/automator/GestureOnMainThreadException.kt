package com.stardust.automator

/**
 * task-60（P0-3 取证结论）：**主线程**上跑阻塞手势在我们这里不会抛错，只会把 UI 线程阻塞到手势结束
 * （证据：GlobalActionAutomator.gesturesWithHandler 用 VolatileDispose.blockedGet()，即无超时 wait()，
 *  而回调由 servant looper 线程投递 —— 见 modules/engine/.../core/looper/Loopers.java:124-148）。
 * 真正**必然死锁且没有任何提示**的是另一种情况：在「回调投递线程」（即 GlobalActionAutomator 构造时传入的
 * Handler 所在 looper 线程）上再调阻塞手势 —— 那条线程自己要 wait，回调永远送不到。
 *
 * 所以这里给的是**具名异常**（对齐 Pro 的 com.stardust.automator.GestureOnMainThreadException 语义，自研实现），
 * 只用于「必然死锁」这一条路径；主线程可用但会卡 UI 的情况只记一条 warning，不改变既有行为（避免误伤现在能跑的脚本）。
 */
class GestureOnMainThreadException(
    /** 给脚本看的原因（点名线程与改法）。 */
    val reason: String,
) : RuntimeException(reason)

/** 纯判定/文案（不依赖 android.os.Looper，便于单测）。 */
object GestureThreadGuard {
    const val HINT_ASYNC = "改用异步形式（如 gesturesAsync/clickAsync 等不等待回调的接口），或把阻塞手势放到脚本线程/子线程执行。"

    /**
     * 阻塞手势是否必须拒绝：调用线程 == 回调投递线程时，blockedGet() 会等一个只有该线程自己能投递的回调 → 必然死锁。
     */
    @JvmStatic
    fun refusesBlockingGesture(callerThreadName: String, callerOwnsCallbackLooper: Boolean): String? {
        if (!callerOwnsCallbackLooper) return null
        return "不能在手势回调线程上提交阻塞手势：当前线程「" + callerThreadName +
            "」就是投递手势回调的线程，阻塞等待会永远等不到回调（不会抛错、表现为脚本卡死）。" + HINT_ASYNC
    }

    /** 主线程可用但会卡 UI：给一条可观察的 warning 文案（不抛）。 */
    @JvmStatic
    fun mainThreadWarning(callerThreadName: String, gestureDescription: String): String =
        "在主线程「" + callerThreadName + "」提交阻塞手势：" + gestureDescription +
            " 会阻塞 UI 直到手势结束（长按/慢滑可能触发 ANR 观感）。" + HINT_ASYNC
}
