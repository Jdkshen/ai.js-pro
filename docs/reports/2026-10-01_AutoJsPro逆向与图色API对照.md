# AutoJs Pro 逆向与图色 API 对照（2026-10-01）

本报告汇总 2026-09-30 ~ 10-01 的 Auto.js Pro 逆向、真机实测与四引擎兼容矩阵，
并落地了配套工具（Pro 自动跑脚本）与发布门禁（图色 API 样本）。dev-737 / dev-738
的改动背景见 `2026-09-29_VSCode自研扩展.md`。

## 0. 结论先行

1. **超大模板（模板大于搜索区域）的 findImage：Pro 是「原生挂死」，不是返回 null、也不抛错。**
   两个 Pro 发行版（五云版 / 高级版）在真机上均复现，进程卡在 `do_epoll_wait`，
   脚本无返回、`try/catch` 捕不到、日志只有「开始运行」没有「运行结束」。
   ⇒ dev-738 恢复的「友好抛错」(`Template WxH is larger than source/search region WxH`)
   **比 Pro 的行为更优**；「以 Pro 为准」的最终裁定仍然成立（Rhino 侧我们本来就抛错，两侧现已一致）。
2. **点对象打印形状四下全对齐**：`'' + p` 在 五云/高级版/自研 Rhino/自研 QuickJS 均为
   `{0.0, 0.0}`（dev-737 的目标完全达成）。
3. **双 Pro 发行版内核相同、行为相同**：arm64 原生库对比中 `libopencv_java4.so` 等
   逐字节相同；兼容矩阵输出规范化后**逐字节一致**。
4. **兼容矩阵 52 项，38 项四家完全一致**；剩余差异集中在 QuickJS 的 6 类已知点（详见 §5）。
5. 新增两个工程资产：`tools/pro-device-run.ps1`（一条命令在 Pro 上跑脚本收结果）
   与 `tools/verify-image-samples-device.ps1`（图色回归发布门禁，设备可选，约 5 秒）。

---

## 1. 两个 Pro 发行版的对象与指纹

| 项目 | 五云版 | 高级版 |
|---|---|---|
| 包名 | `org.autojs.autojspro` | `com.boyai.auma` |
| APK | `C:\Users\18101\Downloads\autoJsPro.apk`（124 MB，`.artifacts\pro-apk\base.apk` 同哈希） | `C:\Users\18101\Downloads\AutoJs高级版_Pro 9.3.11-0.apk`（81.5 MB） |
| 签名 | `CN=AutoJsPro` | `O=Auma, CN=AutoJs Auma` |
| 版本 | Pro 9.3.11-0（versionCode 9131100） | 同 |
| 引擎栈 | V8 + Node + Flutter UI + OpenCV **4.5.1** | 同 |
| 独有原生库 | `libwuyunai_core.so`、`libcoreprotect.so`、`libcorevmp.so`、`libpatchio.so` | `libaumaguard.so`(512 KB)、`libnojoy.so`(6.3 MB) |
| 激活/守卫 | 无额外拦截 | 打开文件时可能弹 `aiagent.integrity.ResetDataActivity`（「未读取到站点 Cookie」） |

> ⚠️ 关于高级版的激活守卫：**不破解**（此前已明确不做激活/登录破除）。
> 实测在弹窗上点「暂不清除」后编辑器仍会打开、脚本可运行；自动化时点
> `android:id/button2`（中心约 `526,1453`）即可。「清除数据并退出」会清空应用数据，切勿误点。

## 2. 双版本「内核相同」的证据

- **arm64 原生库哈希对比**（`.artifacts\pro-apk\so-compare.py`）：
  `libopencv_java4.so`（17,042,888 B，sha256 `59dbf470e9dcc5a8…`）、`libnode.so`(48.8 MB)、
  `libv8.so`、`libroot_automator.so`、`libtermux*.so`、`libBugly.so`、`libautojspro_cvext.so`
  等**逐字节相同**；差异仅在品牌/保护层（上表独有库）与 UI 层（`libapp.so`/`libflutter.so`，
  内含包名字符串差异属正常）。
- **内置 JS 模块**：`assets/v8/built_in_modules/*.js` 33/33 哈希一致（`image.js` 相同），
  唯一差异是各家 30 字节混淆的 `assets/v8/post.js`（防篡改载荷）。
- **真机行为**（本轮）：同一矩阵脚本在两版上输出的 44 行结果，规范化 Java 对象哈希后
  **逐字节相同**（`pro-wuyun.txt` vs `pro-auma.txt`，归一化 diff = 0 行）。

## 3. 逆向方法与调用链（dexdump）

方法：解包 dex → `dexdump.exe -d classes2.dex > dump.txt`（~177 MB）→
`.artifacts\pro-apk\slice.py` 切片定位；⚠️ 大 dump 里用 `Select-String -Context`
定位，不要用 Python 行号交叉引用（对孤儿 `\r` 的换行口径不同）。
中文文件名喂 aapt/dexdump 会报 `Illegal byte sequence`，先复制成 ASCII 名。

