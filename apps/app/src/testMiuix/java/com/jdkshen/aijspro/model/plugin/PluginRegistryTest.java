package com.jdkshen.aijspro.model.plugin;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 插件页扫描事实层的行为契约。
 *
 * <p>起因是台真机缺陷（Redmi 2602BRT18C / Android 16 / HyperOS，2026-10-10）：插件页一个
 * 插件都扫不出来，而同版本宿主在另一台机（K40 / LineageOS）上正常列出 2 个；插件的
 * {@code org.autojs.plugin.sdk.registry} 声明与宿主 {@code QUERY_ALL_PACKAGES}
 * （{@code granted=true}）都已逐项验证。旧实现用 {@code catch (_: RuntimeException) {}}
 * 把扫描异常吞掉，导致「扫不出来」与「没装插件」在界面上无法区分。
 *
 * <p>这些用例锁两件事：两遍扫描的触发条件正确，以及**退化成空列表时策略可辨认**
 * （{@link PluginRegistry.ScanResult.Strategy}），便于把现场写进报告。
 */
public class PluginRegistryTest {

    private static final String XIANGQI = "com.jdkshen.xiangqi.plugin";
    private static final String APKBUILDER = "com.jdkshen.apkbuilder.plugin";
    private static final String UNRELATED = "com.example.totally.unrelated";

    private static PluginRegistry.InstalledApp app(String pkg, String label,
                                                   String versionName, int versionCode,
                                                   String registryClass) {
        return new PluginRegistry.InstalledApp(pkg, label, versionName, versionCode, registryClass);
    }

    private static PluginRegistry.InstalledApp plugin(String pkg, String label, String version) {
        return app(pkg, label, version, 1, pkg + ".PluginRegistry");
    }

    private static PluginRegistry.InstalledApp plain(String pkg) {
        return app(pkg, pkg, "1.0", 1, null);
    }

    /** 按包名回答「它的注册类是什么」，用于慢路径。 */
    private static PluginRegistry.PerPackageLookup lookupOf(Map<String, String> table) {
        return table::get;
    }

    private static final PluginRegistry.PerPackageLookup NO_LOOKUP = packageName -> null;

    private static boolean hasEntryFor(List<PluginRegistry.PluginEntry> entries, String pkg) {
        for (PluginRegistry.PluginEntry entry : entries) {
            if (pkg.equals(entry.getPackageName())) {
                return true;
            }
        }
        return false;
    }

    // ---- 快路径：批量接口已给出注册类 ------------------------------------------------

    @Test
    public void bulkResultsThatCarryRegistryClassesAreListed() {
        PluginRegistry.ScanResult result = PluginRegistry.build(Arrays.asList(
                plugin(XIANGQI, "象棋引擎插件", "1.0.0"),
                plugin(APKBUILDER, "APK 打包插件", "1.0.0"),
                plain(UNRELATED)
        ), NO_LOOKUP, true);

        List<PluginRegistry.PluginEntry> entries = result.getEntries();
        // 两个插件 + 最后那行「安装本地插件 APK」
        assertEquals(3, entries.size());
        assertTrue(hasEntryFor(entries, XIANGQI));
        assertTrue(hasEntryFor(entries, APKBUILDER));
        assertFalse("无关应用不能出现在列表里", hasEntryFor(entries, UNRELATED));
        assertEquals(PluginRegistry.ScanResult.Strategy.BULK, result.getStrategy());
    }

    @Test
    public void localApkEntryIsAlwaysLastAndNotInstalled() {
        PluginRegistry.ScanResult result = PluginRegistry.build(
                Collections.singletonList(plugin(XIANGQI, "象棋引擎插件", "1.0.0")),
                NO_LOOKUP, true);

        List<PluginRegistry.PluginEntry> entries = result.getEntries();
        PluginRegistry.PluginEntry last = entries.get(entries.size() - 1);
        assertEquals("安装本地插件 APK", last.getLabel());
        assertEquals(PluginRegistry.LOCAL_APK_ENTRY_PACKAGE, last.getPackageName());
        assertFalse(last.isInstalled());
    }

