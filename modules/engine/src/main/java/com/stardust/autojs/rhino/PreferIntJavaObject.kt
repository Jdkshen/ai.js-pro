package com.stardust.autojs.rhino

import android.graphics.Paint
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.NativeJavaObject
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.Undefined
import java.lang.reflect.Method

/**
 * Android 12+ 给一批方法加了 `long` 重载（典型是 `Paint.setColor(long)`），
 * Rhino 对 JS 数字默认会挑到 `long` 版本，于是 `paint.setColor(颜色)` 会走进
 * `Color.colorSpace(long)` → `IllegalArgumentException: Invalid ID: xx`。
 *
 * 这个包装器在脚本读取成员时，把「同时存在 int / long 重载」的方法换成一个
 * 优先调用 int 版本的小函数，其它参数类型仍然交给 Rhino 原实现。
 */
class PreferIntJavaObject(
    scope: Scriptable,
    private val target: Any,
    staticType: Class<*>?
) : NativeJavaObject(scope, target, staticType) {

    private val cache = HashMap<String, Any?>()

    override fun get(name: String, start: Scriptable): Any? {
        if (cache.containsKey(name)) {
            return cache[name]
        }
        val result = super.get(name, start)
        val wrapped: Any? = if (result is Function) {
            val intMethod = intOverloadFor(name)
            if (intMethod == null) result else Wrapper(this, result, intMethod)
        } else {
            result
        }
        cache[name] = wrapped
        return wrapped
    }

    private fun intOverloadFor(name: String): Method? {
        val intType = Int::class.javaPrimitiveType ?: return null
        val longType = Long::class.javaPrimitiveType ?: return null
        return try {
            val intMethod = target.javaClass.getMethod(name, intType)
            target.javaClass.getMethod(name, longType)
            intMethod
        } catch (ignored: NoSuchMethodException) {
            null
        } catch (ignored: SecurityException) {
            null
        }
    }

    /** 缓存住的包装函数：数字参数走 int 重载，其它情况退化到原函数。 */
    private class Wrapper(
        private val owner: PreferIntJavaObject,
        private val original: Function,
        private val intMethod: Method
    ) : BaseFunction() {

        override fun call(cx: Context?, scope: Scriptable?, thisObj: Scriptable?, args: Array<out Any>): Any? {
            val first = if (args.isNotEmpty()) args[0] else null
            if (first is Number && args.size == intMethod.parameterCount) {
                val converted = Array<Any?>(args.size) { i -> if (i == 0) first.toInt() else args[i] }
                return try {
                    intMethod.invoke(owner.unwrap(), *converted)
                    Undefined.instance
                } catch (e: Exception) {
                    throw Context.throwAsScriptRuntimeEx(e)
                }
            }
            return original.call(cx, scope, thisObj, args)
        }
    }

    companion object {
        /** 需要走这层包装的类：目前只有 Paint（setColor 的 int/long 重载冲突）。 */
        fun supports(obj: Any?): Boolean = obj is Paint
    }
}
