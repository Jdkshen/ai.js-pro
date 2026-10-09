# third-party/fonts

| 文件 | 说明 |
| --- | --- |
| `NotoSansCJKsc-Regular.otf` | 上游完整字库（Noto Sans CJK SC Regular，16.4 MB），**不进 APK**，只作为子集的生成源 |
| `OFL.txt` | SIL Open Font License（子集是同一字体的派生物，随包时一并携带） |

随包的是子集 `apps/app/src/main/assets/fonts/NotoSansCJKsc-Regular.otf`（≈0.6 MB）——
只保留 ImGui 真正会用的码位（基本拉丁 + CJK 标点/假名 + 半角形 + 2500 常用汉字 +
`imgui_font.h` 显式补的控制标签与棋子汉字）。

```powershell
python tools\imgui-font-subset.py --write    # 改了 ImGui 范围/显式字符后重新生成
python tools\imgui-font-subset.py --check    # Gradle preBuild 会自动跑这个
```

范围读数来自 `imgui_draw.cpp`（`GetGlyphRangesChineseSimplifiedCommon`）与 `imgui_font.h`，
所以 ImGui 升级后如果常用字表变了，`--check` 会直接红，不会静默变豆腐块。
真机对照：ImGui 初始化会打印 `font atlas: … glyphs0=N`，子集前后必须一致
（2026-10-09 实测：完整字体与子集都是 3260）。
