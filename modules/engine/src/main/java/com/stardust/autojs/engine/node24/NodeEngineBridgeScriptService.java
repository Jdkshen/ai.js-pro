package com.stardust.autojs.engine.node24;

/**
 * {@code :script} 脚本宿主进程里的同一个引擎桥实现。
 *
 * <p>Flutter 主界面的外部 Run（以及定时任务等宿主入口）把 Node 父脚本放进 {@code :script}，
 * 因此 {@link com.stardust.autojs.engine.Node24JavaScriptEngine} 的真实注册表也在该进程。
 * 本类不增加任何逻辑，只让 {@link NodeEngineBridgeService} 能在 {@code :script} 进程注册；
 * 主进程仍保留原服务，旧入口/主进程 Node 任务继续可用。
 *
 * <p>两个服务都不导出、只接受同 UID 的 :node24 调用；客户端根据
 * {@link NodeEngineBridgeProtocol#OP_STATUS} 选择真正有活动 Node 引擎的那个进程并固定。
 */
public final class NodeEngineBridgeScriptService extends NodeEngineBridgeService {
}
