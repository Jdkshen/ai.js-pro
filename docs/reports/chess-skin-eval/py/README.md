# 电脑 py 识别（参考实现）—— 2026-09-22 建于 `.artifacts`，2026-09-24 存档到本目录

这套 Python/OpenCV 脚本是「字形模板识别」的**参考实现**，手机端 `src/vision/glyph-recognizer.js`
就是它的 1:1 移植（差异见该模块文件头：搜索窗不对称 + 跨分辨率缩放）。
存档原因：换游戏皮肤/分辨率重采模板、或复核识别行为时要用，`.artifacts` 是临时目录会被清理。

## 文件

| 文件 | 用途 |
|---|---|
| `cv-chess-recognize-2.py` | 识别算法本体：山谷定位 → 逐格三道门 → 11 字形模板 matchTemplate(0.70) → 車馬炮判色 → FEN；`--live` 从 K40 拉当前屏、`--file` 指定图、`--learn` 高置信补模板 |
| `collect-templates2.py` | 从一张标准开局帧按人工核对坐标采齐 11 类模板（68×68，需要 `newgame-frame1.png`，路径可改） |
| `run-py-eval-9.py` | 9 帧皮肤评测集基准：**763/784 = 97.3%**（严格口径，`?` 格跳过） |
| `measure-glyph-offsets.py` | 搜索窗标定：以 lattice 网格为窗心，实测字形需要滑动的像素（结论：\|dx\|≤2、\|dy\|≤16 → 移植版用 ±6/±20 窗） |
| `cv-templates2/` | 模板库（11 个 68×68 灰度字形 + `manifest.json`；与工程 `assets/glyph-tpl/` 同源） |

跑法示例（Windows + Python，需 cv2/numpy/PIL）：

```powershell
python -X utf8 run-py-eval-9.py                 # 9 帧基准（读 ../skin-*.png）
python -X utf8 cv-chess-recognize-2.py --file ..\skin-a.png
```

## 与手机版的对照（2026-09-24 实测）

- 手机移植版（probe-glyph）与 py **逐帧同分**：9 帧 763/784 = 97.3%，识别 ~350ms/帧（K40）。
- 跨分辨率（新手机 1280×2772，板距≈136）：模板按 `scale=板距/114.85` 缩放后整盘 32 子 0 未知；
  不缩放则全部 0.4~0.6 分（识别失败）。
