package com.jdkshen.aijspro.flutterpoc.nativeproc;

import java.io.File;
import java.util.Locale;

/**
 * 判断脚本项目是否自带 native 库，供主进程决定一次执行是否需要交给 :native 隔离进程。
 *
 * <p>Phase0 把需要原生库的脚本执行隔离到 :native 进程；纯 JS 项目没必要付出额外进程和 IPC
 * 成本，因此由 {@link #isNativeProject(File)} / {@link #isNativeScriptFile(File)} 先做判断。
 *
 * <p>规则：从项目根目录起递归向下，最多进入 {@link #MAX_DEPTH} 层子目录；整棵跳过
 * {@link #SKIP_DIR_NAMES} 里的构建/版本库/依赖目录；只要命中任意 .so 或 .dex 文件即视为
 * native 项目（扩展名大小写不敏感）。.apk 不参与判断（也不解包进 APK 内部查找）。
 *
 * <p>检测只为「选执行路径」，任何异常都按非 native 处理，绝不让检测本身打断脚本执行。
 * 纯 JDK（java.io.File）实现，Java 8 语法，无 Android / 第三方依赖。
 */
public final class NativeProjectDetector {

    /** 相对于项目根目录的最大递归目录层数（根目录本身算第 0 层）。 */
    private static final int MAX_DEPTH = 5;

    /** 扫描时整棵跳过的目录名：构建产物、git 元数据、本地产物缓存、npm 依赖。 */
    private static final String[] SKIP_DIR_NAMES = {"build", ".git", ".artifacts", "node_modules"};

    /** 判定为 native 项目的扩展名，比较前统一转小写。 */
    private static final String[] NATIVE_EXTENSIONS = {".so", ".dex"};

    private NativeProjectDetector() {
    }

    /**
     * 项目目录（含最多 5 层子目录）内是否存在任意 .so / .dex 文件。
     *
     * @param projectDir 项目根目录；为 null、不存在或不是目录时返回 false
     * @return 发现 native 库返回 true
     */
    public static boolean isNativeProject(File projectDir) {
        if (projectDir == null) {
            return false;
        }
        try {
            if (!projectDir.isDirectory()) {
                return false;
            }
            return scan(projectDir, 0);
        } catch (Exception e) {
            // 权限不足、目录被并发删除等：宁可留在主进程，也不要因为检测失败中断执行。
            return false;
        }
    }

    /**
     * 单文件运行场景：文件存在且是普通文件时，用其所在目录做项目级判断。
     *
     * @param file 待执行的脚本文件；为 null、不存在或不是文件时返回 false
     * @return 该文件所在目录（按项目规则）含 native 库返回 true
     */
    public static boolean isNativeScriptFile(File file) {
        if (file == null) {
            return false;
        }
        try {
            if (!file.isFile()) {
                return false;
            }
            return isNativeProject(file.getParentFile());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 递归扫描一个目录。
     *
     * @param dir   当前目录
     * @param depth 当前目录相对项目根目录的层数（根目录为 0）
     */
    private static boolean scan(File dir, int depth) {
        if (depth > MAX_DEPTH) {
            return false;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            // 目录不可读（无权限）或已不存在：按没有 native 库处理。
            return false;
        }
        for (File child : children) {
            if (child == null) {
                continue;
            }
            if (child.isDirectory()) {
                if (isSkippedDir(child.getName())) {
                    continue;
                }
                if (scan(child, depth + 1)) {
                    return true;
                }
            } else if (child.isFile() && hasNativeExtension(child.getName())) {
                return true;
            }
        }
        return false;
    }

    /** 是否需要整棵跳过的目录（精确匹配，避免误伤普通业务目录）。 */
    private static boolean isSkippedDir(String dirName) {
        if (dirName == null) {
            return false;
        }
        for (String skipped : SKIP_DIR_NAMES) {
            if (skipped.equals(dirName)) {
                return true;
            }
        }
        return false;
    }

    /** 文件名扩展名是否为 .so / .dex（大小写不敏感，如 .SO / .So / .DEX 也算）。 */
    private static boolean hasNativeExtension(String fileName) {
        if (fileName == null) {
            return false;
        }
        String lower = fileName.toLowerCase(Locale.US);
        for (String extension : NATIVE_EXTENSIONS) {
            if (lower.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }
}
