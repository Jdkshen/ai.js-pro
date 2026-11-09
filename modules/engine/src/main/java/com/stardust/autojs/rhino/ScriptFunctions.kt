package com.stardust.autojs.rhino

import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject

/**
 * 脚本可见「函数包装」的共用支持（task-52）。
 *
 * <h2>为什么需要它</h2>
 * Rhino 的 `BaseFunction()` **无参构造**只设置父作用域/prototype 为 null，于是
 * `getPrototype()` 为 null → **`Function.prototype` 上的 `apply` / `call` / `bind` 一个都找不到**：
 * `typeof java.lang.reflect.Array.newInstance.apply === 'undefined'`，直接调用正常但 `.apply(...)` 被判
 * 「不是函数」；Rhino 在构造这条错误消息时还会对对象调 `toString()`，最终抛出**误导性**的
 * `TypeError: Cannot find default value for object`（真机 device4 上 `util.java.array` / `main.js:74` 就是这样挂的）。
 *
 * 修法：包装函数必须像 Rhino 官方 `NativeJavaMethod` 那样显式拿到 `Function.prototype`。
 * 每个「会被脚本当函数用的包装类」都在构造函数里写：
 * ```
 * BaseFunction(scope, ScriptFunctions.functionPrototype(scope))
 * ```
 * 这样 `apply`/`call`/`bind` 都由 Rhino 原生实现提供，**语义与原生完全一致**：
 * `f.apply(thisArg, argsArray)` 的 argsArray 支持 JS 数组、`arguments`、Java 数组（`NativeJavaArray`，
 * 靠它暴露的 `length` 属性 + 下标访问）、`null`/`undefined`（→ 0 参）；thisArg 对静态方法无意义但不会报错。
 */
object ScriptFunctions {

    /**
     * 取 `Function.prototype`（拿不到时返回 null = 退化为旧行为，不抛异常）。
     *
     * 用 [ScriptableObject.getFunctionPrototype]（等价于 `getClassPrototype(scope, "Function")`）
     * 而不是 `getPrototype()`：包装器新建时还没有原型，而它会沿作用域链找到顶层作用域上注册的
     * `FunctionPrototype`（Rhino 官方做法，也是本仓库 `ScriptJavaObject` 已有的写法）。
     */
    @JvmStatic
    fun functionPrototype(scope: Scriptable?): Scriptable? {
        if (scope == null) return null
        return try {
            ScriptableObject.getFunctionPrototype(scope)
        } catch (error: RuntimeException) {
            // 非标准作用域（没有注册 FunctionPrototype）时保持旧行为：没有原型，而不是让调用方崩掉。
            null
        }
    }
}