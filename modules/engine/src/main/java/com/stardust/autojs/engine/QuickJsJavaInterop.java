package com.stardust.autojs.engine;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * QuickJS 的 Java 互操作层。
 *
 * 用户拍板采用与 Rhino 相同的模型（完整 public 反射）：`Packages` / `importClass` /
 * `importPackage` / `Java.type` 都能解析任意类，类与实例统一用 long 句柄跨边界，
 * JS 侧是 Proxy 包装，参数/返回值用「原生 JSON + {"__ref":handle}」协议编解码。
 *
 * 注意：放开任意反射意味着脚本能做的操作与 Rhino 完全一致（包括调用系统 API），
 * 这是与 Rhino 对齐的既定设计取舍。
 */
final class QuickJsJavaInterop {

    private static final String TAG = "QuickJsJavaInterop";
    private static final int MAX_HANDLES = 65536;

    private final Map<Long, Object> mHandles = new ConcurrentHashMap<>();
    private final AtomicLong mNextHandle = new AtomicLong(1);
    private final Map<String, Long> mClassHandles = new ConcurrentHashMap<>();
    private final Map<String, List<Method>> mMethodCache = new ConcurrentHashMap<>();
    private final Map<String, Field> mFieldCache = new ConcurrentHashMap<>();
    private final Map<String, Boolean> mNoFieldCache = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------
    // 句柄与类解析
    // ------------------------------------------------------------------

    private long put(Object value) {
        if (mHandles.size() >= MAX_HANDLES) {
            Log.w(TAG, "Java handle limit reached, dropping stale handles");
            mHandles.clear();
            mClassHandles.clear();
        }
        long handle = mNextHandle.getAndIncrement();
        mHandles.put(handle, value);
        return handle;
    }

    /** 给脚本侧对象（如 Android Context）注册句柄用。 */
    long putForScript(Object value) {
        return put(value);
    }

    /** 按句柄取回对象（不存在返回 null，调用方决定报错方式）。 */
    Object objectForHandle(long handle) {
        return mHandles.get(handle);
    }

    private Object requireHandle(long handle) {        Object value = mHandles.get(handle);
        if (value == null) {
            throw new IllegalStateException("Java 对象句柄已失效：" + handle);
        }
        return value;
    }

    /** 解析类名（全名，如 `android.content.Intent`）。找不到返回 0。 */
    public long resolveClass(String name) {
        if (name == null || name.isEmpty()) {
            return 0;
        }
        Long cached = mClassHandles.get(name);
        if (cached != null) {
            return cached;
        }
        try {
            Class<?> type = Class.forName(name, false, classLoader());
            long handle = put(type);
            mClassHandles.put(name, handle);
            return handle;
        } catch (Throwable error) {
            return 0;
        }
    }

