// @engine quickjs
"use strict";
const ioModule=require("../../runtime/io-queue.js");

/** logging：每个实例独立持有状态；跨模块动作由装配入口注入。
 * 状态通过参数显式共享；跨模块调用通过动作表，模块间不循环 require。
 */

function create(panelState, panelActions) {
    function panelFileQueue() {
        if (!panelState.fileQueue) panelState.fileQueue=ioModule.create({dir:panelState.BASE_DIR,
            log:function(text){console.log("[floaty] "+text);}});
        return panelState.fileQueue;
    }
    function panelFileWrite(path,text) { return panelFileQueue().write(path,String(text)); }
    function closePanelFiles() {
        panelLogFlush();
        if (panelState.fileQueue) panelState.fileQueue.close();
    }
    function panelLogFlush() {
        if (!panelState.panelLogBuf.length || panelState.panelLogStopped) {
            return;
        }
        if (typeof files === "undefined" || !files || typeof files.append !== "function") {
            panelState.panelLogStopped = true; // 宿主没有 files.append：静默不落盘（不影响任何主流程）
            return;
        }
        const text = panelState.panelLogBuf.join("\n") + "\n";
        panelState.panelLogBuf = [];
        panelState.panelLogFlushAt = Date.now();
        try {
            const header=panelState.panelLogBytes<0?"=== 面板日志 " + String(new Date()) + " ===\n":null;
            if (panelState.panelLogBytes<0) panelState.panelLogBytes=0;
            if (panelState.panelLogBytes + text.length > panelState.PANEL_LOG_MAX_BYTES) {
                panelState.panelLogStopped = true;
                panelFileQueue().append(panelState.PANEL_LOG_FILE, "=== 日志超限，停止写入 ===\n",header);
                return;
            }
            if (!panelFileQueue().append(panelState.PANEL_LOG_FILE,text,header)) throw new Error("IO 队列拒绝写入");
            panelState.panelLogBytes += text.length;
        } catch (error) {
            panelState.panelLogStopped = true; // 写不了就算了（别让日志把主流程带走）
        }
    }

    function panelLogWrite(line) {
        if (panelState.panelLogStopped) {
            return;
        }
        panelState.panelLogBuf.push(line);
        if (panelState.panelLogBuf.length >= 20 || Date.now() - panelState.panelLogFlushAt >= 2000) {
            panelLogFlush();
        }
    }

    /**
     * 日志的实际落地：进面板环形缓冲 + 进度摘要 + logcat + **落盘**。
     * §14：唯一入口是 diagnostics/recorder（限频、环长度、审计导出都在那儿），
     * 这里保留原来的入参形态，调用点一个都不用改。
     */
    function sinkLog(text) {
        const line = String(text);
        // 启动取证写完整日志，但不让路径/心跳实现细节淹没操作提示。
        if (/^(临时目录：|脚本版本 |保活心跳|退出钩子|按钮绑定完成|拖动：|autorun：文件为空|引擎历史通道：|提示：面板默认|开局库：|面板已启动|启动：把游戏带回前台)/.test(line)) {
            console.log("[floaty] " + line);
            panelLogWrite(line);
            return;
        }
        panelActions.noteProgress(line);
        panelState.logLines.push(line);
        while (panelState.logLines.length > panelState.LOG_LINES_FULL) {
            panelState.logLines.shift();
        }
        console.log("[floaty] " + line);
        panelLogWrite(line);
        scheduleRender(); // 日志驱动的重绘做 250ms 合并，别让每个 logLine 都过一次 JS→Java 桥
    }

    /** 日志驱动的重绘合并（250ms）；用户操作直接调 render() 保持即时反馈。 */
    function scheduleRender() {
        if (panelState.renderScheduled) {
            return;
        }
        panelState.renderScheduled = true;
        setTimeout(function () {
            panelState.renderScheduled = false;
            panelActions.render();
        }, 250);
    }

    return {
        panelFileWrite: panelFileWrite,
        closePanelFiles: closePanelFiles,
        panelLogFlush: panelLogFlush,
        panelLogWrite: panelLogWrite,
        sinkLog: sinkLog,
        scheduleRender: scheduleRender
    };
}

module.exports = { create: create };
