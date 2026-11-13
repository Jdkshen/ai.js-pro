# AGSL 高光层（`AgsHighlightView`）脚本用法

> 一句话：想在脚本里给某个栏/卡片**叠一层 AGSL（RuntimeShader）高光**，用这个宿主类，**不要**在脚本里 `new JavaAdapter(...)`。

## 1. 为什么必须用宿主类（实测结论，不是偏好）

| 方案 | 结果 | 原因 |
|---|---|---|
| 脚本里 `new JavaAdapter(android.view.View, {...})` + nodejs 引擎 | ✗ **不可行** | `JavaAdapter` 是 **Rhino 特有**的动态子类机制；nodejs 下三种取法（`typeof` / `global.JavaAdapter` / `require('rhino').JavaAdapter`）**全部 `undefined`** |
| 换 Rhino 引擎跑同一份脚本 | ✗ **不可行** | Rhino **没有 `ui.Activity`**（那是 nodejs 新引擎 API），且 **`class` 是 reserved word**（Rhino 不支持 class 语法） |
| **宿主提供现成 View 类（本类）** | ✓ **两个引擎都行** | 脚本只 `new` + `addView`，不依赖任何引擎特有能力 |

实测（K40 dev-1524，官方 `run_script`）：
```
rhino  : HL=function | new=OK | supported=true (sdk=36) | draw=OK ✓
nodejs : HL=function | new=OK | supported=true (sdk=36) | draw=OK ✓
```

## 2. API

```java
// 构造：context, 宽(px), 高(px), 圆角半径(px), 衰减指数(falloff)
new AgsHighlightView(context, widthPx, heightPx, cornerRadius, falloff)

void update(float widthPx, float heightPx, float cornerRadius, float falloff)  // 尺寸/圆角变化时
void setIntensity(float intensity)     // 亮度系数 0..1，默认 0.12（免出包调参）
void setThicknessPx(float thicknessPx) // 光带厚度 px，默认按 高度/60 自适应(2..6px)
boolean isSupported()                  // false ⇒ 当前系统/画布不支持（正在降级不画）
```

## 3. 脚本示例

### nodejs 引擎
```js
"ui nodejs";
// ... 你的 bar 已经拿到的位置
try {
  var HL = Packages.com.stardust.autojs.runtime.api.AgsHighlightView;
  var W = Math.max(1, bar.getWidth() || 1140);
  var H = Math.max(1, bar.getHeight() || 180);
  var hl = new HL(ctx, W, H, Math.min(H / 2, 90), 2.0);
  hl.setIntensity(0.12);          // 可选：调亮度（0.05 很淡 ~ 0.3 明显）
  hl.setThicknessPx(0);           // 可选：0 = 自适应；也可写死 3 / 5 / 8
  bar.addView(hl, new Packages.android.widget.FrameLayout.LayoutParams(W, H));
  console.log('AGSL 高光已叠加 supported=' + hl.isSupported());
} catch (e) { console.log('AGSL 高光叠加失败（降级为纯色）: ' + e); }
```

### Rhino 引擎
```js
"ui";
try {
  var HL = Packages.com.stardust.autojs.runtime.api.AgsHighlightView;
  var W = Math.max(1, bar.getWidth() || 1140);
  var H = Math.max(1, bar.getHeight() || 180);
  var hl = new HL(context, W, H, Math.min(H / 2, 90), 2.0);
  bar.addView(hl, new Packages.android.widget.FrameLayout.LayoutParams(W, H));
  console.log('AGSL 高光已叠加 supported=' + hl.isSupported());
} catch (e) { console.log('AGSL 高光叠加失败: ' + e); }
```

⚠️ **Rhino 专有两个坑**（本类已规避，但你写周边代码时要知道）：
1. `Canvas` / `Paint` 这两个短名**全局已存在**（`init.js:223` 的 `importClass(android.graphics.Paint)` 等）⇒ 脚本里**再 `const Paint = ...` 会 `redeclaration of var Paint`** ✗ ⇒ 直接用全局名，或换别名。
2. `activity` 是 `with(scope)` 注入的 ⇒ **函数体内拿不到** ✗ ⇒ 需要时在顶层先 `var act = activity;` 再传进函数。

### 在真实底栏上完整跑起来（最小可运行骨架）

上面两段只是「怎么 new 出来」。真正把它跑在一条自绘栏上，至少要这三步 —— 顺序错了就会**看不到高光** ✗：