    @Test
    public void entriesAreSortedByLabelCaseInsensitively() {
        PluginRegistry.ScanResult result = PluginRegistry.build(Arrays.asList(
                plugin(XIANGQI, "zeta plugin", "1.0.0"),
                plugin(APKBUILDER, "Alpha plugin", "1.0.0")
        ), NO_LOOKUP, true);

        List<PluginRegistry.PluginEntry> entries = result.getEntries();
        assertEquals("Alpha plugin", entries.get(0).getLabel());
        assertEquals("zeta plugin", entries.get(1).getLabel());
    }

    @Test
    public void summaryPrefersVersionNameAndFallsBackToVersionCode() {
        PluginRegistry.ScanResult named = PluginRegistry.build(
                Collections.singletonList(plugin(XIANGQI, "象棋引擎插件", "1.0.0")),
                NO_LOOKUP, true);
        assertEquals("已安装 · 1.0.0", named.getEntries().get(0).getSummary());

        PluginRegistry.ScanResult unnamed = PluginRegistry.build(
                Collections.singletonList(app(XIANGQI, "象棋引擎插件", null, 42, "X.Reg")),
                NO_LOOKUP, true);
        assertEquals("已安装 · 42", unnamed.getEntries().get(0).getSummary());
    }

    @Test
    public void blankRegistryClassIsNotAPlugin() {
        // meta-data 存在但值是空白 —— 不是有效注册，不能当成插件列出来
        PluginRegistry.ScanResult result = PluginRegistry.build(Arrays.asList(
                app(XIANGQI, "象棋引擎插件", "1.0.0", 1, "   "),
                app(APKBUILDER, "APK 打包插件", "1.0.0", 1, "")
        ), NO_LOOKUP, true);

        assertEquals("只剩「安装本地插件 APK」一行", 1, result.getEntries().size());
        assertFalse(hasEntryFor(result.getEntries(), XIANGQI));
    }

    // ---- 慢路径：批量接口没给注册类（真机 HyperOS 的表现）-----------------------------

    @Test
    public void fallsBackToPerPackageLookupWhenBulkCarriesNoRegistryClass() {
        // 模拟真机：两个插件都用批量接口取不到 metaData，且包名像插件
        Map<String, String> table = new HashMap<>();
        table.put(XIANGQI, XIANGQI + ".PluginRegistry");
        table.put(APKBUILDER, APKBUILDER + ".PluginRegistry");

        PluginRegistry.ScanResult result = PluginRegistry.build(Arrays.asList(
                app(XIANGQI, "象棋引擎插件", "1.0.0", 1, null),
                app(APKBUILDER, "APK 打包插件", "1.0.0", 1, null),
                plain(UNRELATED)
        ), lookupOf(table), true);

        assertEquals(PluginRegistry.ScanResult.Strategy.PER_PACKAGE_FALLBACK, result.getStrategy());
        assertEquals("两个插件 + 本地 APK 行", 3, result.getEntries().size());
        assertTrue(hasEntryFor(result.getEntries(), XIANGQI));
        assertTrue(hasEntryFor(result.getEntries(), APKBUILDER));
        assertFalse("无关包即使名单里有也不能出现", hasEntryFor(result.getEntries(), UNRELATED));
    }

    @Test
    public void perPackageLookupIsNotQueriedForPackagesThatDoNotLookLikePlugins() {
        List<String> asked = new ArrayList<>();
        PluginRegistry.build(Arrays.asList(
                plain(UNRELATED),
                plain("com.example.another")
        ), packageName -> {
            asked.add(packageName);
            return null;
        }, true);

        assertTrue("不该对无关包逐个查询（会拖慢插件页）", asked.isEmpty());
    }

    @Test
    public void bulkOnlyWhenNoFallbackAllowedEvenIfCandidatesExist() {
        Map<String, String> table = new HashMap<>();
        table.put(XIANGQI, XIANGQI + ".PluginRegistry");

        PluginRegistry.ScanResult result = PluginRegistry.build(
                Collections.singletonList(app(XIANGQI, "象棋引擎插件", "1.0.0", 1, null)),
                lookupOf(table), false);

        assertEquals(PluginRegistry.ScanResult.Strategy.BULK_ONLY, result.getStrategy());
        assertEquals("只剩「安装本地插件 APK」一行", 1, result.getEntries().size());
    }

