package com.jdkshen.aijspro.inrt

import android.content.Context
import android.util.Log

/**
 * task-112/113：反射调用 B 的 :script 线程宿主 InrtScriptThreadHost。
 * 普通构建/类缺失时静默失败，调用方回退主进程执行。
 */
object InrtScriptHostBridge {

    private const val TAG = "C-INRT"
    private const val HOST_CLASS = "com.jdkshen.aijspro.inrt.script.InrtScriptThreadHost"

    fun initialize(context: Context): Boolean {
        return try {
            val clazz = Class.forName(HOST_CLASS)
            val result = clazz.getMethod("initialize", Context::class.java).invoke(null, context)
            result is Boolean && result
        } catch (error: Throwable) {
            Log.w(TAG, "C-INRT: host initialize unavailable: " + error)
            false
        }
    }

    fun postOnScriptThread(runnable: Runnable): Boolean {
        return try {
            val clazz = Class.forName(HOST_CLASS)
            clazz.getMethod("postOnScriptThread", Runnable::class.java).invoke(null, runnable)
            true
        } catch (error: Throwable) {
            Log.w(TAG, "C-INRT: host postOnScriptThread failed: " + error)
            false
        }
    }
}
