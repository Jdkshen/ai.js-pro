package com.stardust.autojs.project;

import android.content.Context;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;
import com.stardust.pio.PFiles;

import java.io.File;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by Stardust on 2018/1/24.
 */

public class ProjectConfig {

    public static final String CONFIG_FILE_NAME = "project.json";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * 只用于**读**的 Gson：在 {@link ProDialect} 里对 Pro 方言做容错（A1-4）。
     * 写路径仍然用上面的 {@link #GSON}（逐字不变；新增字段全部是 transient，不会进 toJson）。
     */
    private static final Gson READ_GSON = new GsonBuilder().setPrettyPrinting()
            .registerTypeAdapter(ProjectConfig.class, new ProDialect())
            .create();

    /** Pro 的 scripts 钩子（值是字符串）——与我们的 scripts（值是对象）分开存，避免静默丢。 */
    private final transient Map<String, String> mScriptHooks = new HashMap<>();

    /** Pro 的 features 对象（例如 nodejs=disabled/enabled、activityIntentTasks）——只读兼容，不参与写。 */
    private final transient Map<String, String> mProFeatures = new HashMap<>();

    /** 读兼容过程中发现的、**不致命**的问题（未知 Pro 键、类型不认识…）：警告而非崩溃。 */
    private final transient List<String> mWarnings = new ArrayList<>();

    @SerializedName("name")
    private String mName;

    @SerializedName("versionName")
    private String mVersionName;

    @SerializedName("versionCode")
    private int mVersionCode = -1;

    @SerializedName("packageName")
    private String mPackageName;

    @SerializedName("main")
    private String mMainScriptFile;

    @SerializedName("assets")
    private List<String> mAssets = new ArrayList<>();

    @SerializedName("launchConfig")
    private LaunchConfig mLaunchConfig;

    @SerializedName("build")
    private BuildInfo mBuildInfo = new BuildInfo();

    @SerializedName("icon")
    private String mIcon;

    @SerializedName("scripts")
    private Map<String, ScriptConfig> mScriptConfigs = new HashMap<>();

    @SerializedName("useFeatures")
    private List<String> mFeatures = new ArrayList<>();

    @SerializedName("engine")
    private String mEngine;

    @SerializedName("type")
    private String mProjectType;

    /**
     * AI.js Pro 工程模式的 Android 资源声明：
     *
     * <pre>
     * "androidResources": { "resDir": "res", "manifest": "AndroidManifest.xml" }
     * </pre>
     *
     * <p>有这一段才允许脚本里用 {@code ui.useAndroidResources()} / {@code ui.R.xxx}（见
     * {@code com.stardust.autojs.core.ui.inflater.ProjectResources}）。
     */
    @SerializedName("androidResources")
    private AndroidResources mAndroidResources;

    /**
     * 工程声明的权限清单（AI.js Pro 的工程字段）：
     *
     * <pre>
     * "permissionConfig": { "manifestPermissions": ["android.permission.INTERNET", ...] }
     * </pre>
     *
     * <p>打包页此前用裸 {@code JSONObject} 直接解析这一段（{@code MiuixBuildActivity
     * .readDeclaredPermissions}），是「读工程 manifest」路径上唯一绕过本类的地方。
     * B1 批次5 把它建模到这里，使 manifest 的读取只剩一个入口。
     *
     * <p>语义三态（与旧实现一致，勿改）：
     * <ul>
     *   <li>整段缺失 → {@code null}，调用方用模板默认权限；</li>
     *   <li>有 {@code permissionConfig} 但没有 {@code manifestPermissions} → 同样 {@code null}；</li>
     *   <li>有 {@code manifestPermissions}（哪怕是空数组）→ 返回该列表，
     *       空列表表示工程明确声明「什么都不声明」。</li>
     * </ul>
     */
    @SerializedName("permissionConfig")
    private PermissionConfig mPermissionConfig;

    /**
     * 脚本保护等级（AI.js Pro / AI.js Pro 工程格式里的 {@code encryptLevel}）。
     *
     * <p>缺省 {@link ScriptProtection#DEFAULT_LEVEL}（加密）：老工程没有这个字段时行为不变；
     * 显式写 0 才会产出明文脚本，写 2 则编译后再加密。
     */
    @SerializedName("encryptLevel")
    private int mEncryptLevel = ScriptProtection.DEFAULT_LEVEL;

    /**
     * 脚本存放位置：{@code "assets"}（默认，写进 {@code assets/project/}）或
     * {@code "native"}（加密载荷嵌进原生库，产物里没有脚本文件）。
     *
     * <p>跟 {@code encryptLevel} 是两个维度：等级决定「怎么保护」，存放位置决定「放哪」。
     */
    @SerializedName("scriptStorage")
    private String mScriptStorage = ScriptProtection.DEFAULT_STORAGE;

    /**
     * 脚本密钥的随机盐（十六进制）。打包时生成，每个包不同；
     * 老产物没有这个字段，运行端回退到旧的密钥派生。
     */
    @SerializedName("scriptSalt")
    private String mScriptSalt;

    /**
     * 打包时使用的签名证书指纹（SHA-256，冒号分隔大写）。
     *
     * <p>参与密钥派生：用别的证书重签就解不开脚本；运行端启动时会比对自己的
     * 签名证书，不一致时直接拒绝运行（典型场景是重打包与二次修改）。
     */
    @SerializedName("signatureFingerprint")
    private String mSignatureFingerprint;

    /**
     * **SJBC 容器**配置（task-81）。SJBC 的 {@code .sjbc} 自带 AES-256-GCM + HKDF 双层加密，
     * 所以用了 SJBC 的工程**推荐把 encryptLevel 选 0（不加密）**；其余等级仍是「**可选再套一层**」。
     *
     * <p>只描述「工程要不要带 SJBC 容器、容器目录在哪、期望的签名证书指纹是什么」；
     * **目录收集、manifest 写入、certSha 的实际计算由打包侧（ApkBuilder/PocPackaging）负责**。</p>
     *
     * <p>未配置时该字段为 {@code null} → Gson **不输出**该键 ⇒ 未用 SJBC 的工程 toJson 逐字不变。</p>
     */
    @SerializedName("sjbc")
    private SjbcConfig mSjbc;

    public SjbcConfig getSjbc() {
        return mSjbc;
    }

    public void setSjbc(SjbcConfig sjbc) {
        mSjbc = sjbc;
    }

    /** 工程是否配置了 SJBC 容器（读兼容端点：只看字段在不在，不猜语义）。 */
    public boolean usesSjbc() {
        return mSjbc != null;
    }

    /** SJBC 容器配置（键名稳定：sjbc.assetsDir / sjbc.certSha）。 */
    public static final class SjbcConfig {

        /** 容器目录默认值（相对工程根）。 */
        public static final String DEFAULT_ASSETS_DIR = "assets/sjbc";

        @SerializedName("assetsDir")
        private String mAssetsDir = DEFAULT_ASSETS_DIR;

        /** 期望的签名证书 SHA-256（十六进制；规范化后无冒号、全小写）。**期望值**，实际值由打包侧算。 */
        @SerializedName("certSha")
        private String mCertSha;

        public String getAssetsDir() {
            return mAssetsDir;
        }

        public void setAssetsDir(String assetsDir) {
            mAssetsDir = assetsDir;
        }

        public String getCertSha() {
            return mCertSha;
        }

        public void setCertSha(String certSha) {
            mCertSha = certSha;
        }
    }

    /** 规范化证书指纹（去冒号/空白、转小写）；空值原样返回 null。 */
    public static String normalizeCertSha(String value) {
        if (value == null) return null;
        String cleaned = value.replace(":", "").replace(" ", "").trim().toLowerCase(java.util.Locale.ROOT);
        return cleaned.isEmpty() ? null : cleaned;
    }


    /** Pro 的 scripts 钩子（值为字符串的条目）；没有则为空表。**只读**，不参与写。 */
    public Map<String, String> getScriptHooks() {
        return Collections.unmodifiableMap(mScriptHooks);
    }

    /** Pro 的 features 对象（只读兼容）；没有则为空表。 */
    public Map<String, String> getProFeatures() {
        return Collections.unmodifiableMap(mProFeatures);
    }

    /** Pro 的 features.nodejs（disabled/enabled）；没有则 null。关系到引擎选择。 */
    public String getNodeJsMode() {
        return mProFeatures.get("nodejs");
    }

    /** 读兼容警告（非致命：未知 Pro 键、类型不认识…）。 */
    public List<String> getWarnings() {
        return Collections.unmodifiableList(mWarnings);
    }

    /**
     * Pro 方言的**只读**容错反序列化（A1-4）。规则：
     * <ul>
     *   <li>{@code scripts}：值是对象 → 走现有 {@link ScriptConfig}（**行为逐字不变**）；
     *       值是字符串 → Pro 钩子，进 {@link #getScriptHooks()}（**不静默丢**）；
     *       其它类型 → 该条目记 warning、**其余键照常解析**（绝不整体崩，Gson 原本会整份抛）。</li>
     *   <li>{@code displaySplash}：Pro 写 {@code launchConfig.displaySplash}，我们历史上叫 {@code showSplash}；
     *       优先级 = {@code launchConfig.displaySplash} &gt; {@code launchConfig.showSplash} &gt; 顶层 {@code displaySplash}。</li>
     *   <li>{@code features}（Pro 是对象）→ 进 {@link #getProFeatures()}；我们的 {@code useFeatures}（数组）不变。</li>
     *   <li>{@code abis}/{@code optimization}/{@code plugins}/{@code signingConfig}：**有意不读**，只在 warnings 里列名
     *       （真值在打包策略侧 AbiPolicy/NativeLibPolicy，避免第二份真值）。</li>
     * </ul>
     */
    static final class ProDialect implements JsonDeserializer<ProjectConfig> {

        private static final String[] PRO_KEYS_NOT_READ =
                {"abis", "optimization", "plugins", "signingConfig"};

        @Override
        public ProjectConfig deserialize(JsonElement element, Type type, JsonDeserializationContext context) {
            JsonObject json = element.getAsJsonObject();
            // **顺序很重要**：普通 Gson 的 Map 适配器一旦看到 scripts 值不是对象就会抛（Expected BEGIN_OBJECT
            // but was STRING/NUMBER）——所以必须先把这些键从**副本**里摘掉，再委托给普通 Gson 解析其余字段。
            JsonObject plain = json.deepCopy();
            JsonElement scripts = plain.remove("scripts");
            // sjbc 同样先摘出来：Gson 对「类型不对的嵌套对象」会整份抛，这里必须手工容错。
            JsonElement sjbc = plain.remove("sjbc");
            JsonElement topDisplay = plain.has("displaySplash") ? plain.remove("displaySplash") : null;
            JsonObject launch = plain.has("launchConfig") && plain.get("launchConfig").isJsonObject()
                    ? plain.getAsJsonObject("launchConfig") : null;
            boolean hasDisplay = launch != null && launch.has("displaySplash");
            boolean hasShow = launch != null && launch.has("showSplash");
            // displaySplash 优先级：launchConfig.displaySplash > launchConfig.showSplash > 顶层 displaySplash
            if (hasDisplay) {
                launch.add("showSplash", launch.get("displaySplash"));
                launch.remove("displaySplash");
            } else if (!hasShow && topDisplay != null && topDisplay.isJsonPrimitive()) {
                if (launch == null) {
                    launch = new JsonObject();
                    plain.add("launchConfig", launch);
                }
                launch.add("showSplash", topDisplay);
            }
            // 正常路径：其余字段仍由普通 Gson 解析（字段级行为与改动前一致；未知键本来就会忽略）。
            ProjectConfig config = GSON.fromJson(plain, ProjectConfig.class);
            // 1) scripts 容错（用摘出来的那份，不再受 Gson 类型约束）
            if (scripts != null) {
                if (scripts.isJsonObject()) {
                    Map<String, ScriptConfig> configs = new HashMap<>();
                    for (Map.Entry<String, JsonElement> entry : scripts.getAsJsonObject().entrySet()) {
                        JsonElement value = entry.getValue();
                        if (value.isJsonObject()) {
                            configs.put(entry.getKey(), GSON.fromJson(value, ScriptConfig.class));
                        } else if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                            config.mScriptHooks.put(entry.getKey(), value.getAsString());
                        } else {
                            config.mWarnings.add("scripts." + entry.getKey()
                                    + " 类型不认识（期望对象或字符串），已忽略该条目");
                        }
                    }
                    config.mScriptConfigs = configs;
                } else {
                    config.mWarnings.add("scripts 不是对象，已忽略");
                }
            }
            // task-101：optimization.removeOcr（只读映射，transient ⇒ toJson 逐字不变）。
            JsonElement optimization = json.get("optimization");
            if (optimization != null && optimization.isJsonObject()
                    && optimization.getAsJsonObject().has("removeOcr")) {
                JsonElement flag = optimization.getAsJsonObject().get("removeOcr");
                if (flag != null && flag.isJsonPrimitive()) {
                    config.mRemoveOcr = flag.getAsBoolean();
                }
            }
            // 2/3) displaySplash 已在委托前落到 showSplash；features（Pro 是对象）见下
            JsonElement features = json.get("features");
            if (features != null) {
                if (features.isJsonObject()) {
                    for (Map.Entry<String, JsonElement> entry : features.getAsJsonObject().entrySet()) {
                        JsonElement value = entry.getValue();
                        config.mProFeatures.put(entry.getKey(),
                                value.isJsonPrimitive() ? value.getAsString() : value.toString());
                    }
                } else {
                    config.mWarnings.add("features 不是对象（我们的 useFeatures 是数组），已忽略");
                }
            }
            // 4) sjbc（SJBC 容器）：容错解析 + 规范化；缺目录用默认值
            if (sjbc != null) {
                if (sjbc.isJsonObject()) {
                    try {
                        config.mSjbc = GSON.fromJson(sjbc, SjbcConfig.class);
                    } catch (RuntimeException error) {
                        config.mWarnings.add("sjbc 配置解析失败（" + error.getClass().getSimpleName() + "），已忽略");
                        config.mSjbc = null;
                    }
                    if (config.mSjbc != null) {
                        String dir = config.mSjbc.getAssetsDir();
                        if (dir == null || dir.trim().isEmpty()) {
                            config.mSjbc.setAssetsDir(SjbcConfig.DEFAULT_ASSETS_DIR);
                            config.mWarnings.add("sjbc.assetsDir 为空，已回退默认 " + SjbcConfig.DEFAULT_ASSETS_DIR);
                        }
                        config.mSjbc.setCertSha(normalizeCertSha(config.mSjbc.getCertSha()));
                        if (config.mSjbc.getCertSha() == null && sjbc.getAsJsonObject().has("certSha")) {
                            config.mWarnings.add("sjbc.certSha 为空，已忽略（不比较证书指纹）");
                        }
                    }
                } else {
                    config.mWarnings.add("sjbc 不是对象，已忽略");
                    config.mSjbc = null;
                }
            }
            // 5) 有意不读的 Pro 键：只列清单
            for (String key : PRO_KEYS_NOT_READ) {
                if (json.has(key)) {
                    config.mWarnings.add("未读取的 Pro 键：" + key + "（真值在打包策略侧，避免第二份真值）");
                }
            }
            return config;
        }
    }

