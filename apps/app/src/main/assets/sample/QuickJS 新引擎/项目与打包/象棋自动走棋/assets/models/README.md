# 模型槽位（已放入象棋模型）

这个目录放工程自带的 ONNX 模型与类别表。**模型随包**，工程一导入就能跑识别，不需要额外下载。

## 放的是什么

| 文件 | 说明 |
|---|---|
| `chessai_160.onnx` | 9,651,802 字节（走 git LFS），SHA-256 `BF9D66E2E5F5C5C8807A84877175C902C878A4FCE26438E868D742BA73287B75`（2026-09-13 域内微调版） |
| `chessai_160.txt` | 类别表**7 行**，与训练时顺序完全一致：`R N B A K C P` |

类别字母就是 FEN 的棋子字符（与 `src/chess/fen.js` 同一套）：

| classId | 0 | 1 | 2 | 3 | 4 | 5 | 6 |
|---|---|---|---|---|---|---|---|
| 字母 | `R` | `N` | `B` | `A` | `K` | `C` | `P` |
| 棋子 | 车 | 马 | 相/象 | 仕/士 | 帅/将 | 炮 | 兵/卒 |

⚠️ **模型分不出红黑** —— 这 7 类把红黑合并了（字母按红方字形命名）。阵营由
`src/vision/piece-side.js` 在棋子范围内数「红笔画 / 黑笔画」补上（实测 shot-01 的 32 个棋子
判出 16 红 16 黑；红像素占比 0.27~0.51、黑 0.14~0.30，完全分离）。

## 输入输出契约

```text
input : [1, 3, 160, 160] float32   # RGB、letterbox（min 缩放 + 居中灰 114 补边）、x/255
output: [1, 300, 6] float32        # 每行 [x1, y1, x2, y2, score, classId]，角点相对模型输入
```

- 是 `end2end`（模型自带 TopK、无需额外 NMS）→ 正好走 `yolo.load({ backend: 'dnn' })` 的六列解码路径；
- 导出时 `dynamic=False`，**batch 恒为 1**（逐张推理，不能一次喂多张）；
- 用 COCO 模型配这份类别表不会得到任何象棋识别能力。

## 怎么训练出来的（便于复现/更新）

- 数据：`.artifacts/chess-merged/`（合并 10 个 Roboflow 子集 + 合成集，统一成 7 类，
  22,958 训练图 / 20.4 万框；来源与映射见 `.artifacts/chess-merge-prep.py`）
- 训练：`yolo26n.pt` @160，30 epochs，AdamW，CPU（`.artifacts/chess-train-merged.py`）
- 结果：val mAP50 **0.532** / mAP50-95 0.326；K40 真机推理 ~6.7ms
- **域内微调（2026-09-13）**：在上述合并版权重上续训 25 epochs —— 2500 张合并样本 + 20 张真机截屏人工标注 ×10 过采样
  （另 6 张留出验证）；脚本 `.artifacts/train-domain-local.py`（含续训脚本 `.artifacts/resume-domain-train.py`），
  产物 `.artifacts/chessai-runs/chess160-domain/`。真实域对照（同批 820 框、IoU≥0.5）：
  留出 6 帧 P/R/F1 **0.910 / 0.958 / 0.933**（旧模型 0.868 / 0.905 / 0.886）、全 26 帧 F1 **0.959**（旧 0.936）
- ⚠️ **不要拿这份 160 权重直接导 320 用**：实测 val mAP50 掉到 0.450、真机 ≥0.5 的框反而更少；
  要 320 必须在 320 重训
- 引擎里 `inputSize` 缺省值是 640（面向任意第三方模型），本工程显式传 160

## 已知短板（免得误判）

- 7 类无阵营；`相/象`、`仕/士`、`帅/将`、`兵/卒` 视为同一类
- 训练数据主要是**实物棋盘照片**，本工程实际用在**手机截屏**上。实测 11 张棋盘页截屏都能出
  32 个框（≥0.5 的 27~33 个，最高分 0.94）——够用，但换皮肤/换 App 前要重新验证
