package com.stardust.autojs.project;

/**
 * 打包脚本保护等级，对应 {@code project.json} 的 {@code encryptLevel} 字段。
 *
 * <p>等级语义对齐 Auto.js / Auto.js Pro 的工程格式，并在高等级上扩展我们自己的实现：
 * <ul>
 *   <li>{@link #LEVEL_NONE}：不加密，脚本以原始文本写入 {@code assets/project/}；</li>
 *   <li>{@link #LEVEL_ENCRYPT}：AES/CBC + 8 字节文件头（默认值，等价于历史行为）；</li>
 *   <li>{@link #LEVEL_COMPILE}：先编译再加密（Rhino 编译为 {@code .class}、QuickJS 编译为字节码），
 *       产物里不含可读源码。</li>
 * </ul>
 *
 * <p>这个类没有任何 Android 依赖，打包端（应用内 {@code ApkBuilder}）与运行端（打包出的
 * App 读回 {@code project.json}）共用同一份判定，避免两端各写一套逻辑后语义漂移。
 */
public final class ScriptProtection {

    /** 不加密。 */
    public static final int LEVEL_NONE = 0;
    /** 加密（AES/CBC）。 */
    public static final int LEVEL_ENCRYPT = 1;
    /** 编译 + 加密。 */
    public static final int LEVEL_COMPILE = 2;
    /** 当前支持的最高等级。 */
    public static final int MAX_LEVEL = LEVEL_COMPILE;

    /** 脚本存放位置：产物的 {@code assets/project/} 里的脚本文件（历史行为）。 */
    public static final String STORAGE_ASSETS = "assets";
    /** 脚本存放位置：加密载荷嵌进产物里的原生库（{@code libaijscrypto.so} 尾部），产物中没有脚本文件。 */
    public static final String STORAGE_NATIVE = "native";

    /** {@code project.json} 里没有 {@code scriptStorage} 字段时使用的位置。 */
    public static final String DEFAULT_STORAGE = STORAGE_ASSETS;

    /** 打包页给用户看的档位：不加密。 */
    public static final int CHOICE_NONE = 0;
    /** 打包页档位：加密（AES）。 */
    public static final int CHOICE_ENCRYPT = 1;
    /** 打包页档位：快照（先编译成 class / 字节码，再加密）。 */
    public static final int CHOICE_COMPILE = 2;
    /** 打包页档位：加密 so（载荷嵌进原生库，产物里没有脚本文件）。 */
    public static final int CHOICE_NATIVE = 3;
    /**
     * 打包页档位：快照 so（先编译再加密，然后把密文嵌进原生库）。
     *
     * <p>比 {@link #CHOICE_NATIVE} 更进一步：产物里既没有脚本文件、也没有可读源码；
     * 与 {@link #CHOICE_COMPILE} 的唯一区别是密文放哪（原生库尾部 vs {@code assets/project/main.js}）。
     */
    public static final int CHOICE_NATIVE_COMPILE = 4;

    /** 档位数量（界面遍历用）。 */
    public static final int CHOICE_COUNT = CHOICE_NATIVE_COMPILE + 1;

    /**
     * {@code project.json} 里没有该字段时使用的等级。
     *
     * <p>取「加密」而不是「不加密」：这样历史工程行为不变（以前是无条件加密），
     * 只有显式写了 {@code "encryptLevel": 0} 的工程才会产出明文。
     */
    public static final int DEFAULT_LEVEL = LEVEL_ENCRYPT;

    private ScriptProtection() {
    }

    /** 把任意输入夹到支持范围内（负数按 0、超上限按上限），避免非法值导致产物不可运行。 */
    public static int normalize(int level) {
        if (level < LEVEL_NONE) {
            return LEVEL_NONE;
        }
        return Math.min(level, MAX_LEVEL);
    }

    /** 是否需要加密：等级 ≥ {@link #LEVEL_ENCRYPT}。 */
    public static boolean shouldEncrypt(int level) {
        return normalize(level) >= LEVEL_ENCRYPT;
    }

    /** 是否需要编译：等级 ≥ {@link #LEVEL_COMPILE}。 */
    public static boolean shouldCompile(int level) {
        return normalize(level) >= LEVEL_COMPILE;
    }

    /** 给界面/日志用的中文描述。 */
    public static String describe(int level) {
        switch (normalize(level)) {
            case LEVEL_NONE:
                return "不加密";
            case LEVEL_ENCRYPT:
                return "加密（AES）";
            default:
                return "编译 + 加密";
        }
    }

    /** 把档位夹到支持范围内。 */
    public static int normalizeChoice(int choice) {
        if (choice < CHOICE_NONE) {
            return CHOICE_NONE;
        }
        return Math.min(choice, CHOICE_NATIVE_COMPILE);
    }

    /** 规范化存放位置：只认 {@link #STORAGE_NATIVE}，其余一律当 {@link #STORAGE_ASSETS}。 */
    public static String normalizeStorage(String storage) {
        if (storage == null) {
            return STORAGE_ASSETS;
        }
        return STORAGE_NATIVE.equalsIgnoreCase(storage.trim()) ? STORAGE_NATIVE : STORAGE_ASSETS;
    }

    /** 是否把脚本载荷嵌进原生库（产物里没有脚本文件）。 */
    public static boolean usesNativeStorage(String storage) {
        return STORAGE_NATIVE.equals(normalizeStorage(storage));
    }

    /** 档位 → {@code encryptLevel}。 */
    public static int levelOfChoice(int choice) {
        switch (normalizeChoice(choice)) {
            case CHOICE_NONE:
                return LEVEL_NONE;
            case CHOICE_COMPILE:
            case CHOICE_NATIVE_COMPILE:
                return LEVEL_COMPILE;
            default:
                // CHOICE_ENCRYPT 与 CHOICE_NATIVE 都是「加密」；嵌进原生库不允许明文。
                return LEVEL_ENCRYPT;
        }
    }

    /** 档位 → {@code scriptStorage}。 */
    public static String storageOfChoice(int choice) {
        switch (normalizeChoice(choice)) {
            case CHOICE_NATIVE:
            case CHOICE_NATIVE_COMPILE:
                return STORAGE_NATIVE;
            default:
                return STORAGE_ASSETS;
        }
    }

    /**
     * 由 {@code encryptLevel} + {@code scriptStorage} 反推打包页档位（打开工程时用）。
     *
     * <p>两个字段的每种合法组合都要能原样推回，否则「打开工程再打包」会**静默降级**
     * （例如「等级 2 + 原生库」若被认成「加密 so」，再打包时编译就丢了）。
     */
    public static int choiceOf(int level, String storage) {
        boolean nativeStorage = usesNativeStorage(storage);
        switch (normalize(level)) {
            case LEVEL_NONE:
                return nativeStorage ? CHOICE_NATIVE : CHOICE_NONE;
            case LEVEL_COMPILE:
                return nativeStorage ? CHOICE_NATIVE_COMPILE : CHOICE_COMPILE;
            default:
                return nativeStorage ? CHOICE_NATIVE : CHOICE_ENCRYPT;
        }
    }

    /** 档位的中文描述（日志/界面用）。 */
    public static String describeChoice(int choice) {
        switch (normalizeChoice(choice)) {
            case CHOICE_NONE:
                return "不加密";
            case CHOICE_COMPILE:
                return "快照（编译）";
            case CHOICE_NATIVE:
                return "加密 so";
            case CHOICE_NATIVE_COMPILE:
                return "快照 so";
            default:
                return "加密（AES）";
        }
    }
}
