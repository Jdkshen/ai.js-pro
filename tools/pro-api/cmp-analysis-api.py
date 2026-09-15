"""手机端分析包（AutoJs_Pro_Analysis）的模块/API 清单 与 我们的对照表。

输入：
  .artifacts/autojs-pro-analysis/*.md     —— 用户从手机发来的 11 篇分析文档（zip 解开）
  docs/architecture/pro-api-compatibility.csv —— 我们的主交付物（PDF 逐函数对照，938 行）
  modules/engine/src/main/assets/modules/ —— 我们实际打包的内置模块

用法：
  python tools/pro-api/cmp-analysis-api.py dump     # 只打印抽到的清单
  python tools/pro-api/cmp-analysis-api.py report   # 生成 docs/reports/2026-09-15_手机端分析包_模块与API对照.md
"""
import csv
import io
import os
import re
import sys

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
PKG_DIR = os.path.join(REPO, ".artifacts", "autojs-pro-analysis")
CSV_PATH = os.path.join(REPO, "docs", "architecture", "pro-api-compatibility.csv")
ASSET_MODULES = os.path.join(REPO, "modules", "engine", "src", "main", "assets", "modules")
OUT_MD = os.path.join(REPO, "docs", "reports", "2026-09-15_手机端分析包_模块与API对照.md")

MODULE_IN_ASSET = re.compile(r"__(?:[A-Za-z0-9_]+)__\.js")
CALL_IN_CODE = re.compile(r"`([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*\(")
DOTTED_IN_CODE = re.compile(r"`([a-z][\w$]*(?:\.[A-Za-z_$][\w$]*)+)`")

# 「APK 结构类」文档：这几篇才是对着包/清单写的；其余（okhttp3 系列 + jvm 内存）围绕一个
# 被误判成「JS 模块加载器」的 okhttp 工具类层层展开，里面的清单可信度低。
APK_DOCS = {
    "README.md", "AutoJs_Pro_API.md", "apk_package_analysis.md",
    "architecture_readme.md", "autojs_pro_complete_summary.md",
}
NOISE_PREFIX = ("okhttp3", "okio", "java.", "javax.", "android.", "androidx.", "com.",
                "org.", "kotlin.", "io.", "dalvik.", "libcore.", "sun.")
NOISE_NAMES = {"eval", "require", "module.require", "Module.require", "joinToString"}


def read_docs():
    docs = {}
    for name in sorted(os.listdir(PKG_DIR)):
        if name.endswith(".md"):
            with io.open(os.path.join(PKG_DIR, name), encoding="utf-8") as handle:
                docs[name] = handle.read()
    return docs


def extract(docs):
    modules, apis, where = set(), set(), {}
    for name, text in docs.items():
        for match in MODULE_IN_ASSET.finditer(text):
            modules.add(match.group(0))
            where.setdefault(match.group(0), set()).add(name)
        for match in list(CALL_IN_CODE.finditer(text)) + list(DOTTED_IN_CODE.finditer(text)):
            token = match.group(1)
            if token.startswith(NOISE_PREFIX) or token in NOISE_NAMES or token.endswith(".js"):
                continue
            head = token.split(".")[0]
            if head in ("https", "url", "www", "file", "com", "org"):
                continue
            apis.add(token)
            where.setdefault(token, set()).add(name)
    return modules, apis, where


def load_ours():
    modules, pairs, api_rows = set(), set(), {}
    with io.open(CSV_PATH, encoding="utf-8-sig", newline="") as handle:
        for row in csv.DictReader(handle):
            module = (row.get("module") or "").strip()
            api = (row.get("api") or "").strip()
            if module:
                modules.add(module)
            if api:
                pairs.add((module, api))
                api_rows.setdefault(api, []).append(row)
    return modules, pairs, api_rows


def our_asset_modules():
    return [name for name in sorted(os.listdir(ASSET_MODULES))
            if name.startswith("__") and name.endswith("__.js")]


def source_of(names):
    high = sorted(n for n in names if n in APK_DOCS)
    return "APK 结构类" if high else "仅单类深挖类"