    public static ProjectConfig fromJson(String json) {
        if (json == null) {
            return null;
        }
        ProjectConfig config = READ_GSON.fromJson(json, ProjectConfig.class);
        if (!isValid(config)) {
            return null;
        }
        return config;
    }

    /**
     * 纯 Java 判空（原 android.text.TextUtils.isEmpty 的等价语义 + trim）。
     * 换掉 Android 依赖是为了让 isValid/AndroidResources 这段逻辑**能在 JVM 单测里跑**（A1-4 回归的前提）。
     */
    private static boolean isEmptyText(CharSequence value) {
        return value == null || value.toString().trim().isEmpty();
    }

    private static boolean isValid(ProjectConfig config) {
        if (isEmptyText(config.getName())) {
            return false;
        }
        if (isEmptyText(config.getPackageName())) {
            return false;
        }
        if (isEmptyText(config.getVersionName())) {
            return false;
        }
        if (isEmptyText(config.getMainScriptFile())) {
            return false;
        }
        if (config.getVersionCode() == -1) {
            return false;
        }
        return true;
    }


    public static ProjectConfig fromAssets(Context context, String path) {
        try {
            return fromJson(PFiles.read(context.getAssets().open(path)));
        } catch (Exception e) {
            return null;
        }
    }

    public static ProjectConfig fromFile(String path) {
        try {
            return fromJson(PFiles.read(path));
        } catch (Exception e) {
            return null;
        }
    }

