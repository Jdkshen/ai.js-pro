package com.jdkshen.aijspro.model.plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 插件页的扫描事实层（Android-free）。
 *
 * <p>把 {@code MiuixPluginFragment.loadPlugins()} 里原来和 {@code PackageManager} 搅在一起、
 * 又用 {@code catch (_: RuntimeException) {}} 静默吞掉的部分抽出来，变成可测的纯逻辑。
 *
 * <p><b>为什么要有「两遍扫描」</b>：宿主用
 * {@code getInstalledApplications(GET_META_DATA)} 一次拿全部应用的 meta-data，这是快路径。
 * 但真机证据（Redmi 2602BRT18C / Android 16 / HyperOS，2026-10-10）显示：该机的插件页
 * 一个插件都扫不出来，而同一 APK 装在同版本宿主的另一台机（K40 / LineageOS）上正常列出 2 个；
 * 插件自身的 {@code org.autojs.plugin.sdk.registry} 声明、宿主
 * {@code QUERY_ALL_PACKAGES} 授权（{@code granted=true}）都已逐项验证无误。
 * 因此怀疑是「批量接口返回的 metaData 被系统裁剪」，而旧的静默 catch 把真实异常也一起吞了。
 *
 * <p>所以这里保留批量快路径，并增加一条**逐个补齐**的慢路径：只有当批量结果里
 * 一个注册插件都没有、但确实存在「看起来像插件」的包时才启用，避免正常机型上白扫一遍。
 */
public final class PluginRegistry {

    /** Auto.js 插件 SDK 约定的注册键名；官方 Auto.js Pro 与本仓库都用它找注册类。 */
    public static final String REGISTRY_KEY = "org.autojs.plugin.sdk.registry";

    /** 插件页列表里那一行「安装本地插件 APK」的包名占位。 */
    public static final String LOCAL_APK_ENTRY_PACKAGE = "";

    /** 插件包名里出现过的近似词，仅用于决定「要不要走慢路径逐个补齐」。 */
    private static final String[] PLUGIN_HINTS = {"plugin", "xiangqi", "aijspro", "autojs"};

    private PluginRegistry() {
    }

    /** 扫描输入：每个已安装应用的包名，以及批量接口给出的注册类（没有则为 null）。 */
    public static final class InstalledApp {

        private final String packageName;
        private final String label;
        private final String versionName;
        private final int versionCode;
        private final String registryClass;

        public InstalledApp(String packageName, String label, String versionName,
                            int versionCode, String registryClass) {
            this.packageName = packageName;
            this.label = label;
            this.versionName = versionName;
            this.versionCode = versionCode;
            this.registryClass = registryClass;
        }

        public String getPackageName() {
            return packageName;
        }

        public String getLabel() {
            return label;
        }

        public String getVersionName() {
            return versionName;
        }

        public int getVersionCode() {
            return versionCode;
        }

        /** 非 null/非空 = 这是本引擎认得的插件。 */
        public String getRegistryClass() {
            return registryClass;
        }

        public boolean isRegisteredPlugin() {
            return registryClass != null && !registryClass.trim().isEmpty();
        }
    }

    /** 插件页一行的展示数据。 */
    public static final class PluginEntry {

        private final String label;
        private final String summary;
        private final String packageName;
        private final boolean installed;

        public PluginEntry(String label, String summary, String packageName, boolean installed) {
            this.label = label;
            this.summary = summary;
            this.packageName = packageName;
            this.installed = installed;
        }

        public String getLabel() {
            return label;
        }

        public String getSummary() {
            return summary;
        }

        public String getPackageName() {
            return packageName;
        }

        public boolean isInstalled() {
            return installed;
        }
    }

    /** 扫描结果：列表 + 本次实际走了哪条路径（便于把现场写进报告/日志）。 */
    public static final class ScanResult {

