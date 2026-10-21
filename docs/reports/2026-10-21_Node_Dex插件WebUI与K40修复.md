# Node Dex、插件、Web UI 与 K40 内置示例修复

日期：2026-10-21。主架构保持 Flutter-only、`:script` 协调器和现有 Node24 进程池。
本轮实测包为 dev-1180 / dev-1181；dev-1182 已发布，更新源手机访问 HTTP 200。
发布 APK 为 `AI-js-Pro-Flutter-dev-1182.apk`，151128802 字节，
SHA-256：`4a9efa8288a6f0cf604a93fb32b080b25a4ba56628906de93acd1f8d3348968e`。
源码改动仍在 main 工作区，尚未提交。
发布后 K40 / 小米 8 均已安装 dev-1182；SDK v2 与资源重新加载再验通过。
K40 最终人工触摸路径验证了日志按钮、真实卡片记录、配置提示，以及一次返回后正常退出，
没有停留在空白 RunIntentActivity。验后已恢复 K40 的内置 M3 主页面。
原始示例通过不代表全部 44 个候选已通过，也不代表 Rhino / QuickJS / Node API 完全等价。

## 已修复的行为

| 问题 | 修复及实测 |
| --- | --- |
| 动态 Java 子类退出后 DexFile 回收报错 | `Node24DexLifetime` 用 ClassLoader 的 PhantomReference 延后关闭实际 DexFile；不强持有 loader，不屏蔽异常。K40 同一活进程 PID 8353 创建 8 个真实子类，后续脚本确认 8 个 loader + 8 个 DexFile 的弱引用全部清空，pending=0、failure=0；之前 M3 的两个 loader 也已回收。 |
| 通用 APK 插件只返回元数据 | 真实加载 `org.autojs.plugin.sdk.registry`、APK Java 类和资产脚本，走现有 Java 对象/数组/回调桥。SDK v2 实际 bindService、跨进程 Binder 调用、异常回传和卸载解绑通过。 |
| 旧插件工厂参数不兼容 | 同时兼容直接 Java SDK 调用及 `unwrap()` / `getMainScriptPath()` 工厂约定；保留底层 Java 句柄身份。整数 SDK 版本和旧插件的字符串显示版本分别处理。 |
| 插件重载和失败后残留 require 缓存 | 按每个 job / 每次 load 分配独立资产目录；卸载或入口加载失败清理整棵 require 缓存及 Service 绑定。 |
| Web UI 空门面与桥重入死锁 | 真正创建 WebView，加载本地/HTTP 页面；实现 `autojs://sdk/v1.js`、双向 Promise、错误、事件和 console；Node→页面调用走已有 Java IO 调用，事件循环可处理页面回调。 |
| Loading→Web 页面切换提前退出 | 真 Activity 切换、销毁与最后一个 Activity 关闭分别处理；初始状态栏设置生效，关闭后结束脚本及它自己的开发服务器。 |
| 原始 `.node.js` UI 示例误走 Rhino | 无显式引擎覆盖时，`.node.js` 默认选择 Node24；已有显式指令和项目配置继续优先。 |
| React/Vue 开发模式没有 node/npm | 增加同一个固定 Node24 核心的 CLI 启动器、npm 11.13.0、私有可执行 bin 包装；支持 `--no-bin-links` 的依赖安装，流式 Shell 输出和单独停止。 |
| React 开发编译报 Unicode 正则错误 | Node 24.22.0 重新编译为 full ICU，并用匹配配置重编 JNI。真实 `Intl`、ICU 78.3、Unicode 17.0、`\p{Script=Han}`、`\P{ASCII}`、Emoji、时区和 CLI 全部通过；没有改写依赖正则或伪造 release 模式。 |
| npm 失败看不到异常原因 | Android Node CLI 的原生 stderr 输出转回 Shell，脚本日志能收到实际异常堆栈。 |
| npm 启动前在共享存储上遍历全部依赖过慢 | 优先从 package-lock 的 bin 元数据生成私有包装；无 lock 时仅检查根依赖，不同步扫描所有 node_modules/package.json。 |
| UI 开发任务十分钟后被杀 | UI job 不再套普通脚本运行时限；仍可取消、关闭，Service 断连仍唤醒客户端。普通非 UI 脚本原有时限保留。 |
| 大型打包 Node 入口被 Binder / 长度限制截断 | 超过 96 Ki 字符的入口使用受控私有临时文件运输，16 MiB UTF-8 上限，服务端校验路径并删除；状态广播只带有限预览。160 KB React/Vue 生产入口通过。 |
| K40 日志页空白、配置提示缺失 | 内置 Node/Rhino 原生资源工程在 APK 构建时自动编译，挂入真实资源。K40 从 app-private 内置示例入口运行，日志的“日志/警告/错误”按钮、勾选卡片后的记录、“运行次数/运行时间”提示均已实测。 |
| 更新 APK 后资源包被误判不兼容 | sidecar 校验未压缩 `resources.arsc` 的 SHA-256，资源表相同则允许 APK 大小变化；保留旧 sidecar 的 size 校验。显式加载刷新 Node 进程池的旧 R 缓存。 |

## 真机结果

