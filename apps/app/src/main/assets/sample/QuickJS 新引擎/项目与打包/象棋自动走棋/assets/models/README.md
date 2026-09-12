# 模型槽位（尚未放入模型）

这个目录准备放工程自带的 ONNX 模型与类别表。**当前是空的**——没有模型，就没有任何识别能力，
不要用"示例默认能识别"之类的说法描述本工程。

## 放进来时需要的文件

| 文件 | 说明 |
|---|---|
| `chess-yolo26-160.onnx` | 固定输入 `[1,3,160,160]` FLOAT32、RGB、letterbox 灰 114 补边、`x/255` |
| `labels.txt` | 与训练时**完全一致**的类别顺序，一行一个 |
| `model-manifest.json` | 输入输出契约、预处理、解码器、SHA-256，见下 |

## 输出契约

本工程走 `yolo.load({ backend: 'dnn' })`（OpenCV DNN），当前引擎只适配**六列输出**：

```text
output0: [1, 300, 6] float32   # 每行 [x1, y1, x2, y2, score, classId]，角点相对模型输入
```

- `end2end`（模型自带 TopK、无需额外 NMS）才符合这条路径；
- YOLOv5 那种二十列输出（4 框 + 1 目标分 + 15 类分）**不能**套用六列解码，必须另写解码器；
- 用 COCO 模型配象棋标签数组不会获得任何象棋识别能力。

## 现有可用的训练产物（尚未入库）

- `.artifacts/chessai-runs/chess160/chessai_160.onnx`：`[1,3,160,160]` → `[1,300,6]`，约 9.7 MB
- 类别：**只有 7 类红方棋子**（车 马 相 仕 帅 炮 兵），没有黑方、没有 board 类 → 与工程需要的
  14 棋子 + 棋盘不一致，直接换上会缺黑方；要么补训，要么明确降级为"只识别红方"
- 合并数据集训练（`.artifacts/chessai-runs/chess160-merged/`）跑完后应以新产物为准，重新填 `manifest`

## manifest 模板（数值必须按真实模型填写，不可照抄）

```json
{
  "id": "chess-yolo26-160",
  "version": "1.0.0",
  "file": "assets/models/chess-yolo26-160.onnx",
  "sha256": "<实际文件 SHA-256>",
  "backend": "dnn",
  "input": {
    "name": "images",
    "dtype": "float32",
    "shape": [1, 3, 160, 160],
    "layout": "NCHW",
    "color": "RGB",
    "normalization": "x / 255",
    "resize": "letterbox",
    "padColor": [114, 114, 114]
  },
  "output": {
    "name": "output0",
    "shape": [1, 300, 6],
    "decoder": "xyxy_score_class",
    "coordinates": "input_pixels",
    "hasObjectness": false,
    "postprocess": "model_topk"
  },
  "labelsFile": "assets/models/labels.txt",
  "classMapVersion": "1"
}
```

## 体积提醒

`.onnx` 走 git LFS；`*.nnue`（本地引擎权重，约 48 MiB）**目前没有 LFS 规则**，直接提交会塞进普通 blob，
放进来之前先在 `.gitattributes` 里加 `*.nnue filter=lfs diff=lfs merge=lfs -text`。
