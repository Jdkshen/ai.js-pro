# tools 目录说明与分类（2026-09-20）

> 背景：`tools/` 一度长到 42 个脚本，正式工具与阶段性探针混在一起，看名字分不出谁还有效。
> 这份说明先做**分类与搬迁方案**（不改文件、零风险），等确认后再按「搬迁步骤」执行。

## 一、分类

### active —— 正式工具（长期维护，改代码后要跟着跑）
| 文件 | 用途 | 何时跑 |
|---|---|---|
| `run-regress.js` | **场景回归入口**（PC 与真机同一份）：跑 `tools/regress/cases/*` 全部场景，PC 上顺带跑 self-check / 格子契约；`--only <关键字>` / `--no-legacy` / `--report <路径>` | 改 `src/**` 后；发版前 |
| `regress/harness.js` | 场景框架核心（断言 `t.ok/equal/deepEqual/…`、`t.host()` 假宿主、`t.pipeline()` 接线、`t.recognize()` 多帧） | 加场景前先看它 |
| `regress/cases/*.js` | **场景库**（一条场景 = 一次真机故障的固化） | 新增/修改场景 |
| `self-check.js` | 离线自检（纯 JS 模块断言） | 每次改 `src/**` 后（PC 上 `node tools/self-check.js`） |
| `test-cells-contract.js` | 格子三态契约 + 几何搜索行为测试 | 改 `cells.js` / `grid-search.js` 后 |
| `recognize-board.js` | **底座**：定位/几何（`pickGrid`/`calibrateGrid`）+ 旧识别路径 + 原生检测 | 面板/回放/工具都依赖它 |
| `locate-board.js` | 棋盘定位（格线法；这套皮肤上不可靠，见仓库记忆） | 面板定位的备选 |
| `vision-pipeline-probe.js` | 新流水线实机探针（出帧 + `report.json` + 失败样本） | 改流水线后、发版前 |
| `board-replay-test.js` | 历史帧回放（回归安全网） | 改识别链后 |
| `ocr-benchmark.js` | OCR 耗时/一致率基准 | 换模型、调候选策略后 |
| `color-calibration-probe.js` | 红黑判定标定（打印真实特征值；采样 + 老分类器那条通道） | 改 `piece-color/piece-side` 阈值后 |
| `ink-lookup-probe.js` | 红黑判色（**找色版**）：逐格数红/黑墨迹 + 与采样对照耗时 + `report.json` | 改 `ink-color` 参考色/阈值后 |
| `ocr-prep-probe.js` | 「给 OCR 之前先处理图」：同一帧 20 格 × 10 种预处理（clip/灰度/2×/Otsu/自适应/inRange）对比识字率、分数、耗时 + 裁剪图落盘 | 想调 `board-scan` 的 OCR 输入通道前（**先跑它，别猜**） |
| `capture-bench-probe.js` | 抓帧拆解：`screencap -p`(PNG) / `screencap`(raw) / `images.read` / `/data/local/tmp` / MediaProjection 各段耗时 + **raw vs PNG 像素逐点对照** | 改抓帧通道、怀疑截图慢时 |
| `board-scan-cost-probe.js` | 全盘扫描拆解（2026-09-20）：A 现状全盘 scan（90 格都 OCR）／B 90 格逐格找色（耗时 + 假阴性/假阳性）／C 只扫「有框 ∪ 有墨迹」并逐格对比 A | 改 `board-scan` 的扫描范围、怀疑全盘 OCR 慢时（真机：2895ms → 1015ms，预筛 19ms） |
| `ocr-batch-probe.js` | 「能不能整盘一次 detect 拿回所有字形」：逐格 vs 整盘裁剪 vs 整帧+region 三条路（耗时/召回/块↔格映射） | **已测：不行**（PP-OCR 把整行合成一条、无 bounds）—— 别再为省时间试批量 |
| `analyze-panel-log.py` | 面板真机日志汇总（Python）：出招/确认次数、**确认耗时**、未看清/帧不稳定/规则门否决/恢复、tick 与观察耗时中位数、各依赖合计 | 做 A/B（如 `observeFrames`）或看一局的总账时（先 `logcat -d -f` + `adb pull`） |
| `clip-probe.js` | `images.clip` 坐标系最小诊断（帧逻辑/像素尺寸 + 越界行为） | `clip` 报 `Clip rectangle is outside the source frame` 时 |
| `ocr-config-probe.js` | `$ocr.create({models})` 生效性验证 | 改 OCR 后端/模型目录逻辑后 |
| `collect-piece-samples.js` | 七类样本采集（§12 #10 数据集） | 要训练专用分类模型前 |

### fixtures —— 测试夹具（被别的工具/自检 require，不单独运行）
| 文件 | 用途 |
|---|---|
| `fake-vision-host.js` | 流水线黑盒测试的假宿主（假检测/假像素/假 OCR 会话/假预处理）；场景框架与 self-check 共用 |

## 一之二、场景回归框架（怎么用 / 怎么加）

**为什么要它**：真机上反复踩的坑（帅/将没检出、OCR 读不出、几何整行偏、灰度重试）**大都不是模块级逻辑错**，
而是**组装错 / 端到端行为错**；真机复现成本又高（截图 → 定位 → 跑一局）。
所以把一次真机故障固化成一条**场景**：造假宿主 → 跑流水线 → 断言，在 PC 与真机上跑**同一份代码**。

