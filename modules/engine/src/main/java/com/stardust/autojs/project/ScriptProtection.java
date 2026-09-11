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
}
