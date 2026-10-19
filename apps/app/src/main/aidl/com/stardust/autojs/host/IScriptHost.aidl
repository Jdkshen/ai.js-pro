/*
 * Phase1 脚本宿主进程（:script）的跨进程接口骨架（task-15）。
 *
 * 方向：主进程（UI/设置页） -> :script 进程的 ScriptHostService。
 * 现状：本任务只定接口、不实现；Phase1-B 的 ScriptHostService 仍走 Messenger
 * （RUN=1 / STOP=2 / STARTED=100 / FINISHED=101 / ERROR=102），本接口是后续把协议
 * 类型化的入口，方法集与 Messenger 协议一一对应。
 *
 * 修改本文件要同步：
 * - apps/app/src/flutterpoc/.../scriptproc/ScriptHostService.java（服务端，:script）
 * - apps/app/src/flutterpoc/.../scriptproc/PocScriptHost.java（客户端，主进程）
 *
 * 注意：本文件位于 src/main/aidl，生成的 Java 接口对所有变体可见（含非 flutterPoc 构建），
 * 但 :script 服务只在 -PflutterPoc=true 时编译/注册。
 */
package com.stardust.autojs.host;

interface IScriptHost {

    /**
     * 在 :script 进程里启动一个脚本。
     *
     * @param path    入口脚本的绝对路径
     * @param workDir 工作目录（可空；空表示用脚本所在目录）
     * @param token   主进程生成的运行标识，服务端在回调/停止时原样使用
     */
    void run(String path, String workDir, String token);

    /**
     * 停止 token 对应的脚本。
     *
     * @return true 表示找到了这次运行并已请求停止；false 表示 token 不存在或已结束
     */
    boolean stop(String token);

    /**
     * 当前运行中的 token 列表（空数组表示没有运行中的脚本）。
     */
    String[] getRunning();
}
