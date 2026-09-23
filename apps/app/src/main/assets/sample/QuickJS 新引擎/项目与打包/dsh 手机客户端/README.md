# dsh 手机客户端 · QuickJS 1.1.0

此项目在 AI.js Pro 的 QuickJS 新引擎中运行，打包后是独立 APK。支持服务器地址输入、粘贴链接、扫码填入、WebView、设备 ID 保存和 Cookie 持久化；不包含配对令牌。

1. 将整个项目目录导入 AI.js Pro，入口选择 `main.js`，引擎选择 **QuickJS**。
2. 打包时使用项目包名 `com.dsh.aijsclient.quickjs`，保留网络和相机权限，关闭无障碍模块。
3. **保留图色/OpenCV 模块**，不要开启“移除 OpenCV”。当前模板中的 QuickJS JNI 动态链接依赖 `libopencv_java5.so`；移除会导致 APK 启动失败，即使脚本没有使用图色 API。
4. 安装后自行输入服务器地址，或扫码/粘贴新鲜的 `/pair-accept?pair=…` 链接，点击“保存并连接”。已过期的配对链接需要在桌面服务刷新。

新版使用独立包名，可与旧版并存；旧版的设备 ID 与 Cookie 不会自动迁移，需要重新配对。测试机为 Mi 8；Android 8.0 及以上。

`main.js` 使用 QuickJS 的 `const`、`let` 和箭头函数，界面来自 `layout.xml`。`client-runtime.jar` 内含 `ClientRuntime.java` 编译的 Android 回调组件与 ZXing core 3.4.1。组件不包含 JavaScript 解释器；它处理 QuickJS 当前 JavaAdapter 尚未支持的相机、Surface 和 TLS 回调，以队列把事件交给脚本线程。更改 Java 源码后执行附带构建脚本生成 jar，再打包。

相机在后台/取消时释放，二维码仅填入输入框，须点击保存后连接。登录 Cookie 由 Android CookieManager 持久保存；同源重定向给出的设备 ID 存入脚本配置。

对于 `https://106.55.18.198`，脚本固定公开证书 SHA-256 指纹，并检查实际证书有效期与 URL 来源。证书变更后需更新 `main.js` 的 `certificatePins`。其他地址遵循普通系统证书校验。手机日期必须准确。

ZXing 许可证与声明见 `ZXING-LICENSE.txt`、`ZXING-NOTICE.txt`。主程序 APK 的依赖许可证由 AI.js Pro 模板随包提供。
