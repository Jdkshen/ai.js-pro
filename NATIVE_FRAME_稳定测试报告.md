# Native Frame 稳定测试报告

更新时间：2026-09-02

## 本轮已验证

- QuickJS 原生库已使用 NDK r27c 为 `armeabi-v7a`、`arm64-v8a`、`x86` 三个 ABI 重新编译。
- `:app:assembleCommonDebug` 构建成功。
- 8 个相关 JavaScript 案例通过 `node --check` 语法检查。
- APK 已覆盖安装到小米 K40（硬件序列号 `cccc62c7`，Android 13 / API 33），保留原应用数据。
- 新增的定向迁移标记 `.quickjs-samples-native-frame-stability-v10` 已创建。
- 手机私有示例工作区中的 8 个目标案例与本次源码逐一进行 SHA256 比对，结果为 `8/8` 一致。
- 应用连续冷启动 3 次，进程均正常存活；测试日志中没有 `FATAL EXCEPTION`、native fatal signal 或本应用 ANR。

## 本轮修复

- 修正 Native Frame `activeHandles` 统计公式，并将该字段暴露给 QuickJS 测试脚本。
- 图色和 YOLO 长稳案例增加预热后、周期中、全部释放后的池与活动句柄统计。
- 长稳结束后若仍有活动句柄，或复用池超过设计上限，案例会明确失败。
- 修正 YOLO 区域检测在任一阶段失败后继续访问未赋值结果的问题。
- 修正 YOLO 持续识别在失败帧上产生 `NaN` 统计的问题。
- 修正 YOLO 实时测试复用上一帧结果，以及全部推理失败时空统计数组再次报错的问题。
- APK 升级后只刷新本轮修改的 8 个内置案例，不依赖用户清除应用数据。

## APK

- 路径：`app/build/outputs/apk/common/debug/app-common-arm64-v8a-debug.apk`
- 大小：94,661,051 bytes
- SHA256：`9411347B4B1F481AE8BD7B7A959B786FE48241D82F7397B54B55D6416F59B350`

## 尚未宣称通过的项目

以下项目需要手机保持解锁、同意屏幕捕获授权并实际运行较长时间，本轮没有用构建或启动烟雾测试代替：

- 图色、找图和模板匹配混合 1000 轮；
- YOLO 连续 1000 帧或 30 分钟；
- 长测期间的 PSS、温度和降频记录；
- 横竖屏切换、锁屏恢复、前后台恢复；
- Android 16 手机上的完整图色和 YOLO 长稳回归。

这些项目只有取得脚本最终 `[STABILITY]`、周期 `[POOL]` 输出及外部 PSS 记录后，才能标记为长稳通过。