```bash
# PC（在象棋自动走棋目录下）
node tools/run-regress.js                 # 全部场景 + legacy 套件（self-check / 格子契约）
node tools/run-regress.js --only king     # 只跑名字或标签含 king 的场景
node tools/run-regress.js --report C:\path\regress.txt   # 留档（默认不写文件）

# 真机（同一批场景，日志前缀 [regress]，报告落 /sdcard/xiangqi-p4/regress/）
adb shell am start -S -n com.jdkshen.aijspro/com.jdkshen.aijspro.external.open.RunIntentActivity \
  --es path "/sdcard/xiangqi-p4/proj/tools/run-regress.js"
```

**加一条场景**（两步）：

1. 在 `tools/regress/cases/` 新建模块：

```js
// @engine quickjs
"use strict";
module.exports = {
    name: "帅/将抢救（有框）：…",          // 会打进日志/报告
    tags: ["pipeline", "king"],            // --only 也能按标签过滤
    run: function (t) {
        const host = t.host({ pieces: [...], hiddenFromDetect: ["4,9"] });
        const pipeline = t.pipeline(host);
        const result = t.recognize(pipeline, host, 3);   // 多帧：融合/投票/抢救都要几帧才稳
        t.equal(result.chars[85], "K", "(4,9) 应当被救回成红帅");
        t.ok(result.validation.ok, "规则门应当通过");
        t.equal(host.stats().leaked, 0, "临时帧不许泄漏");
    }
};
```

2. 把路径加进 `tools/regress/index.js` 的清单（**显式注册**，不扫目录 —— 真机侧没有 Node 的 fs）。

**假宿主的预设字段**：`pieces[].ocr`（彩色读数）/ `pieces[].ocrGray`（灰度读数，不写=同彩色，`null`=也读不出）、
`pieces[].label`（检测类别）、`hiddenFromDetect`（哪几格检测框没检出）、`host.stats()`（帧创建/释放数，断言不泄漏）。

**注意事项**：
- 断言失败不会中断场景（一次列出全部失败）；`t.skip("原因")` 用来跳过设备/PC 专属场景；
- 新场景必须首行 `// @engine quickjs`（`verifyQuickJsSamplesMarked` 会拦）；
- ⚠️ 不要用 PowerShell 的 `*>` 重定向收集输出（UTF-8 会被当 GBK 存成乱码）—— 要留档用 `--report`。

**验证「框架真的能抓到回归」**（变异测试，已演练过一次）：故意打坏一条路，对应场景必须变红、其余保持绿。
例：Node 里先 `pieceOcr.classOfLabel = function () { return null; }`（模拟 YOLO 候选路失效）再 require 入口 ⇒
「抢救（有框）」红了 4 条断言（`(4,9)` 变 `?`、规则门报「红帅数量 0（应为 1）」），
而同批另外 4 条（无框字形路 / 反例 / 规则补全 / 灰度重试）全绿 —— 说明它**能定位到是哪条路坏了**。

### legacy —— 阶段性探针（保留供追溯，不再维护）
- 全部 `p4-*.js`（23 个）：几何/点击/截图通道/皮肤评测等一次性取证。
- `ocr-board-probe.js`、`ocr-probe.js`、`recognize-board-probe.js`、`runtime-probe.js`、`probe-locate-device.js`
- `ocr-model-ab.js`、`ocr-model-ab2.js`、`ocr-model-one.js`（模型 A/B 史的中间产物；现役做法见 `ocr-config-probe.js`）

## 二、搬迁步骤（**执行前先 commit**）

1. 在 `tools/` 下建 `active/`、`legacy/`、`fixtures/`；
2. `git mv` 按上表分别挪进去（**不要用移动工具**，保住 git 历史）；
3. 改三处引用：
   - 各脚本顶部的 `BASE_DIR` 计算：现在是 `__dirname.replace(/\/tools$/, "")`，
     挪进子目录后要改成 `/\/tools\/(active|legacy|fixtures)$/`（或更稳的「向上找 `src/`」写法）；
   - 它们之间的 `require("./xxx.js")`（例如 `fake-vision-host.js`）；
   - 仓库里对 `tools/xxx.js` 的**文档引用**（`README.md`、`docs/**`）。
4. `node tools/active/self-check.js` 必须全绿（自检里 `require("../src/...")` 的相对深度也会变，要一起改）；
5. 设备端工程副本 `/sdcard/xiangqi-p4/proj` 是**手工 push** 的 ⇒ 搬迁后要重推对应文件，
   面板里写死的路径（`/sdcard/xiangqi-p4/proj/tools/...`）也要同步；
6. 真机跑一次 `tools/active/vision-pipeline-probe.js` 确认链路没断。

## 三、注意事项
- `assets/sample/**` 是**打包内容**：只把文件挪进 `tools/legacy/` **不会**「不参与发布」；
  真要瘦包得把它们移出 assets，或加打包排除规则（顺带要处理 gradle 的
  `verifyQuickJsSamplesMarked` / `verifyBundledSamplesNoNode` 这类校验）。
- 每个 sample JS 的首行 `// @engine quickjs` 标记不能丢（`verifyQuickJsSamplesMarked` 会拦）。
- 搬迁是**纯机械操作**，但一次做完再跑自检；中间状态容易漏引用。
