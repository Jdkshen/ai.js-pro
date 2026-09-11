package com.stardust.autojs.project;

import android.content.Context;
import android.text.TextUtils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import com.stardust.pio.PFiles;

import java.io.File;
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

    /**
     * 脚本保护等级（Auto.js / Auto.js Pro 工程格式里的 {@code encryptLevel}）。
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


    public static ProjectConfig fromJson(String json) {
        if (json == null) {
            return null;
        }
        ProjectConfig config = GSON.fromJson(json, ProjectConfig.class);
        if (!isValid(config)) {
            return null;
        }
        return config;
    }

    private static boolean isValid(ProjectConfig config) {
        if (TextUtils.isEmpty(config.getName())) {
            return false;
        }
        if (TextUtils.isEmpty(config.getPackageName())) {
            return false;
        }
        if (TextUtils.isEmpty(config.getVersionName())) {
            return false;
        }
        if (TextUtils.isEmpty(config.getMainScriptFile())) {
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

    public String toJson() {
        return GSON.toJson(this);
    }

    public String getIcon() {
        return mIcon;
    }

    public void setIcon(String icon) {
        mIcon = icon;
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
        return mEngine;
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
