package com.stardust.autojs.engine.node24;

/** UI rendering is isolated in :nodeui; scheduling/settings keep their main-process endpoint. */
public final class NodeUiProtocol {
    public static final String SERVICE_CLASS = "com.jdkshen.aijspro.engine.NodeUiRenderService";
    public static final String WORK_SERVICE_CLASS = "com.jdkshen.aijspro.engine.NodeUiBridgeService";
    public static final String ACTIVITY_CLASS = "com.jdkshen.aijspro.engine.NodeUiActivity";
    public static final int MSG_SHOW = 1;
    public static final int MSG_CLOSE = 2;
    public static final int MSG_RESULT = 3;
    public static final int MSG_EVENT = 4;
    public static final int MSG_UPDATE = 5;
    /** task-135 Phase2：跨进程 View 句柄命令（setContentView/findViewById/inflate/属性方法）。 */
    public static final int MSG_VIEW = 6;
    /** task-141：主进程对话框（alert/confirm/prompt），同步等待用户操作结果。 */
    public static final int MSG_DIALOG = 7;
    /** task-142：主进程 floating_window overlay（show/close/closeAll）。 */
    public static final int MSG_OVERLAY = 8;
    /** task-144：真实定时任务（主进程 TimedTaskScheduler/DB）。 */
    public static final int MSG_WORK = 9;
    public static final int MSG_CLEANUP_OWNER = 10;
    public static final String K_SESSION = "sessionId";
    public static final String K_PROJECT = "projectDir";
    public static final String K_LAYOUT_NAME = "layoutName";
    public static final String K_LAYOUT_ID = "layoutId";
    public static final String K_TITLE = "title";
    public static final String K_CLASS = "className";
    public static final String K_SUCCESS = "success";
    public static final String K_ERROR = "error";
    public static final String K_EVENT = "event";
    public static final String K_PAYLOAD = "payload";
    public static final String K_MODE = "mode";
    public static final String K_REQUEST = "request";
    public static final String K_RESULT = "result";
    public static final String EVENT_CREATE = "create";
    public static final String EVENT_START = "start";
    public static final String EVENT_RESUME = "resume";
    public static final String EVENT_CONTENT_VIEW_SET = "content_view_set";
    public static final String EVENT_BACK_PRESSED = "back_pressed";
    public static final String EVENT_DESTROY = "destroy";
    public static final String EVENT_CLICK = "click";

    private NodeUiProtocol() {
    }
}
