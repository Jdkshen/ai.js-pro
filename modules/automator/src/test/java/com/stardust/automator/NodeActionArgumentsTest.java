// SPDX-License-Identifier: MIT
package com.stardust.automator;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Focused checks for the shared generic nodeAction argument contract. */
public class NodeActionArgumentsTest {

    @Test
    public void acceptsEveryZeroArgumentGenericAction() throws Exception {
        String[] actions = {
                "copy", "cut", "paste", "select", "collapse", "expand", "dismiss", "show",
                "scrollForward", "scrollBackward", "scrollUp", "scrollDown", "scrollLeft",
                "scrollRight", "contextClick", "focus", "clearFocus", "accessibilityFocus",
                "clearAccessibilityFocus",
        };
        for (String action : actions) {
            NodeActionArguments.validate(action, new JSONArray());
        }
    }

    @Test
    public void rejectsExtraArgumentsForZeroArgumentActions() throws Exception {
        assertInvalid("scrollDown", new JSONArray().put(1), "不接受参数");
        assertInvalid("clearAccessibilityFocus", new JSONArray().put(true), "不接受参数");
    }

    @Test
    public void rejectsDedicatedBackendActions() throws Exception {
        for (String action : new String[]{"click", "clickCenter", "longClick", "setText"}) {
            assertInvalid(action, new JSONArray(), "不支持 actionName");
            assertInvalid(action, new JSONArray().put("value"), "不支持 actionName");
        }
    }

    @Test
    public void setSelectionAcceptsOrderedNonNegativeInt32Values() throws Exception {
        NodeActionArguments.validate("setSelection", new JSONArray().put(0).put(0));
        NodeActionArguments.validate("setSelection",
                new JSONArray().put(0).put(Integer.MAX_VALUE));
        NodeActionArguments.validate("setSelection",
                new JSONArray().put(Integer.MAX_VALUE).put(Integer.MAX_VALUE));
    }

    @Test
    public void setSelectionRejectsWrongArityNegativeOverflowAndFractions() throws Exception {
        assertInvalid("setSelection", new JSONArray(), "参数");
        assertInvalid("setSelection", new JSONArray().put(0), "参数");
        assertInvalid("setSelection", new JSONArray().put(0).put(1).put(2), "参数");
        assertInvalid("setSelection", new JSONArray().put(-1).put(0), "32 位整数");
        assertInvalid("setSelection",
                new JSONArray().put(0).put(2_147_483_648L), "32 位整数");
        assertInvalid("setSelection", new JSONArray().put(1.5).put(2), "整数");
        assertInvalid("setSelection", new JSONArray().put("0").put(1), "整数");
        assertInvalid("setSelection", new JSONArray().put(false).put(1), "整数");
        assertInvalid("setSelection", new JSONArray().put(JSONObject.NULL).put(1), "整数");
        assertInvalid("setSelection", new JSONArray().put(5).put(4), "start <= end");
    }

    @Test
    public void scrollToAcceptsNonNegativeRowsAndColumns() throws Exception {
        NodeActionArguments.validate("scrollTo", new JSONArray().put(0).put(0));
        NodeActionArguments.validate("scrollTo",
                new JSONArray().put(0).put(Integer.MAX_VALUE));
        NodeActionArguments.validate("scrollTo",
                new JSONArray().put(Integer.MAX_VALUE).put(0));
    }

    @Test
    public void scrollToRejectsWrongArityNegativeOverflowStringsBooleansAndFractions() throws Exception {
        assertInvalid("scrollTo", new JSONArray(), "参数");
        assertInvalid("scrollTo", new JSONArray().put(0), "参数");
        assertInvalid("scrollTo", new JSONArray().put(0).put(1).put(2), "参数");
        assertInvalid("scrollTo", new JSONArray().put(-1).put(0), "32 位整数");
        assertInvalid("scrollTo",
                new JSONArray().put(0).put(2_147_483_648L), "32 位整数");
        assertInvalid("scrollTo", new JSONArray().put(0.5).put(1), "整数");
        assertInvalid("scrollTo", new JSONArray().put("0").put(1), "整数");
        assertInvalid("scrollTo", new JSONArray().put(true).put(1), "整数");
        assertInvalid("scrollTo", new JSONArray().put(JSONObject.NULL).put(1), "整数");
    }

    @Test
    public void setProgressAcceptsFiniteFloatRangeValues() throws Exception {
        NodeActionArguments.validate("setProgress", new JSONArray().put(0));
        NodeActionArguments.validate("setProgress", new JSONArray().put(0.5));
        NodeActionArguments.validate("setProgress", new JSONArray().put(-1.5));
        NodeActionArguments.validate("setProgress", new JSONArray().put(Float.MAX_VALUE));
        NodeActionArguments.validate("setProgress", new JSONArray().put(-Float.MAX_VALUE));
    }

    @Test
    public void setProgressRejectsWrongArityNonNumbersAndFloatOverflow() throws Exception {
        assertInvalid("setProgress", new JSONArray(), "参数");
        assertInvalid("setProgress", new JSONArray().put(0.5).put(1), "参数");
        assertInvalid("setProgress", new JSONArray().put("0.5"), "必须是数字");
        assertInvalid("setProgress", new JSONArray().put(true), "必须是数字");
        assertInvalid("setProgress", new JSONArray().put(JSONObject.NULL), "必须是数字");
        assertInvalid("setProgress", new JSONArray().put(1e39), "float 有限范围");
        assertInvalid("setProgress", new JSONArray().put(-1e39), "float 有限范围");
        assertInvalid("setProgress", new JSONArray().put(Double.MAX_VALUE), "float 有限范围");
    }

    @Test
    public void setProgressRejectsNonFiniteNumbers() throws Exception {
        assertInvalid("setProgress", singleValue(nonFinite(Double.NaN)), "有限的 float 数字");
        assertInvalid("setProgress", singleValue(nonFinite(Double.POSITIVE_INFINITY)),
                "有限的 float 数字");
        assertInvalid("setProgress", singleValue(nonFinite(Double.NEGATIVE_INFINITY)),
                "有限的 float 数字");
    }

    @Test
    public void rejectsMissingActionOrArgumentsArray() throws Exception {
        assertInvalid(null, new JSONArray(), "actionName");
        assertInvalid("", new JSONArray(), "actionName");
        assertInvalid("scrollDown", null, "arguments");
    }

    /** Bypasses JSONArray.put()'s own finite-number check so the validator sees NaN/Infinity. */
    private static JSONArray singleValue(final Object value) {
        return new JSONArray() {
            @Override
            public int length() {
                return 1;
            }

            @Override
            public Object opt(int index) {
                return index == 0 ? value : JSONObject.NULL;
            }
        };
    }

    private static Number nonFinite(final double value) {
        return new Number() {
            @Override
            public int intValue() {
                return (int) value;
            }

            @Override
            public long longValue() {
                return (long) value;
            }

            @Override
            public float floatValue() {
                return (float) value;
            }

            @Override
            public double doubleValue() {
                return value;
            }
        };
    }

    private static void assertInvalid(String action, JSONArray arguments, String messagePart) {
        try {
            NodeActionArguments.validate(action, arguments);
            fail("expected validation failure for " + action + " " + arguments);
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
            assertTrue("expected message containing \"" + messagePart + "\" but was \""
                    + expected.getMessage() + "\"", expected.getMessage().contains(messagePart));
        }
    }
}
