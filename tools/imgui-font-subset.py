"""生成 / 校验 ImGui 用的中文字体子集。

ImGui 只会向字体要这两组码位（见 modules/engine/src/main/cpp/imgui_font.h）：

  1. ImFontAtlas::GetGlyphRangesChineseSimplifiedCommon()
     —— 基本拉丁、CJK 标点/假名、半角形、2500 个常用汉字（ImGui 源码里的累积偏移表）；
  2. imgui_font.h 里 AddText("…") 显式补的那批控制标签与棋子汉字。

范围外的字今天就已经渲染成空白豆腐块，所以把字体裁到这个并集是**行为等价**的，
而 Noto CJK 的完整字库里有 4 万多字，其中绝大多数永远用不到。

用法：

    python tools/imgui-font-subset.py --write     # 从 third-party/fonts 的完整字体重新生成子集
    python tools/imgui-font-subset.py --check     # 校验随包子集覆盖了全部需要的码位（Gradle 用）
    python tools/imgui-font-subset.py --ranges    # 打印范围与期望字形数

真机侧的对照：ImGui 初始化时会打印 `font atlas: … cjkGlyphs=N`，
子集后的 N 必须等于本工具算出的期望值。
"""
import argparse
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
IMGUI_DRAW = REPO / "modules/engine/src/main/cpp/third_party/imgui/imgui_draw.cpp"
IMGUI_FONT_H = REPO / "modules/engine/src/main/cpp/imgui_font.h"
FULL_FONT = REPO / "third-party/fonts/NotoSansCJKsc-Regular.otf"
BUNDLED_FONT = REPO / "apps/app/src/main/assets/fonts/NotoSansCJKsc-Regular.otf"


class FontSubsetError(Exception):
    pass


def _read(path):
    if not path.is_file():
        raise FontSubsetError("找不到文件：%s" % path)
    return path.read_text(encoding="utf-8", errors="replace")


def _base_ranges(source):
    """`static ImWchar base_ranges[]` 里的码位区间（不读结尾的 0 终止符）。"""
    match = re.search(r"static ImWchar base_ranges\[\].*?\{(.*?)\}", source, re.S)
    if not match:
        raise FontSubsetError("imgui_draw.cpp 里找不到 base_ranges（ImGui 版本变了？）")
    pairs = [int(value, 16) for value in re.findall(r"0x([0-9A-Fa-f]{4})", match.group(1))]
    if not pairs or len(pairs) % 2:
        raise FontSubsetError("base_ranges 解析结果不是成对区间：%r" % pairs)
    return [(pairs[i], pairs[i + 1]) for i in range(0, len(pairs), 2)]


def _common_ideographs(source):
    """`accumulative_offsets_from_0x4E00` → 2500 个常用汉字的码位集合。"""
    match = re.search(
        r"static const short accumulative_offsets_from_0x4E00\[\]\s*=\s*\{(.*?)\n\s*\};", source, re.S)
    if not match:
        raise FontSubsetError("imgui_draw.cpp 里找不到 accumulative_offsets_from_0x4E00")
    offsets = [int(value) for value in re.findall(r"\d+", match.group(1))]
    codepoints = set()
    base = 0x4E00
    for offset in offsets:
        base += offset
        codepoints.add(base)
    if len(codepoints) < 2000:
        raise FontSubsetError("常用字表只有 %d 项，解析可疑" % len(codepoints))
    return codepoints


def _control_labels(source):
    """imgui_font.h 里 AddText("…") 的显式汉字。"""
    literals = re.findall(r'AddText\("([^"]*)"\)', source)
    if not literals:
        raise FontSubsetError("imgui_font.h 里找不到 AddText(...) 的显式字符")
    codepoints = set()
    for literal in literals:
        codepoints.update(ord(char) for char in literal)
    return codepoints


def required_codepoints():
    """ImGui 可能向字体要的全部码位（范围 + 显式字符）。"""
    draw = _read(IMGUI_DRAW)
    font_h = _read(IMGUI_FONT_H)
    codepoints = set()
    for start, end in _base_ranges(draw):
        codepoints.update(range(start, end + 1))
    codepoints.update(_common_ideographs(draw))
    codepoints.update(_control_labels(font_h))
    return codepoints


def _font_codepoints(path):
    from fontTools.ttLib import TTFont

    try:
        font = TTFont(str(path), lazy=True)
    except Exception as error:  # noqa: BLE001 - 解析失败要给出可读原因
        raise FontSubsetError("%s 不是可解析的字体：%s" % (path, error))
    try:
        best = font.getBestCmap()
    except Exception as error:  # noqa: BLE001
        raise FontSubsetError("%s 没有可用的 cmap：%s" % (path, error))
    return set(best.keys())