旧 API `images.*` 的实现链（classes2.dex，Kotlin）：

```
images.findImage(img, tpl, opts)
  → com.stardust.autojs.runtime.api.Images.findImage
    → TemplateMatching.fastTemplateMatching(img, tpl, options)
        · selectPyramidLevel(...)            // 金字塔层选择
        · getPyramidDownAtLevel(...)         // 逐层缩放
        · createTransparentMask(可选)
        · matchTemplate(src, tpl, method, mask)
             → new Mat(src.rows - tpl.rows + 1,
                       src.cols - tpl.cols + 1, CV_32FC1)   // ← 模板更大 → 负尺寸
    → 返回 org.opencv.core.Point（x/y 为 double）
    → region 偏移后经 ScreenMetrics.scaleX/scaleY（逻辑坐标）
```

要点：**Pro 里没有「模板过大」的专门检查/文案**，负尺寸直接进入 OpenCV 原生层
（4.5.1），实际表现为**挂死**（详见 §4）。这就是 2026-09-12 `8d575dd9`
「改返回 null 称对齐 Auto.js」的误判根源——Pro 既不是 null，也不是异常。

## 4. 真机实测（决定性证据）

探针：`.artifacts\pro-hang-probe.js`（逐步把进度写入 `/sdcard/脚本/steps.txt`，
每一步都重写整个文件，挂死时保留最后进度）。

步骤：合成 64×64 帧（`images.fromBase64`）→ `clip(0,0,32,32)` → 正常找图
（region 64×64）→ 找色 → **`findImage`(32×32 模板 vs 8×8 region)** → done。

两边输出（末行即挂死点，其后 12 秒无任何进展）：

```
start ; clip_ok ; find_ok={0.0, 0.0} typ=object ; pick={0.0, 0.0}
  json="{\"x\":0.0,\"y\":0.0}" x=0 ; before_oversize ;
```

- 五云版：`:script` 进程常驻 `do_epoll_wait`；日志页有「开始运行」、无「运行结束」。
- 高级版：输出**完全相同**，进程同样 `do_epoll_wait`。
- `try/catch` 捕不到（根本不是异常，是原生等待/死锁）；脚本永远到不了 `done`。

顺带实测的点形状：`'' + p` = `{0.0, 0.0}`、`p.x` = `0`、
`JSON.stringify(p)` 经 Java 桥 = `"{\"x\":0.0,\"y\":0.0}"`（带转义）——
与自研引擎 dev-737 对齐后的形状一致。

## 5. 四引擎兼容矩阵（52 项，38 项全一致）

生成器：`.artifacts\gen-matrix-probe.py` → `.artifacts\matrix-{rhino,qjs}.js`
（64×64 合成帧：底色 `#123456`，`(0,0)` 红、`(1,0)` 绿、`(0,1)` 蓝、`(3,3)(4,4)` 品红；
约 44 个探针：存在性、命中/未命中、越界、颜色工具、点对象形状等）。
四方采集 → `.artifacts\matrix-compare.py` → `.artifacts\matrix\matrix.md`。

**四家完全一致**（节选）：26 个 `ex.*` 存在性/类型（`images` 19 项 + `colors` 8 项）、
`img 64x64`、`findColor.hit` 形状 `{0.0, 0.0}`、`findColor.miss=null`、
`findImage.miss=null`、`findAllPointsForColor n=2 p0={3.0,3.0}`、
`detectsColor=true`、`matchTemplate points=1`、`'' + p = {0.0, 0.0}`、
`colors.rgb=255,0,0,255`、`isSimilar=true`。

**重要发现：`findMultiColors` 的调用形式**
- **全平台只有数组形式**：`images.findMultiColors(image, firstColor, [[dx, dy, color], …], options)`；
- 字符串形式（如 `'#FF0000,1,0,#00FF00'`）**四家全部拒绝**（错误文案不同：
  Pro `argument 'paths' of findMultiColors() must be a array`；自研 QuickJS
  `paths must be [[dx, dy, color], ...]`；自研 Rhino 抛 `NumberFormatException`——文案待优化）；
- 自研扩展生成的多点代码用的正是数组形式（`vscode-extension/src/color.ts`），**无需修改**。

**差异清单**（全部为自研 QuickJS vs 其余三家；均为已知/可接受，暂不修改）：

| # | 项 | 五云/高级版/自研 Rhino | 自研 QuickJS | 说明 |
|---|---|---|---|---|
| 1 | `images.pixel` / `colors.parseColor` 返回值 | `-65536` / `-65281`（有符号） | `4294901760` / `4294902015`（无符号） | 位值相同；`colors.toString` 后正常 |
| 2 | `colors.toString()` 大小写 | `#ffff0000`（小写） | `#FFFF0000`（大写） | 早已文档化；比较需 `toLowerCase` 归一 |
| 3 | 点对象结构 | Java Point（`Object.keys` 列出一堆 Java 方法名） | 纯 JS `keys=[x,y]` | 打印形状已对齐，内部结构不同不影响脚本 |
| 4 | `JSON.stringify(p)` | `{"x":0.0,"y":0.0}`（Pro 经 Java 桥还有一层转义字符串） | `{"x":0,"y":0}`（整数） | QuickJS 数值无 `.0`；dev-737 后 `''+p` 已一致 |
| 5 | `findImage` 命中对象 | 纯 Point | 附带 `similarity:1` 属性 | 多一个附加属性，兼容 |
| 6 | 错误文案 | 越界 region：`out of region …`；数组形式外全拒 | `RangeError: region is outside the NativeFrame` 等 | 四家**行为一致**（越界全抛错、坏参全拒绝），仅文案不同 |