    public static ProjectConfig fromProjectDir(String path) {
        return fromFile(configFileOfDir(path));
    }


    public static String configFileOfDir(String projectDir) {
        return PFiles.join(projectDir, CONFIG_FILE_NAME);
    }

    public BuildInfo getBuildInfo() {
        return mBuildInfo;
    }

    public void setBuildInfo(BuildInfo buildInfo) {
        mBuildInfo = buildInfo;
    }

    public String getName() {
        return mName;
    }

    public ProjectConfig setName(String name) {
        mName = name;
        return this;
    }

    public String getVersionName() {
        return mVersionName;
    }

    public ProjectConfig setVersionName(String versionName) {
        mVersionName = versionName;
        return this;
    }

    public int getVersionCode() {
        return mVersionCode;
    }

    public ProjectConfig setVersionCode(int versionCode) {
        mVersionCode = versionCode;
        return this;
    }

    public String getPackageName() {
        return mPackageName;
    }

    public ProjectConfig setPackageName(String packageName) {
        mPackageName = packageName;
        return this;
    }

    public String getMainScriptFile() {
        return mMainScriptFile;
    }

    public ProjectConfig setMainScriptFile(String mainScriptFile) {
        mMainScriptFile = mainScriptFile;
        return this;
    }

    /**
     * 工程模式的资源声明；老工程没有这个字段时返回 {@code null}（= 不支持 Android 资源特性）。
     */
    public AndroidResources getAndroidResources() {
        return mAndroidResources;
    }

