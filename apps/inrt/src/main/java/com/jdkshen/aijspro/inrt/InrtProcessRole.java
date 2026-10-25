package com.jdkshen.aijspro.inrt;

/**
 * inrt 打包运行时的进程归属判定。
 *
 * <p>正常路由下脚本引擎、UI/浮窗和脚本 Activity 都在 {@code :script} 进程；引擎模块声明的
 * 无障碍服务没有 {@code android:process}，所以只在主进程。音量上键的全局通道因此落在主进程，
 * 必须显式中继到 {@code :script}。这里保持为纯函数，方便 JVM 测试固定映射关系。
 */
public final class InrtProcessRole {

    public static final String SCRIPT_SUFFIX = ":script";

    private InrtProcessRole() {
    }

    /**
     * 是否主进程。进程名读取失败（null/空/unknown）时按主进程处理：宁可多注册一次主进程
     * 常驻监听，也不要在启动竞态里丢掉紧急停止入口。真正的子进程名一定带 {@code :}。
     */
    public static boolean isMain(String packageName, String processName) {
        return processName == null || processName.isEmpty()
                || "unknown".equals(processName)
                || packageName.equals(processName);
    }

    public static boolean isScript(String packageName, String processName) {
        return processName != null && (packageName + SCRIPT_SUFFIX).equals(processName);
    }
}
