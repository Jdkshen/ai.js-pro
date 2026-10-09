package com.stardust.autojs.project;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 工程权限声明（{@code project.json} 的 {@code permissionConfig.manifestPermissions}）。
 *
 * <p>背景（B1 批次5）：打包页原先用裸 {@code JSONObject} 直接解析这一段，是「读工程 manifest」
 * 路径上唯一绕过 {@link ProjectConfig} 的地方。收口后三态语义必须与旧实现逐条一致，
 * 否则会出现「工程没声明权限 → 被打上模板 INTERNET」这类静默行为变化。
 *
 * <p><b>为什么不走 {@code ProjectConfig.fromJson()}</b>：它内部会调
 * {@code android.text.TextUtils.isEmpty}（{@code isValid()} 里），而本地单元测试的
 * {@code android.jar} 是 not-mocked 桩，一调即抛 RuntimeException —— 与本模块既有的
 * {@code Color.rgb} 同类限制。因此这里：
 * <ol>
 *   <li>用 {@link ProjectConfig#getDeclaredPermissions()} 直接覆盖三态与过滤逻辑（真正的行为契约）；</li>
 *   <li>用本地 Gson 实例反序列化 {@link ProjectConfig.PermissionConfig}，验证
 *       {@code @SerializedName("manifestPermissions")} 的字段映射 —— 这是打包页真正依赖的部分，</li>
 *   <li>不重复测 {@code fromJson()}（那需要 Robolectric，属另一件事）。
 * </ol>
 */
public class ProjectConfigPermissionTest {

    /** 与 ProjectConfig 内部同配置的 Gson（prettyPrinting 不影响解析）。 */
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static ProjectConfig configWith(List<String> permissions) {
        ProjectConfig config = new ProjectConfig();
        ProjectConfig.PermissionConfig permissionConfig = new ProjectConfig.PermissionConfig();
        permissionConfig.setManifestPermissions(permissions);
        config.setPermissionConfig(permissionConfig);
        return config;
    }

    // ---- 三态语义（与旧 readDeclaredPermissions 的返回值逐条对应）------------------------

    @Test
    public void noPermissionConfigObjectMeansNotDeclared() {
        // 旧实现：project.json 里没有 permissionConfig 段 -> null -> 调用方用模板默认权限
        assertNull(new ProjectConfig().getDeclaredPermissions());
    }

    @Test
    public void permissionConfigWithoutManifestPermissionsMeansNotDeclared() {
        // 旧实现：有 permissionConfig 但没有 manifestPermissions -> 同样按「未声明」处理
        ProjectConfig config = new ProjectConfig();
        config.setPermissionConfig(new ProjectConfig.PermissionConfig());
        assertNull(config.getDeclaredPermissions());
    }

    @Test
    public void explicitManifestPermissionsAreReturnedInOrder() {
        ProjectConfig config = configWith(Arrays.asList(
                "android.permission.INTERNET", "android.permission.VIBRATE"));
        List<String> declared = config.getDeclaredPermissions();
        assertNotNull(declared);
        assertEquals(2, declared.size());
        assertEquals("android.permission.INTERNET", declared.get(0));
        assertEquals("android.permission.VIBRATE", declared.get(1));
    }

    @Test
    public void emptyManifestPermissionsMeansDeclaredAsNothing() {
        // 与「未声明」是两回事：空数组是工程明确表达「什么都不声明」，不能退化成 null
        ProjectConfig config = configWith(Collections.<String>emptyList());
        List<String> declared = config.getDeclaredPermissions();
        assertNotNull("空数组不能退化成 null", declared);
        assertTrue("空数组应返回空列表", declared.isEmpty());
    }

    @Test
    public void blankEntriesAreDropped() {
        // 旧实现用 takeIf { it.isNotBlank() } 过滤空白项；null 项同理
        List<String> raw = new ArrayList<>();
        raw.add("android.permission.INTERNET");
        raw.add("");
        raw.add("   ");
        raw.add(null);
        ProjectConfig config = configWith(raw);

        List<String> declared = config.getDeclaredPermissions();
        assertNotNull(declared);
        assertEquals("只应保留 1 条有效权限", 1, declared.size());
        assertEquals("android.permission.INTERNET", declared.get(0));
    }

    @Test
    public void getterReflectsSetter() {
        ProjectConfig.PermissionConfig permissionConfig = new ProjectConfig.PermissionConfig();
        permissionConfig.setManifestPermissions(
                Collections.singletonList("android.permission.CAMERA"));

        ProjectConfig config = new ProjectConfig();
        config.setPermissionConfig(permissionConfig);

        assertNotNull(config.getPermissionConfig());
        assertEquals(Collections.singletonList("android.permission.CAMERA"),
                config.getPermissionConfig().getManifestPermissions());
    }

    // ---- 字段映射：打包页真正依赖的是 Gson 能否把 JSON 段读进模型 -------------------------

    @Test
    public void jsonFieldMappingMatchesAutoJsProFormat() {
        ProjectConfig.PermissionConfig parsed = GSON.fromJson(
                "{\"manifestPermissions\":[\"android.permission.INTERNET\","
                        + "\"android.permission.VIBRATE\"]}",
                ProjectConfig.PermissionConfig.class);

        assertNotNull(parsed);
        List<String> permissions = parsed.getManifestPermissions();
        assertNotNull(permissions);
        assertEquals(2, permissions.size());
        assertEquals("android.permission.INTERNET", permissions.get(0));
        assertEquals("android.permission.VIBRATE", permissions.get(1));
    }

    @Test
    public void jsonWithoutManifestPermissionsYieldsNullNotFailingParse() {
        ProjectConfig.PermissionConfig parsed = GSON.fromJson(
                "{\"other\":true}", ProjectConfig.PermissionConfig.class);
        assertNotNull("段本身能被解析", parsed);
        assertNull("没有 manifestPermissions 时字段应为 null（即「未声明」）",
                parsed.getManifestPermissions());
    }

    @Test
    public void jsonEmptyArrayYieldsEmptyListNotDeclaredNothing() {
        ProjectConfig.PermissionConfig parsed = GSON.fromJson(
                "{\"manifestPermissions\":[]}", ProjectConfig.PermissionConfig.class);
        assertNotNull(parsed);
        List<String> permissions = parsed.getManifestPermissions();
        assertNotNull("空数组应解析成空列表，而不是 null", permissions);
        assertTrue(permissions.isEmpty());
    }

    @Test
    public void permissionConfigSerializesBackWithTheSameFieldName() {
        // 工程配置需能原样读写不丢字段（本类既有约定）
        ProjectConfig.PermissionConfig permissionConfig = new ProjectConfig.PermissionConfig();
        permissionConfig.setManifestPermissions(
                Collections.singletonList("android.permission.CAMERA"));

        String json = GSON.toJson(permissionConfig);
        assertTrue("写出的字段名必须是 manifestPermissions，实际: " + json,
                json.contains("manifestPermissions"));

        ProjectConfig.PermissionConfig reparsed =
                GSON.fromJson(json, ProjectConfig.PermissionConfig.class);
        assertEquals(Collections.singletonList("android.permission.CAMERA"),
                reparsed.getManifestPermissions());
    }

    @Test
    public void unknownFieldsAreIgnoredRatherThanFailingTheParse() {
        // 真实工程可能带本引擎不认识的其他权限字段；Gson 默认忽略未知字段
        ProjectConfig.PermissionConfig parsed = GSON.fromJson(
                "{\"manifestPermissions\":[\"android.permission.INTERNET\"],"
                        + "\"requestPermissions\":[\"android.permission.CAMERA\"]}",
                ProjectConfig.PermissionConfig.class);
        assertNotNull(parsed);
        List<String> permissions = parsed.getManifestPermissions();
        assertNotNull(permissions);
        assertEquals(1, permissions.size());
        assertEquals("android.permission.INTERNET", permissions.get(0));
    }
}
