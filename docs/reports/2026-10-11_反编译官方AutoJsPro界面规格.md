# 反编译官方 Auto.js Pro（9.3.11-0）—— 界面权威规格

日期：2026-10-11　　仓库：`ai-js-pro`

## 一、结论先说

**官方 Auto.js Pro 是"原生 View + Flutter"混合应用，不是纯 Flutter。**

| 证据 | 内容 |
| --- | --- |
| `lib/arm64-v8a/libflutter.so` + `libapp.so` | 有 Flutter 运行时与 Dart AOT 产物 |
| `assets/flutter_assets/` 76 项 | `MaterialIcons-Regular.otf`、CupertinoIcons、`shaders/ink_sparkle.frag` |
| `libcoreprotect.so` / `libcorevmp.so` | 核心代码 VMP 虚拟化保护 |
| `libapp.so` 字符串搜索 | 有 `MaterialApp`/`Scaffold`/`CupertinoApp`/`Navigator`；**没有** `script_file_list`/`ExplorerView`/`first_char`/`file_code_icon` |
| `AndroidManifest` | 启动页 `org.autojs.autojs.ui.splash.SplashActivity`，主界面 `org.autojs.autojs.ui.main.MainActivity`（原生） |

⇒ Flutter 承担的是登录/支付/商城这类页面；**文件管理页等核心界面仍是原生 View**，
所以 `res/layout/script_file_list_*.xml` 是真实在用的，可以直接读规格。

## 二、怎么读到的（可复现）

### 1. 官方包 `org.autojs.autojspro` 的资源名被混淆

`res/` 下的**资源名被混淆成 `res/layout/a.xml` 之类**，无法按名字定位。
`C:\Users\18101\Downloads\autoJsPro.apk`（124 MB，Pro 9.3.11-0）。

### 2. 改用 `com.boyai.auma`（同版本、资源名未混淆）

`C:\Users\18101\Documents\ai-js-pro\.artifacts\auma-pro.apk`（81.5 MB，`versionCode 9131100`、
`versionName 'Pro 9.3.11-0'`，**同一版本号**），`res/layout/` 下 261 个布局**名字可读**，
含 `script_file_list_category/directory/file.xml`、`explorer_view.xml`、`tab_file.xml`、
`dialog_script_loop.xml`、`item_module.xml` 等。

### 3. 用 `aapt dump xmltree` 读二进制 XML，再解码 dimension

```powershell
& 'C:\Android\build-tools\28.0.3\aapt.exe' dump xmltree <apk> "res/layout/<name>.xml"
```

输出形如 `A: android:minHeight(0x...) = (type 0x5)0x3c01`。
**解码规则（本次校准得出）：`值 = raw / 256`，低 4 位是单位**（1=dp，2=sp）。

校准样本（都是整数，可交叉验证）：

| raw | 解码 | 合理性 |
| --- | --- | --- |
| `0x801` | 8 | 8dp ✓ |
| `0x1002` | 16 | 16sp ✓ |
| `0xe02` | 14 | 14sp ✓ |
| `0xb02` | 11 | 11sp ✓ |
| `0x2001` | 32 | 32dp ✓ |
| `0x2401` | 36 | 36dp ✓ |
| `0xc01` | 12 | 12dp ✓ |
| `0x3201` | 50 | 50dp ✓ |
| `0x3c01` | 60 | 60dp ✓ |

> 官方包 `libapp.so` 是 release 版 AOT（**无 `kernel_blob.bin`**），Dart 界面还原不成源码；
> 且 `libcoreprotect/libcorevmp` 做了 VMP 保护。**下表的数值全部来自原生布局 XML，不是从 Dart 猜的。**

## 三、官方文件页规格 vs AI.js Pro 现状

### `script_file_list_file.xml`（文件行）

