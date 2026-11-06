package com.stardust.autojs.rhino

import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.NativeJavaArray
import org.mozilla.javascript.NativeJavaClass
import org.mozilla.javascript.NativeJavaObject
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.Undefined
import org.mozilla.javascript.WrappedException
import java.lang.reflect.Array as JavaArray
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * WrapFactory 路线：只替换 Java Class 的 JS 包装，不碰 Rhino 的 Packages/ClassCache/作用域树。
 *
 * 解决三件事：
 * 1) Packages.<类>.class 返回真正的 java.lang.Class 对象（而不是报 no public field/method "class"）；
 * 2) 静态方法按“整数优先”选择重载：String.valueOf(1) -> "1"，1.5 仍走 double；
 * 3) java.lang.reflect.Array.get/set 对 NativeJavaArray 先解包，并按 componentType 收窄参数
 *    （int[] 的 value 用 Integer，而不是 Double）。
 *
 * 不使用 AijsJavaPackage / 不自定义 Packages，避免 dev-1377 的 ClassCache.get 顶层作用域崩溃。
 */
internal class AijsNativeJavaClass(scope: Scriptable, private val targetClass: Class<*>) :
    NativeJavaClass(scope, targetClass) {

    override fun get(name: String, start: Scriptable): Any? {
        if (name == "class") {
            // 直接构造 java.lang.Class 的 JavaObject；不能再走 Context.javaToJS（会被本 WrapFactory 再包成 NativeJavaClass）。
            return NativeJavaObject(start, targetClass, Class::class.java)
        }
        val value = super.get(name, start)
        return if (value is Function && value !is NativeJavaClass) {
            AijsJavaStaticMethod(targetClass, name, value)
        } else value
    }
}

