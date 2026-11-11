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

    /** task-150: overlay permission is only needed for REAL overlay scripts (not every ui.layout project). */
    @Test
    public void overlayPermissionIsOnlyNeededForRealOverlayScripts() {
        assertFalse("pure ui.layout goes to a normal window",
                ScriptHostRouting.needsOverlayPermission(true, false, false, false));
        assertTrue("floaty", ScriptHostRouting.needsOverlayPermission(true, true, false, false));
        assertTrue("imgui", ScriptHostRouting.needsOverlayPermission(true, false, true, false));
        assertTrue("node ui project (device evidence: window type 2038)",
                ScriptHostRouting.needsOverlayPermission(true, false, false, true));
        assertFalse("non-ui mode never needs it", ScriptHostRouting.needsOverlayPermission(false, true, true, true));
    }

    /** text judges: floaty/$floaty/rawWindow and imgui markers. */
    @Test
    public void overlayTextJudges() {
        assertTrue(ScriptHostRouting.textUsesOverlayApi("floaty.checkPermission()"));
        assertTrue(ScriptHostRouting.textUsesOverlayApi("$floaty.rawWindow(xml)"));
        assertFalse(ScriptHostRouting.textUsesOverlayApi("ui.layout(xml)"));
        assertFalse(ScriptHostRouting.textUsesOverlayApi(null));
        assertTrue(ScriptHostRouting.textUsesImGui("var x = libImGuiBridge;"));
        assertTrue(ScriptHostRouting.textUsesImGui("IMGUI frame"));
        assertFalse(ScriptHostRouting.textUsesImGui("ui.layout(xml)"));
    }
    /** task-150: inrt must (a) check only when needed, (b) go through the script console, (c) never auto-jump. */
    @Test
    public void inrtOnlyGuidesOverlayWhenNeededAndViaConsole() throws Exception {
        String launcher = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                "../../apps/inrt/src/main/java/com/jdkshen/aijspro/inrt/launch/AssetsProjectLauncher.kt")),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue("inrt must use the shared overlay predicate",
                launcher.contains("ScriptHostRouting.needsOverlayPermission("));
        assertTrue("inrt must check Settings.canDrawOverlays", launcher.contains("Settings.canDrawOverlays("));
        assertTrue("warning carries the actionable text",
                launcher.contains("本产物需要「显示在其他应用上层」权限"));
        assertTrue("warning mentions the script-side escape hatch", launcher.contains("floaty.checkPermission()"));
        assertTrue("warning goes through the script console", launcher.contains("globalConsole"));
        int fnStart = launcher.indexOf("private fun reportOverlayPermissionIfNeeded");
        int fnEnd = launcher.indexOf("\n    }", fnStart);
        assertTrue("overlay helper exists", fnStart > 0 && fnEnd > fnStart);
        String helper = launcher.substring(fnStart, fnEnd);
        assertFalse("must NOT auto-jump to system settings", helper.contains("startActivity"));
        assertFalse("must NOT fake permissions", helper.contains("ACTION_MANAGE_OVERLAY_PERMISSION"));
        assertTrue("already granted => no message", helper.contains("if (granted) {"));
    }
}
