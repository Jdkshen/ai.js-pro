# PP-OCR 模型策略变更全程记录：按需下载 → 回滚内置 → 彻底禁用下载

涉及任务：task-121（按需下载）、task-125（回滚内置）、task-128（彻底禁用下载）。
日期：2026-10-18。执行：t1-native-services。设备：K40 cccc62c7（LineageOS）。

---

## 1. 最初的按需下载方案（task-121，已被 task-125/128 取代）

- 当时把 `assets/models/ppocrv6/` 两个 `.ncnn.bin`（15.4 MB）从 APK 移除，改为首次使用
  `$ocr.create({backend:'ppocr'})` 时由 app 模块 `ModelDownloadService` 从更新源
  `http://192.168.10.7:8080/models/ppocrv6/<file>` 下载到 `files/models/ppocrv6/`，
  带大小/SHA-256 校验、Range 断点重试、通知栏进度、中文报错、跨进程文件锁。
- 曾在 K40 上验证通过：首次下载 + 识别 5 块文字、二次缓存命中无下载、离线中文报错不崩溃。
  历史证据：`.artifacts/ppocr-download-smoke-1054/`。
- 该方案已**全部回滚并删除**，以下为最终状态。

## 2. 回滚为内置（task-125，2026-10-18 16:30）

用户要求「还是内置，不要下载了」：

1. 两个 `.ncnn.bin` 复制回 `apps/app/src/main/assets/models/ppocrv6/`，
   assets 恢复完整 5 文件（15,599,864 B），APK 体积回升（约 +10 MB 量级属预期）；
2. `PPOcr` 改为 **assets 优先**：首次识别前检查 assets 完整性（5 文件 + 大小），
   齐全则直接释放到 `files/models/ppocrv6/`，日志 `I/PPOcr: PP-OCR models source=assets dir=…`；
   进程内缓存模型目录，不会每次 detect 重拷 15 MB（`source=cache`）；
3. 当时仍保留下载器作为「瘦身版/模板包」兜底（`source=download`）。

## 3. 彻底禁用下载（task-128，2026-10-18 16:50，当前最终状态）

用户进一步明确「不要下载」，删除所有联网下载分支：

1. **删除** `apps/app/src/main/java/com/jdkshen/aijspro/model/ModelDownloadService.java`
   （下载实现、更新源 URL 解析、HTTP/Range/重试/通知进度、文件锁等全部移除）；
2. `PPOcr` 不再反射调用任何下载器：只做 assets 完整性检查 + 释放；
   assets 不完整时**直接抛中文错误**：
   `此版本未内置 OCR 模型，请更新安装包后重试（缺少：…）`，不发起任何网络请求；
3. 删除仓库根 `models/`（下载源备份）与本机更新源目录 `.artifacts/updates/models/`；
4. 全仓 grep 已无 `ModelDownloadService` / 下载 URL 引用；
5. OCR 示例注释同步为「模型随包、不联网、不下载；缺模型会明确报错」；
6. `PPOcr.java` javac smoke 编译通过。

### 最终行为

```
$ocr.create({backend:'ppocr'}).detect(...)
  ├─ 已有 files/models/ppocrv6/（5 文件完整） → 直接加载（source=cache）
  └─ assets 完整（主 APK 正常情况） → 从 APK assets 释放一次（source=assets，不联网）→ 加载
      assets 不完整 → 抛 IllegalStateException("此版本未内置 OCR 模型，请更新安装包后重试…")
```

## 4. 真机冒烟（K40 dev-1059，2026-10-18 16:51）

已装 dev-1059 经确认包含 task-128 代码（pull 出 APK 解包核对）：

- APK 150,759,553 B；`assets/models/ppocrv6/` 5 个文件齐全（bins 4,929,604 / 10,562,152 等）；
- dex 字符串：`source=assets`=True、`source=download`=False、`ModelDownloadService`=False、
  中文报错文案=True、旧兜底文案=False。

严格断网测试：
1. `run-as ... rm -rf files/models/ppocrv6` → MODELS_ABSENT；
2. `svc wifi disable` + `svc data disable` → `dumpsys connectivity` 显示
   `Active default network: none`（确认离线）；
3. 广播跑 `sample/QuickJS 引擎/文字识别(OCR)/截图并识别文字(PP-OCR).js`
   （MediaProjection 选共享「设置」应用）；
4. 结果日志：
   - `I/PPOcr: PP-OCR models source=assets dir=/data/user/0/com.jdkshen.aijspro/files/models/ppocrv6`
   - `PPOCR model loaded target=512` / `detect done raw=5` / `final=5`
   - `PP-OCR 识别到 5 块文字，耗时 501ms`；`运行结束，用时7.992秒`
   - 全量 logcat 中 `ModelDownload` / `source=download` / `正在下载 OCR` / `OCR 模型下载` = 0 行
5. `files/models/ppocrv6` 由 assets 释放出 5 个文件，**没有 `.download.lock`**（下载器完全不存在）；
6. 测试后已恢复 WiFi/移动数据；app 已 stopAll。

证据目录：`.artifacts/ppocr-nodownload-smoke/`
（`smoke-summary.md`、`installed-apk-check.txt`、`offline2-logcat-full.txt`、
`models-ls-after-offline2.txt`、`installed.apk`）。

说明：「assets 缺失 → 中文报错」分支无法在本 APK 上实跑（assets 完整），已由源码 +
dex 中报错文案 + javac 编译验证；该分支不发任何网络请求。
