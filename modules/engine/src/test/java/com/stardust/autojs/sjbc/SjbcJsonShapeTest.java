package com.stardust.autojs.sjbc;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * task-87：QuickJS/Node24 这类**没有 Rhino 对象模型**的宿主，用 JSON 形态拿到可用脚本对象
 * （`JSON.parse` 后 `r.steps`/`r.prints[0]` 直读、`prints` 是真 JS 数组）——这条是 ③ 的落点。
 *
 * <p>同时钉住装配事实：QuickJS 的模块加载清单（`QuickJsHostBridge.modernModulesScript()`）与 JS 侧绑定约定
 * （`Java.type` 是既有宿主 API，见 `pro-modern-compat.js:368`）。
 */
public class SjbcJsonShapeTest {

    @Test
    public void jsonShapeKeepsFieldNamesAndArrayKinds() throws Exception {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("ok", Boolean.TRUE);
        map.put("prints", java.util.Arrays.asList("fib(10)=55", "counter=1,2"));
        map.put("steps", Double.valueOf(1990));
        map.put("entries", java.util.Arrays.asList(Double.valueOf(0), Double.valueOf(244)));
        map.put("certShaUsed", "38166a4b");
        JSONObject json = new JSONObject(SjbcApi.toJson(map));
        assertEquals(true, json.getBoolean("ok"));
        assertEquals(1990.0, json.getDouble("steps"), 1e-9);
        assertEquals("38166a4b", json.getString("certShaUsed"));
        JSONArray prints = json.getJSONArray("prints");
        assertEquals("prints 必须是 JSON 数组（QuickJS parse 后即真 JS 数组）", 2, prints.length());
        assertEquals("fib(10)=55", prints.getString(0));
        assertEquals(2, json.getJSONArray("entries").length());
        // 脚本侧等价写法的语义检查：parse 后按属性访问。
        assertTrue(json.has("steps") && json.has("prints") && json.has("ok") && json.has("certShaUsed"));
    }

    /** QuickJS 加载清单里必须**包含**我们要挂的模块（源码钉），且绑定约定用既有 Java.type（不新造桥）。 */
    @Test
    public void quickJsBootstrapIsPinned() throws Exception {
        String bridge = read("src/main/java/com/stardust/autojs/engine/QuickJsHostBridge.java");
        assertTrue("QuickJS 加载清单应含 qjs- 兼容模块", bridge.contains("modules/qjs-console-compat.js"));
        assertTrue("宿主 op 形态：public 方法供 QuickJS 调（cryptoCall 是既有样例）", bridge.contains("public String cryptoCall("));
        String compat = read("src/main/assets/modules/pro-modern-compat.js");
        assertTrue("QuickJS 侧 Java 桥是既有 Java.type（见 pro-modern-compat.js）", compat.contains("Java.type"));
    }

