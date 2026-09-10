package com.stardust.autojs.core.web;

/**
 * QuickJS 版可注入 WebView 需要的宿主回调。
 *
 * 页面里的 JS 通过 `rhino` 对象调用这些方法，WebView 的调用线程不是脚本引擎线程，
 * 所以这里只负责把请求放进队列，真正的 JS 执行由引擎线程完成（见 `QuickJsHostBridge.postScript*`）。
 */
public interface QuickJsWebScriptHost {

    /** 页面调用脚本里的全局函数：`rhino.call('fnName', ...args)`。 */
    void postScriptCall(String name, String argsJson);

    /** 页面请求在脚本引擎里求值一段脚本：`rhino.eval('1 + 1')`。 */
    void postScriptEval(String script);

    /** `inject(script, callback)` 的结果回到脚本回调；callbackId 为 0 表示没有回调。 */
    void postScriptCallback(long callbackId, String value);
}
