package com.stardust.autojs.core.ui.widget;

import org.json.JSONArray;
import org.json.JSONObject;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.NativeObject;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;
import org.mozilla.javascript.Wrapper;

import java.util.Iterator;

/**
 * WebView <-> 脚本之间搬 JS 值的工具。
 *
 * <p>为什么需要它：{@code org.json.JSONObject/JSONArray} 直接塞给 Rhino 时属性访问不生效
 * （实测 {@code args.path} 为 undefined），而 Pro 的 jsBridge 是在 JS 里 {@code JSON.parse}。
 * 本引擎把 JSON 预先转成 Rhino 的 NativeObject/NativeArray，脚本侧就能按普通对象使用
 * （例如 {@code jsBridge.handle('fetch', (e, args) => args.path)}）。
 */
final class WebViewJs {

    private WebViewJs() {
    }

    /** 构造脚本事件对象：{@code {name, arguments, consumed}}。 */
    static NativeObject newEvent(String name, Object[] args) {
        NativeObject event = new NativeObject();
        event.defineProperty("name", name == null ? "" : name, ScriptableObject.EMPTY);
        event.defineProperty("arguments", new NativeArray(args == null ? new Object[0] : args),
                ScriptableObject.EMPTY);
        event.defineProperty("consumed", Boolean.FALSE, ScriptableObject.EMPTY);
        return event;
    }

    /** JSON 值 -> Rhino 脚本对象/原始值。 */
    static Object jsonToJs(Object value) {
        if (value == null || value == JSONObject.NULL) {
            return null;
        }
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            NativeObject result = new NativeObject();
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                result.defineProperty(key, jsonToJs(object.opt(key)), ScriptableObject.EMPTY);
            }
            return result;
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            Object[] elements = new Object[array.length()];
            for (int i = 0; i < array.length(); i++) {
                elements[i] = jsonToJs(array.opt(i));
            }
            return new NativeArray(elements);
        }
        return value;
    }

    /** 脚本返回值 -> 可 JSON 序列化的值（响应回页面用）。 */
    static Object jsToJson(Object value) {
        if (value == null || value == Undefined.instance || value == ScriptableObject.NOT_FOUND) {
            return null;
        }
        if (value instanceof Wrapper) {
            Object unwrapped = ((Wrapper) value).unwrap();
            return unwrapped == null ? null : jsToJson(unwrapped);
        }
        if (value instanceof CharSequence) {
            return value.toString();
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value;
        }
        return String.valueOf(value);
    }

    /** 事件对象当前名称（Java 事件对象/JS 事件对象都兼容）。 */
    static String eventName(Object event) {
        if (event instanceof WebViewEvent) {
            return ((WebViewEvent) event).getName();
        }
        if (event instanceof NativeObject) {
            Object name = ((NativeObject) event).get("name");
            return name == null || name == ScriptableObject.NOT_FOUND ? "" : String.valueOf(name);
        }
        return "";
    }
}