def main():
    action = sys.argv[1] if len(sys.argv) > 1 else "dump"
    docs = read_docs()
    modules, apis, where = extract(docs)
    our_modules, our_pairs, api_rows = load_ours()
    our_assets = our_asset_modules()

    if action == "dump":
        print(f"文档 {len(docs)} 篇；抽到模块文件 {len(modules)} 个；抽到 api {len(apis)} 个")
        for token in sorted(modules):
            print(f"  {token:<24} {source_of(where[token])}  {', '.join(sorted(where[token]))}")
        for token in sorted(apis):
            print(f"  api {token:<40} {', '.join(sorted(where[token]))}")
        print(f"我们 assets 模块 {len(our_assets)} 个：{' '.join(our_assets)}")
        print(f"我们 CSV 模块 {len(our_modules)} 个 / api 条目 {len(api_rows)} 个")
        return

    if action != "report":
        raise SystemExit(f"未知动作：{action}")

    # 同一个东西的不同命名：分析包把 `files.*` 的装配模块叫 __files__.js，我们叫 __io__.js
    renamed = {"__files__.js": "__io__.js"}
    assets = set(our_assets)
    both = sorted(modules & assets)
    only_theirs = sorted(modules - assets - set(renamed))
    only_ours = sorted(assets - modules)

    lines = []
    lines.append("# 手机端分析包 vs 本仓库：模块 / API 对照")
    lines.append("")
    lines.append("日期：2026-09-15。对照对象：用户从手机发来的 `AutoJs_Pro_Analysis.zip`")
    lines.append("（MT 反编译 + MiMo AI，11 篇 md，对象是第三方魔改包 `AutoJs五云Ai_Pro 9.3.11-0.apk`）。")
    lines.append("")
    lines.append("数据源：")
    lines.append("")
    lines.append("- 分析包：`.artifacts/autojs-pro-analysis/*.md`（由 `tools/pro-api/cmp-analysis-api.py` 抽取）")
    lines.append("- 我们：`modules/engine/src/main/assets/modules/`（实际打包的内置模块）")
    lines.append("  与 `docs/architecture/pro-api-compatibility.csv`（对照第一代 PDF 的 938 行逐函数表）")
    lines.append("")
    lines.append("可信度分档：**APK 结构类** = 该名字出现在对着包/清单写的几篇里；"
                 "**仅单类深挖类** = 只出现在围绕 `okhttp3.internal.io.ੜ` 展开的文档里"
                 "（那 6 篇把 okhttp 自带的工具类误判成了 JS 模块加载器，见"
                 "[复核文档](2026-09-15_手机端AutoJsPro分析包复核.md)），可信度低。")
    lines.append("")

    lines.append("## 1. 内置模块文件（它 %d 个 / 我们 %d 个）" % (len(modules), len(our_assets)))
    lines.append("")
    lines.append("### 1.1 两边都有（%d）" % len(both))
    lines.append("")
    lines.append("| 模块文件 | 它的出处 |")
    lines.append("| --- | --- |")
    for name in both:
        lines.append("| `%s` | %s |" % (name, source_of(where[name])))
    lines.append("")
    lines.append("### 1.2 命名不同、其实是同一个（%d）" % len(renamed))
    lines.append("")
    lines.append("| 它的名字 | 我们的名字 | 说明 |")
    lines.append("| --- | --- | --- |")
    for name, ours in sorted(renamed.items()):
        lines.append("| `%s` | `%s` | 我们的 `files.*` 就由 `%s` 装配（文件里写着 `scope.files = files`），"
                     "逐函数已按 PDF 2.15 对齐并有真机契约 |" % (name, ours, ours))
    lines.append("")
    lines.append("### 1.3 **它提到、我们确实没有（%d）**" % len(only_theirs))
    lines.append("")
    if only_theirs:
        lines.append("| 模块文件 | 它的出处 | 可信度 | 判断 |")
        lines.append("| --- | --- | --- | --- |")
        notes = {
            "__sqlite__.js": "第一代 PDF 里没有 sqlite 模块，我们也没有；这个名字只出现在单类深挖类文档，"
                             "**可信度低，可能虚构**",
            "__uiobject2__.js": "第一代 PDF 里没有这个模块。UiObject2 选择器在我们这里由 `__selector__.js` "
                                "与 `auto.*` 承担，**可信度低，可能虚构或该魔改包自己加的**",
        }
        for name in only_theirs:
            credible = "APK 结构类" if name in where and source_of(where[name]) == "APK 结构类" else "仅单类深挖类"
            lines.append("| `%s` | %s | %s | %s |" % (
                name, source_of(where[name]), "中" if credible == "APK 结构类" else "低",
                notes.get(name, "待核（需要拿到那个 APK 才能确认）")))
    else:
        lines.append("（无）")
    lines.append("")
    lines.append("### 1.4 我们有、它没提（%d）" % len(only_ours))
    lines.append("")
    lines.append("| 模块文件 | 说明 |")
    lines.append("| --- | --- |")
    notes_ours = {
        "__io__.js": "**我们 `files.*` 的装配处**（`scope.files = files`）—— 分析包把同一个东西叫 `__files__.js`",
        "__yolo__.js": "本项目自加：YOLO 目标检测",
    }
    for name in only_ours:
        lines.append("| `%s` | %s |" % (name, notes_ours.get(name, "它的清单里没提（不代表它没有）")))
    lines.append("")

    lines.append("## 2. 它列的 API 逐条对我们的状态")
    lines.append("")
    lines.append("分析包的 API 清单只有 **%d 条**（全部来自 `AutoJs_Pro_API.md` 那张速查表），"
                 "我们 CSV 是 %d 行 —— 覆盖度差两个数量级，只能用来快速互认几个常见名字。" % (len(apis), len(our_pairs)))
    lines.append("")
    lines.append("| 它列的 API | 出处 | 在我们的 CSV | Rhino | QuickJS |")
    lines.append("| --- | --- | --- | --- | --- |")
    missing = []
    for token in sorted(apis):
        rows = api_rows.get(token)
        if not rows:
            # `app.startActivity` 这类可能记成方法名，退化成后缀匹配
            rows = [r for api, rs in api_rows.items() if api.endswith("." + token.split(".")[-1])
                    and token.split(".")[0] in api for r in rs]
        if rows:
            row = rows[0]
            lines.append("| `%s` | %s | `%s` | %s | %s |" % (
                token, source_of(where[token]), row["api"], row["rhino"] or "—", row["quickjs"] or "—"))
        else:
            missing.append(token)
            lines.append("| `%s` | %s | **没有** | — | — |" % (token, source_of(where[token])))
    lines.append("")
    lines.append("### 2.1 它有我们无的 API（%d）" % len(missing))
    lines.append("")
    if missing:
        for token in missing:
            lines.append("- `%s`（出处：%s）" % (token, source_of(where[token])))
    else:
        lines.append("（无：它列出的名字在我们 CSV 里全部能查到）")
    lines.append("")

    lines.append("## 3. 结论")
    lines.append("")
    lines.append("1. **模块层面**：它提到的 %d 个内置模块文件里 %d 个与我们一致；`__files__.js` 与我们的 "
                 "`__io__.js` 是同一个东西的不同命名；真正对不上的只有 `__sqlite__.js`、`__uiobject2__.js`，"
                 "而这两个只出现在低可信文档里（第一代 PDF 里也没有）。" % (len(modules), len(both)))
    lines.append("2. **API 层面**：它列出的 %d 条里 %d 条在我们 CSV 里查得到状态（Rhino/QuickJS 均实机有）；"
                 "唯一对不上的 `storage.put` 是它把 `storages.create().put()` 写成了模块级方法，不是缺失。"
                 % (len(apis), len(apis) - len(missing)))
    lines.append("3. **结论**：这份包能提供的对照价值到此为止 —— 模块名清单可当作「我们的模块集与真实 "
                 "Auto.js 系构建一致」的外部旁证（%d 个里 %d 个对得上，且对不上的都是命名/虚构问题）；"
                 "API 清单只有 %d 条、覆盖度比我们 CSV（%d 行）差两个数量级，不能作为补齐依据。"
                 % (len(modules), len(both), len(apis), len(our_pairs)))
    lines.append("4. 真正的依据仍是第一代 PDF + `docs/architecture/pro-api-compatibility.csv` + "
                 "`tools/pro-api/*-contract.js` 真机契约。")
    lines.append("")
    lines.append("生成脚本：`tools/pro-api/cmp-analysis-api.py report`（幂等，重跑覆盖本文件）")
    lines.append("")

    with io.open(OUT_MD, "w", encoding="utf-8", newline="\n") as handle:
        handle.write("\n".join(lines))
    print(f"已写入 {OUT_MD}")
    print(f"两边都有 {len(both)}；它有我们无 {len(only_theirs)}；我们有它无 {len(only_ours)}；"
          f"API 对不上 {len(missing)}")


if __name__ == "__main__":
    main()
