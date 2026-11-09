package com.stardust.autojs.rhino;

/**
 * task-53：Rhino/JS↔Java 语义回归组的 **Java 探针**（纯 JVM，不依赖 Android）。
 *
 * <p>为什么不用 `android.graphics.Paint`：单测里造不出（android.jar 是 stub）；这里给出等价的
 * int/long 实例重载（覆盖 `PreferIntJavaObject` 的包装路径）与 int/long/double/String 静态重载
 * （覆盖 `AijsJavaStaticMethod` 的整数优先路径）。
 */
public class SemanticsOverloadTarget {

    /** 最近一次 setValue 命中的重载。PreferIntJavaObject 的包装器对返回值不敏感（返回 undefined），所以只能看副作用。 */
    public static String lastCall;

    // ---- 静态重载：AijsJavaStaticMethod 的「整数优先」路径 ----

    public static String overloaded(int value) { lastCall = "static-int:" + value; return "int:" + value; }

    public static String overloaded(long value) { lastCall = "static-long:" + value; return "long:" + value; }

    public static String overloaded(double value) { lastCall = "static-double:" + value; return "double:" + value; }

    public static String overloaded(String value) { lastCall = "static-string:" + value; return "string:" + value; }

    public static String overloaded(Object value) { lastCall = "static-object"; return "object"; }

    // ---- varargs / Object[] 形参：task-51 的展开边界 ----

    public static int sum(int... values) {
        int total = 0;
        for (int value : values) total += value;
        return total;
    }

    public static int firstLength(Object[] values) { return values.length; }

    // ---- 实例 int/long 重载：PreferIntJavaObject 的包装路径 ----

    public String setValue(int value) { lastCall = "instance-int:" + value; return "int:" + value; }

    public String setValue(long value) { lastCall = "instance-long:" + value; return "long:" + value; }

    /** ScriptJavaObject 只包装它自己的 observed 成员名（release/close/addView…），这里用 release。 */
    public String release() { return "released"; }
}