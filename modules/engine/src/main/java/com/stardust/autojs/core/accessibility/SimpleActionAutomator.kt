package com.stardust.autojs.core.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import androidx.annotation.RequiresApi
import com.stardust.app.GlobalAppContext
import com.stardust.autojs.annotation.ScriptInterface
import com.stardust.autojs.core.image.ImageWrapper
import com.stardust.autojs.core.image.capture.AccessibilityScreenCapturer
import com.stardust.autojs.core.util.ProcessShell
import com.stardust.autojs.runtime.ScriptRuntime
import com.stardust.autojs.runtime.accessibility.AccessibilityConfig
import com.stardust.autojs.runtime.api.remote.RemoteAutomationBridge
import com.stardust.autojs.runtime.exception.ScriptException
import com.stardust.autojs.runtime.api.ShizukuShell
import com.stardust.automator.GlobalActionAutomator
import com.stardust.automator.UiObject
import com.stardust.automator.simple_action.ActionFactory
import com.stardust.automator.simple_action.ActionTarget
import com.stardust.automator.simple_action.SimpleAction
import com.stardust.util.DeveloperUtils
import com.stardust.util.ScreenMetrics

import org.json.JSONArray
import org.json.JSONObject

import java.io.File

/**
 * Created by Stardust on 2017/4/2.
 */

class SimpleActionAutomator(private val mAccessibilityBridge: AccessibilityBridge, private val mScriptRuntime: ScriptRuntime) {

    private lateinit var mGlobalActionAutomator: GlobalActionAutomator

    private var mScreenMetrics: ScreenMetrics? = null

    init {
        // PDF §2.1.3.1：UiObject.clickCenter() 需要坐标点击，而 automator 模块拿不到本类。
        // 必须在这里（构造时）就注入：如果放在 prepareForGesture() 里，
        // 第一次直接调 clickCenter()（没先调过 click）会因 clicker 为 null 而返回 false。
        UiObject.coordinateClicker = { x, y -> click(x, y) }
    }

    private val isRunningPackageSelf: Boolean
        get() = DeveloperUtils.isSelfPackage(mAccessibilityBridge.infoProvider.latestPackage)

    @ScriptInterface
    fun text(text: String, i: Int): ActionTarget {
        return ActionTarget.TextActionTarget(text, i)
    }

    @ScriptInterface
    fun bounds(left: Int, top: Int, right: Int, bottom: Int): ActionTarget {
        return ActionTarget.BoundsActionTarget(Rect(left, top, right, bottom))
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    @ScriptInterface
    fun editable(i: Int): ActionTarget {
        ScriptRuntime.requiresApi(Build.VERSION_CODES.LOLLIPOP)
        return ActionTarget.EditableActionTarget(i)
    }

    @ScriptInterface
    fun id(id: String): ActionTarget {
        return ActionTarget.IdActionTarget(id)
    }

    @ScriptInterface
    fun click(target: ActionTarget): Boolean {
        return performAction(target.createAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    @ScriptInterface
    fun longClick(target: ActionTarget): Boolean {
        return performAction(target.createAction(AccessibilityNodeInfo.ACTION_LONG_CLICK))
    }

    @ScriptInterface
    fun scrollUp(target: ActionTarget): Boolean {
        return performAction(target.createAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD))
    }

    @ScriptInterface
    fun scrollDown(target: ActionTarget): Boolean {
        return performAction(target.createAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
    }

    @ScriptInterface
    fun scrollBackward(i: Int): Boolean {
        return performAction(ActionFactory.createScrollAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, i))
    }

    @ScriptInterface
    fun scrollForward(i: Int): Boolean {
        return performAction(ActionFactory.createScrollAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, i))
    }

    @ScriptInterface
    fun scrollMaxBackward(): Boolean {
        return performAction(ActionFactory.createScrollMaxAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD))
    }

    @ScriptInterface
    fun scrollMaxForward(): Boolean {
        return performAction(ActionFactory.createScrollMaxAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
    }

    @ScriptInterface
    fun focus(target: ActionTarget): Boolean {
        return performAction(target.createAction(AccessibilityNodeInfo.ACTION_FOCUS))
    }

    @ScriptInterface
    fun select(target: ActionTarget): Boolean {
        return performAction(target.createAction(AccessibilityNodeInfo.ACTION_SELECT))
    }

    @ScriptInterface
    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    fun setText(target: ActionTarget, text: String): Boolean {
        ScriptRuntime.requiresApi(Build.VERSION_CODES.LOLLIPOP)
        return performAction(target.createAction(AccessibilityNodeInfo.ACTION_SET_TEXT, text))
    }

    @ScriptInterface
    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    fun appendText(target: ActionTarget, text: String): Boolean {
        ScriptRuntime.requiresApi(Build.VERSION_CODES.LOLLIPOP)
        return performAction(target.createAction(UiObject.ACTION_APPEND_TEXT, text))
    }

    @ScriptInterface
    fun back(): Boolean {
        return performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    }

    @ScriptInterface
    fun home(): Boolean {
        return performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
    }

    @ScriptInterface
    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    fun powerDialog(): Boolean {
        ScriptRuntime.requiresApi(Build.VERSION_CODES.LOLLIPOP)
        return performGlobalAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)
    }

