package com.jdkshen.aijspro.flutterpoc.scriptproc;

import android.content.Context;
import android.util.Log;

import com.jdkshen.aijspro.flutterpoc.nativeproc.NativeProjectDetector;
import com.jdkshen.aijspro.flutterpoc.nativeproc.NativeProjectStaging;

import java.io.File;
import java.io.IOException;

/**
 * {@code :script} 进程的暂存策略：<b>项目脚本</b>执行前复制到应用私有目录，<b>普通单文件脚本</b>
 * 直接在原路径执行（保持 Auto.js 的 cwd=脚本父目录语义）。
 *
 * <p>为什么项目要暂存：从 {@code /sdcard/...} 直接执行项目会有两类问题 ——
 * <ul>
 *   <li>加载 .so/.dex 时命中可写目录限制（logcat：{@code Attempt to load writable dex file}），
 *       Pro 打包版正是靠在私有目录执行解决；</li>
 *   <li>脚本运行期间原目录可能被用户/其它进程改动（重启后路径/内容变化），执行环境不稳定。</li>
 * </ul>
 *
 * <p>为什么单文件脚本<b>不</b>暂存：真机回归发现，普通脚本（如
 * {@code /sdcard/脚本/__phase1_probe.js}）如果按「项目根=脚本父目录」整棵复制，会命中
 * 256MB 上限直接报「脚本暂存失败」——用户把脚本平铺在一个大目录里是很常见的用法，
 * 不能为了少数需要原生库的文件把整个目录复制一遍。单文件脚本没有 .so/.dex 需求，直接在原路径
 * 执行即可；真正需要原生库的目录会被主进程判为 native 项目（走本项目判定 / :native 回退）。
 *
 * <p>本类只做「策略」：判定是否项目、解析项目根、把入口脚本映射到暂存副本；真正的文件复制
 * 复用 {@link NativeProjectStaging}（跳过 {@code build}/{@code .git} 目录与 {@code *.apk}，
 * 单文件 64MB / 整树 256MB 上限，失败抛中文 {@link IOException}）。
 *
 * <p>与 {@code :native} 的关键区别：暂存根目录用 {@code <filesDir>/script-staging/}，
 * Phase0 的 {@code :native} 用 {@code <filesDir>/native-staging/} —— 两个进程同跑同一个项目时，
 * 一方清理旧副本不会踩掉另一方正在用的私有副本（{@code NativeProjectStaging} 本身行为不变）。
 *
 * <p>已知限制（等 Phase1-A spec 对齐后可细化）：
 * <ul>
 *   <li>单文件脚本不复制：如果某个单文件脚本运行时需要加载同目录的 .so/.dex，仍可能命中可写
 *       目录限制。这类目录通常会被主进程 {@code NativeProjectDetector} 判成 native 工程并路由
 *       到 {@code :native}（那里会暂存）；后续也可以按「脚本声明依赖清单」再补；</li>
 *   <li>项目判定是启发式（见 {@link #isProjectExecution}）：没有 project.json、入口也不叫 main.js
 *       的工程会被当单文件脚本、不复制；</li>
 *   <li>暂存副本每次 RUN 前重建（见 {@link NativeProjectStaging}），脚本运行中的写操作落在
 *       私有副本里，不会回写原项目目录。</li>
 * </ul>
 */
public final class ScriptHostStaging {

    private static final String TAG = "ScriptHostStaging";

    /** 暂存根目录名（位于 {@code context.getFilesDir()} 下，与 :native 分开）。 */
    public static final String STAGING_DIR_NAME = "script-staging";

    private ScriptHostStaging() {
    }

    /** 一次暂存的结果：实际执行的入口脚本 + 工作目录 + 是否真的复制过。 */
    public static final class Staged {
        /** 实际执行的入口脚本：项目脚本是私有副本，单文件脚本保留原路径。 */
        public final File scriptFile;
        /** 传给 {@code ExecutionConfig.workingDirectory} 的工作目录（脚本所在的项目根）。 */
        public final File workingDirectory;
        /** true 表示做了复制；false 表示脚本本来就在私有目录，或单文件脚本按原路径执行。 */
        public final boolean copied;

        Staged(File scriptFile, File workingDirectory, boolean copied) {
            this.scriptFile = scriptFile;
            this.workingDirectory = workingDirectory;
            this.copied = copied;
        }
    }