def expected_glyphs(font_path):
    """ImGui 会从这份字体里真正加进图集的字形数（范围 ∩ 字体覆盖）。"""
    required = required_codepoints()
    covered = required & _font_codepoints(font_path)
    return len(covered), sorted(required - covered)


def write_subset(source=FULL_FONT, target=BUNDLED_FONT):
    from fontTools import subset

    if not source.is_file():
        raise FontSubsetError(
            "缺少完整字体 %s（子集只能从它生成；它在 third-party/fonts 下随仓库保存）" % source)
    required = required_codepoints()
    options = subset.Options()
    # 只保留 ImGui 走的那条最简渲染路径要用的东西，尽量去掉体积：
    #   --no-hinting / --desubroutinize：stb_truetype 的 CFF 解析更省事、更稳；
    #   --notdef-glyph / --recommended-glyphs：stb 与 ImGui 都期望这两个备用字形存在；
    #   --layout-features=''：ImGui 不做字形替换，GSUB/GPOS 全丢。
    options.hinting = False
    options.desubroutinize = True
    options.layout_features = []
    options.name_IDs = ["*"]
    options.notdef_glyph = True
    options.recommended_glyphs = True
    font = subset.load_font(str(source), options)
    subsetter = subset.Subsetter(options=options)
    subsetter.populate(unicodes=sorted(required))
    subsetter.subset(font)
    target.parent.mkdir(parents=True, exist_ok=True)
    subset.save_font(font, str(target), options)
    font.close()
    return target


def check():
    """校验随包子集覆盖了 ImGui 需要的码位（缺任何一个都会在真机上变成豆腐块）。

    判据是「子集 ≥ 完整字体能覆盖的范围」：完整字库里本来就没有的码位（U+007F–U+009F
    这类控制字符）不是可渲染字形，ImGui 拿不到也不算缺字。
    """
    required = required_codepoints()
    if not BUNDLED_FONT.is_file():
        raise FontSubsetError("随包字体不存在：%s" % BUNDLED_FONT)
    covered = required & _font_codepoints(BUNDLED_FONT)
    reachable = required & _font_codepoints(FULL_FONT) if FULL_FONT.is_file() else required
    missing = sorted(reachable - covered)
    print("ImGui 需要 %d 个码位；随包字体覆盖 %d 个（完整字体可达 %d 个）"
          % (len(required), len(covered), len(reachable)))
    print("随包字体：%s（%.1f MB）" % (BUNDLED_FONT.relative_to(REPO),
                                   BUNDLED_FONT.stat().st_size / 1048576))
    if missing:
        raise FontSubsetError(
            "随包字体比完整字体少了 %d 个码位（会显示成空白）：%s"
            % (len(missing), " ".join("U+%04X" % code for code in missing[:20])))
    unreachable = sorted(required - reachable)
    if unreachable:
        print("完整字体本身也没有的 %d 个码位（控制字符，不参与渲染）：%s"
              % (len(unreachable), " ".join("U+%04X" % code for code in unreachable[:8])))
    print("期望真机日志 `font atlas: … glyphs0=` ≥ %d（完整字体基准为 3260）" % len(covered))
    return 0


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    group = parser.add_mutually_exclusive_group()
    group.add_argument("--write", action="store_true", help="从 third-party/fonts 的完整字体重新生成随包子集")
    group.add_argument("--check", action="store_true", help="校验随包子集覆盖全部需要的码位（默认）")
    group.add_argument("--ranges", action="store_true", help="打印需要的码位统计与期望字形数")
    args = parser.parse_args(argv)

    try:
        if args.write:
            print("已生成 %s（%.1f MB）" % (write_subset().relative_to(REPO),
                                     BUNDLED_FONT.stat().st_size / 1048576))
            return check()
        if args.ranges:
            required = required_codepoints()
            covered = required & _font_codepoints(BUNDLED_FONT)
            reachable = required & _font_codepoints(FULL_FONT) if FULL_FONT.is_file() else required
            print("需要 %d 个码位；随包字体覆盖 %d 个；完整字体可达 %d 个"
                  % (len(required), len(covered), len(reachable)))
            missing = sorted(reachable - covered)
            if missing:
                print("缺 %d 个：%s" % (len(missing), " ".join("U+%04X" % code for code in missing[:20])))
            return 0
        return check()
    except FontSubsetError as error:
        print("FONT SUBSET FAILED: %s" % error, file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
