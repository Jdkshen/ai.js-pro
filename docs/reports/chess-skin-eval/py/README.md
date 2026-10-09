# 电脑 py 识别（参考实现）—— 2026-09-22 建于 `.artifacts`，2026-09-24 存档到本目录

这套 Python/OpenCV 脚本是「字形模板识别」的**参考实现**，手机端 `src/vision/glyph-recognizer.js`
就是它的 1:1 移植（差异见该模块文件头：搜索窗不对称 + 跨分辨率缩放）。
存档原因：换游戏皮肤/分辨率重采模板、或复核识别行为时要用，`.artifacts` 是临时目录会被清理。

## 文件

| 文件 | 用途 |
|---|---|
| `cv-chess-recognize-2.py` | 识别算法本体：山谷定位 → 逐格三道门 → 11 字形模板 matchTemplate(0.70) → 車馬炮判色 → FEN；含「空中子模糊门」（BLUR_RATIO，2026-10-04）；`--live` 从 K40 拉当前屏、`--file` 指定图、`--learn` 高置信补模板 |
| `collect-templates2.py` | 从一张标准开局帧按人工核对坐标采齐 11 类模板（68×68，需要 `newgame-frame1.png`，路径可改） |
| `run-py-eval-9.py` | 9 帧皮肤评测集基准：**805/809 = 99.5%**（严格口径，`?` 格跳过；2026-09-25 真值修正后） |
| `build-templates3.py` | 从 9 帧多样本重建模板（半像素对齐 ±4px + 逐像素中值；NCC<0.85 样本按动画/遮挡剔除）。⚠️ 2026-10-04 实验结论：**评分持平但余量略降，不采用**——产量为 `cv-templates3/`（仅存档，含 `compare.png` 新旧对照图） |
| `measure-animation-state.py` | 2026-10-04：“空中子”可分性探针：284 个静止子 Laplacian 方差最低 1289，而 skin2-04 的 3 个飞行子只有 248~395（差 4~5 倍，完全可分） |
| `verify-blur-gate.py` | 2026-10-04：“模糊门”收益验证：对返回 `?` 的格，若窗清晰度 < 帧内认准子中位数×0.5 → 判“空中子/忽略”（=空）。实测 **805→808/809**（skin2-04 直接 100%），门仅触发 4 次全对、284 静止子零误伤。已落地：py `recognize()` 与 JS `glyph-recognizer.js` 都内置此门（JS 另覆盖“勉强对上<0.82”的格）——最后成绩 **JS 809/809 = 100%**（离线与 K40 真机一致） |
| `compare-template-sets.py` | 两套模板目录的「余量」A/B：对 285 个真值格取**正确字模板**在 ±12 搜索窗内的最高 NCC（用它对 2026-10-04 的合成集下了结论：全局均值 0.974→0.966，旧集胜） |
| `measure-glyph-offsets.py` | 搜索窗标定：以 lattice 网格为窗心，实测字形需要滑动的像素（结论：\|dx\|≤2、\|dy\|≤16 → 移植版用 ±6/±20 窗） |
| `cv-templates2/` | 模板库（11 个 68×68 灰度字形 + `manifest.json`；与工程 `assets/glyph-tpl/` 同源）——**现役＝单帧 v2 集**（2026-10-04 多帧合成实验后维持不变） |
| `cv-templates2-old-backup/` | v2 集备份（=现役；给 `compare-template-sets.py` 当 A/B 的「旧」侧） |
| `cv-templates3/` | 多帧合成实验集（只存档不再采用；重建再跑 `build-templates3.py`） |

跑法示例（Windows + Python，需 cv2/numpy/PIL）：

```powershell
python -X utf8 run-py-eval-9.py                 # 9 帧基准（读 ../skin-*.png）
python -X utf8 cv-chess-recognize-2.py --file ..\skin-a.png
```

## 与手机版的对照（2026-09-24 实测）

- 手机移植版（probe-glyph）与 py **逐帧同分**：修正真值前 9 帧 763/784 = 97.3%；
  2026-09-25 真值修正 31 处（`?` 只剩 1 个）+ 手机端加「多尺度抢救」后：
  py 805/809 = 99.5%、手机/电脑 JS 806/809 = 99.6%，识别 ~350ms/帧（K40）。剩余 3 格全在 `skin2-04`（落子动画中间帧）。
- 跨分辨率（新手机 1280×2772，板距≈136）：模板按 `scale=板距/114.85` 缩放后整盘 32 子 0 未知；
  不缩放则全部 0.4~0.6 分（识别失败）。