    private ClassLoader classLoader() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return loader != null ? loader : QuickJsJavaInterop.class.getClassLoader();
    }

    /** `m` 方法 / `f` 字段 / `p:<getter>` JavaBean 属性 / 空串都没有。 */
    public String probe(long handle, String name) {
        if (name == null || name.isEmpty()) {
            return "";
        }
        Object receiver = mHandles.get(handle);
        if (receiver == null) {
            return "";
        }
        Class<?> type = receiver instanceof Class ? (Class<?>) receiver : receiver.getClass();
        if (!methodsNamed(type, name).isEmpty()) {
            return "m";
        }
        if (findField(type, name) != null) {
            return "f";
        }
        // Rhino 的 JavaBean 语义：没有同名方法/字段时，getName()/isName() 当属性用。
        if (name.length() > 1) {
            String suffix = Character.toUpperCase(name.charAt(0)) + name.substring(1);
            if (!methodsNamed(type, "get" + suffix).isEmpty()) {
                return "p:get" + suffix;
            }
            if (!methodsNamed(type, "is" + suffix).isEmpty()) {
                return "p:is" + suffix;
            }
        }
        return "";
    }

    // ------------------------------------------------------------------
    // 调用 / 字段 / 构造
    // ------------------------------------------------------------------

    public String call(long handle, String name, String argsJson) throws JSONException {
        Object receiver = requireHandle(handle);
        boolean staticReceiver = receiver instanceof Class;
        Class<?> type = staticReceiver ? (Class<?>) receiver : receiver.getClass();
        List<Object> rawArgs = parseArgs(argsJson);
        List<Method> candidates = methodsNamed(type, name);
        if (candidates.isEmpty()) {
            Field field = findField(type, name);
            if (field != null) {
                return encode(readField(field, staticReceiver ? null : receiver));
            }
            throw new IllegalArgumentException("找不到方法或字段：" + type.getName() + "." + name);
        }
        Method method = null;
        Object[] converted = null;
        int bestScore = Integer.MIN_VALUE;
        for (Method candidate : candidates) {
            if (staticReceiver && !Modifier.isStatic(candidate.getModifiers())) {
                continue;
            }
            int score;
            Object[] args;
            try {
                ScoreAndArgs scored = scoreAndConvert(candidate, rawArgs);
                score = scored.score;
                args = scored.args;
            } catch (RuntimeException incompatible) {
                continue;
            }
            if (score > bestScore) {
                bestScore = score;
                method = candidate;
                converted = args;
            }
        }
        if (method == null) {
            throw new IllegalArgumentException("没有匹配参数的方法：" + type.getName()
                    + "." + name + "(" + rawArgs.size() + " 个参数)");
        }
        // InvocationFailure 直接向上抛：native 会把它转成脚本侧的 Error（消息带 Java 异常类名）。
        return encode(invoke(method, staticReceiver ? null : receiver, converted));
    }

    public String getField(long handle, String name) throws JSONException {
        Object receiver = requireHandle(handle);
        boolean staticReceiver = receiver instanceof Class;
        Class<?> type = staticReceiver ? (Class<?>) receiver : receiver.getClass();
        Field field = findField(type, name);
        if (field == null) {
            throw new IllegalArgumentException("找不到字段：" + type.getName() + "." + name);
        }
        if (staticReceiver && !Modifier.isStatic(field.getModifiers())) {
            throw new IllegalArgumentException("字段不是静态字段：" + type.getName() + "." + name);
        }
        return encode(readField(field, staticReceiver ? null : receiver));
    }

    public boolean setField(long handle, String name, String valueJson) throws JSONException {
        Object receiver = requireHandle(handle);
        boolean staticReceiver = receiver instanceof Class;
        Class<?> type = staticReceiver ? (Class<?>) receiver : receiver.getClass();
        Field field = findField(type, name);
        if (field == null) {
            throw new IllegalArgumentException("找不到字段：" + type.getName() + "." + name);
        }
        Object value = convertValue(field.getType(), parseSingleArg(valueJson));
        try {
            field.set(staticReceiver ? null : receiver, value);
            return true;
        } catch (IllegalAccessException error) {
            throw new IllegalStateException("无法写入字段：" + type.getName() + "." + name, error);
        }
    }

    public String instantiate(long classHandle, String argsJson) throws JSONException {
        Object receiver = requireHandle(classHandle);
        if (!(receiver instanceof Class)) {
            throw new IllegalArgumentException("不是 Java 类句柄");
        }
        Class<?> type = (Class<?>) receiver;
        List<Object> rawArgs = parseArgs(argsJson);
        Constructor<?> best = null;
        Object[] converted = null;
        int bestScore = Integer.MIN_VALUE;
        for (Constructor<?> constructor : type.getConstructors()) {
            try {
                ScoreAndArgs scored = scoreAndConvert(constructor.getParameterTypes(), constructor.isVarArgs(),
                        rawArgs);
                if (scored.score > bestScore) {
                    bestScore = scored.score;
                    best = constructor;
                    converted = scored.args;
                }
            } catch (RuntimeException incompatible) {
                // 尝试下一个构造器
            }
        }
        if (best == null) {
            throw new IllegalArgumentException("没有匹配参数的构造器：" + type.getName()
                    + "(" + rawArgs.size() + " 个参数)");
        }
        try {
            return encode(best.newInstance(converted));
        } catch (InvocationTargetException error) {
            throw new IllegalStateException(describeTargetError(error));
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("构造失败：" + type.getName(), error);
        }    }

    /** 引擎关闭时释放句柄（避免脚本结束后仍持有 Java 对象引用）。 */
    public void clear() {
        mHandles.clear();
        mClassHandles.clear();
        mMethodCache.clear();
        mFieldCache.clear();
        mNoFieldCache.clear();
    }

    // ------------------------------------------------------------------
    // 反射辅助
    // ------------------------------------------------------------------

    private List<Method> methodsNamed(Class<?> type, String name) {
        String key = type.getName() + '#' + name;
        List<Method> cached = mMethodCache.get(key);
        if (cached != null) {
            return cached;
        }
        List<Method> result = new ArrayList<>();
        try {
            for (Method method : type.getMethods()) {
                if (method.getName().equals(name)) {
                    result.add(method);
                }
            }
        } catch (Throwable error) {
            Log.w(TAG, "Cannot enumerate methods of " + type.getName(), error);
        }
        mMethodCache.put(key, result);
        return result;
    }

    private Field findField(Class<?> type, String name) {
        String key = type.getName() + '#' + name;
        if (mNoFieldCache.containsKey(key)) {
            return null;
        }
        Field cached = mFieldCache.get(key);
        if (cached != null) {
            return cached;
        }
        try {
            Field field = type.getField(name);
            mFieldCache.put(key, field);
            return field;
        } catch (NoSuchFieldException error) {
            mNoFieldCache.put(key, Boolean.TRUE);
            return null;
        }
    }

    private Object readField(Field field, Object receiver) {
        try {
            return field.get(receiver);
        } catch (IllegalAccessException error) {
            throw new IllegalStateException("无法读取字段：" + field.getName(), error);
        }
    }

    private Object invoke(Method method, Object receiver, Object[] args) {
        try {
            return method.invoke(receiver, args);
        } catch (InvocationTargetException error) {
            throw new InvocationFailure(describeTargetError(error));
        } catch (ReflectiveOperationException error) {
            throw new InvocationFailure("Java 调用失败：" + method.getName() + " - " + error);
        }
    }

    private static String describeTargetError(InvocationTargetException error) {
        Throwable cause = error.getTargetException();
        if (cause == null) {
            return "Java 调用抛出异常";
        }
        String message = cause.getMessage();
        return cause.getClass().getName() + (message == null || message.isEmpty() ? "" : ": " + message);
    }

    private static final class InvocationFailure extends RuntimeException {
        InvocationFailure(String message) {
            super(message);
        }
    }

    private static final class ScoreAndArgs {
        final int score;
        final Object[] args;

        ScoreAndArgs(int score, Object[] args) {
            this.score = score;
            this.args = args;
        }
    }

    private ScoreAndArgs scoreAndConvert(Method method, List<Object> rawArgs) {
        return scoreAndConvert(method.getParameterTypes(), method.isVarArgs(), rawArgs);
    }

    /** 参数打分 + 转换：分数越高越匹配，任何参数不兼容就抛 RuntimeException 跳过该重载。 */
    private ScoreAndArgs scoreAndConvert(Class<?>[] parameterTypes, boolean varArgs,
                                         List<Object> rawArgs) {
        if (varArgs && parameterTypes.length > 0) {
            int fixed = parameterTypes.length - 1;
            if (rawArgs.size() < fixed) {
                throw new IllegalArgumentException("参数太少");
            }
            Class<?> componentType = parameterTypes[fixed].getComponentType();
            Object[] args = new Object[parameterTypes.length];
            int score = 0;
            for (int i = 0; i < fixed; i++) {
                score += scoreOf(parameterTypes[i], rawArgs.get(i));
                args[i] = convertValue(parameterTypes[i], rawArgs.get(i));
            }
            int rest = rawArgs.size() - fixed;
            // JS 数组直接当可变参数列表使用：String.join('-', ['a','b','c'])
            if (rest == 1 && rawArgs.get(fixed) instanceof JSONArray
                    && arrayElementsCompatible(componentType, (JSONArray) rawArgs.get(fixed))) {
                JSONArray array = (JSONArray) rawArgs.get(fixed);
                Object varArray = Array.newInstance(componentType, array.length());
                for (int i = 0; i < array.length(); i++) {
                    score += scoreOf(componentType, array.opt(i));
                    Array.set(varArray, i, convertValue(componentType, array.opt(i)));
                }
                args[fixed] = varArray;
                return new ScoreAndArgs(score, args);
            }
            Object varArray = Array.newInstance(componentType, rest);
            for (int i = 0; i < rest; i++) {
                Object raw = rawArgs.get(fixed + i);
                score += scoreOf(componentType, raw);
                Array.set(varArray, i, convertValue(componentType, raw));
            }
            args[fixed] = varArray;
            return new ScoreAndArgs(score, args);
        }
        if (parameterTypes.length != rawArgs.size()) {
            throw new IllegalArgumentException("参数个数不匹配");
        }
        Object[] args = new Object[parameterTypes.length];
        int score = 0;
        for (int i = 0; i < parameterTypes.length; i++) {
            score += scoreOf(parameterTypes[i], rawArgs.get(i));
            args[i] = convertValue(parameterTypes[i], rawArgs.get(i));
        }
        return new ScoreAndArgs(score, args);
    }

    private int scoreOf(Class<?> target, Object raw) {        if (raw == null) {
            if (target.isPrimitive()) {
                throw new IllegalArgumentException("基本类型不接受 null");
            }
            return 1;
        }
        if (raw instanceof JSONObject) {
            Object value = referenceOf((JSONObject) raw);
            if (target == Object.class) {
                return 2;
            }
            if (target.isInstance(value)) {
                return 6;
            }
            throw new IllegalArgumentException("对象类型不匹配：" + target.getName());
        }
        if (raw instanceof JSONArray) {
            if (target.isArray()) {
                return 4;
            }
            if (Collection.class.isAssignableFrom(target) || target == Object.class) {
                return 3;
            }
            throw new IllegalArgumentException("数组类型不匹配：" + target.getName());
        }
        if (raw instanceof Number) {
            // 整数值优先匹配 int/long（Rhino 同样会选最窄的整数重载，
            // 否则 String.valueOf(42) 会落到 valueOf(double) 变成 "42.0"）。
            boolean integral = isIntegral((Number) raw);
            if (target == int.class || target == Integer.class) {
                return integral ? 7 : 4;
            }
            if (target == long.class || target == Long.class) {
                return integral ? 7 : 4;
            }
            if (target == short.class || target == Short.class
                    || target == byte.class || target == Byte.class) {
                return integral ? 6 : 3;
            }
            if (target == double.class || target == Double.class
                    || target == float.class || target == Float.class) {
                return integral ? 5 : 7;
            }
            if (target == char.class || target == Character.class) {
                return integral ? 3 : 1;
            }
            if (target == String.class || target == CharSequence.class) {
                return 2;
            }
            if (target == Object.class) {
                return 1;
            }
            throw new IllegalArgumentException("数字类型不匹配：" + target.getName());
        }
        if (raw instanceof CharSequence) {
            if (target == String.class) {
                return 6;
            }
            if (target == CharSequence.class) {
                return 5;
            }
            if (target == char.class || target == Character.class) {
                return ((CharSequence) raw).length() == 1 ? 4 : 1;
            }
            if (target == Object.class) {
                return 1;
            }
            throw new IllegalArgumentException("字符串类型不匹配：" + target.getName());
        }
        if (raw instanceof Boolean) {
            if (target == boolean.class || target == Boolean.class) {
                return 6;
            }
            if (target == Object.class) {
                return 1;
            }
            throw new IllegalArgumentException("布尔类型不匹配：" + target.getName());
        }
        throw new IllegalArgumentException("不支持的参数类型：" + raw.getClass().getName());
    }

    /** 可变参数判断：数组里每个元素都能当 componentType 用。 */
    private boolean arrayElementsCompatible(Class<?> componentType, JSONArray array) {
        if (componentType == null || array.length() == 0) {
            return array.length() == 0;
        }
        for (int i = 0; i < array.length(); i++) {
            try {
                scoreOf(componentType, array.opt(i));
            } catch (RuntimeException incompatible) {
                return false;
            }
        }
        return true;
    }

    private static boolean isIntegral(Number value) {
        double d = value.doubleValue();
        return d == Math.rint(d) && !Double.isInfinite(d);
    }

    private Object convertValue(Class<?> target, Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof JSONObject) {
            return referenceOf((JSONObject) raw);
        }
        if (raw instanceof JSONArray) {
            JSONArray array = (JSONArray) raw;
            if (target.isArray()) {
                Class<?> componentType = target.getComponentType();
                Object result = Array.newInstance(componentType, array.length());
                for (int i = 0; i < array.length(); i++) {
                    Array.set(result, i, convertValue(componentType, array.opt(i)));
                }
                return result;
            }
            List<Object> list = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                list.add(convertValue(Object.class, array.opt(i)));
            }
            return list;
        }
        if (raw instanceof Number) {
            Number number = (Number) raw;
            if (target == int.class || target == Integer.class) return number.intValue();
            if (target == long.class || target == Long.class) return number.longValue();
            if (target == short.class || target == Short.class) return number.shortValue();
            if (target == byte.class || target == Byte.class) return number.byteValue();
            if (target == double.class || target == Double.class) return number.doubleValue();
            if (target == float.class || target == Float.class) return number.floatValue();
            if (target == char.class || target == Character.class) return (char) number.intValue();
            if (target == String.class) return String.valueOf(number);
            if (target == CharSequence.class) return String.valueOf(number);
            return number;
        }
        if (raw instanceof CharSequence) {
            String text = raw.toString();
            if (target == char.class || target == Character.class) {
                return text.isEmpty() ? '\0' : text.charAt(0);
            }
            return text;
        }
        if (raw instanceof Boolean) {
            return raw;
        }
        return raw;
    }

    private Object referenceOf(JSONObject json) {
        long handle = json.optLong("__ref", 0);
        if (handle == 0) {
            throw new IllegalArgumentException("非法的 Java 对象参数");
        }
        return requireHandle(handle);
    }

    // ------------------------------------------------------------------
    // JSON 编解码（JSON 原生值 + {"__ref":handle}；数组递归）
    // ------------------------------------------------------------------

    private List<Object> parseArgs(String argsJson) throws JSONException {
        List<Object> args = new ArrayList<>();
        if (argsJson == null || argsJson.isEmpty()) {
            return args;
        }
        JSONArray array = new JSONArray(argsJson);
        for (int i = 0; i < array.length(); i++) {
            args.add(parseArg(array.get(i)));
        }
        return args;
    }

    private Object parseArg(Object value) throws JSONException {        if (value == null || value == JSONObject.NULL) {
            return null;
        }
        if (value instanceof JSONObject) {
            return value;
        }
        if (value instanceof JSONArray) {
            return value;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof String) {
            return value;
        }
        return String.valueOf(value);
    }

    /** 字段写入路径用：单个值也可能带 `{"__ref":..}`。 */
    private Object parseSingleArg(String valueJson) throws JSONException {
        if (valueJson == null || valueJson.isEmpty()) {
            return null;
        }
        return parseArg(new org.json.JSONTokener(valueJson).nextValue());
    }

    private String encode(Object value) throws JSONException {
        Object encoded = encodeValue(value);
        if (encoded instanceof JSONObject || encoded instanceof JSONArray) {
            return encoded.toString();
        }
        // 标量：用数组包一层再拆括号，保证字符串带引号、布尔/数字合法（含 JSONObject.NULL）。
        JSONArray wrapper = new JSONArray();
        wrapper.put(encoded);
        String text = wrapper.toString();
        return text.substring(1, text.length() - 1);
    }

    private Object encodeValue(Object value) throws JSONException {
        if (value == null) {
            return JSONObject.NULL;
        }
        if (value instanceof Boolean || value instanceof Number || value instanceof String) {
            return value;
        }
        if (value instanceof Character) {
            return String.valueOf(value);
        }
        // 注意：只把 String 当字符串。StringBuilder/StringBuffer/SpannableString 等
        // CharSequence 实现按 Java 对象包装（与 Rhino 一致），否则 `sb.append(...)` 链式调用会被截断。
        if (value instanceof Object[]) {
            return encodeArray((Object[]) value);
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            JSONArray array = new JSONArray();
            for (int i = 0; i < length; i++) {
                array.put(encodeValue(Array.get(value, i)));
            }
            return array;
        }
        // 其余一律包成句柄：类对象带 __isClass，普通实例带 __class 方便脚本判断。
        return referenceHandle(value, value instanceof Class
                ? ((Class<?>) value).getName() : value.getClass().getName(),
                value instanceof Class);
    }

    private JSONArray encodeArray(Object[] values) throws JSONException {
        JSONArray array = new JSONArray();
        for (Object value : values) {
            array.put(encodeValue(value));
        }
        return array;
    }

    private JSONObject referenceHandle(Object value, String className, boolean isClass) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("__ref", put(value));
        json.put("__class", className);
        if (isClass) {
            json.put("__isClass", true);
        }
        return json;
    }
}
