# AI.js Pro MCP：capture_screen 默认改 JPEG（电脑侧改法）

目标：让 `capture_screen` **默认输出 JPEG（quality 60）**，不再是 PNG；并修掉「mode 预设设了 quality 却没设 format」这个 bug。

只改常量，不动截图管线、不动 `saveFormat=raw`、不动 region/裁剪逻辑。

---

## 1. 实测现状（MI8 1280×2160 / 本机 1280×2772，dev-1532）

| 调用 | 实际编码 | 字节 | 说明 |
|---|---|---|---|
| `{mode:"fastest"}` | **PNG** 360×779 | 113,909 | 预设只设了 quality=40，format 没设 → 默认 PNG，quality 对 PNG 无意义 |
| `{mode:"fastest",format:"jpg",quality:40}` | JPEG 360×779 | **22,834** | 同参数小 5 倍 |
| `{}`（缺省 mode=balanced） | PNG 原尺寸 | ~217 KB | 历史默认 |
| `{format:"jpg"}`（全原尺寸、不传 quality） | JPEG 1280×2772 | **220,103** | **quality 缺省 = 100，比 PNG 还大** |
| `{format:"jpg",quality:60,maxWidth:540}` | JPEG 540×1169 | **43,494** | 合理档位 |
| `{format:"jpg",quality:60,region:"[0,360][1280,2512]"}` | JPEG 1280×2152(1:1) | 111,561 | 先裁后缩，保留原始像素 |

结论两条：
1. **预设里的 quality 是空转的**（没带 format，走默认 PNG）。
2. **quality 缺省值是 100**，`format:jpg` 单独用反而更大 —— 所以「默认 jpg」必须同时定 quality=60，不能只改 format。

---

## 2. 改哪些常量

### 2.1 缺省值（没有 mode、也没有显式 format/quality 时）

```java
// 改前（推断）                      // 改后
String  format  = "png";   →        String  format  = "jpg";
int     quality = 100;     →        int     quality = 60;
```

### 2.2 四个 mode 预设：每个都显式带 format

现在只设 width / quality / timeout，缺 format。改成：

| mode | maxWidth | format | quality | timeout | 预期字节（本机） |
|---|---|---|---|---|---|
| `fastest` | 360 | jpg | 40 | 1500 | ~23 KB |
| `fast` | 540 | jpg | 60 | 2500 | ~43 KB |
| `balanced`（缺省） | 540（原来是 0=原尺寸） | jpg | 60 | 3000 | ~43 KB |
| `quality` | 0（原尺寸） | **png** | — | 3000+ | ~217 KB（存档专用） |

> 关键点：**`quality` 是唯一还出 PNG 的入口**，语义变成「原图无损、存档/像素级比对」；`balanced` 从“原尺寸 PNG”改成“540 宽 JPEG60”，这是行为变更，见 §5 兼容性。

### 2.3 显式参数优先级保持不变

`显式 format/quality/maxWidth/timeoutMillis` > `mode 预设` > `缺省值`。
改完用这组调用验证优先级没被写反：

```
{mode:"quality", format:"jpg"}      → 应为 jpg（显式覆盖预设），原尺寸
{mode:"fast", maxWidth:1080}        → 应为 jpg q60，1080 宽
```

---

## 3. 代码定位（不用通读，按字符串搜）

这几个字面量就在实现文件里，直接全局搜第一个：

```
"balanced：原图 PNG"        // modeNote 文案，和预设表在同一个文件
"fastest：360 宽 + JPEG40"  // 同上
"与历史默认一致"
```

再配合：

```
"saveFormat"      // savePath 三态 auto/png/jpg/raw
"image/png"       // MCP content 的 mimeType 拼装处
"Bitmap.CompressFormat.PNG"
```

**同一个文件里应该同时出现**：mode 预设表、缺省 format/quality、`Bitmap.CompressFormat` 选择、`effective{...}` 回填、`modeNote` 文案。

---

## 4. 别动的东西（动了会连带坏）

- `saveFormat=raw`：RGBA8888 裸像素、`stride=width*4`、无文件头，逐帧 CV 用；它是独立分支，**不要**并到 format 里。
- `saveFormat=auto`：按扩展名推断，保持。
- `dataUri`：base64 前缀要跟着 `format` 变（`data:image/jpeg;base64,` / `image/png`），改完顺手核一遍。
- `region` 先裁后缩、`regionClamped`、`scale`、`regionNote` 逻辑不动。
- `dev_screen_diff` 的 `captureMode` **共用同一套预设**：它默认 `maxDims` 省略时沿用上一帧宽度，若把 `balanced` 的宽度从 0 改成 540，记得确认 `dev_screen_diff` 不会因此出现 `SIZE_MISMATCH`（它自己会回 `suggestedArguments`，可接受）。

---

## 5. 兼容性影响（要知情）

1. `balanced` 由“原尺寸 PNG”变“540 宽 JPEG60” → 以前靠缺省拿全屏无损截图的脚本会拿到缩小的图。若要图全屏无损：显式 `{mode:"quality"}`。
2. MCP 客户端（RikkaHub 等）吃到 `image/jpeg` 一般没问题；但**上下文体积**从“每张 30k~90k token”降到“每张 8k~10k token”，这正是本次改动的目的。
3. `modeNote` 文案必须同步改，否则工具描述与实际行为自相矛盾（文档落差会被 `list_engine_api` 那套对账逻辑当成缺陷）。

---

## 6. 验收（重装后真机跑，逐条对表）

```
1) capture_screen{}                        → mimeType image/jpeg, quality 60, 540 宽, 40~50 KB
2) capture_screen{mode:"fastest"}          → image/jpeg, 360 宽, ~23 KB（当前是 113,909 B PNG）
3) capture_screen{mode:"quality"}          → image/png, 原尺寸, ~217 KB
4) capture_screen{format:"jpg"}            → quality 60（不再 100 膨胀到 220 KB）
5) capture_screen{savePath:"x.raw",saveFormat:"raw"} → 文件大小 == width*height*4
6) capture_screen{region:"[0,360][1280,2512]"} → 1280×2152, scale=1.0, cropped=true
7) 返回体里 effective.format / effective.quality 与实际编码一致（这两项是自检抓手）
```

`effective` 字段是最好用的验收点：它回填的就是**最终生效**的 format/quality/maxWidth/timeout，改错立刻能看出来。

---

## 7. 顺带（可选，同一次改完更省事）

- `effective` 里加 `bytes`（现在顶层有 `size`，但 `savePath` 模式没有 `encodeMs` 的对照），方便客户端自己算 token。
- 截图工具描述首句加一句「默认 JPEG q60，别再传 format:png 除非要存档」，省得每题都踩一遍。
- 若要做 Settings 开关：`mcp.screenshot.format` / `mcp.screenshot.quality` 两个 key，缺省 jpg/60，工具里读不到就用缺省 —— 这样以后不用再改代码。

---

（本文件由本机实测数据生成，数字对应 dev-1532 / 1280×2772 屏幕。）