    public void setAndroidResources(AndroidResources androidResources) {
        mAndroidResources = androidResources;
    }

    /** 工程权限声明；见 {@link #getDeclaredPermissions()} 的三态语义。 */
    public PermissionConfig getPermissionConfig() {
        return mPermissionConfig;
    }

    public void setPermissionConfig(PermissionConfig permissionConfig) {
        mPermissionConfig = permissionConfig;
    }

    public Map<String, ScriptConfig> getScriptConfigs() {
        if (mScriptConfigs == null) {
            mScriptConfigs = new HashMap<>();
        }
        return mScriptConfigs;
    }

    public List<String> getAssets() {
        if (mAssets == null) {
            mAssets = Collections.emptyList();
        }
        return mAssets;
    }

    public boolean addAsset(String assetRelativePath) {
        if (mAssets == null) {
            mAssets = new ArrayList<>();
        }
        for (String asset : mAssets) {
            if (new File(asset).equals(new File(assetRelativePath))) {
                return false;
            }
        }
        mAssets.add(assetRelativePath);
        return true;
    }

    public void setAssets(List<String> assets) {
        mAssets = assets;
    }

    public LaunchConfig getLaunchConfig() {
        if (mLaunchConfig == null) {
            mLaunchConfig = new LaunchConfig();
        }
        return mLaunchConfig;
    }