        public enum Strategy {
            /** 批量接口的结果里就有注册插件。 */
            BULK,
            /** 批量接口没给出注册插件，改用逐个查询批量结果里的候选包补齐。 */
            PER_PACKAGE_FALLBACK,
            /** 批量接口没给出注册插件，且没有像插件的包，未做补齐。 */
            BULK_ONLY,
            /** 批量枚举根本没返回插件（真机 Android 16 只返回 1 个应用），改由已知包名精确查询。 */
            DIRECT_KNOWN_PACKAGES
        }

        private final List<PluginEntry> entries;
        private final Strategy strategy;
        private final int candidateCount;

        ScanResult(List<PluginEntry> entries, Strategy strategy, int candidateCount) {
            this.entries = entries;
            this.strategy = strategy;
            this.candidateCount = candidateCount;
        }

        public List<PluginEntry> getEntries() {
            return entries;
        }

        public Strategy getStrategy() {
            return strategy;
        }

        /** 本次被判为「像插件」的包数量（诊断用；正常机型不关心）。 */
        public int getCandidateCount() {
            return candidateCount;
        }
    }

    /**
     * 从批量扫描结果构建插件页列表。
     *
     * @param bulkApps   {@code getInstalledApplications(GET_META_DATA)} 的适配结果，可为 null
     * @param perPackage 慢路径：给包名逐个返回注册类；返回 null 表示该包不是插件。
     *                   {@code useFallback=false} 时不会被调用。
     * @param useFallback 是否允许启用慢路径（真机诊断用；正常机型传 true）
     */
    public static ScanResult build(List<InstalledApp> bulkApps,
                                   PerPackageLookup perPackage,
                                   boolean useFallback) {
        return build(bulkApps, perPackage, useFallback, null, null);
    }

    /**
     * 完整版：额外支持「直接查已知插件包名」。
     *
     * <p>为什么必须有这条路径：真机证据（Redmi 2602BRT18C / Android 16 / HyperOS，
     * 2026-10-10 诊断输出）显示
     * {@code getInstalledApplications(GET_META_DATA)} 只返回 **1** 个应用、
     * {@code getInstalledPackages(GET_META_DATA)} 同样只返回 **1** 个 —— 两个批量枚举
     * 在这台机上基本被废掉，插件包压根不在结果里；而
     * {@code getPackageInfo("com.jdkshen.xiangqi.plugin", GET_META_DATA)} 能正常拿到
     * {@code registry=com.jdkshen.xiangqi.plugin.PluginRegistry}。
     * 所以「按已知包名精确查询」是这台机上唯一可行的发现方式。
     *
     * @param directPackages 已知插件包名（本仓库自研插件的固定身份，见
     *                       {@code PluginDownloads.APK_BUILDER_PLUGIN_PACKAGE} 等）；
     *                       可为 null。这些包只有在**精确查询确认登记**后才进入列表，
     *                       不会因为「包里带了这个名字」就被当成插件。
     * @param directLookup   给包名返回注册类；null 表示不是插件。可为 null。
     */
    public static ScanResult build(List<InstalledApp> bulkApps,
                                   PerPackageLookup perPackage,
                                   boolean useFallback,
                                   List<String> directPackages,
                                   RichPerPackageLookup directLookup) {
        List<PluginEntry> registered = new ArrayList<>();
        List<String> candidates = new ArrayList<>();
        if (bulkApps != null) {
            for (InstalledApp app : bulkApps) {
                if (app == null || app.getPackageName() == null) {
                    continue;
                }
                if (app.isRegisteredPlugin()) {
                    registered.add(toEntry(app));
                } else if (looksLikePlugin(app.getPackageName())) {
                    candidates.add(app.getPackageName());
                }
            }
        }

        ScanResult.Strategy strategy = ScanResult.Strategy.BULK;
        if (registered.isEmpty() && useFallback && perPackage != null && !candidates.isEmpty()) {
            // 走到这里说明批量结果里一个注册插件都没有（registered 为空），
            // 于是对「看起来像插件」的包逐个查询注册类补齐。
            strategy = ScanResult.Strategy.PER_PACKAGE_FALLBACK;
            for (String candidate : candidates) {
                String registryClass = perPackage.lookup(candidate);
                if (isBlank(registryClass)) {
                    continue;
                }
                addIfAbsent(registered, candidate, find(bulkApps, candidate));
            }
        } else if (registered.isEmpty()) {
            strategy = ScanResult.Strategy.BULK_ONLY;
        }

        // 兜底兜底：批量枚举在这台机上只返回 1 个应用，上面两条路径都够不着插件包，
        // 只能用已知包名精确查询。只有确认登记（注册类非空）才加入。
        if (directPackages != null && directLookup != null) {
            boolean added = false;
            for (String packageName : directPackages) {
                if (isBlank(packageName) || containsPackage(registered, packageName)) {
                    continue;
                }
                PackageMatch match = directLookup.lookup(packageName);
                if (match == null || isBlank(match.getRegistryClass())) {
                    continue;
                }
                String label = !isBlank(match.getLabel()) ? match.getLabel() : packageName;
                String version = !isBlank(match.getVersionName())
                        ? match.getVersionName() : packageName;
                registered.add(new PluginEntry(label, "已安装 · " + version, packageName, true));
                added = true;
            }
            if (added) {
                strategy = ScanResult.Strategy.DIRECT_KNOWN_PACKAGES;
            }
        }

        registered.sort(Comparator.comparing(PluginEntry::getLabel,
                String.CASE_INSENSITIVE_ORDER));
        List<PluginEntry> entries = new ArrayList<>(registered);
        entries.add(new PluginEntry("安装本地插件 APK", "选择设备中的 APK 文件",
                LOCAL_APK_ENTRY_PACKAGE, false));
        return new ScanResult(entries, strategy, candidates.size());
    }

