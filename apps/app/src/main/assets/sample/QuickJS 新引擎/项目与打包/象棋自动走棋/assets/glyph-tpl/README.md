# 字形模板库（11 类棋子字形）

采集：2026-09-22，K40 + 腾讯天天象棋「浅木皮肤」，脚本见
`docs/reports/chess-skin-eval/py/collect-templates2.py`（从标准开局帧按人工核对坐标裁剪，68×68 灰度）。

用途：`src/vision/glyph-recognizer.js`（电脑 py 的移植版）逐格匹配用；
评测入口 `tools/probe-glyph.js`（9 帧皮肤集 763/784 = 97.3%）。

| 文件 | 说明 |
|---|---|
| `tpl_車.png` `tpl_馬.png` `tpl_炮.png` | 红黑同形，阵营由笔画颜色（暗像素 R-B）判定 |
| `tpl_相.png` `tpl_仕.png` `tpl_帥.png` `tpl_兵.png` | 红方 |
| `tpl_象.png` `tpl_士.png` `tpl_將.png` `tpl_卒.png` | 黑方 |

注意：

- **模板按皮肤采集**——换皮肤（棋盘/棋子画风变化）需要重采：py 版 `cv-chess-recognize-2.py --learn`
  或按 `collect-templates2.py` 重跑，然后把新 `tpl_*.png` 覆盖回本目录。
- **跨分辨率不用重采**：`glyph-recognizer.js` 用 `prepareTemplates(scale)` 按板距缩放
  （K40 1080 基准 114.85；如新手机 1280 板距≈136 → scale≈1.19，实测整盘 32 子 0 未知）。
- 与 py 参考模板同源（`docs/reports/chess-skin-eval/py/cv-templates2/`），改一边要同步另一边。