    @ScriptInterface
    fun notifications(): Boolean {
        return performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
    }

    @ScriptInterface
    fun quickSettings(): Boolean {
        return performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
    }

    @ScriptInterface
    fun recents(): Boolean {
        return performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
    }

    @ScriptInterface
    @RequiresApi(api = Build.VERSION_CODES.N)
    fun splitScreen(): Boolean {
        return performGlobalAction(AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
    }

    @ScriptInterface
    @RequiresApi(api = Build.VERSION_CODES.N)
    fun gesture(start: Long, duration: Long, vararg points: IntArray): Boolean {
        if (RemoteAutomationBridge.isActive()) {
            return RemoteAutomationBridge.gesture(strokesJson(start, duration, points))
        }
        prepareForGesture()
        return mGlobalActionAutomator.gesture(start, duration, *points)
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    fun gestureAsync(start: Long, duration: Long, vararg points: IntArray) {
        if (RemoteAutomationBridge.isActive()) {
            throw ScriptException("隔离进程暂不支持 automator.gestureAsync()，请改用 gesture()")
        }
        prepareForGesture()
        mGlobalActionAutomator.gestureAsync(start, duration, *points)
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    fun gestures(strokes: Any): Boolean {
        if (RemoteAutomationBridge.isActive()) {
            throw ScriptException("隔离进程暂不支持 automator.gestures(strokes)，请改用 gesture(start, duration, points)")
        }
        prepareForGesture()
        @Suppress("UNCHECKED_CAST")
        return mGlobalActionAutomator.gestures(*strokes as Array<GestureDescription.StrokeDescription>)
    }

    //如果这里用GestureDescription.StrokeDescription[]为参数，安卓7.0以下会因为找不到这个类而报错
    @RequiresApi(api = Build.VERSION_CODES.N)
    fun gesturesAsync(strokes: Any) {
        if (RemoteAutomationBridge.isActive()) {
            throw ScriptException("隔离进程暂不支持 automator.gesturesAsync(strokes)，请改用 gesture(start, duration, points)")
        }
        prepareForGesture()
        @Suppress("UNCHECKED_CAST")
        mGlobalActionAutomator.gesturesAsync(*strokes as Array<GestureDescription.StrokeDescription>)
    }

    private fun prepareForGesture() {
        ScriptRuntime.requiresApi(24)
        if (!::mGlobalActionAutomator.isInitialized) {
            mGlobalActionAutomator = GlobalActionAutomator(Handler(mScriptRuntime.loopers.servantLooper)) {
                ensureAccessibilityServiceEnabled()
                return@GlobalActionAutomator mAccessibilityBridge.service!!
            }
        }
        mGlobalActionAutomator.setScreenMetrics(mScreenMetrics)
    }

    @ScriptInterface
    @RequiresApi(api = Build.VERSION_CODES.N)
    fun click(x: Int, y: Int): Boolean {
        if (RemoteAutomationBridge.isActive()) {
            // AIDL 坐标约定=设备像素；脚本坐标先按 ScreenMetrics 换算（与本地 GlobalActionAutomator 一致）。
            return RemoteAutomationBridge.click(scaleRemoteX(x), scaleRemoteY(y))
        }
        prepareForGesture()
        return mGlobalActionAutomator.click(x, y)
    }

    @ScriptInterface
    @RequiresApi(api = Build.VERSION_CODES.N)
    fun press(x: Int, y: Int, delay: Int): Boolean {
        if (RemoteAutomationBridge.isActive()) {
            // AIDL 契约只有 longClick；按下时长差异（delay）批一不透传，统一按长按处理。
            return RemoteAutomationBridge.longClick(scaleRemoteX(x), scaleRemoteY(y))
        }
        prepareForGesture()
        return mGlobalActionAutomator.press(x, y, delay)
    }

    @ScriptInterface
    @RequiresApi(api = Build.VERSION_CODES.N)
    fun longClick(x: Int, y: Int): Boolean {
        if (RemoteAutomationBridge.isActive()) {
            return RemoteAutomationBridge.longClick(scaleRemoteX(x), scaleRemoteY(y))
        }
        prepareForGesture()
        return mGlobalActionAutomator.longClick(x, y)
    }

    @ScriptInterface
    @RequiresApi(api = Build.VERSION_CODES.N)
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, delay: Int): Boolean {
        if (RemoteAutomationBridge.isActive()) {
            return RemoteAutomationBridge.swipe(
                    scaleRemoteX(x1), scaleRemoteY(y1),
                    scaleRemoteX(x2), scaleRemoteY(y2), delay.toLong())
        }
        prepareForGesture()
        return mGlobalActionAutomator.swipe(x1, y1, x2, y2, delay.toLong())
    }

    private fun performGlobalAction(action: Int): Boolean {
        if (RemoteAutomationBridge.isActive()) {
            return when (action) {
                AccessibilityService.GLOBAL_ACTION_BACK -> RemoteAutomationBridge.back()
                AccessibilityService.GLOBAL_ACTION_HOME -> RemoteAutomationBridge.home()
                AccessibilityService.GLOBAL_ACTION_RECENTS -> RemoteAutomationBridge.recents()
                AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS -> RemoteAutomationBridge.notifications()
                else -> throw ScriptException(
                        "隔离进程暂不支持该全局动作（action=" + action
                                + "），批一只代理 back/home/recents/notifications")
            }
        }
        ensureAccessibilityServiceEnabled()
        val service = mAccessibilityBridge.service ?: return false
        return service.performGlobalAction(action)
    }

    /**
     * PDF §2.1.3.2（Pro 8.8.0 新增）：使用无障碍权限截图，返回 Image。
     * 不需要申请投屏权限，但受系统频率限制（Android 15 要求两次间隔 > 333ms，
     * [AccessibilityScreenCapturer] 会排队等待而不是抛错——比 Pro 的“太快就抛异常”更宽容）。
     */
    @ScriptInterface
    @RequiresApi(api = Build.VERSION_CODES.R)
    fun takeScreenshot(): ImageWrapper {
        if (RemoteAutomationBridge.isActive()) {
            return captureRemoteScreenshot()
        }
        ensureAccessibilityServiceEnabled()
        return ImageWrapper.ofBitmap(AccessibilityScreenCapturer().capture(SCREENSHOT_TIMEOUT_MILLIS))
    }

    /**
     * PDF §2.1.3.3（Pro 8.8.0 新增）：切换到指定包名的输入法。
     * 先试公开 API，再试写安全设置（需要 WRITE_SECURE_SETTINGS），最后读回系统当前输入法自证。
     */
    @ScriptInterface
    fun switchToInputMethod(packageName: String): Boolean {
        if (packageName.isEmpty()) return false
        val context = GlobalAppContext.get() ?: return false
        val inputMethodManager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                ?: return false
        val inputMethodId = findEnabledInputMethodId(inputMethodManager, packageName) ?: return false
        try {
            inputMethodManager.setInputMethod(null, inputMethodId)
        } catch (e: Throwable) {
            // 没有权限时系统会抛异常，继续尝试写安全设置
        }
        if (waitForInputMethod(context, inputMethodId)) return true
        return try {
            Settings.Secure.putString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD, inputMethodId)
                    && waitForInputMethod(context, inputMethodId)
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * PDF §2.1.3.4（Pro 8.8.0 新增）：模拟耳机键，用于挂断/接听电话、播放/暂停音乐。
     * 依次尝试：Shizuku 注入 → root 注入 → AudioManager 媒体键（无 root 时的降级路径）。
     */
    @ScriptInterface
    fun headsetHook(): Boolean {
        if (injectKeyEventViaShizuku(HEADSET_HOOK_KEYCODE)) return true
        if (injectKeyEventViaRoot(HEADSET_HOOK_KEYCODE)) return true
        return dispatchMediaKeyEvent(HEADSET_HOOK_KEYCODE)
    }

    private fun findEnabledInputMethodId(inputMethodManager: InputMethodManager, packageName: String): String? {
        val methods = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                inputMethodManager.enabledInputMethodList
            } else {
                inputMethodManager.inputMethodList
            }
        } catch (e: Throwable) {
            return null
        }
        return methods?.firstOrNull { it.packageName == packageName }?.id
    }

    private fun waitForInputMethod(context: Context, inputMethodId: String): Boolean {
        for (i in 0 until INPUT_METHOD_SWITCH_TRIES) {
            val current = try {
                Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            } catch (e: Throwable) {
                null
            }
            if (current == inputMethodId) return true
            try {
                Thread.sleep(INPUT_METHOD_SWITCH_INTERVAL_MILLIS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return false
    }

    private fun injectKeyEventViaShizuku(keyCode: Int): Boolean {
        if (!ShizukuShell.isAvailable() || !ShizukuShell.hasPermission()) return false
        return try {
            ShizukuShell.execute("input keyevent $keyCode", SHELL_TIMEOUT_MILLIS, 0).code == 0
        } catch (e: Throwable) {
            false
        }
    }

    private fun injectKeyEventViaRoot(keyCode: Int): Boolean {
        return try {
            ProcessShell.exec(arrayOf("input", "keyevent", keyCode.toString()), true).code == 0
        } catch (e: Throwable) {
            false
        }
    }

    private fun dispatchMediaKeyEvent(keyCode: Int): Boolean {
        val context = GlobalAppContext.get() ?: return false
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return try {
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            true
        } catch (e: Throwable) {
            false
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
    @ScriptInterface
    fun paste(target: ActionTarget): Boolean {
        ScriptRuntime.requiresApi(18)
        return performAction(target.createAction(AccessibilityNodeInfo.ACTION_PASTE))
    }

    private fun ensureAccessibilityServiceEnabled() {
        if (RemoteAutomationBridge.isActive()) {
            // Phase1 P1：:script 进程里无障碍服务在主进程，走远程可用性检查（中文错误同主进程版语义）。
            RemoteAutomationBridge.ensureAccessibility()
            return
        }
        mAccessibilityBridge.ensureServiceEnabled()
    }

    private fun performAction(simpleAction: SimpleAction): Boolean {
        if (RemoteAutomationBridge.isActive()) {
            throw ScriptException(
                    "隔离进程暂不支持 automator 的 ActionTarget 形式（如 click(\"文本\")/scrollForward(target)）；"
                            + "请改用选择器链，如 text(\"文本\").findOne().click()")
        }
        ensureAccessibilityServiceEnabled()
        if (AccessibilityConfig.isUnintendedGuardEnabled() && isRunningPackageSelf) {
            return false
        }
        val roots = mAccessibilityBridge.windowRoots().filter { it != null }
        if (roots.isEmpty())
            return false
        var succeed = true
        for (root in roots) {
            succeed = succeed and simpleAction.perform(UiObject.createRoot(root))
        }
        return succeed
    }

    fun setScreenMetrics(metrics: ScreenMetrics) {
        mScreenMetrics = metrics
    }

    // ---- Phase1 P1 远程自动化辅助（:script 进程 -> 主进程 AutomationHostService） ------

    /** AIDL 坐标=设备像素：脚本坐标按 ScreenMetrics 换算（与本地 GlobalActionAutomator 的规则一致）。 */
    private fun scaleRemoteX(x: Int): Int = mScreenMetrics?.scaleX(x) ?: x

    private fun scaleRemoteY(y: Int): Int = mScreenMetrics?.scaleY(y) ?: y

    /** 把 gesture(start, duration, points) 转成 IAutomationHost 契约的 strokesJson（单条 stroke）。 */
    private fun strokesJson(start: Long, duration: Long, points: Array<out IntArray>): String {
        val stroke = JSONArray()
        val count = points.size
        for (i in 0 until count) {
            val point = points[i]
            val x = scaleRemoteX(if (point.isNotEmpty()) point[0] else 0)
            val y = scaleRemoteY(if (point.size > 1) point[1] else 0)
            val t = if (count <= 1) start else start + duration * i / (count - 1)
            stroke.put(JSONObject().put("x", x).put("y", y).put("t", t))
        }
        return JSONArray().put(stroke).toString()
    }

    /** 远程截图：主进程把 PNG 落盘到 :script 缓存目录，这里解码成与 takeScreenshot 同型的 ImageWrapper。 */
    private fun captureRemoteScreenshot(): ImageWrapper {
        val context = GlobalAppContext.get()
                ?: throw ScriptException("隔离进程截图失败：应用上下文不可用")
        val target = File(context.cacheDir, "remote-shot-" + SystemClock.elapsedRealtime() + ".png")
        val saved = RemoteAutomationBridge.screenshot(target.absolutePath)
        val file = if (saved.isNullOrEmpty()) target else File(saved)
        return try {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                    ?: throw ScriptException("隔离进程截图失败：主进程返回的图片无法解码")
            ImageWrapper.ofBitmap(bitmap)
        } finally {
            file.delete()
        }
    }

    private companion object {
        /** 无障碍截图等待预算：文档说系统限制至少一秒一次，留出排队余量。 */
        const val SCREENSHOT_TIMEOUT_MILLIS = 2000L
        val HEADSET_HOOK_KEYCODE = KeyEvent.KEYCODE_HEADSETHOOK
        const val INPUT_METHOD_SWITCH_TRIES = 5
        const val INPUT_METHOD_SWITCH_INTERVAL_MILLIS = 100L
        const val SHELL_TIMEOUT_MILLIS = 3000
    }

}
