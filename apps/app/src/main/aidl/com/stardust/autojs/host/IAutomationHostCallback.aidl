/*
 * Phase1 P1 自动化代理回调（task-163）：主进程 -> :script 的反向通道。
 * 只用于无障碍事件/服务状态；回调用 oneway，避免主进程无障碍事件线程被 :script 阻塞。
 */
package com.stardust.autojs.host;

oneway interface IAutomationHostCallback {
    void onAccessibilityEvent(String eventJson);
    void onServiceStateChanged(boolean ready);
}
