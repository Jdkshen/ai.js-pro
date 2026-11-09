package com.stardust.autojs.sjbc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SJBC 值语义（**照 `pc-tools/vm.js` 的 JS 行为**实现）：数字统一为 Double、字符串为 String、
 * 数组为 List、对象为 Map、闭包为 {@link SjbcVm.Closure}、JS undefined 为 {@link SjbcVm#UNDEFINED}。
 *
 * <p>必须与 oracle 一致的几处：严格相等（NaN 不等于 NaN、数字与字符串不等）、
 * 比较用 JS 语义（NaN 参与的比较全 false ⇒ {@link #compare} 返回 NaN 时四个比较都 false）、
 * `+` 任一侧字符串就拼接（`String(x)` 规则）、`|| []`/`|| {}` 的空值兜底。
 */
public final class SjbcValues {

    private SjbcValues() {
    }

    public static Double number(double value) {
        return value;
    }

    public static double toNumber(Object value) {
        if (value instanceof Double) {
            return (Double) value;
        }
        if (value instanceof String) {
            String text = ((String) value).trim();
            if (text.isEmpty()) {
                return 0;
            }
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException e) {
                return Double.NaN;
            }
        }
        if (value == null || value == SjbcVm.UNDEFINED) {
            return value == null ? 0 : Double.NaN;
        }
        if (value instanceof Boolean) {
            return ((Boolean) value) ? 1 : 0;
        }
        return Double.NaN;
    }

    public static boolean isNumberZero(Object value) {
        return value instanceof Double && ((Double) value) == 0.0;
    }

    /** JS `String(v)` 的可读子集（数字整数不带小数点、NaN/Infinity 按 JS 拼）。 */
    public static String toDisplayString(Object value) {
        if (value == null) {
            return "null";
        }
        if (value == SjbcVm.UNDEFINED) {
            return "undefined";
        }
        if (value instanceof Double) {
            double d = (Double) value;
            if (Double.isNaN(d)) {
                return "NaN";
            }
            if (Double.isInfinite(d)) {
                return d > 0 ? "Infinity" : "-Infinity";
            }
            if (d == Math.rint(d) && Math.abs(d) < 1e15) {
                return String.valueOf((long) d);
            }
            return String.valueOf(d);
        }
        if (value instanceof String) {
            return (String) value;
        }
        if (value instanceof List) {
            StringBuilder sb = new StringBuilder();
            List<?> list = (List<?>) value;
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                Object item = list.get(i);
                sb.append(item == null || item == SjbcVm.UNDEFINED ? "" : toDisplayString(item));
            }
            return sb.toString();
        }
        if (value instanceof Map) {
            return "[object Object]";
        }
        if (value instanceof SjbcVm.Closure) {
            return "function";
        }
        return String.valueOf(value);
    }

    /** `+`：任一侧是字符串 ⇒ 拼接（JS 规则），否则数值相加。 */
    public static Object add(Object a, Object b) {
        if (a instanceof String || b instanceof String) {
            return toDisplayString(a) + toDisplayString(b);
        }
        return number(toNumber(a) + toNumber(b));
    }

    /** 严格相等（`===`）：数字按值（NaN≠NaN）、字符串按内容、其余按引用；数字与字符串不等。 */
    public static boolean strictEquals(Object a, Object b) {
        if (a instanceof Double && b instanceof Double) {
            return ((Double) a).doubleValue() == ((Double) b).doubleValue();
        }
        if (a instanceof String && b instanceof String) {
            return a.equals(b);
        }
        if (a == null || b == null || a == SjbcVm.UNDEFINED || b == SjbcVm.UNDEFINED) {
            return a == b;
        }
        return a == b;
    }

    /** JS 比较：两侧字符串按字典序，否则数值比较；NaN ⇒ 返回 NaN（四个比较因此全 false）。 */
    public static double compare(Object a, Object b) {
        if (a instanceof String && b instanceof String) {
            return Integer.signum(((String) a).compareTo((String) b));
        }
        return toNumber(a) - toNumber(b);
    }

    /** `(x||[]).length`。 */
    public static double lengthOf(Object value) {
        if (value instanceof List) {
            return ((List<?>) value).size();
        }
        if (value instanceof String) {
            return ((String) value).length();
        }
        return 0;
    }

    /** `(x||[])[i]`：数组按整数下标、字符串按下标取字符；越界给 undefined。 */
    public static Object indexGet(Object container, Object index) {
        if (container instanceof List) {
            List<?> list = (List<?>) container;
            int i = (int) toNumber(index);
            return i >= 0 && i < list.size() ? list.get(i) : SjbcVm.UNDEFINED;
        }
        if (container instanceof String) {
            String text = (String) container;
            int i = (int) toNumber(index);
            return i >= 0 && i < text.length() ? String.valueOf(text.charAt(i)) : SjbcVm.UNDEFINED;
        }
        return SjbcVm.UNDEFINED;
    }

    /** `a[i] = v`（字符串不可写 ⇒ 忽略，与 JS 一致）。 */
    @SuppressWarnings("unchecked")
    public static void indexSet(Object container, Object index, Object value) {
        if (!(container instanceof List)) {
            return;
        }
        List<Object> list = (List<Object>) container;
        int i = (int) toNumber(index);
        while (list.size() <= i) {
            list.add(SjbcVm.UNDEFINED);
        }
        if (i >= 0) {
            list.set(i, value);
        }
    }

    /** `(x||{})[key]`（键按 JS 对象语义字符串化）。 */
    public static Object keyGet(Object container, Object key) {
        if (container instanceof Map) {
            Object found = ((Map<?, ?>) container).get(toKey(key));
            return found == null ? SjbcVm.UNDEFINED : found;
        }
        return SjbcVm.UNDEFINED;
    }

    /** `o[key] = v`。 */
    @SuppressWarnings("unchecked")
    public static void keySet(Object container, Object key, Object value) {
        if (container instanceof Map) {
            ((Map<Object, Object>) container).put(toKey(key), value);
        }
    }

    public static String toKey(Object key) {
        return key instanceof String ? (String) key : toDisplayString(key);
    }

    /** 便于测试：新建数组。 */
    public static List<Object> array(Object... values) {
        List<Object> list = new ArrayList<>(values.length);
        for (Object value : values) {
            list.add(value);
        }
        return list;
    }

    /** 便于测试：新建对象。 */
    public static Map<String, Object> object() {
        return new LinkedHashMap<>();
    }
}