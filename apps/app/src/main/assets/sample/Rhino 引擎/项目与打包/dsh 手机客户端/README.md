# dsh 手机客户端（AI.js Pro 脚本项目）

Rhino UI 脚本工程，在 AI.js Pro 中打包为独立 APK。相机预览和识别都由 main.js 实现；识别库 zxing.jar 跟随项目打包，无需修改 AI.js Pro 或安装其他扫码软件。

打开 APK，手动输入服务器地址，或点「扫码填入」扫描配对二维码，再点「保存并连接」。首次扫码才申请相机权限；取消或拒绝后可手动输入。重新打开使用已保存的设备 ID 和 Cookie，登录态是否有效由服务端决定。

把整个目录导入 AI.js Pro，选择项目的「打包 APK」，引擎选 Rhino。仅保留 INTERNET、ACCESS_NETWORK_STATE、CAMERA；关闭无障碍和图色模块。不要只打包 main.js，否则扫码库不会进入产物。

本次 APK 使用 arm64 模板，适用于 Mi 8。扫码功能支持 Android 7.0+。zxing.jar 来源 ZXing core 3.4.1，经 Android d8 转换，采用 Apache 2.0 许可证。

HTTPS 保留校验；对本次提供的服务固定公开证书 SHA-256 指纹，并检查有效期。手机时间需准确，服务器换证书后应更新 main.js 中的指纹；其他服务需有效证书或另行配置。项目不含固定地址、配对令牌或登录凭证。