- **非棋盘页也会误检**（手机桌面最多凭空认到 10 个"棋子"）→ 必须先做棋盘定位，
  定位失败就不要跑识别（`tools/recognize-board.js` 就是这么做的）
- 想更准：拿现有模型给棋盘页截屏自动标注 → 人工过一遍 → 域内微调（比换 320 划算）

## 喂模型的方式（实测过，别改回去）

| 喂法 | shot-41 / shot-49 结果 |
|---|---|
| 写死屏幕比例裁棋盘区 | 33 框 / 只有 29 个 ≥0.5（最低 0.26，边缘棋子被切） |
| **按定位出来的棋盘外框裁（当前做法）** | **32 框 / 全部 ≥0.5（最低 0.55）** |
| 逐格特写裁剪 | 更差（丢掉棋盘上下文，类别都会错） |

## manifest

本工程只用到「模型 + 标签」两个文件，`model-manifest.json` 尚未使用（六列输出已由引擎的
`yolo.load` 契约覆盖）。将来换模型/加预处理时再按模板补：

```json
{
  "id": "chess-160",
  "version": "1.0.0",
  "file": "assets/models/chessai_160.onnx",
  "sha256": "BF9D66E2E5F5C5C8807A84877175C902C878A4FCE26438E868D742BA73287B75",
  "backend": "dnn",
  "input": { "shape": [1, 3, 160, 160], "color": "RGB", "normalization": "x / 255",
             "resize": "letterbox", "padColor": [114, 114, 114] },
  "output": { "shape": [1, 300, 6], "decoder": "xyxy_score_class", "postprocess": "model_topk" },
  "labelsFile": "assets/models/chessai_160.txt"
}
```

## 体积提醒

`.onnx` 走 git LFS（已确认本文件 filter=lfs）；`*.nnue`（本地引擎权重，约 48 MiB）**目前没有 LFS 规则**，
直接提交会塞进普通 blob，放进来之前先在 `.gitattributes` 里加 `*.nnue filter=lfs diff=lfs merge=lfs -text`。


## v5r（2026-09-13 接入，当前默认）

| 文件 | 输入 | 用途 |
| --- | --- | --- |
| `chessai_160x320_v5r.onnx` | 1×3×320×160（宽 160 高 320） | **默认**：竖屏整屏上盘面级成绩最好 |
| `chessai_160_v5r.onnx` | 1×3×160×160 | 方形档回滚/对照 |
| `chessai_160.onnx` | 1×3×160×160 | 更老的基线，供历史评测脚本对照 |

两者输出都是 `[1, 300, 6]`（end2end，无需额外 NMS），标签集不变（7 类 R N B A K C P，
颜色仍由格心像素采样判定）。

⚠️ **矩形档用整屏入检，不要裁棋盘再喂**：K40 冻结帧实测（9 帧 / 787 格，人工真值）
`整屏→矩形 763/787 = 97.0%` ＞ `裁棋盘→矩形 759/787 = 96.4%` ＞（方形档 771/787 = 98.0%），
而且整屏正好是训练侧的评测口径（电脑端 xq36：全屏→矩形 0.969）。
所以 `tools/recognize-board.js` 里 `REGION_MODE = "full"`（`recognizer.regionMode === "full"`
时检测区域 = 整张图；`"board"` 才是裁棋盘外框）。
⚠️ 这 9 帧很可能就是更老的 `chessai_160.onnx`（同套帧 782/787 = 99.4%）当年的基线集，
它「见过」这些帧，所以那个数不能当泛化结论；v5r 的强项（残局页）也不在这套帧里。

回滚到方形档：`tools/recognize-board.js` 里把 `MODEL_FILE/LABELS_FILE` 换成
`chessai_160_v5r.onnx/txt`，并把 `REGION_ASPECT` 设为 `null`、`REGION_MODE` 设为 `"board"`。
