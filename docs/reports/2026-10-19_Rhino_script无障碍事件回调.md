# Rhino :script 无障碍事件反向通道（task-163）

日期：2026-10-19。执行：t1-native-services。验证机：K40（cccc62c7）。

## 1. 结论

- IAutomationHost 增加主进程→:script 的 oneway 回调通道：registerEventListener(IAutomationHostCallback) / unregisterEventListener，回调方法 onAccessibilityEvent(String json)、onServiceStateChanged(boolean)；
- 主进程 AutomationHostService 用 AccessibilityService 的 delegate 把 view_clicked/view_long_clicked/windows_changed/window_state_changed/window_content_changed/toast/notification/accessibility_event 转成 JSON 推给 :script；无订阅者时不产生 Binder 流量；
- :script 侧 Rhino 的 events.on('accessibility_event'/'view_clicked'/'toast'…) 首次订阅时自动向主进程注册回调，事件到达后 emit('accessibility_event', RemoteAccessibilityEvent) 并同时 emit(kind, event)；Events.recycle() 时退订；
- auto.service 在 :script 不再恒为 null：返回 JS 代理对象（__remoteProxy=true、enabled getter、on/once/off 转发 events、openSettings()、getServiceInfo() 形状），主进程/本地场景仍返回真实 AccessibilityService。

## 2. 协议

### 2.1 AIDL

    // IAutomationHostCallback.aidl（task-163 新增）
    oneway interface IAutomationHostCallback {
        void onAccessibilityEvent(String eventJson);
        void onServiceStateChanged(boolean ready);
    }
    // IAutomationHost 扩展
    void registerEventListener(IAutomationHostCallback callback);
    void unregisterEventListener(IAutomationHostCallback callback);

### 2.2 事件 JSON（与 Node24 通路同 schema）

    {kind:'view_clicked'|'view_long_clicked'|'windows_changed'|'window_state_changed'|'window_content_changed'|'toast'|'notification'|'accessibility_event',
     eventType:int, packageName, className, text, windowId, eventTime, contentChangeTypes?, windowChangeTypes?}

## 3. 实现

- apps/app/src/main/aidl/.../IAutomationHostCallback.aidl（新增）、IAutomationHost.aidl（+2 方法）；
- AutomationHostService（flutterpoc）：RemoteCallbackList 管理回调；ensureEventDelegate() 在首个订阅时 AccessibilityService.addDelegate(9200, …)；事件在主线程串行 beginBroadcast 推送；注册时立刻 onServiceStateChanged(ready)；
- engine RemoteAutomationBridge：Backend 增加 subscribe/unsubscribeAccessibilityEvents + AccessibilityEventListener 接口 + 静态 helper；
- engine RemoteAccessibilityEvent：跨进程事件快照 bean（getKind/getPackageName/getClassName/getText/getWindowId/getEventType/toJson）；
- engine Events：on/once/prependListener/prependOnceListener 对无障碍事件类型懒订阅；回调经 mHandler post 后 emit；recycle 退订（另会 emit accessibility_service_state_changed）；
- AutomationHostClient（:script）：实现新 Backend 方法，IAutomationHostCallback.Stub 转发到 listener；disconnect 时反注册；
- modules/engine/src/main/assets/modules/__automator__.js：auto.service 远程代理。

## 4. K40 验证（dev-XXXX，待填）

（待填：auto.service nonNull/proxy/enabled、accessibility_event/view_clicked/toast 计数与 result JSON）

## 5. 修改文件

- apps/app/src/main/aidl/com/stardust/autojs/host/IAutomationHostCallback.aidl（新增）/ IAutomationHost.aidl；
- apps/app/src/flutterpoc/java/com/jdkshen/aijspro/automation/AutomationHostService.java；
- apps/app/src/flutterpoc/java/com/jdkshen/aijspro/flutterpoc/scriptproc/AutomationHostClient.java；
- modules/engine/src/main/java/com/stardust/autojs/runtime/api/remote/RemoteAutomationBridge.java；
- modules/engine/src/main/java/com/stardust/autojs/runtime/api/remote/RemoteAccessibilityEvent.java（新增）；
- modules/engine/src/main/java/com/stardust/autojs/runtime/api/Events.java；
- modules/engine/src/main/assets/modules/__automator__.js。

编译验证：:app:compileMiuixCompatDebugJavaWithJavac -PflutterPoc=true、:engine:compileDebugJavaWithJavac BUILD SUCCESSFUL（未跑完整 gradle）。

## 6. 无法对齐项 / 已知限制

1. 远程事件对象不是 android AccessibilityEvent，也没有 event.source(UiObject)：系统 AccessibilityEvent 依赖主进程的 binder 节点对象，跨进程只传可序列化快照（RemoteAccessibilityEvent / JSON）。需要节点操作请用已有的远程选择器（auto.root/selector/click 等）。
2. auto.service 代理只覆盖 enabled / 事件订阅 / openSettings / getServiceInfo 形状；平台 Service 的 getWindows()、getRootInActiveWindow()、getServiceInfo().flags 真值、dispatchGesture 等不能伪造，调用会得到空/0/undefined。主进程内脚本仍拿到真实 service。
3. onServiceStateChanged 目前只在订阅注册时推送一次（以及连接建立时）；运行中无障碍服务被系统断开没有实时推（可通过定期 isReady 或下次事件发现）。
4. 事件频率未做节流：后台脚本订阅 accessibility_event 会收到所有事件（可只在需要的 kind 上订阅，或后续加类型过滤）。
5. toast/notification 只转发 AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED 中 className 含 Toast 的项与普通通知事件；不包含通知正文对象（NotificationListenerService 的 Notification 对象不跨进程）。
6. 仅 :script 隔离进程生效（RemoteAutomationBridge.isActive()）；主进程脚本不受影响。