    @Test
    public void fallbackEntriesKeepLabelAndVersionFromBulkScan() {
        Map<String, String> table = new HashMap<>();
        table.put(XIANGQI, XIANGQI + ".PluginRegistry");

        PluginRegistry.ScanResult result = PluginRegistry.build(
                Collections.singletonList(app(XIANGQI, "象棋引擎插件", "1.0.0", 7, null)),
                lookupOf(table), true);

        PluginRegistry.PluginEntry entry = result.getEntries().get(0);
        assertEquals("补齐时仍应沿用批量扫描拿到的名字", "象棋引擎插件", entry.getLabel());
        assertEquals("已安装 · 1.0.0", entry.getSummary());
        assertTrue(entry.isInstalled());
    }

    // ---- 退化输入 -------------------------------------------------------------------

    @Test
    public void nullOrEmptyBulkScanStillYieldsTheLocalApkEntry() {
        for (List<PluginRegistry.InstalledApp> input :
                Arrays.asList(null, Collections.<PluginRegistry.InstalledApp>emptyList())) {
            PluginRegistry.ScanResult result = PluginRegistry.build(input, NO_LOOKUP, true);
            assertEquals(1, result.getEntries().size());
            assertEquals(PluginRegistry.ScanResult.Strategy.BULK_ONLY, result.getStrategy());
        }
    }

    @Test
    public void nullEntriesInsideBulkScanAreSkippedWithoutThrowing() {
        PluginRegistry.ScanResult result = PluginRegistry.build(Arrays.asList(
                null,
                plugin(XIANGQI, "象棋引擎插件", "1.0.0"),
                null
        ), NO_LOOKUP, true);

        assertEquals(2, result.getEntries().size());
        assertTrue(hasEntryFor(result.getEntries(), XIANGQI));
    }

    @Test
    public void bulkScanWithNullPackageNameIsSkipped() {
        PluginRegistry.ScanResult result = PluginRegistry.build(
                Collections.singletonList(app(null, "坏了", "1.0", 1, "X.Reg")),
                NO_LOOKUP, true);

        assertEquals(1, result.getEntries().size());
    }

    // ---- 常量与适配对象 -------------------------------------------------------------

    @Test
    public void registryKeyMatchesTheAutoJsPluginSdkContract() {
        // 官方 Auto.js Pro 与本仓库都按这个键名找注册类，改了会让插件两边都装不上
        assertEquals("org.autojs.plugin.sdk.registry", PluginRegistry.REGISTRY_KEY);
    }

    @Test
    public void installedAppReportsRegisteredPluginOnlyForNonBlankRegistryClass() {
        assertTrue(plugin(XIANGQI, "x", "1").isRegisteredPlugin());
        assertFalse(plain(XIANGQI).isRegisteredPlugin());
        assertFalse(app(XIANGQI, "x", "1", 1, "  ").isRegisteredPlugin());
        assertFalse(app(XIANGQI, "x", "1", 1, null).isRegisteredPlugin());
    }

    @Test
    public void pluginEntryExposesItsIdentity() {
        PluginRegistry.PluginEntry entry =
                new PluginRegistry.PluginEntry("象棋引擎插件", "已安装 · 1.0.0", XIANGQI, true);
        assertEquals("象棋引擎插件", entry.getLabel());
        assertEquals("已安装 · 1.0.0", entry.getSummary());
        assertEquals(XIANGQI, entry.getPackageName());
        assertTrue(entry.isInstalled());
    }

    @Test
    public void scanResultExposesEntriesAndStrategy() {
        PluginRegistry.ScanResult result = PluginRegistry.build(null, null, true);
        assertNotNull(result.getEntries());
        assertNotNull(result.getStrategy());
        assertEquals(PluginRegistry.ScanResult.Strategy.BULK_ONLY, result.getStrategy());
    }
}