设备：小米 8 `ce4d2bdb`、K40 `cccc62c7`。测试期间没有取消用户的无障碍配置。

- **Web UI 契约 14 项**：真实 Activity/Window/View、页面 console、异步文件列表、eval、双向调用重入、页面/宿主/eval 错误、事件参数、Activity 切换、最后销毁，无提前结束，全部为 true。
- **原生 M3 契约 7 项**：真实 View / Window、Material item inflate、卡片点击与回收后状态、ViewPager 与导航双向同步、日志过滤、替换 contentView，全部通过。
- **SDK v2 真实测试 APK**：Java/resources/assets/JNI、嵌套 require、Java 身份、Map 嵌套、IO/UI/current/ui-sync 线程、跨进程 Binder、异常、重载和解绑通过。卸载后 dumpsys 无残留 Service 绑定。
- **已安装的实际插件**：小米 8 加载打包插件，真实模板大小 `44842488`；象棋插件棋规模块及原生引擎 available 均通过。K40 没安装打包插件，因此该机没有把此项记为通过。
- **React 原始开发工程**：真实 npm 安装、手机开发编译、原始 Web 页面与热更新通过；恢复源文件后页面恢复，源文件字节一致。
- **Vue3+CLI 原始开发工程**：手机原始 `npm i --no-bin-links` 完成 exit 0，开发页面和热更新/恢复通过。
- **原始文件浏览器 / Vue2+Vant SFC**：文件页面、SFC 下载进度、实际前台服务开关往返及状态栏通过。开关已恢复。
- **React/Vue3 生产构建**：按正常打包流程生成 dist 和 BuildInfo，真实页面与关闭通过；没有把开发工程强改成 release 冒充兼容。
- **资源重新加载**：先无 APK 回落，再恢复资源后同进程切换真实 ID；正确 digest + 错误 appApkSize 仍通过，错误 digest 拒绝，恢复原 sidecar 通过。
- **构建 / 本地检查**：应用构建通过；选择的 Node / 脚本路由 / 工程资源 Java 单测 102 项、失败 0；12 组 Node JS 契约全部通过。

## 构建与重现

Node 核心构建、原始源码 hash 及 Android 补丁已收进 `tools/node24-runtime/`；
说明见其 README。CLI/npm 生成器为 `tools/build-node24-tools.py`，JNI 生成器沿用
`tools/build-node24-bridge.ps1`，必须使用同一核心的 config.gypi / node_base.target.mk。

当前原生固定值：

```text
Node source archive: 41f97cff2c666f1f8293dc5f85c65e8dd605c16b43fd9febd49ede32ee1e0329
libnode.so: d2165091a8360066bfb27ebfe381e3e509d38c7d0d4d778f9f44ece9345f7274
libajsnode24bridge.so: 24456e4231c9c70daae62c19cdd5f2f23c786006ef6d7feab8f02b5f31fef274
libc++_shared.so: 8dfbd800f0d4b8ff2b664b0e5012a4a5f08794668dccfaa29b8b1c2cdcdc18b3
CLI node: e26cf59d4381f83ea88ff5e1573f0328e0c7681dcf1914ada409b2b420c4cfc8
npm.zip: 8619e4cdfc02e6b2ce6a809013b440227082b474332f0f9ff5687906174810ea
```

核心与 JNI 的 ELF LOAD 对齐 16 KB；完整 ICU 使本地 APK 比旧候选增加约 11 MB。
本轮没有重新承诺反射调用性能数字，也没有更换另外一种 App 引擎架构。

探针和测试 APK 源码在 `tools/probes/node24-*` 与 `tools/fixtures/node24-plugin/`。
完整原始证据在忽略目录 `output/node-pro-gaps-20261021/`：

- `k40-bundled-1180.log`、`k40-bundled-1180-log.png`、`k40-bundled-1180-settings.png`；
- `k40-resource-reload-1180.log`、`k40-webui-1180.log`、`k40-dex-{create,collect}-1180.log`；
- `plugin-1180.log`、`k40-sdk2-1181.log`、`installed-plugins-1181.log`、`npm-shell-1180.log`；
- `full-icu-first.log`、`react-live-reload.{log,png,xml}`、`vue3-live-reload.{log,png,xml}`；
- `m3-final-1181.log`、`build-full-icu-1180.log`、`icu-build/{build,bridge,reproduced}.log`。
- `sdk2-release-1182.log`、`resource-release-1182.log`、`k40-release-{dashboard,log,settings}.{png,xml}`、`k40-release-closed.log`、`publish-1182.log`。

## 仍需单独验收的边界

1. 任意第三方插件若强制转换旧 V8 ScriptRuntime / 原生 V8 句柄，仍需要其具体 SDK 适配；SDK v1/v2 主链路通过不等于所有封闭插件通过。尚无可验证的官方 FFmpeg APK。
2. 外部原生 res 工程没有编译包时，动态 inflater 仍是子集；内置两个工程已自动提供编译包。
3. 完整 OpenCV 包、44 个高级版 Node 候选及所有三引擎 API 的全量一致性不能据本轮结果宣称完成。
4. 开发工程首次 npm 安装依赖网络且耗时，包含原生 npm addon 的任意包也不等于已经适配 Android。
