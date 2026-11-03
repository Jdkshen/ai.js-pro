# MCP 免 Root Gadget APK 集成

状态：dev-1330 已发布到LAN，小米8正式包已安装并完成免Root完整链路验收。仅使用自建测试APK，不操作K40或第三方目标进程。

## 用户范围

按用户要求，只增加免Root Gadget路径，不部署Root Server。主要功能通过手机端MCP完成，不依赖电脑作为分析主机。电脑用于本轮开发、打包和真机验收。

## 实现

- 新工具 `apk_inject_gadget`：原始完整单APK的 `path` → 新 `outPath`，默认后台。单独下载 `frida-gadget` 组件，不在主包中嵌入约149MB的四架构库。
- 直接修改二进制Manifest，保留原属性的资源ID和引用；添加非导出Provider、INTERNET、hasCode/extractNativeLibs，把minSdk提高到至少21。原DEX、资源表、图片和其他资产逐条复制，不通过Apktool重编。
- 独立API21 DEX包含随机类名加载器，应用默认进程Provider初始化时加载Gadget，晚于attachBaseContext、早于Application.onCreate。Java层加载异常记录AIjsGadget日志；原生库内部故障仍可能影响目标进程。
- 只监听127.0.0.1，随机48位十六进制令牌，on_load=resume，不等待AI连接。端口可指定，冲突fail，不能误把另一个Gadget当目标。
- 官方Frida17.25.2四架构库和发布资产SHA256锁定；64位库校验16KiB LOAD，32位官方库为4KiB，按32位条件检查。真实16KiB页设备尚未验。
- 自动ZIP对齐、重新签名、密码学验签，输出原子发布；不覆盖原APK，不安装、不卸载任何原应用。
- 修复Gadget的官方Android `.config.so` JSON命名被通用构建检查/ELF检查误认为损坏原生库；仅精确文件名和有效配置结构识别，损坏配置仍报错。
- Frida客户端增加token认证，组件版本 `v2-token` 与已装 `v1` 并存、原子切换。旧客户端显示needsUpdate，不要求删除旧版本。
- MCP总数170名（161规范+9兼容）；逆向19项、toolkit100。指南dev1330-1与dynamic机读工作流同步，标准MCP资源/工具元信息和手机指南入口共用资源。

## 调用

完整说明：[MCP逆向调用说明](../MCP逆向调用说明.md)，[随包指南](../../apps/app/src/main/assets/mcp/AIjs-Reverse-Guide.md)。

```text
reverse_guide(topic=dynamic)
reverse_capabilities
apk_component_install(component=frida-gadget)
apk_component_install(component=frida)
apk_inject_gadget(path=target.apk,outPath=target-gadget.apk,port=27042)
手动安装并打开输出APK
frida_status(endpoint=结果endpoint,token=结果token)
frida_processes(endpoint=结果endpoint,token=结果token)
frida_eval(endpoint=结果endpoint,token=结果token,pid=实际返回整数,script=脚本文本)
```

所有后台调用先轮询job到终态，再按nextOffset读完job_result并检查ok。错误保留error.code。可用reverse_plan(goal=dynamic)读取同一流程。

## 兼容边界

仅完整单APK；Split、必需分包、sharedUserId、重复集成和不支持ABI明确拒绝。重签证书与原应用不同不能直接覆盖；用户需先保留原数据并安排安装。原应用的签名权限、登录、完整性或加固逻辑可能受影响；不修改这些检查。默认进程以外、attachBaseContext之前的代码不自动覆盖。Frida17不自带Java桥，Java.perform需要另行打包frida-java-bridge；本轮没有冒充实现Java桥编译器。

## 验收记录

- App单元回归650项，0失败/错误/跳过；最终R8构建完成。主包签名、23项64位ELF的16KiB、Retrofit泛型/注解及平台接口阴影构建守卫通过。
- 正式候选实际MCP发现170工具，指南dev1330-1、dynamic计划、9个组件和v2-token客户端可用状态通过。
- 自建test.aijs.gadget1330 APK通过集成、重新签名、验签、安装启动；原classes.dex/libprobe.so/assets字节不变。Gadget配置被正确识别，native检查valid。
- 普通应用UID=10209（非Root），MCP附加并调用probe_add(20,22)=42；按钮正常响应，界面树显示READY:1；错误令牌被拒绝后正确令牌仍可连接。
- 退出/重开目标应用后PID变化，重新附加与原生调用通过。重复集成、覆盖已有文件被拒绝；取消终态CANCELLED且无半成品发布。
- 首轮辅助脚本修正了Accept头和Frida状态嵌套字段的断言，最终正式包5组验收全部通过；这些脚本错误未作为产品失败或产品通过证据。
- 证据：`.artifacts/gadget-20261104/acceptance.json`、`jvm.log`、`final-build.log`、`fixture-layout.json`、Gadget四ABI静态检查记录。真实16KiB页设备、Android17原机、32位/x86设备以及任意第三方业务APK未验。

DP只读审查完成（SDK任务2a2d9735c6704dae），主代理采纳Provider/独立DEX、配置识别、hasCode、组件策略及签名边界建议，并独立实现/验证。没有给用户桌面聊天发消息。

上游依据：[Frida Gadget文档](https://frida.re/docs/gadget/)、[17.25.2发布](https://github.com/frida/frida/releases/tag/17.25.2)。许可证、资产摘要和源代码地址随可选组件分发。


## 发布产物

- 版本：dev-1330 / 1330。
- APK：144,392,641字节（137.70MiB），比1329增加18,084字节，约17.66KiB。
- SHA256：`43c50a05d94ada4feef65747fce18e7b8d88a61c82f1f152c00fc2cb411f31f0`。
- LAN源：`http://192.168.10.7:8080/update.json`。
- 下载：`http://192.168.10.7:8080/AI-js-Pro-Flutter-dev-1330.apk`。
- Downloads：`AIjsPro-dev-1330.apk`、`AIjsPro-MCP免RootGadget使用说明-dev1330.md`、`AIjsPro-MCP逆向调用说明-dev1330.md`。
- 正式包DEX包含最新SDK/令牌升级代码，随包指南与源码字节一致；主包不含Gadget库。沿用LAN AndroidDebug开发证书，与1329同证书；没有更换为公开生产签名。
- PC和MI8均从实际LAN URL完整下载144,392,641字节，SHA与清单/候选/本地副本/实装包一致。

仅本地提交与LAN，不push、不创建GitHub Release；K40未操作。组件安装保留供实际使用；测试应用经APK摘要确认后已卸载，2个公开临时APK与私有下载已清理，未清理用户文件。
