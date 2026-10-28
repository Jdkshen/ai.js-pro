// SPDX-License-Identifier: MIT
package com.stardust.automator;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;

/**
 * Strict validation for the generic accessibility {@code nodeAction} contract.
 *
 * <p>This class is shared by the Node24 automation host bridge and the QuickJS
 * UiObject/collection adapters so both reject malformed arguments before any
 * {@code getInt()}/{@code getDouble()} narrowing. It only covers actions sent through
 * the generic backend {@code nodeAction} API. {@code click}, {@code clickCenter},
 * {@code longClick} and {@code setText} are intentionally rejected because they use
 * the dedicated backend methods ({@code clickNode}/{@code longClickNode}/
 * {@code setTextNode}).
 */
public final class NodeActionArguments {

    private static final String SUPPORTED_ACTIONS =
            "copy/cut/paste/select/collapse/expand/dismiss/show/scrollForward/scrollBackward/"
                    + "scrollUp/scrollDown/scrollLeft/scrollRight/contextClick/focus/clearFocus/"
                    + "accessibilityFocus/clearAccessibilityFocus/setSelection/scrollTo/setProgress";

    private NodeActionArguments() {
    }

    /**
     * Validates a generic node action name and its JSON argument array.
     *
     * <p>Zero-argument actions require an empty array. Selection and scroll rows/columns
     * must be non-negative 32-bit integers, selection requires {@code start <= end}, and
     * progress must be a finite number representable by {@code float}. Numeric strings,
     * booleans, nulls and fractional values are rejected.
     *
     * @throws IllegalArgumentException when the action is not a generic node action or
     *                                  any argument is missing, extra, or malformed
     */
    public static void validate(String action, JSONArray arguments) {
        if (action == null || action.isEmpty()) {
            throw new IllegalArgumentException("nodeAction 缺少 actionName");
        }
        if (arguments == null) {
            throw new IllegalArgumentException("nodeAction." + action
                    + " 的 arguments 必须是 JSON 数组（无参动作传 []）");
        }
        switch (action) {
            case "copy":
            case "cut":
            case "paste":
            case "select":
            case "collapse":
            case "expand":
            case "dismiss":
            case "show":
            case "scrollForward":
            case "scrollBackward":
            case "scrollUp":
            case "scrollDown":
            case "scrollLeft":
            case "scrollRight":
            case "contextClick":
            case "focus":
            case "clearFocus":
            case "accessibilityFocus":
            case "clearAccessibilityFocus":
                requireCount(action, arguments, 0);
                return;
            case "setSelection": {
                requireCount(action, arguments, 2);
                int start = requireInt32(arguments.opt(0), "nodeAction.setSelection[0]");
                int end = requireInt32(arguments.opt(1), "nodeAction.setSelection[1]");
                if (start > end) {
                    throw new IllegalArgumentException(
                            "nodeAction.setSelection 需要 start <= end，收到：" + start + " > " + end);
                }
                return;
            }
            case "scrollTo": {
                requireCount(action, arguments, 2);
                requireInt32(arguments.opt(0), "nodeAction.scrollTo[0](row)");
                requireInt32(arguments.opt(1), "nodeAction.scrollTo[1](column)");
                return;
            }
            case "setProgress": {
                requireCount(action, arguments, 1);
                requireProgress(arguments.opt(0));
                return;
            }
            default:
                throw new IllegalArgumentException("nodeAction 不支持 actionName：" + action
                        + "（支持 " + SUPPORTED_ACTIONS
                        + "；click/longClick 请用 clickNode/longClickNode，setText 请用 setTextNode）");
        }
    }

    private static void requireCount(String action, JSONArray arguments, int expected) {
        if (arguments.length() == expected) {
            return;
        }
        if (expected == 0) {
            throw new IllegalArgumentException("nodeAction." + action
                    + " 不接受参数，arguments 必须是 []，收到 " + arguments.length() + " 个");
        }
        throw new IllegalArgumentException("nodeAction." + action + " 需要 " + expected
                + " 个参数，收到 " + arguments.length() + " 个");
    }

    /** Accepts only numeric JSON nodes whose exact value is a non-negative 32-bit integer. */
    private static int requireInt32(Object raw, String label) {
        if (!(raw instanceof Number)) {
            throw new IllegalArgumentException(label + " 必须是整数，收到：" + valueType(raw));
        }
        long value;
        try {
            value = new BigDecimal(raw.toString()).longValueExact();
        } catch (NumberFormatException | ArithmeticException error) {
            throw new IllegalArgumentException(label + " 必须是整数，收到：" + raw);
        }
        if (value < 0L || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(label + " 必须是 0.." + Integer.MAX_VALUE
                    + " 的 32 位整数，收到：" + value);
        }
        return (int) value;
    }

    /** Rejects NaN/Infinity and numbers that would overflow when narrowed to float. */
    private static void requireProgress(Object raw) {
        if (!(raw instanceof Number)) {
            throw new IllegalArgumentException(
                    "nodeAction.setProgress[0] 必须是数字，收到：" + valueType(raw));
        }
        double value = ((Number) raw).doubleValue();
        // Double.isFinite is API 24+ on Android; minSdk here is 21.
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException("nodeAction.setProgress[0] 必须是有限的 float 数字");
        }
        if (Math.abs(value) > Float.MAX_VALUE) {
            throw new IllegalArgumentException("nodeAction.setProgress[0] 超出 float 有限范围（±"
                    + Float.MAX_VALUE + "），收到：" + raw);
        }
    }

    private static String valueType(Object value) {
        if (value == null || value == JSONObject.NULL) {
            return "null";
        }
        if (value instanceof String) {
            return "字符串";
        }
        if (value instanceof Boolean) {
            return "布尔值";
        }
        if (value instanceof Number) {
            return "数字";
        }
        if (value instanceof JSONObject) {
            return "JSON 对象";
        }
        if (value instanceof JSONArray) {
            return "JSON 数组";
        }
        return value.getClass().getSimpleName();
    }
}
