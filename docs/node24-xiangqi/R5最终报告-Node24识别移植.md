# R5 最终报告：真实象棋识别链移植到 Node24 —— 逐格等价 + 反超 QuickJS（2026-10-20）

> 设备 K40｜旁路包 `com.jdkshen.aijspro.nodebench`（测试后卸载）｜HEAD `b773138f`

## 0. 最终验收：两栏全 PASS

### ① 正确性（strict parity，3 轮独立复验全 PASS）
| 输入 | 判定 | 路径 | bbox IoU | grid delta | 逐格 | counts | placement |
|---|---|---|---|---|---|---|---|
| f01 真实帧 | **PASS** | **B 原生 recognizeImage** | 1.0000 | 0/0 | **90/90** | 29/2 = 29/2 | 相等 |
| skin-a | **PASS** | **B 原生 recognizeImage** | 1.0000 | 0/0 | **90/90** | 32/0 = 32/0 | 相等 |

### ② 性能（Node24 已快于 QuickJS）
| 口径 | Node24 | QuickJS | 倍率 |
|---|---|---|---|
| **生产路径稳态**（perf 探针，共享 detector） | **f01 277–279ms / skin-a 265–272ms** | 367 / 364ms | **0.73–0.77×（Node 更快）** |
| canonical 探针（含每帧 analyzeDiag 诊断调用） | f01 中位 **681ms** / skin-a 中位 **501ms** | — | 目标 ≤800/≤600 达成 |

## 1. 为「Node 不支持就改到支持」补的宿主能力（+1513 行，2 次提交）
| 能力 | 原状 | 实现 | 验证 |
|---|---|---|---|
| `images.analyzeXiangqiBoard`（Canny/Hough 9×10 定位） | QuickJS 独占 | C++ 215 行逐函数移植（Java/OpenCV）+ detectXiangqiCircles + debug 诊断 | 与设备 C++ raw **逐字段 0 差异**（outer/grid/circles/segments…）|
| `yolo.load/detect`（ONNX 检测） | QuickJS 独占 | `kind:'yolo'` 域 + prelude `yolo` 模块（region clamp、labels、modelAsset） | detection f01 34 / skin-a 32 = 基线；inference 17–19ms |
| ICC 解码一致性 | BitmapFactory 应用 ICC → 颜色偏移 | `read()` 探测 iCCP/ICC_PROFILE → OpenCV imread(UNCHANGED) | 1090 点/帧 **0 diff** |
| YOLO region 语义 | 全屏检测（+1 框 / autoAlign 偏 3px） | shim 帧对象对齐 QuickJS + prelude region clamp | 检测数与 autoAlign 与基线一致 |

## 2. 修掉的 4 个真实 bug
1. **Java 读多通道 Mat**（`Mat.get` 按元素不按通道）→ Hough votes 全零 → 定位失败。
2. **ICC 色彩管理差异** → f01 阵营翻转（78/90 → 90/90）。
3. **YOLO 区域选择语义差异** → skin-a 33 vs 32 框、autoAlign +3px。
4. **analyze 优化引入的几何回归**（ROI 先裁 vs C++ 整帧转换→裁子矩阵的边界语义）→ f01 originY/stepY 偏移 0.75px；正确性优先版已恢复（性能仍 +19–21%）。

## 3. 性能优化清单（task-30 + task-31）
- **shim 整帧像素缓存**：`exportPixelsSync` 一次 + `fs.readFileSync` → Uint32Array，pixel/colors 内存取（side 采样 359–381ms → 40–61ms，200 点 0 mismatch）；detector 复用（load 一次 81ms→66ms）。
- **analyze 木色扫描**：逐像素 `Mat.get` → 一次 `Bitmap.getPixels` 整块 int[]（analyze warm 237–263 → 185–214ms，输出 deepEqual 全等）。
- 结果：识别稳态从 1.84–2.25s → **265–279ms**，**优于 QuickJS 364–367ms**。

## 4. 提交
| 提交 | 内容 |
|---|---|
| `a1d68eb9` | analyzeXiangqiBoard + yolo actions + ICC 解码 parity（+1352）|
| `b773138f` | analyze 提速 19–21%（+161/−69），parity 保持 |
| `617b40fb` / `d547f2ef` / `b780cccd` | R1–R4 桥与批量 API |

## 5. 评价
真实象棋识别链路已在 **Node24 原生**跑通（非降级、非 glyph 回退），与 QuickJS **逐格 90/90 逐位等价**，且稳态性能**反超 QuickJS 23–27%**。R5 目标全部达成。

## 6. 证据
`r5-recognition-parity.md/.json`（PASS，3 轮）、`r5-recognition-parity.rep{1,2,3}.*`、`r5-node24-recognition.rep{1,2,3}.json`｜`r5-perf-report.md`/`r5-perf.json`/`r5-node24-perf.json`｜`decode-parity.json/.md`、`yolo-parity.md`｜`task26/28/31-*.json`｜`quickjs-baseline-recognition.json`｜复跑：`run-r5-node24.ps1 -Repeats 3`