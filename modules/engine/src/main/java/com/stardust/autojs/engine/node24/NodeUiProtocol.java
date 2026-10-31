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
    /** task-254：通用 View 事件（宿主监听器写入现有 pollEvents 队列）。 */
    public static final String EVENT_CHECK = "check";
    public static final String EVENT_TEXT_CHANGE = "text_change";
    public static final String EVENT_ITEM_SELECTED = "item_selected";

    // ------------------------------------------------------------------
    // task-254：真实 View 树 / 通用事件宿主协议（与 assets/node24/ui.js 一致）
    //
    // JS 侧统一走 uiRequestView(action, params)：
    //   op:"uiRequestView" + action/params，或 op:<action> 直接携带参数。
    // 视图命令仍复用 op:"viewCommand" + action（旧客户端兼容）：
    //   addView/removeView/removeAllViews/getParent/getChildCount/getChildAt
    //   setOnClickListener/setOnCheckedChangeListener/setOnTextChangedListener/
    //   setOnItemSelectedListener
    //   getSelectedItemId/setSelectedItemId/getMenuSize/getMenuItemId/menuItems
    // ------------------------------------------------------------------
    public static final String OP_UI_REQUEST_VIEW = "uiRequestView";
    public static final String OP_VIEW_COMMAND = "viewCommand";
    public static final String K_ACTION = "action";
    public static final String K_PARAMS = "params";
    public static final String K_HANDLE = "handleId";
    public static final String K_CHILD_HANDLE = "childHandle";
    public static final String K_PARENT_HANDLE = "parentHandle";
    public static final String K_ATTACH_TO_PARENT = "attachToParent";
    public static final String K_INDEX = "index";
    public static final String K_VIEW_ID = "viewId";
    public static final String K_VIEW_NAME = "viewName";
    public static final String K_CLASS_NAME = "className";
    public static final String K_NATIVE_VIEW = "nativeView";
    public static final String K_CHANNEL = "channel";
    public static final String K_VALUE = "value";

    public static final String ACTION_ADD_VIEW = "addView";
    public static final String ACTION_REMOVE_VIEW = "removeView";
    public static final String ACTION_REMOVE_ALL_VIEWS = "removeAllViews";
    public static final String ACTION_GET_PARENT = "getParent";
    public static final String ACTION_GET_CHILD_COUNT = "getChildCount";
    public static final String ACTION_GET_CHILD_AT = "getChildAt";
    public static final String ACTION_SET_ON_CLICK_LISTENER = "setOnClickListener";
    public static final String ACTION_SET_ON_CHECKED_CHANGE_LISTENER = "setOnCheckedChangeListener";
    public static final String ACTION_SET_ON_TEXT_CHANGED_LISTENER = "setOnTextChangedListener";
    public static final String ACTION_SET_ON_ITEM_SELECTED_LISTENER = "setOnItemSelectedListener";
    public static final String ACTION_GET_SELECTED_ITEM_ID = "getSelectedItemId";
    public static final String ACTION_SET_SELECTED_ITEM_ID = "setSelectedItemId";
    public static final String ACTION_GET_MENU_SIZE = "getMenuSize";
    public static final String ACTION_GET_MENU_ITEM_ID = "getMenuItemId";
    public static final String ACTION_MENU_ITEMS = "menuItems";
    /** 2.2：把 JS 定时回调交给宿主主线程 Looper，规避 Node 工作线程没有 Looper 的问题。 */
    public static final String ACTION_POST_DELAYED = "postDelayed";
    public static final String ACTION_REMOVE_CALLBACKS = "removeCallbacks";
    public static final String EVENT_TIMER = "timer";

    private NodeUiProtocol() {
    }
}