| 元素 | 官方 Auto.js Pro | AI.js Pro 现值 | 是否一致 |
| --- | --- | --- | --- |
| 行容器 `android:padding` | **8dp**（四边统一） | `paddingStart=16dp` | ✗ |
| 图标圆底 `first_char` | **32dp** | **44dp** | ✗ |
| 图标 `layout_marginStart` | **8dp** | — | ✗ |
| 名称 `textSize` | **16sp** | 15sp | ✗ |
| 描述 `textSize` | 11sp | 11sp | ✓ |
| 描述 `layout_marginTop` | 4dp（`0x401`） | 2dp | ✗ |
| 右侧按钮（run/more） | **36dp**，`paddingTop/Bottom 8dp` | 48dp | ✗ |
| 图标左 `layout_marginStart` | **16dp** | 14dp | ✗ |
| `android:minHeight` | **无此属性** | 60dp | ✗ |
| 图标 `layout_height` | `match_parent` | 44dp | ✗ |

### `script_file_list_directory.xml`（文件夹行）

| 元素 | 官方 | AI.js Pro |
| --- | --- | --- |
| 外层 `paddingTop/Bottom` | **16dp** | — |
| 外层 `layout_marginStart/End` | **3dp** | — |
| 圆形背景 | **24dp**（`0x1801`） | 44dp |

### `script_file_list_category.xml`（分类头）

| 元素 | 官方 | AI.js Pro |
| --- | --- | --- |
| 标题 `textSize` | **12sp**（`0xc02`） | 14sp |
| 标题 `textColor` | **`#FF828384`（写死灰）** | 主题令牌 |
| 按钮 `padding` | **9dp**（`0x901`） | 12dp |
| 按钮 `layout_height` | `match_parent` | 固定 |

> **注意**：官方分类头标题也是**写死的 `#828384`** —— 和 AI.js Pro 旧界面
> `script_file_list_category.xml` 的 `app:tint="#828384"` 同源。
> 说明"深色模式下不变色"是**官方原生设计**，不是 AI.js Pro 引入的缺陷。

### `item_module.xml`（官方"模块/插件"卡片）

```
TextView
  textSize        16sp
  gravity         center
  background      @0x7f08007b（chip 底）
  paddingLeft/Right 12dp
  layout_width    match_parent
  layout_height   50dp
```

⇒ 官方插件是**横向 50dp 高的 chip**，不是两列卡片。

## 四、这对 AI.js Pro 意味着什么（重要）

1. **AI.js Pro 的界面数值不是照抄官方 9.3.11 的**：它源自 Auto.js 4.4.1 血统，
   数值体系（44dp 圆底 / 16sp 文件名 / 60dp 行高）与官方 9.3.11（32dp / 16sp / 无 minHeight）**不同**。
2. **此前"对齐"工作的基准选错了**：我最早拿"官方 Auto.js Pro 的插件页截图"当目标，
   但官方那一页在 `com.boyai.auma` 里**找不到对应的卡片式布局**（只有 chip 式的 `item_module.xml`），
   说明用户给的那张截图**可能来自另一个修改版/另一个版本**，或出自 Flutter 侧。
3. **所以"对齐官方"必须由用户拍板到底对齐谁**：
   - 对齐**官方 9.3.11** → 文件行要改成 32dp 圆底 / 8dp 内边距 / 16sp 名称，
     且旧 `ExplorerView` 与 Compose 版**两套都要改**（旧实现仍被文件选择器等复用）
   - 对齐**AI.js Pro 自己的旧界面** → 保持 44dp / 15sp / 60dp（即当前 Compose 版的状态）
   - 两者不能同时满足

## 五、尚未做的

- 官方 XML 里的 `android:background="@0x7f08007b"`、`app:tint="@0x7f060042"` 等资源引用
  尚未解出**实际颜色值**（需要读 `resources.arsc` 或反查 `com.boyai.auma` 的 `res/values/colors.xml`）。
  若要对齐颜色，这是下一步。
- 官方 Flutter 侧（`libapp.so`）的界面无法还原，登录/商城等页面对齐不可行。
- `res/drawable` 有 1015 个 `drawable-xhdpi`，可进一步提取官方图标资源。
