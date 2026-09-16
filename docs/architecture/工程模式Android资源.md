# 工程模式 Android 资源（`project.json` 的 `androidResources`）

给「照着 Auto.js Pro 的工程写的脚本」兜底：工程里带 `res/` 时，`ui.useAndroidResources()`、
`ui.R.*`、`ui.layoutFile("名字")`、布局里的 `@color/@drawable/@style/@menu` 引用都能用。

两个示例受益：`Rhino 引擎/复杂界面/使用Android原生界面`（原生 `res/layout` + 底部导航 + 动画）、
以及任何从 Pro 工程搬过来的界面。

## 两条路线（引擎会自动挑）

| | 合成 id 路线（默认） | 真 resource id 路线（可选） |
| --- | --- | --- |
| 触发条件 | 只要有 `res/` + `project.json` 声明 `androidResources` | 工程下存在 `build/project-res.apk`（PC 编出来的） |
| `ui.R.*` | 引擎自造的 `0x7e******` id | aapt2 编出来的真 id |
| 布局里的 `@color/@drawable/...` | inflater 自己按名字解析（shape 用手写的子集画） | 交给 framework（shape 等原样保真） |
| `AnimationUtils.loadAnimation(activity, ui.R.anim.x)` | ❌ `Resources$NotFoundException` | ✅ 原生可用 |
| 代价 | 无 | 需要 PC 上跑一次 `tools/build-project-res.ps1` |

## 为什么真 id 必须「PC 编译 + 设备挂载」

- Google 只发布 **x86_64** 的 aapt2（`aapt2-*-linux.jar` / `-windows.jar` / `-osx.jar`），
  **没有 arm64/Android 版本**；Android 10+ 又禁止从 app data 目录 exec。
- 所以工程 `res/` 在电脑上编成一份独立的资源包，推到设备后由引擎用
  `AssetManager.addAssetPath()` 叠进宿主进程（`CompiledProjectResources`），
  再经 `ScriptExecuteActivity.getResources()` 交给 framework —— 那些「只认真 resource id」的调用点
  （`AnimationUtils`、`Resources.getColor`、`Theme.applyStyle`）用的就是 **Activity 自己的** Resources。

## 用法

```powershell
# 工程目录里要有 res/ 与 AndroidManifest.xml；跑完把资源包推到手机的同一工程目录下
.\tools\build-project-res.ps1 -ProjectDir <工程目录> -Push -DeviceId <序列号>
```

产物：`<工程>/build/project-res.apk` + `project-res.json`（sidecar）。
手机上要落在 `脚本目录/<工程目录名>/build/`。

⚠️ **每次重装 app 后必须重跑**：aapt2 的 `-I` 会把 app 侧资源 id 编进引用里，宿主换版本后 id 可能漂移；
引擎比对 sidecar 里的 `appApkSize`，不符就忽略资源包并打日志提示重编（此时自动回落合成 id 路线）。
不想用真 id 就删掉 `build/` —— 什么都不用改，脚本照跑。

## 踩过的坑（改这块之前先读）

1. **命名空间**：`res/` 里的 xml 全是 `android:xxx`，而引擎的 DOM 解析器是按本地名取属性的 ——
   `ProjectResources.attributeOf()` 必须同时认带前缀写法。
2. **`app:` 属性以前被整块丢掉**（`BaseViewInflater.setAttr(ns,…)` 里 `app` 直接 `return false`），
   `app:title` / `app:menu` / `app:srcCompat` 全都失效；现在 `android:` 与 `app:` 一视同仁。
3. **按名字查资源要试两个包名**（app 的 + 资源包的），见 `util/ResIds`。
   `getIdentifier(name, type, "")` **不跨包**（实测恒 0）；`--package-id 0x81` 时 aapt2 会把包名
   改成 `<app 包名>.feature`，包名由 sidecar 提供。
4. **资源包必须用独立包 id（0x81）**：与 app 同 0x7f 时会「按名字查得到、按 id 查不到」
   （`getDrawable(0x7f030001)` 直接 NotFoundException）。
5. **菜单项 id 必须与 `ui.R.id.*` 同源**，所以 `app:menu` 不用 framework 的 `inflateMenu(真id)`
   （那样 item id 由 MenuInflater 给，脚本里 `ids.indexOf(item.itemId)` 会失效），仍由引擎自己建项。
6. **中文路径**：PS 5.1 把中文参数传给原生 exe 会乱码，工具在 ASCII 临时目录里跑完再拷回。

## 没做到的

- `activity.theme.applyStyle(真 styleId, true)` 静默无效：Activity 的 theme 在挂载资源包**之前**
  就已经建好（`ContextThemeWrapper` 缓存）。示例里紧接着自己设了状态栏颜色，所以看不出差别。
- 手写 shape 子集只覆盖 `<shape>` 的 solid/corners/stroke/尺寸；`ring` 的半径按 View 尺寸算不出来，
  `thicknessRatio<=2`（环厚≥半径，实为实心）按 oval 画。走了真 id 路线时这些由 framework 处理，不存在此问题。