    private static void addIfAbsent(List<PluginEntry> registered, String packageName,
                                    InstalledApp app) {
        if (containsPackage(registered, packageName)) {
            return;
        }
        String label = app != null && app.getLabel() != null ? app.getLabel() : packageName;
        String version = app != null ? versionOf(app) : null;
        registered.add(new PluginEntry(label, "已安装 · " + version, packageName, true));
    }

    private static boolean containsPackage(List<PluginEntry> registered, String packageName) {
        for (PluginEntry entry : registered) {
            if (packageName.equals(entry.getPackageName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /** 慢路径回调：给定包名，返回它的插件注册类；不是插件返回 null。 */
    public interface PerPackageLookup {
        String lookup(String packageName);
    }

    /** 精确查询的完整结果：注册类 + 展示用的名称/版本。 */
    public static final class PackageMatch {

        private final String registryClass;
        private final String label;
        private final String versionName;

        public PackageMatch(String registryClass, String label, String versionName) {
            this.registryClass = registryClass;
            this.label = label;
            this.versionName = versionName;
        }

        public String getRegistryClass() {
            return registryClass;
        }

        public String getLabel() {
            return label;
        }

        public String getVersionName() {
            return versionName;
        }
    }

    /** 慢路径回调：给定包名返回完整匹配结果；不是插件返回 null。 */
    public interface RichPerPackageLookup {
        PackageMatch lookup(String packageName);
    }

    private static boolean looksLikePlugin(String packageName) {
        String lower = packageName.toLowerCase(Locale.ROOT);
        for (String hint : PLUGIN_HINTS) {
            if (lower.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    private static InstalledApp find(List<InstalledApp> apps, String packageName) {
        if (apps == null) {
            return null;
        }
        for (InstalledApp app : apps) {
            if (app != null && packageName.equals(app.getPackageName())) {
                return app;
            }
        }
        return null;
    }

    private static PluginEntry toEntry(InstalledApp app) {
        String label = app.getLabel() != null ? app.getLabel() : app.getPackageName();
        return new PluginEntry(label, "已安装 · " + versionOf(app), app.getPackageName(), true);
    }

    private static String versionOf(InstalledApp app) {
        String versionName = app.getVersionName();
        if (versionName != null && !versionName.trim().isEmpty()) {
            return versionName;
        }
        return String.valueOf(app.getVersionCode());
    }

    /** 便于测试与诊断：常量列表快照。 */
    public static List<String> pluginHints() {
        return Collections.unmodifiableList(java.util.Arrays.asList(PLUGIN_HINTS));
    }
}