    public void setLaunchConfig(LaunchConfig launchConfig) {
        mLaunchConfig = launchConfig;
    }

    /**
     * task-101：{@code optimization.removeOcr} 的**只读**映射（与 removeOpenCv 同形）。
     *
     * <p>真值仍由打包侧（{@code NativeLibPolicy}/{@code ApkBuilder}）从 project.json 直接读；
     * 这里只是给引擎/UI 一个可查询的口径，字段是 transient ⇒ **写路径逐字不变**（未设置时为 null）。</p>
     */
    private transient Boolean mRemoveOcr;

    /** 工程是否声明 {@code optimization.removeOcr=true}（未声明 = false）。 */
    public boolean isRemoveOcr() {
        return Boolean.TRUE.equals(mRemoveOcr);
    }

    public String toJson() {
        return GSON.toJson(this);
    }

    public String getIcon() {
        return mIcon;
    }

    public void setIcon(String icon) {
        mIcon = icon;
    }

    /**
     * 工程模式（AI.js Pro 的「主题/资源」特性）的资源声明。
     *
     * <p>字段名与 Pro 的 {@code project.json} 一致（{@code resDir} / {@code manifest}），
     * 目的是让工程配置能原样读写不丢字段；真正用到的是 {@code resDir}。
     */
    public static class AndroidResources {

        /** 资源目录，相对工程根；缺省 {@code res}。 */
        @SerializedName("resDir")
        private String mResDir = "res";

        /** 清单文件，相对工程根；当前引擎不解析它，只为格式对齐保留。 */
        @SerializedName("manifest")
        private String mManifest;

        public String getResDir() {
            return isEmptyText(mResDir) ? "res" : mResDir;
        }

        public void setResDir(String resDir) {
            mResDir = resDir;
        }

        public String getManifest() {
            return mManifest;
        }

