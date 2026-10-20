# Node24 vs QuickJS 图像解码 parity（task-29）

> 2026-10-20 ｜ node-smem ｜ QuickJS=dev-1153 外部 intent；Node24=nodebench R5e（ICC 解码修复后）。
> 每帧 1090 点 = 棋盘 9x10 交点（90）+ 1000 个固定种子随机点（覆盖全画面）；像素取 `images.pixel` / `image.pixelSync` 的 ARGB。
> 原图：f01.png（含 520B ICC，Display P3 系）／f01-noicc.png（像素同源、去 ICC）／skin-a.png（sRGB）。

## 1. 结论

- **R5e 修复后解码已经完全对齐**：f01 / f01-noicc / skin-a 三帧各 1090 点，Node24 vs QuickJS **0 差异**（maxAbs=0，含棋盘交点、随机点、棋盘内/外分组全部 0）。
- ICC 修复前（R5d）只有 f01（含 ICC）不同：1090 点中 1078 点 RGB 不同，均值 delta R/G/B = **+5.386 / -1.331 / -5.010**，maxAbs=59；f01-noicc 与 skin-a 当次也是 0 差异 → 差异纯粹来自 **ICC 色彩管理**（Node24 解码应用 ICC；QuickJS 不应用）。
- 对象棋识别的影响链路：f01 ICC → 红/黑阵营采样像素偏移 → f01 出现 7 处阵营翻转（如 row3 五个兵 p→P）；node-api 在 R5e 把 image.read 解码对齐 QuickJS 后，阵营翻转消失，f01 parity 90/90。

## 2. R5e 后逐帧统计（decode-parity.json 原始表）

| 输入 | 点数 | 不同点 | maxAbs | meanDelta R/G/B | std R/G/B |
|---|---|---|---|---|---|
| f01 | 1090 | 0 | 0 | (0, 0, 0) | (0, 0, 0) |
| f01-noicc | 1090 | 0 | 0 | (0, 0, 0) | (0, 0, 0) |
| skin-a | 1090 | 0 | 0 | (0, 0, 0) | (0, 0, 0) |

分组（交点/随机/棋盘内/棋盘外）全部也是 0，完整数字见 `decode-parity.json` 的 `frames[fid].{intersection,random,board,outsideBoard}`。

## 3. 修复前（R5d，保留为回归基线）

| 输入 | 不同点/总数 | maxAbs | meanDelta R/G/B | 解释 |
|---|---|---|---|---|
| f01（ICC） | 1078/1090 | 59 | +5.386 / -1.331 / -5.010 | ICC/P3→sRGB 系统偏移，R 偏高 B 偏低 |
| f01-noicc | 0/1090 | 0 | 0 / 0 / 0 | 去掉 ICC 后逐点相同 → 证明差异源是 ICC |
| skin-a | 0/1090 | 0 | 0 / 0 / 0 | sRGB 帧两引擎解码本就一致 |

## 4. 方法/复现

- 探针：`r5-node24/decode-dump.node24.js`、`r5-node24/decode-dump.quickjs.js`（同一份坐标生成代码）；输出 `r5-decode-node24.json`、`r5-decode-quickjs.json`；统计脚本 `decode-parity.py`。
- QuickJS：`am start -n com.jdkshen.aijspro/.external.open.RunIntentActivity --es path .../decode-dump.quickjs.js`；Node24：nodebench 广播运行。
- 结论：解码域**无剩余缺口**；task-29 的该子项在 R5e 上关闭。