> 说明：越界 region 四家都抛错（Pro `IllegalArgumentException: out of region: region = […], image.size = [64, 64]`，
> 自研 Rhino/QuickJS 均为可读版本文案），这块**行为一致**，只有文字差异。

## 6. 配套工具与门禁

### 6.1 `tools/pro-device-run.ps1`（Pro 自动跑脚本收结果）

```
用法： .\tools\pro-device-run.ps1 -Script <本地.js> [-Package org.autojs.autojspro|com.boyai.auma]
        [-ResultFile /sdcard/脚本/steps.txt] [-SaveResult <本地文件>] [-TimeoutSeconds 25] [-KeepRunning]
```

流程：push → 唤醒屏(KEYCODE_WAKEUP 224 + dismiss-keyguard) → 删结果文件 → force-stop →
monkey 启动 → 收起通知栏 → uiautomator dump 动态找行（10 轮 + 滑动）→ 点行 → 等 3s →
点 `:id/run`（兜底 `(772,165)`）→ 轮询结果文件至稳定 → force-stop。
退出码：0=收到、2=超时、3=无设备/找不到行。

踩过的坑（全部已内置处理）：
- 编辑器标签页缓存旧内容 → 每次必须 force-stop 重开；
- 通知栏遮罩吞点击 → `cmd statusbar collapse`；
- **屏幕灭掉后 uiautomator/截图全废** → 先唤醒+解锁；
- Pro 重开可能直接进上次的编辑器（五云版实测）→ 若已是目标文件直接跑，否则 BACK 回列表；
- 高级版守卫弹窗 → 自动点 button2「暂不清除」；
- `Write-Host` 输出不进管道（捕获文件为空）+ 控制台折行污染 → 用 `-SaveResult` 直接落盘。

### 6.2 `tools/verify-image-samples-device.ps1`（图色发布门禁，已挂进 `dev-update.ps1`）

- 设备可选：`adb get-state != device` → **SKIP**(exit 0)；应用 dev 通道连不上 → **SKIP**；
- 可达时：跑 assets `图色处理/01-*.js`（QuickJS 样本，断言包含
  截图→取色→找色→多点→裁剪/缩放/灰度→压缩→保存→找图→matchTemplate→
  NaN 阈值拒绝→**超大模板报错**），等 `QUICKJS_IMAGE_API_OK`，约 **5 秒**；
- 失败(assert 不过/标记缺失) → exit 1，发布中止。
- runner `dist/probe-run-sample.js` 新增 `PROBE_WAIT_MS`（默认 15s）与
  `PROBE_EXPECT`（见到标记提前结束；未见 exit 2）。

### 6.3 其他脚本

- `.artifacts\find-row.ps1`：解析 uiautomator dump 找行（注意传绝对路径）；
- `.artifacts\pro-apk\{so-compare.py, dex-compare.py}`：原生库/dex 哈希对比；
- `.artifacts\{gen-matrix-probe.py, matrix-compare.py}`：矩阵生成与对比。

## 7. 数据文件索引（`.artifacts\`）

| 文件 | 内容 |
|---|---|
| `matrix\pro-wuyun.txt` / `pro-auma.txt` | 两 Pro 版矩阵原始输出（后者与前者归一化后逐字节相同） |
| `matrix\our-rhino.txt` / `our-qjs.txt` | 自研双引擎矩阵原始输出（设备端文件直接拉取） |
| `matrix\matrix.md` | 四列对照矩阵（52 项，标注一致/差异） |
| `matrix-{rhino,qjs}.js` | 矩阵探针（64×64 合成帧 + 44 探针，含 base64 内嵌） |
| `pro-hang-probe.js` | 挂死定位探针（步骤写 `/sdcard/脚本/steps.txt`） |
| `pro-apk\base.apk` / `auma-pro.apk` | 两份 Pro 安装包（ASCII 名副本） |

## 8. 后续可选（未做，供决策）

- 把自研 Rhino 的 `findMultiColors` 坏参错误文案改成与 Pro 同形的
  `paths must be array`（当前是 `NumberFormatException: …under radix 16`）；
- 越界 region 文案是否向 Pro 逐字对齐（`out of region: region = […], image.size = […, …]`）——
  当前自研版可读性更好，建议保留；
- QuickJS `colors.toString` 大小写、pixel 无符号——建议维持现状（已有文档与归一化约定），
  除非遇到依赖逐字比较的脚本。
