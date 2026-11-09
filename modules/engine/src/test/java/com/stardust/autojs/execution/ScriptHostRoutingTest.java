package com.stardust.autojs.execution;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * task-78：Activity 宿主判据（打包版 `activity` 未定义的根因就是这条判据太窄）。
 *
 * <p>真机现象：`"ui";` + `activity.getEventEmitter()/startActivityForResult` 的脚本被判 headless
 * ⇒ 打包版 `ReferenceError: "activity" is not defined`。
 */
public class ScriptHostRoutingTest {

    /** 核心修复：`"ui";` + activity.* 也要 Activity 宿主（用户实报的那类脚本）。 */
    @Test
    public void uiModeScriptUsingActivityApiNeedsActivityHost() {
        assertTrue(ScriptHostRouting.needsActivityHost(true, false, true, false));
        assertTrue("有 ui.layout 时照旧", ScriptHostRouting.needsActivityHost(true, true, false, false));
    }

    /** ImGui/浮窗项目（同样写 "ui";）必须留在 headless，不能被 activity.* 判据拽进 Activity。 */
    @Test
    public void imGuiProjectsStayHeadlessEvenIfTheyTouchActivity() {
        assertFalse(ScriptHostRouting.needsActivityHost(true, false, true, true));
    }

    /** 非 UI 模式、以及既无 ui.layout 也无 activity.* 的脚本：不上 Activity 宿主。 */
    @Test
    public void plainScriptsDoNotGetAnActivityHost() {
        assertFalse(ScriptHostRouting.needsActivityHost(false, true, true, false));
        assertFalse(ScriptHostRouting.needsActivityHost(true, false, false, false));
    }

    /** 文本判据：activity.* 与 ui.layout 家族；null 安全。 */
    @Test
    public void textPatternsMatchTheRealScriptShapes() {
        assertTrue(ScriptHostRouting.textUsesActivityApi("activity.getEventEmitter().on('activity_result', f)"));
        assertTrue(ScriptHostRouting.textUsesActivityApi("  activity . startActivityForResult(intent, 8000)"));
        assertTrue(ScriptHostRouting.textUsesActivityApi("activity.runOnUiThread(function(){})"));
        assertFalse("只是字符串/注释里出现不算（保守）", ScriptHostRouting.textUsesActivityApi("var s = 'myActivity.x'"));
        assertFalse(ScriptHostRouting.textUsesActivityApi(null));
        assertTrue(ScriptHostRouting.textUsesUiLayout("ui.layout('<vertical/>')"));
        assertTrue(ScriptHostRouting.textUsesUiLayout("ui.inflate(xml)"));
        assertFalse(ScriptHostRouting.textUsesUiLayout("ui.run(function(){})"));
        assertFalse(ScriptHostRouting.textUsesUiLayout(null));
    }

    /** 同源：inrt 的路由必须用 engine 的这条判据（防止打包版又退回「只看 ui.layout」）。 */
    @Test
    public void inrtLauncherUsesTheSharedRoutingPredicate() throws Exception {
        String launcher = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                "../../apps/inrt/src/main/java/com/jdkshen/aijspro/inrt/launch/AssetsProjectLauncher.kt")),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue("inrt 必须 import ScriptHostRouting",
                launcher.contains("import com.stardust.autojs.execution.ScriptHostRouting"));
        assertTrue("inrt 必须调用 needsActivityHost（含 activity.* 判据）",
                launcher.contains("ScriptHostRouting.needsActivityHost("));
        assertTrue("inrt 必须用 textUsesActivityApi", launcher.contains("ScriptHostRouting.textUsesActivityApi("));
        assertTrue("headless 无 Activity 时必须有明确提示（不伪造）", launcher.contains("headless 执行没有宿主 Activity"));
    }
}