    /** 装配级：QuickJS 侧模块 + 宿主 op + 白名单 + 模块清单，四处必须同时成立（少一处真机就 undefined）。 */
    @Test
    public void quickJsSjbcIsWiredEndToEndAtSourceLevel() throws Exception {
        String bridge = read("src/main/java/com/stardust/autojs/engine/QuickJsHostBridge.java");
        assertTrue("模块清单必须加载 qjs-sjbc.js", bridge.contains("\"modules/qjs-sjbc.js\""));
        assertTrue("宿主 op 必须是 public 方法（QuickJS 只调 public）", bridge.contains("public String sjbcCall("));
        // 只在 sjbcCall 的方法体里钉白名单与「不做任意反射」（整个文件别处本来就有反射，不能全局断言）。
        int start = bridge.indexOf("public String sjbcCall(");
        int end = bridge.indexOf("\n    }", start);
        assertTrue("找不到 sjbcCall 方法体", start > 0 && end > start + 20);
        String body = bridge.substring(start, end);
        assertTrue("白名单：run", body.contains("case \"run\":"));
        assertTrue("白名单：runEntry", body.contains("case \"runEntry\":"));
        assertTrue("sjbcCall 内不做任意反射（只读白名单）",
                !body.contains("Class.forName") && !body.contains("getDeclaredMethod") && !body.contains("newInstance"));
        assertTrue("默认分支拒绝未知 op", body.contains("不支持的 sjbc op"));
        String module = read("src/main/assets/modules/qjs-sjbc.js");
        assertTrue("JS 侧必须走既有 Java.type 桥", module.contains("Java.type('com.stardust.autojs.engine.QuickJsHostBridge')"));
        assertTrue("JS 侧必须调宿主 op", module.contains(".sjbcCall("));
        assertTrue("JS 侧必须 JSON.parse（⇒ r.steps/r.prints 属性可直读，不需要 get()）", module.contains("JSON.parse"));
        assertTrue("JS 侧挂 global.sjbc", module.contains("g.sjbc = sjbc"));
        assertTrue("与 Rhino 同源：只调 SjbcApi（本模块不复制实现）", !module.contains("SjbcV5Container") && !module.contains("SjbcVm"));
        // task-89：forEngine 失败必须点名为可诊断错误 + 提供 debug()（真机只看到「脚本引擎已经结束」查不动）。
        assertTrue("失败要点名 forEngine(id) 与注册点", module.contains("forEngine(") && module.contains("QuickJsHostBridge.java"));
        assertTrue("task-89 兜底：id 取不到时退回唯一活动 bridge", module.contains("forCurrentEngine()"));
        assertTrue("task-89 兜底在宿主侧存在且多 engine 不猜", bridge.contains("public static QuickJsHostBridge forCurrentEngine()") && bridge.contains("已注册="));
        // task-90：id 后分配 ⇒ 构造期 getId() 可能为 0，注册必须补在 id 落定之后；兜底改用「活着的 bridge」。
        assertTrue("活 bridge 集合存在", bridge.contains("sLiveBridges"));
        assertTrue("兜底用活 bridge 且只认恰好一个", bridge.contains("sLiveBridges.size() == 1"));
        String engine = read("src/main/java/com/stardust/autojs/engine/QuickJsJavaScriptEngine.java");
        assertTrue("id 落定后补登记（setId 覆盖）", engine.contains("public void setId(int id)") && engine.contains("bridge.setOwnerEngineId(id)"));
        assertTrue("init 里 attach 之后也补登记", engine.contains("hostBridge.setOwnerEngineId(getId());"));
        assertTrue("debug 有 engineHandleFromHost", module.contains("engineHandleFromHost"));
        assertTrue("debug 有 registeredIds", module.contains("registeredIds"));
        // task-92：Java List 会被宿主包成 {__javaHandle:…} ⇒ 走 JSON 字符串再 parse（普通整数数组）。
        assertTrue("宿主提供 JSON 形式的 id 列表", bridge.contains("public static String registeredIdsJson()"));
        assertTrue("JS 侧 parse 成普通数组", module.contains("JSON.parse(String(cls.registeredIdsJson()))"));
        assertTrue("debug 注释写清兜底是正常路径", module.contains("正常兜底路径"));
        assertTrue("提供 sjbc.debug()", module.contains("debug: function ()"));
        assertTrue("debug 回显引擎 id 与桥解析结果", module.contains("engineId") && module.contains("bridgeResolved"));
        String bridgeFile = read("src/main/java/com/stardust/autojs/engine/QuickJsHostBridge.java");
        assertTrue("根因位置仍在（forEngine 查 sActiveBridges → null 则抛）",
                bridgeFile.contains("sActiveBridges.get(engineId)") && bridgeFile.contains("throw new IllegalStateException(\"脚本引擎已经结束\")"));
    }

    private static String read(String relativePath) throws Exception {
        return new String(Files.readAllBytes(Paths.get(relativePath)), StandardCharsets.UTF_8);
    }
}