        public void setManifest(String manifest) {
            mManifest = manifest;
        }
    }

    /**
     * 工程权限声明的载体，见 {@link #mPermissionConfig}。
     *
     * <p>字段名与 Pro 的 {@code project.json} 一致（{@code manifestPermissions}），
     * 以便原样读写不丢字段。
     */
    public static class PermissionConfig {

        /** 声明进产物清单的权限；{@code null} 表示工程没写这一段。 */
        @SerializedName("manifestPermissions")
        private List<String> mManifestPermissions;

        public List<String> getManifestPermissions() {
            return mManifestPermissions;
        }

        public void setManifestPermissions(List<String> manifestPermissions) {
            mManifestPermissions = manifestPermissions;
        }
    }

    /**
     * 工程声明的权限列表；{@code null} = 工程没写这一段（调用方用模板默认）。
     *
     * <p>等价于旧 {@code MiuixBuildActivity.readDeclaredPermissions()} 的返回值，
     * 空白项会被过滤（旧实现用 {@code takeIf { it.isNotBlank() }}）。
     * 与旧实现一致：解析异常时不抛给调用方，按「工程没写」处理。
     */
    public List<String> getDeclaredPermissions() {
        if (mPermissionConfig == null) {
            return null;
        }
        List<String> declared = mPermissionConfig.getManifestPermissions();
        if (declared == null) {
            return null;
        }
        List<String> result = new ArrayList<>(declared.size());
        for (String permission : declared) {
            if (permission != null && !permission.trim().isEmpty()) {
                result.add(permission);
            }
        }
        return result;
    }

    /** 脚本保护等级，见 {@link ScriptProtection}。 */
    public int getEncryptLevel() {
        return mEncryptLevel;
    }

    public void setEncryptLevel(int encryptLevel) {
        mEncryptLevel = ScriptProtection.normalize(encryptLevel);
    }

    /** 脚本存放位置，见字段说明；非法/为空时当 {@code assets}。 */
    public String getScriptStorage() {
        return ScriptProtection.normalizeStorage(mScriptStorage);
    }

    public void setScriptStorage(String scriptStorage) {
        mScriptStorage = ScriptProtection.normalizeStorage(scriptStorage);
    }

    /** 脚本密钥随机盐（hex）；老产物为 null。 */
    public String getScriptSalt() {
        return mScriptSalt;
    }

    public void setScriptSalt(String scriptSalt) {
        mScriptSalt = scriptSalt;
    }

    /** 打包时的签名证书指纹（SHA-256，冒号分隔大写）；老产物为 null。 */
    public String getSignatureFingerprint() {
        return mSignatureFingerprint;
    }

    public void setSignatureFingerprint(String signatureFingerprint) {
        mSignatureFingerprint = signatureFingerprint;
    }

    public String getBuildDir() {
        return "build";
    }

    public List<String> getFeatures() {
        if (mFeatures == null) {
            mFeatures = new ArrayList<>();
        }
        return mFeatures;
    }

    public void setFeatures(List<String> features) {
        mFeatures = features;
    }

    public String getEngine() {
        if (mEngine != null && !mEngine.trim().isEmpty()) return mEngine;
        if ("node".equalsIgnoreCase(mProjectType) || "nodejs".equalsIgnoreCase(mProjectType)) return "node";
        return mEngine;
    }

    public void setEngine(String engine) {
        mEngine = engine;
    }

    public String getEngine(String path) {
        ScriptConfig scriptConfig = mScriptConfigs == null ? null : mScriptConfigs.get(path);
        if (scriptConfig != null && scriptConfig.getEngine() != null
                && !scriptConfig.getEngine().trim().isEmpty()) {
            return scriptConfig.getEngine();
        }
        return getEngine();
    }

    public ScriptConfig getScriptConfig(String path) {
        if (mScriptConfigs == null) {
            mScriptConfigs = new HashMap<>();
        }
        ScriptConfig config = mScriptConfigs.get(path);
        if (config == null) {
            config = new ScriptConfig();
        }
        List<String> projectFeatures = getFeatures();
        if(projectFeatures.isEmpty()){
            return config;
        }
        ArrayList<String> features = new ArrayList<>(config.getFeatures());
        for (String feature : projectFeatures) {
            if (!features.contains(feature)) {
                features.add(feature);
            }
        }
        config.setFeatures(features);
        return config;
    }
}
