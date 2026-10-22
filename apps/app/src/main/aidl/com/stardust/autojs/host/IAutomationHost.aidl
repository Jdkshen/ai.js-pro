/*
 * Phase1 P1 自动化代理接口：:script 进程 -> 主进程。
 *
 * 主进程持有 AccessibilityService / Automator / ScreenCapturer，实现
 * com.jdkshen.aijspro.automation.AutomationHostService（见 task-19）；
 * :script 引擎侧通过 AutomationHostClient 调用（见 task-20）。
 * selectorJson / strokesJson 格式见 docs/reports/2026-10-19_IAutomationHost契约与JSON协议.md
 */
package com.stardust.autojs.host;

import android.os.ParcelFileDescriptor;
import com.stardust.autojs.host.IAutomationHostCallback;

interface IAutomationHost {
    boolean isReady();
    boolean isAccessibilityServiceEnabled();
    String getLatestNodeDump();
    String findNodes(String selectorJson);
    String getNodeInfo(String selectorJson, int index);
    boolean clickNode(String selectorJson, int index);
    boolean longClickNode(String selectorJson, int index);
    boolean setTextNode(String selectorJson, int index, String text);
    boolean click(int x, int y);
    boolean longClick(int x, int y);
    boolean swipe(int x1, int y1, int x2, int y2, long duration);
    boolean gesture(String strokesJson);
    boolean back();
    boolean home();
    boolean recents();
    boolean notifications();
    String screenshot(String targetPath);

    /**
     * 直传截图原始像素（32B Raw header + ARGB_8888）。
     * 返回只读端；写端由主进程线程负责，关闭写端后调用方读到 EOF。
     * format=1（RawScreenshotFormat.FORMAT_ARGB_8888）；maxWidth/maxHeight=0 表示全分辨率。
     */
    ParcelFileDescriptor screenshotPixels(int maxWidth, int maxHeight, int format);

    // task-163：主进程 -> :script 的无障碍事件反向通道
    void registerEventListener(IAutomationHostCallback callback);
    void unregisterEventListener(IAutomationHostCallback callback);
}
