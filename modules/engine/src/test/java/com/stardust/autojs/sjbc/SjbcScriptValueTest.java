package com.stardust.autojs.sjbc;

import org.junit.Test;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * task-83：入口返回值必须是**脚本可属性访问**的对象（不是 Java Map），`prints` 必须是**真 JS 数组**。
 *
 * <p>真机教训：返回 Map 时脚本里 `r.steps` 是 `undefined`（只有 `r.get('steps')` 能用），E2E 探针因此被带偏过。
 * 这里用真 Rhino 上下文跑一段脚本做**属性访问**断言（不是 `get()`）。
 */
public class SjbcScriptValueTest {

    private static Map<String, Object> sampleResult() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("ok", Boolean.TRUE);
        map.put("prints", java.util.Arrays.asList("fib(10)=55", "counter=1,2"));
        map.put("steps", Double.valueOf(1990));
        map.put("entries", java.util.Arrays.asList(Double.valueOf(0), Double.valueOf(244)));
        map.put("codeBytes", Double.valueOf(476));
        map.put("certShaUsed", "38166a4b");
        map.put("certSource", "package-signing-certificate");
        return map;
    }

    /** 真 Rhino：脚本里 `r.steps` / `r.prints.forEach` / `r.ok` 全可用（属性访问，非 get()）。 */
    @Test
    public void scriptCanReadResultByPropertyAccess() {
        Context context = Context.enter();
        try {
            Scriptable scope = context.initStandardObjects();
            Object value = SjbcApi.toScriptValue(sampleResult());
            assertTrue("Rhino 上下文里应转成脚本对象", value instanceof Scriptable);
            ScriptableObject.putProperty(scope, "r", value);
            Object out = context.evaluateString(scope,
                    "var seen = []; r.prints.forEach(function (x) { seen.push(x); });"
                            + " [r.steps, r.ok, r.certShaUsed, r.prints.length, seen.length, typeof r.prints.forEach, r.entries.length].join('|')",
                    "sjbc-script-value", 1, null);
            // 注：`instanceof Array` 在「转换时自带的标准 scope」与测试 scope 之间会因原型跨 scope 而不成立；
            // 真 JS 数组由 forEach/map 与下一条 NativeArray 断言证明。
            assertEquals("1990|true|38166a4b|2|2|function|2", Context.toString(out));
        } finally {
            Context.exit();
        }
    }

    /** `prints` 必须是真 JS 数组（NativeArray），字段名与片3 交付一致。 */
    @Test
    public void printsIsARealJsArrayAndFieldsKeepTheirNames() {
        Context context = Context.enter();
        try {
            Object value = SjbcApi.toScriptValue(sampleResult());
            Scriptable scriptable = (Scriptable) value;
            assertTrue(ScriptableObject.getProperty(scriptable, "prints") instanceof NativeArray);
            assertEquals(2, ((NativeArray) ScriptableObject.getProperty(scriptable, "prints")).size());
            for (String key : new String[] { "ok", "prints", "steps", "entries", "codeBytes",
                    "poolSize", "entryIndex", "path", "certShaUsed", "certSource", "uncaught" }) {
                // 只有存在的字段才断言「不是 undefined」（sampleResult 少几个字段，属于预期）。
                Object got = ScriptableObject.getProperty(scriptable, key);
                if (sampleResult().containsKey(key)) {
                    assertTrue(key + " 不应是 NOT_FOUND", got != Scriptable.NOT_FOUND);
                }
            }
        } finally {
            Context.exit();
        }
    }

    /** 没有 Rhino 上下文（QuickJS/Node24/纯 JVM）时**原样返回 Map**——不硬造、不抛。 */
    @Test
    public void withoutRhinoContextItFallsBackToMap() {
        Map<String, Object> map = sampleResult();
        Object value = SjbcApi.toScriptValue(map);
        assertTrue("无 Rhino 上下文时应返回原 Map", value instanceof Map);
        assertEquals(map, value);
        assertTrue(((Map<?, ?>) value).get("prints") instanceof List);
    }
}