```js
"ui nodejs";
// ① 栏必须真的被画出来（有非零尺寸），否则 W/H 取到 0，shader 的 SDF 直接退化成空
var bar = ...;                       // 你的栏（FrameLayout / View）
bar.post(function () {               // post 等到布局完成再量尺寸
  var W = Math.max(1, bar.getWidth());
  var H = Math.max(1, bar.getHeight());

  // ② 高光层必须 addView 进栏，否则它不在视图树里、永远不画（这一点最容易漏 ✗）
  //    用 index 0 让它垫在图标/文字**下面**（真机：高光属于玻璃底，不属于内容层）
  var HL = Packages.com.stardust.autojs.runtime.api.AgsHighlightView;
  var hl = new HL(ctx, W, H, Math.min(H / 2, 90), 2.0);
  hl.setIntensity(0.12);
  hl.setThicknessPx(0);              // 0 = 自适应（H/60 夹到 2~6px）
  bar.addView(hl, 0, new Packages.android.widget.FrameLayout.LayoutParams(W, H));

  // ③ 不要在外面再给 bar 画一圈描边 —— 描边 + AGSL 边缘光叠加会变成很粗的白边 ✗（真机踩过）
  console.log('AGSL 高光 ready supported=' + hl.isSupported() + ' ' + W + 'x' + H);
});
```

**排查顺序**（看到问题先按这个走）：
1. 日志里 `supported=false` ⇒ 系统 < Android 13，类会静默降级 ⇒ 放弃 ✗
2. 日志里根本没有 `AgsHighlightView` ⇒ **没 addView** ✗ 或 `post` 没跑到 ✗
3. 有日志但画面没变化 ⇒ `intensity` 太小（< 0.02 肉眼不可见）✗
4. 一圈很粗的白边 ⇒ `thickness` > 10px，或**外面又套了一层描边** ✗
## 4. 平台要求

| 项 | 说明 |
|---|---|
| **API ≥ 33** | `RuntimeShader` 是 API 33（Android 13）才有。**低版本 `onDraw` 直接 return（静默不画），不影响底栏本身** ✓ |
| 硬件画布 | `RuntimeShader` 需要硬件加速画布；**不要**画到 `Bitmap+Canvas`（会报 `Software rendering doesn't support RuntimeShader`）✓ |
| 无额外依赖 | 不需要改 `AndroidManifest`、不需要 JVMTI / Debug ✓ |

已验证平台：**K40 (Android 16 / SDK 36) ✓、MI 8 (Android 15 / SDK 35) ✓**。

## 5. 参数怎么调（效果对照，实测量化）

边缘亮带宽度的量化口径：截图后数「整行都被点亮」的行数。

| 版本 | thickness | intensity | 实测亮带 | 观感 |
|---|---|---|---|---|
| dev-1528 | 12.8px | 0.45 | **25 行** | 明显白边 ✗ |
| dev-1529 | 5.5px | 0.18 | **10 行** | 仍偏粗 ✗ |
| **dev-1530** | 2.5px（h/60） | 0.12 | **5 行** | 细腻描边感光 ✓ |

**调参建议**：`setIntensity` 0.05（几乎看不见的边缘光）～ 0.3（明显高光）；`setThicknessPx` 2～8，**超过 10 在小高度栏上会看起来像白边** ✗。

## 6. 排查

| 现象 | 看什么 |
|---|---|
| 完全没高光 | 日志 `supported=` 是否 `false`；`Build.VERSION.SDK_INT` 是否 < 33 |
| 一圈很粗的白边 | `thickness` 太大 ✗（真机量化：>10px 就会像白边）|
| 整块发白 | shader 的 `band` 没把「边界外」挡掉（本类已处理：`if (sd < 0.0)` + `step`）|
| 高光位置错位 | 传入的 `widthPx/heightPx` 与 View 实际尺寸不一致 |

日志关键字：`AgsHighlightView`（一次性打印实际生效参数：`draw params: WxH radius=… thickness=… intensity=… falloff=…`）。

## 7. 参考

- 实现：[modules/engine/src/main/java/com/stardust/autojs/runtime/api/AgsHighlightView.java](../../modules/engine/src/main/java/com/stardust/autojs/runtime/api/AgsHighlightView.java)
- 同类范式：[apps/app/src/main/java/com/jdkshen/aijspro/ui/widget/GlassTabLayout.java](../../apps/app/src/main/java/com/jdkshen/aijspro/ui/widget/GlassTabLayout.java)（`@RequiresApi(33)` 隔离 + 低版本降级）
- **姊妹篇**（液态透镜 / 折射，不是高光）：[AGSL液态透镜用法.md](AGSL液态透镜用法.md)
- 用户脚本：`/storage/emulated/0/脚本/酷安底栏/coolapk_bar.js`（nodejs 版）· `coolapk_bar.rhino.js`（Rhino 版）
- 原改法文档：`/storage/emulated/0/脚本/酷安底栏/NODE-AGSL-改法.md`（该文档的「改法 A/B」实测均不可行，见本文 §1）