    /**
     * 按需把脚本执行环境暂存到应用私有目录。
     *
     * @param context    任意 Context（内部只用 {@link Context#getFilesDir()}）
     * @param scriptFile 入口脚本（绝对路径）
     * @param workDir    调用方下发的工作目录（脚本项目根）；可为 null，null/不包含脚本时退回脚本父目录
     * @return 实际应执行的脚本与工作目录（项目脚本为私有副本；单文件脚本保留原路径与原父目录）
     * @throws IOException 路径非法、脚本不存在、私有目录不可用、复制失败、超限等（消息为中文）
     */
    public static Staged stageIfNeeded(Context context, File scriptFile, File workDir) throws IOException {
        if (context == null) {
            throw new IOException("脚本暂存失败：Context 为空");
        }
        if (scriptFile == null) {
            throw new IOException("脚本暂存失败：脚本路径为空");
        }
        final File script;
        try {
            script = scriptFile.getCanonicalFile();
        } catch (IOException error) {
            throw new IOException("脚本暂存失败：无法解析脚本路径：" + scriptFile.getPath(), error);
        }
        if (!script.isFile()) {
            throw new IOException("脚本暂存失败：脚本文件不存在：" + script.getPath());
        }
        final File privateRoot;
        try {
            File filesDir = context.getFilesDir();
            if (filesDir == null) {
                throw new IOException("脚本暂存失败：应用私有目录不可用");
            }
            privateRoot = filesDir.getCanonicalFile();
        } catch (IOException error) {
            if (error.getMessage() != null && error.getMessage().startsWith("脚本暂存失败")) {
                throw error;
            }
            throw new IOException("脚本暂存失败：无法解析应用私有目录", error);
        }

        if (NativeProjectStaging.isUnder(script, privateRoot)) {
            // 脚本本来就在私有目录（例如打包版项目 / 上一次运行留下的私有项目）：不重复复制。
            File workingDirectory = resolveWorkingDirectory(script, workDir);
            Log.i(TAG, "脚本已在应用私有目录，跳过暂存：" + script.getPath());
            return new Staged(script, workingDirectory, false);
        }

        File projectDir = resolveProjectDir(script, workDir);
        if (!isProjectExecution(script, workDir, projectDir)) {
            // 普通单文件脚本：不复制（避免把 /sdcard/脚本 这种超大目录整棵搬进私有目录），
            // cwd 保持 Auto.js 语义 = 脚本父目录。
            File parent = script.getParentFile();
            File workingDirectory = parent == null ? projectDir : parent.getCanonicalFile();
            Log.i(TAG, "单文件脚本不暂存，按原路径执行：" + script.getPath()
                    + "（cwd=" + workingDirectory.getPath() + "）");
            return new Staged(script, workingDirectory, false);
        }
        final File stagedDir;
        try {
            stagedDir = NativeProjectStaging.stageIfNeeded(context, projectDir, STAGING_DIR_NAME);
        } catch (IOException error) {
            // 必须明确失败：回退到原目录执行会让「统一从私有目录跑」这个前提静默失效。
            throw new IOException("脚本暂存失败：" + messageOf(error), error);
        }
        File stagedScript = mapStagedScript(projectDir, stagedDir, script);
        Log.i(TAG, "已暂存脚本运行环境：" + projectDir.getPath() + " -> " + stagedDir.getPath()
                + "（入口：" + stagedScript.getName() + "）");
        return new Staged(stagedScript, stagedDir, !stagedDir.equals(projectDir));
    }

    /**
     * 暂存复制用的「项目根」：优先用调用方的 workDir（入口脚本在它之下且确实存在时），
     * 否则退回入口脚本所在目录 —— 保证入口脚本一定在被复制的树里。
     */
    private static File resolveProjectDir(File scriptFile, File workDir) throws IOException {
        if (workDir != null && !workDir.getPath().trim().isEmpty()) {
            try {
                File candidate = workDir.getCanonicalFile();
                if (candidate.isDirectory() && NativeProjectStaging.isUnder(scriptFile, candidate)) {
                    return candidate;
                }
            } catch (IOException ignored) {
                // workDir 解析失败：退回脚本父目录，不中断执行。
            }
        }
        File parent = scriptFile.getParentFile();
        if (parent == null || !parent.isDirectory()) {
            throw new IOException("脚本暂存失败：无法确定脚本所在目录：" + scriptFile.getPath());
        }
        return parent.getCanonicalFile();
    }

