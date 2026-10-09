# 字形模板库（11 类棋子字形）

采集：2026-09-22，K40 + 腾讯天天象棋「浅木皮肤」，脚本见
`docs/reports/chess-skin-eval/py/collect-templates2.py`（从标准开局帧按人工核对坐标裁剪，68×68 灰度）。

用途：`src/vision/glyph-recognizer.js`（电脑 py 的移植版）逐格匹配用；
评测入口 `tools/probe-glyph.js`（真机）与 `tools/glyph-pc-eval.js`（电脑，同一份识别代码）：
2026-09-25 **真值修正 31 处 + 加「多尺度抢救」** 后为 **806/809 = 99.6%**（修正前 763/784 = 97.3%，
其中 31 格是真值标错）；仅剩 `skin2-04` 的 3 格是落子动画中间帧；
详见 `docs/reports/2026-09-25_字形识别_多尺度抢救与电脑侧评测.md`。

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

## ⚠️ 2026-10-04 实验记录：多帧合成「细节修整」实测无收益，维持 v2

试过用 9 帧真机样本重建模板（半像素对齐 + 逐像素中值合成，工具
`docs/reports/chess-skin-eval/py/build-templates3.py`）：

- 评分：持平（py 805/809、JS 806/809，两套模板完全同分）；
- **余量**（正确字模板在 ±12 搜索窗内的最高 NCC，285 个真值格，
  工具 `py/compare-template-sets.py`）：旧集均值 0.974 → 合成集 0.966（-0.008）——
  单帧真实渲染（含木纹/抗锯齿）在 `TM_CCOEFF_NORMED` 下比平滑合成集相关更高；
- 结论：**维持 v2 单帧模板**；合成集只存档在 `py/cv-templates3/`（含新旧对照图），不再采用。
- 若以后要再提升：方向不是“修图”，而是换皮肤/换分辨率时**按原生分辨率重采**（如 1280 屏采 80px 原图），
  或动识别端（搜索窗/多尺度），本库维持现状即可。
