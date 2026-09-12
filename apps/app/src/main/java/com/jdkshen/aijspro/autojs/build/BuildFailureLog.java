package com.jdkshen.aijspro.autojs.build;

import android.util.Log;

import com.jdkshen.aijspro.autojs.AutoJs;
import com.stardust.autojs.runtime.api.Console;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * 打包失败的统一上报：把「可读摘要 + 完整异常链」同时写进 logcat 和应用内全局日志。
 *
 * <p>背景（用户反馈）：打包失败只弹最外层异常的 message——外层没带信息时（甚至 message 为空）
 * 用户拿不到原因；而且失败只进 logcat，应用内「全局日志」里查不到任何打包记录，事后无法追溯。
 *
 * <p>这里保证两点：
 * <ul>
 *     <li>{@link #describe(Throwable)} 沿 cause 链收集信息，弹窗/Toast 不再只显示外层消息；</li>
 *     <li>{@link #report(String, String, Throwable)} 把摘要 + 完整堆栈写进应用内全局日志
 *     （脚本日志界面同一条通道），排查时不用连电脑抓 logcat。</li>
 * </ul>
 */
public final class BuildFailureLog {

    private static final String TAG = "BuildFailureLog";

    /** cause 链的展示深度：正常打包异常最多两三层，再深大概率是异常环。 */
    private static final int MAX_CAUSE_DEPTH = 16;

    private BuildFailureLog() {
    }

    /**
     * 把异常链上的 message 依次拼成可直接展示的摘要。
     *
     * <p>外层异常常常只有一句笼统的话（甚至 message 为空），真实原因在里面一层；
     * 链上重复的消息（包装异常很常见）只保留一份，避免同一句话刷两遍。
     */
    public static String describe(Throwable error) {
        if (error == null) {
            return "未知错误";
        }
        StringBuilder summary = new StringBuilder();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = error;
        int depth = 0;
        while (current != null && depth < MAX_CAUSE_DEPTH && seen.add(current)) {
            String message = current.getMessage();
            if (message == null || message.trim().isEmpty()) {
                message = current.getClass().getSimpleName();
            } else {
                message = message.trim();
            }
            if (summary.indexOf(message) < 0) {
                if (summary.length() > 0) {
                    summary.append('\n');
                }
                summary.append(message);
            }
            current = current.getCause();
            depth++;
        }
        return summary.length() == 0 ? String.valueOf(error) : summary.toString();
    }

    /**
     * 上报一次打包失败。
     *
     * @param source 打包源（工程目录或入口脚本路径），可为 null
     * @param stage  失败时所在阶段（如“打包中”），可为 null
     * @param error  失败原因
     */
    public static void report(String source, String stage, Throwable error) {
        StringBuilder summary = new StringBuilder("打包失败");
        if (stage != null && !stage.isEmpty()) {
            summary.append("（").append(stage).append("）");
        }
        if (source != null && !source.isEmpty()) {
            summary.append(" 工程: ").append(source);
        }
        summary.append(": ").append(describe(error));
        Log.e(TAG, summary.toString(), error);
        try {
            Console console = AutoJs.getInstance().getScriptEngineService().getGlobalConsole();
            console.error(summary.toString());
            // 完整堆栈单独一条：脚本日志界面直接可查，等同于把 logcat 搬进应用。
            console.error(Log.getStackTraceString(error));
        } catch (Throwable ignored) {
            // 全局日志不可用（初始化未完成等）不能影响打包失败本身的提示流程。
        }
    }
}