    /** 脚本已在私有目录时的执行工作目录：workDir 合法就用它，否则脚本父目录。 */
    private static File resolveWorkingDirectory(File scriptFile, File workDir) throws IOException {
        if (workDir != null && !workDir.getPath().trim().isEmpty()) {
            try {
                File candidate = workDir.getCanonicalFile();
                if (candidate.isDirectory() && NativeProjectStaging.isUnder(scriptFile, candidate)) {
                    return candidate;
                }
            } catch (IOException ignored) {
                // 同上：退回脚本父目录。
            }
        }
        File parent = scriptFile.getParentFile();
        if (parent == null) {
            throw new IOException("脚本暂存失败：无法确定脚本所在目录：" + scriptFile.getPath());
        }
        return parent.getCanonicalFile();
    }

    /**
     * 原入口脚本在暂存目录里的对应文件：按「原项目目录 -&gt; 暂存目录」的相对路径映射。
     * 项目本来就在私有目录（{@link NativeProjectStaging#stageIfNeeded} 原样返回）时直接用原脚本。
     */
    private static File mapStagedScript(File projectDir, File stagedDir, File scriptFile) throws IOException {
        if (stagedDir.equals(projectDir)) {
            return scriptFile;
        }
        String root = projectDir.getCanonicalPath();
        String script = scriptFile.getCanonicalPath();
        String prefix = root.endsWith(File.separator) ? root : root + File.separator;
        File staged;
        if (script.startsWith(prefix)) {
            staged = new File(stagedDir, script.substring(prefix.length()));
        } else {
            // 正常不会走到（resolveProjectDir 已保证脚本在项目根下）：按文件名放到暂存根下。
            staged = new File(stagedDir, scriptFile.getName());
        }
        if (!staged.isFile()) {
            throw new IOException("脚本暂存失败：复制后的入口脚本不存在：" + staged.getPath());
        }
        return staged;
    }

    /**
     * 是否需要整棵暂存：只有「项目」才暂存。
     *
     * <p>判定顺序（Phase1 真机回归约定）：
     * <ol>
     *   <li>入口脚本父目录有 {@code project.json} → 项目；</li>
     *   <li>调用方 workDir（脚本在其下）是带 {@code project.json} 的项目根 → 项目
     *       （runProject 场景，入口脚本可能在子目录里）；</li>
     *   <li>没有 project.json 的 native 工程兜底：入口叫 {@code main.js}，且项目根有
     *       {@code lib} 子目录、或目录树里存在 .so/.dex → 项目（典型 Pro 工程布局）；</li>
     *   <li>其余一律按「单文件脚本」处理，不复制（保持 cwd=脚本父目录）。</li>
     * </ol>
     */
    private static boolean isProjectExecution(File scriptFile, File workDir, File projectDir) throws IOException {
        if (hasProjectJson(scriptFile.getParentFile())) {
            return true;
        }
        if (workDir != null && !workDir.getPath().trim().isEmpty()) {
            try {
                File candidate = workDir.getCanonicalFile();
                if (candidate.isDirectory() && NativeProjectStaging.isUnder(scriptFile, candidate)
                        && hasProjectJson(candidate)) {
                    return true;
                }
            } catch (IOException ignored) {
                // workDir 解析失败：按单文件处理，不影响脚本执行。
            }
        }
        if (isMainJs(scriptFile)) {
            // 兼容没有 project.json 的 Pro 工程布局：main.js + lib/<abi>/*.so。
            return hasLibDirectory(projectDir) || NativeProjectDetector.isNativeProject(projectDir);
        }
        return false;
    }

    private static boolean hasProjectJson(File dir) {
        return dir != null && new File(dir, "project.json").isFile();
    }

    private static boolean hasLibDirectory(File dir) {
        return dir != null && new File(dir, "lib").isDirectory();
    }

    private static boolean isMainJs(File scriptFile) {
        return "main.js".equalsIgnoreCase(scriptFile.getName());
    }

    private static String messageOf(Throwable error) {
        if (error == null) {
            return "未知错误";
        }
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return error.getClass().getSimpleName();
        }
        return message;
    }
}