private class AijsJavaStaticMethod(
    private val owner: Class<*>,
    private val methodName: String,
    private val original: Function,
) : BaseFunction() {

    override fun call(cx: Context?, scope: Scriptable?, thisObj: Scriptable?, args: Array<out Any>): Any? {
        val context = cx ?: Context.getCurrentContext()
        val callScope = scope ?: thisObj
        // Array.get/set 的形参声明是 Object，Rhino 不会替我们解包 NativeJavaArray，
        // 也不会把 int[] 的 value 从 Double 收窄；这里显式处理。
        if (owner == JavaArray::class.java && args.isNotEmpty() && args[0] is NativeJavaArray) {
            val array = (args[0] as NativeJavaArray).unwrap()
            if (methodName == "set" && args.size == 3) {
                val index = (args[1] as Number).toInt()
                val componentType = array.javaClass.componentType ?: Any::class.java
                JavaArray.set(array, index, coerceArrayValue(componentType, args[2]))
                return Undefined.instance
            }
            if (methodName == "get" && args.size == 2) {
                return JavaArray.get(array, (args[1] as Number).toInt())
            }
        }

        // 只有出现“整数值的 JS number”时才尝试整数优先；普通字符串/对象/小数仍交给 Rhino 原实现。
        val hasIntegralNumber = args.any { it is Number && isIntegral(it.toDouble()) }
        if (hasIntegralNumber) {
            val picked = pickStaticOverload(args)
            if (picked != null) {
                val (method, values) = picked
                return try {
                    val result = method.invoke(null, *values!!)
                    if (method.returnType == Void.TYPE) Undefined.instance else result
                } catch (failure: InvocationTargetException) {
                    // 目标方法自己抛出的异常按 Rhino 边界语义交给脚本层（Error 由 feature 13 边界处理）。
                    throw WrappedException(failure.targetException ?: failure)
                } catch (_: IllegalArgumentException) {
                    // 只是这一组整数优先候选不匹配；回退 Rhino 原生重载解析。
                    original.call(context, callScope, thisObj, args)
                }
            }
        }
        return original.call(context, callScope, thisObj, args)
    }

    /** 返回 null 表示没有可靠的整数优先候选，调用方回退 Rhino 原生解析。 */
    private fun pickStaticOverload(args: Array<out Any>): Pair<Method, Array<Any?>>? {
        var best: Method? = null
        var bestValues: Array<Any?>? = null
        var bestScore = Int.MIN_VALUE
        for (method in owner.methods) {
            if (method.name != methodName || !Modifier.isStatic(method.modifiers) ||
                method.parameterCount != args.size || method.isBridge || method.isSynthetic) continue
            val values = arrayOfNulls<Any?>(args.size)
            var score = 0
            var compatible = true
            for (i in args.indices) {
                val converted = convertArgument(method.parameterTypes[i], args[i])
                if (converted === INVALID) {
                    compatible = false
                    break
                }
                values[i] = converted.value
                score += converted.score
            }
            if (compatible && score > bestScore) {
                best = method
                bestValues = values
                bestScore = score
            }
        }
        return if (best == null || bestValues == null) null else best to bestValues
    }

    private fun convertArgument(type: Class<*>, value: Any?): Converted {
        if (value == null) return if (type.isPrimitive) INVALID else Converted(null, 1)
        if (value is NativeJavaArray && type.isArray) return Converted(value.unwrap(), 20)
        if (value is Number) {
            val d = value.toDouble()
            val integral = isIntegral(d)
            return when (type) {
                Int::class.javaPrimitiveType, Int::class.javaObjectType ->
                    if (integral && d > Int.MIN_VALUE.toDouble() && d < Int.MAX_VALUE.toDouble()) Converted(d.toInt(), 100) else INVALID
                Long::class.javaPrimitiveType, Long::class.javaObjectType ->
                    if (integral) Converted(d.toLong(), 90) else INVALID
                Short::class.javaPrimitiveType, Short::class.javaObjectType ->
                    if (integral) Converted(d.toInt().toShort(), 80) else INVALID
                Byte::class.javaPrimitiveType, Byte::class.javaObjectType ->
                    if (integral) Converted(d.toInt().toByte(), 70) else INVALID
                Char::class.javaPrimitiveType, Char::class.javaObjectType ->
                    if (integral) Converted(d.toInt().toChar(), 60) else INVALID
                Float::class.javaPrimitiveType, Float::class.javaObjectType ->
                    if (integral) Converted(d.toFloat(), 20) else Converted(d.toFloat(), 90)
                Double::class.javaPrimitiveType, Double::class.javaObjectType ->
                    if (integral) Converted(d, 10) else Converted(d, 100)
                Number::class.java -> if (integral) Converted(d.toInt(), 5) else Converted(d, 5)
                Any::class.java -> if (integral) Converted(d.toInt(), 1) else Converted(d, 1)
                else -> if (type.isInstance(value)) Converted(value, 1) else INVALID
            }
        }
        if (type.isInstance(value)) return Converted(value, 30)
        if (type == Any::class.java) return Converted(value, 1)
        if (type == String::class.java && value is CharSequence) return Converted(value.toString(), 30)
        if (type == CharSequence::class.java && value is CharSequence) return Converted(value, 20)
        return INVALID
    }

    private fun coerceArrayValue(type: Class<*>, value: Any?): Any? {
        if (value == null) return null
        if (value is Double) {
            val d = value
            return when (type) {
                Int::class.javaPrimitiveType -> d.toInt()
                Long::class.javaPrimitiveType -> d.toLong()
                Short::class.javaPrimitiveType -> d.toInt().toShort()
                Byte::class.javaPrimitiveType -> d.toInt().toByte()
                Char::class.javaPrimitiveType -> d.toInt().toChar()
                Float::class.javaPrimitiveType -> d.toFloat()
                Double::class.javaPrimitiveType -> d
                else -> value
            }
        }
        if (value is Number) {
            return when (type) {
                Int::class.javaPrimitiveType -> value.toInt()
                Long::class.javaPrimitiveType -> value.toLong()
                Short::class.javaPrimitiveType -> value.toInt().toShort()
                Byte::class.javaPrimitiveType -> value.toInt().toByte()
                Float::class.javaPrimitiveType -> value.toFloat()
                Double::class.javaPrimitiveType -> value.toDouble()
                else -> value
            }
        }
        return value
    }

    private fun isIntegral(value: Double): Boolean =
        value == Math.rint(value) && !value.isInfinite() && !value.isNaN()

    private class Converted(val value: Any?, val score: Int)

    private companion object {
        val INVALID = Converted(null, Int.MIN_VALUE)
    }
}
