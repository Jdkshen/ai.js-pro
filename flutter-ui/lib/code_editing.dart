// Auto-indent behavior is adapted from AI.js Pro / Auto.js AutoIndent.java
// (Stardust, 2018). Covered adaptations are provided under MPL-2.0 with the
// upstream non-commercial condition; see third_party/aijs-editor/LICENSE.md.
// Flutter rendering and input integration were written for this project.
import 'dart:math' as math;
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'code_folding.dart';
import 'mono_font.dart';

/// 编辑器显示选项。值来自保留运行库自己的偏好存储（键名见设置页），
/// 设置页读到或写入后同步到这里，编辑器据此改变绘制。
/// 这里只存值不改桥，避免与桥接层互相依赖。
class EditorPreferences extends ChangeNotifier {
  EditorPreferences._();
  static final EditorPreferences instance = EditorPreferences._();

  bool softWrap = false;
  bool showInvisibles = false;
  bool showGutter = true;
  bool showPrintMargin = true;
  bool liveCompletion = true;

  /// 用 `runtimePreferences` 的完整返回值刷新；缺键按运行库默认处理。
  void apply(Map<String, dynamic> values) {
    final nextSoftWrap = values['key_soft_wrap'] == true;
    final nextInvisibles = values['key_show_invisibles'] == true;
    final nextGutter = values['key_show_gutter'] != false;
    final nextMargin = values['key_show_print_margin'] != false;
    final nextCompletion = values['key_enable_live_completion'] != false;
    if (nextSoftWrap == softWrap &&
        nextInvisibles == showInvisibles &&
        nextGutter == showGutter &&
        nextMargin == showPrintMargin &&
        nextCompletion == liveCompletion) {
      return;
    }
    softWrap = nextSoftWrap;
    showInvisibles = nextInvisibles;
    showGutter = nextGutter;
    showPrintMargin = nextMargin;
    liveCompletion = nextCompletion;
    notifyListeners();
  }
}

class CodeToken {
  final int start, end;
  final String kind;
  const CodeToken(this.start, this.end, this.kind);
}

/// A display lexer, never used to validate or modify executable source.
List<CodeToken> codeTokens(String source) {
  if (source.length > 200000) return const [];
  const keywords = {
    'async',
    'await',
    'break',
    'case',
    'catch',
    'class',
    'const',
    'continue',
    'debugger',
    'default',
    'delete',
    'do',
    'else',
    'export',
    'extends',
    'finally',
    'for',
    'from',
    'function',
    'if',
    'import',
    'in',
    'instanceof',
    'let',
    'new',
    'of',
    'return',
    'static',
    'super',
    'switch',
    'this',
    'throw',
    'try',
    'typeof',
    'var',
    'void',
    'while',
    'with',
    'yield',
    'true',
    'false',
    'null',
    'undefined'
  };
  const apis = {
    'console',
    'log',
    'ui',
    'auto',
    'app',
    'device',
    'files',
    'threads',
    'events',
    'engines',
    'http',
    'images',
    'storages',
    'dialogs',
    'floaty',
    'require',
    'module',
    'exports',
    'process',
    'setTimeout',
    'setInterval',
    'clearTimeout',
    'clearInterval',
    'sleep',
    'toast',
    'exit',
    'Math',
    'JSON'
  };
  bool word(int c) =>
      (c >= 65 && c <= 90) ||
      (c >= 97 && c <= 122) ||
      c == 95 ||
      c == 36 ||
      c >= 128;
  bool digit(int c) => c >= 48 && c <= 57;
  final tokens = <CodeToken>[];
  var i = 0;
  while (i < source.length && tokens.length < 20000) {
    final start = i, c = source.codeUnitAt(i);
    String? kind;
    if (source.startsWith('//', i)) {
      final end = source.indexOf('\n', i);
      i = end < 0 ? source.length : end;
      kind = 'comment';
    } else if (source.startsWith('/*', i)) {
      final end = source.indexOf('*/', i + 2);
      i = end < 0 ? source.length : end + 2;
      kind = 'comment';
    } else if (c == 34 || c == 39 || c == 96) {
      i++;
      while (i < source.length) {
        final next = source.codeUnitAt(i++);
        if (next == 92) {
          if (i < source.length) i++;
        } else if (next == c) {
          break;
        } else if (next == 10 && c != 96) {
          break;
        }
      }
      kind = 'string';
    } else if (digit(c)) {
      i++;
      while (i < source.length) {
        final next = source.codeUnitAt(i);
        if (!digit(next) && !word(next) && next != 46) break;
        i++;
      }
      kind = 'number';
    } else if (word(c)) {
      i++;
      while (i < source.length &&
          (word(source.codeUnitAt(i)) || digit(source.codeUnitAt(i)))) i++;
      final name = source.substring(start, i);
      if (keywords.contains(name)) kind = 'keyword';
      if (apis.contains(name)) kind = 'api';
    } else {
      i++;
    }
    if (kind != null) tokens.add(CodeToken(start, i, kind));
  }
  return tokens;
}

class CodeEditingController extends TextEditingController {
  final bool highlight;
  String? _source;
  List<CodeToken> _tokens = const [];
  CodeEditingController({this.highlight = true});

  @override
  TextSpan buildTextSpan(
      {required BuildContext context,
      TextStyle? style,
      required bool withComposing}) {
    if (_source != text) {
      _source = text;
      _tokens = highlight ? codeTokens(text) : const [];
    }
    final dark = Theme.of(context).brightness == Brightness.dark;
    final colors = dark
        ? const {
            'keyword': Color(0xffc586c0),
            'string': Color(0xffce9178),
            'comment': Color(0xff6a9955),
            'number': Color(0xffb5cea8),
            'api': Color(0xff9cdcfe)
          }
        : const {
            'keyword': Color(0xff8f287f),
            'string': Color(0xffa31515),
            'comment': Color(0xff008000),
            'number': Color(0xff098658),
            'api': Color(0xff005a9e)
          };
    final composing = withComposing &&
            value.isComposingRangeValid &&
            !value.composing.isCollapsed
        ? value.composing
        : TextRange.empty;
    final spans = <TextSpan>[];
    void emit(int start, int end, TextStyle? tokenStyle) {
      if (end <= start) return;
      final left = composing.isValid ? math.max(start, composing.start) : end;
      final right = composing.isValid ? math.min(end, composing.end) : start;
      if (left >= right) {
        spans
            .add(TextSpan(text: text.substring(start, end), style: tokenStyle));
        return;
      }
      if (left > start)
        spans.add(
            TextSpan(text: text.substring(start, left), style: tokenStyle));
      spans.add(TextSpan(
          text: text.substring(left, right),
          style: (tokenStyle ?? const TextStyle())
              .copyWith(decoration: TextDecoration.underline)));
      if (right < end)
        spans
            .add(TextSpan(text: text.substring(right, end), style: tokenStyle));
    }

    var offset = 0;
    for (final token in _tokens) {
      emit(offset, token.start, null);
      emit(token.start, token.end, TextStyle(color: colors[token.kind]));
      offset = token.end;
    }
    emit(offset, text.length, null);
    return TextSpan(style: style, children: spans);
  }
}

/// Only a single committed newline triggers indentation; paste and IME text
/// are left intact, as are programmatic reload, undo and redo operations.
class CodeAutoIndent extends TextInputFormatter {
  CodeAutoIndent();
  @override
  TextEditingValue formatEditUpdate(
      TextEditingValue oldValue, TextEditingValue newValue) {
    if (!oldValue.selection.isValid ||
        !oldValue.selection.isCollapsed ||
        !newValue.selection.isCollapsed ||
        (newValue.composing.isValid && !newValue.composing.isCollapsed)) {
      return newValue;
    }
    final cursor = oldValue.selection.extentOffset;
    if (cursor > oldValue.text.length ||
        newValue.text.length != oldValue.text.length + 1 ||
        newValue.selection.extentOffset != cursor + 1 ||
        newValue.text != oldValue.text.replaceRange(cursor, cursor, '\n')) {
      return newValue;
    }
    final lineStart = codeLineStart(oldValue.text, cursor);
    final before = oldValue.text.substring(lineStart, cursor);
    var indent = RegExp(r'^[ \t]*').firstMatch(before)!.group(0)!;
    final trimmed = before.trimRight();
    if (trimmed.endsWith('{') || trimmed.endsWith('(')) indent += '    ';
    return newValue.copyWith(
        text: newValue.text.replaceRange(cursor + 1, cursor + 1, indent),
        selection: TextSelection.collapsed(offset: cursor + 1 + indent.length),
        composing: TextRange.empty);
  }
}

int codeLineStart(String text, int offset) {
  final cursor = offset.clamp(0, text.length).toInt();
  return cursor == 0 ? 0 : text.lastIndexOf('\n', cursor - 1) + 1;
}

int codeLineEnd(String text, int offset) {
  final end = text.indexOf('\n', offset.clamp(0, text.length));
  return end < 0 ? text.length : end;
}

TextEditingValue deleteCodeLine(TextEditingValue value) {
  var start = codeLineStart(value.text, value.selection.extentOffset);
  var end = codeLineEnd(value.text, start);
  if (end < value.text.length) {
    end++;
  } else if (start > 0) {
    start--;
    if (start > 0 && value.text.codeUnitAt(start - 1) == 13) start--;
  }
  return TextEditingValue(
      text: value.text.replaceRange(start, end, ''),
      selection: TextSelection.collapsed(offset: start));
}

TextEditingValue duplicateCodeLine(TextEditingValue value) {
  final start = codeLineStart(value.text, value.selection.extentOffset);
  final end = codeLineEnd(value.text, start);
  final separator = value.text.contains('\r\n') ? '\r\n' : '\n';
  final contents =
      value.text.substring(start, end).replaceFirst(RegExp(r'\r$'), '');
  if (end < value.text.length) {
    return TextEditingValue(
        text: value.text.replaceRange(end + 1, end + 1, '$contents$separator'),
        selection: TextSelection.collapsed(offset: end + 1));
  }
  return TextEditingValue(
      text: value.text.replaceRange(end, end, '$separator$contents'),
      selection: TextSelection.collapsed(offset: end + separator.length));
}

TextEditingValue moveCodeCaret(TextEditingValue value, String direction) {
  final source = value.text;
  final selection = value.selection;
  var cursor = selection.extentOffset.clamp(0, source.length).toInt();
  if (!selection.isCollapsed && (direction == '←' || direction == '→')) {
    cursor = direction == '←' ? selection.start : selection.end;
  } else if (direction == '←' && cursor > 0) {
    cursor--;
    if (cursor > 0 &&
        source.codeUnitAt(cursor) >= 0xdc00 &&
        source.codeUnitAt(cursor) <= 0xdfff &&
        source.codeUnitAt(cursor - 1) >= 0xd800 &&
        source.codeUnitAt(cursor - 1) <= 0xdbff) cursor--;
  } else if (direction == '→' && cursor < source.length) {
    final c = source.codeUnitAt(cursor++);
    if (c >= 0xd800 &&
        c <= 0xdbff &&
        cursor < source.length &&
        source.codeUnitAt(cursor) >= 0xdc00 &&
        source.codeUnitAt(cursor) <= 0xdfff) cursor++;
  } else if (direction == '↑' || direction == '↓') {
    final start = codeLineStart(source, cursor);
    final column = cursor - start;
    if (direction == '↑' && start > 0) {
      final target = codeLineStart(source, start - 1);
      cursor = math.min(target + column, start - 1);
    } else if (direction == '↓') {
      final end = codeLineEnd(source, cursor);
      if (end < source.length)
        cursor = math.min(end + 1 + column, codeLineEnd(source, end + 1));
    }
    if (cursor > 0 &&
        cursor < source.length &&
        source.codeUnitAt(cursor) >= 0xdc00 &&
        source.codeUnitAt(cursor) <= 0xdfff &&
        source.codeUnitAt(cursor - 1) >= 0xd800 &&
        source.codeUnitAt(cursor - 1) <= 0xdbff) cursor--;
    if (cursor > 0 &&
        cursor < source.length &&
        source.codeUnitAt(cursor) == 10 &&
        source.codeUnitAt(cursor - 1) == 13) cursor--;
  }
  return value.copyWith(
      selection: TextSelection.collapsed(offset: cursor),
      composing: TextRange.empty);
}

/// Keeps the source buffer intact while drawing line numbers beside a
/// horizontally scrolling, non-wrapping EditableText.
class CodeSourceField extends StatefulWidget {
  final CodeEditingController controller;
  final ScrollController vertical, horizontal;
  final FocusNode focus;
  final GlobalKey fieldKey;
  final bool readOnly;
  final double fontSize;
  final Map<int, FoldRegion> foldArrows;
  final Set<int> foldedLines;
  final void Function(int displayLine)? onFoldTap;
  const CodeSourceField(
      {Key? key,
      required this.controller,
      required this.vertical,
      required this.horizontal,
      required this.focus,
      required this.fieldKey,
      required this.readOnly,
      required this.fontSize,
      this.foldArrows = const {},
      this.foldedLines = const {},
      this.onFoldTap})
      : super(key: key);
  @override
  State<CodeSourceField> createState() => _CodeSourceFieldState();
}

class _CodeSourceFieldState extends State<CodeSourceField> {
  String? measured;
  double measuredSize = 0, measuredScale = 0, width = 0, viewport = 0;
  TextSelection? selection;
  @override
  void initState() {
    super.initState();
    widget.controller.addListener(revealCursor);
  }

  @override
  void dispose() {
    widget.controller.removeListener(revealCursor);
    super.dispose();
  }

  @override
  void didUpdateWidget(CodeSourceField oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.controller != widget.controller) {
      oldWidget.controller.removeListener(revealCursor);
      widget.controller.addListener(revealCursor);
      measured = null;
      selection = null;
    }
  }

  TextStyle get codeStyle => TextStyle(
      fontFamily: 'monospace', fontFamilyFallback: monoFontFallback,
      fontSize: widget.fontSize,
      height: 1.5,
      color: Theme.of(context).colorScheme.onSurface);
  double textWidth(String text) {
    final painter = TextPainter(
        text: TextSpan(text: text, style: codeStyle),
        textScaleFactor: MediaQuery.of(context).textScaleFactor,
        textDirection: TextDirection.ltr)
      ..layout();
    return painter.width;
  }

  void revealCursor() {
    final next = widget.controller.selection;
    if (selection == next) return;
    selection = next;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted || !next.isValid || !widget.horizontal.hasClients) return;
      final source = widget.controller.text;
      final cursor = next.extentOffset.clamp(0, source.length).toInt();
      final x =
          textWidth(source.substring(codeLineStart(source, cursor), cursor));
      final current = widget.horizontal.offset;
      final target = x < current
          ? x
          : x > current + viewport - 24
              ? x - viewport + 24
              : current;
      widget.horizontal.jumpTo(target
          .clamp(0, widget.horizontal.position.maxScrollExtent)
          .toDouble());
    });
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
      animation: EditorPreferences.instance,
      builder: (context, _) => buildField(context));

  Widget buildField(BuildContext context) {
    final preferences = EditorPreferences.instance;
    final scale = MediaQuery.of(context).textScaleFactor;
    final source = widget.controller.text;
    if (measured != source ||
        measuredSize != widget.fontSize ||
        measuredScale != scale) {
      measured = source;
      measuredSize = widget.fontSize;
      measuredScale = scale;
      width = 0;
      if (source.length <= 200000) {
        for (final line in source.split('\n'))
          width = math.max(width, textWidth(line));
      } else {
        var longest = 0;
        for (final line in source.split('\n'))
          longest = math.max(longest, line.length);
        width = longest * widget.fontSize * scale * 2;
      }
    }
    final lineHeight = widget.fontSize * scale * 1.5;
    final lineCount = '\n'.allMatches(source).length + 1;
    final gutter = preferences.showGutter
        ? math.max(38.0, textWidth('$lineCount') + 20)
        : 0.0;
    return Directionality(
        textDirection: TextDirection.ltr,
        child: LayoutBuilder(builder: (context, constraints) {
          viewport = math.max(1, constraints.maxWidth - gutter);
          // 自动换行时不横向滚动：把编辑区宽度限制成可视宽度，长行就会折行；
          // 关闭时按最长行撑开并横向滚动（原有行为）。
          final fieldWidth = preferences.softWrap
              ? viewport
              : math.max(viewport, width + 24);
          return Row(children: [
            if (preferences.showGutter)
              GestureDetector(
                behavior: HitTestBehavior.opaque,
                onTapDown: widget.onFoldTap == null
                    ? null
                    : (details) {
                        if (!widget.vertical.hasClients) return;
                        final line = ((details.localPosition.dy +
                                        widget.vertical.offset) /
                                    lineHeight)
                                .floor() +
                            0;
                        if (widget.foldArrows.containsKey(line)) {
                          widget.onFoldTap!(line);
                        }
                      },
                child: ExcludeSemantics(
                    child: SizedBox(
                        width: gutter,
                        height: constraints.maxHeight,
                        child: AnimatedBuilder(
                            animation: widget.vertical,
                            builder: (context, _) => CustomPaint(
                                painter: _LineNumbers(
                                    offset: widget.vertical.hasClients
                                        ? widget.vertical.offset
                                        : 0,
                                    height: lineHeight,
                                    count: lineCount,
                                    current: '\n'
                                        .allMatches(source.substring(
                                            0,
                                            widget.controller.selection
                                                .extentOffset
                                                .clamp(0, source.length)))
                                        .length,
                                    style: codeStyle.copyWith(
                                        color: Theme.of(context)
                                            .colorScheme
                                            .onSurface
                                            .withOpacity(.45)),
                                    scale: scale,
                                    accent: Theme.of(context)
                                        .colorScheme
                                        .primary,
                                    arrows: widget.foldArrows,
                                    folded: widget.foldedLines)))))),
            Expanded(
                child: SingleChildScrollView(
                    controller: widget.horizontal,
                    scrollDirection: Axis.horizontal,
                    child: SizedBox(
                        width: fieldWidth,
                        height: constraints.maxHeight,
                        child: Stack(children: [
                          if (preferences.showPrintMargin)
                            Positioned.fill(
                                child: IgnorePointer(
                                    child: CustomPaint(
                                        painter: _PrintMargin(
                                            column: 80,
                                            charWidth: textWidth('0'),
                                            inset: 12,
                                            color: Theme.of(context)
                                                .colorScheme
                                                .onSurface
                                                .withOpacity(.18))))),
                          TextField(
                            key: widget.fieldKey,
                            controller: widget.controller,
                            scrollController: widget.vertical,
                            focusNode: widget.focus,
                            readOnly: widget.readOnly,
                            expands: true,
                            minLines: null,
                            maxLines: null,
                            keyboardType: TextInputType.multiline,
                            inputFormatters: [CodeAutoIndent()],
                            autocorrect: false,
                            enableSuggestions: false,
                            textDirection: TextDirection.ltr,
                            style: codeStyle,
                            strutStyle: StrutStyle(
                                fontFamily: 'monospace', fontFamilyFallback: monoFontFallback,
                                fontSize: widget.fontSize,
                                height: 1.5,
                                forceStrutHeight: true),
                            decoration: const InputDecoration(
                                border: InputBorder.none,
                                isCollapsed: true,
                                contentPadding: EdgeInsets.zero))
                        ]))))
          ]);
        }));
  }
}

/// 页分割线：在页宽列位置画一条参考竖线（对应原版 `key_show_print_margin`）。
class _PrintMargin extends CustomPainter {
  final int column;
  final double charWidth, inset;
  final Color color;
  _PrintMargin(
      {required this.column,
      required this.charWidth,
      required this.inset,
      required this.color});
  @override
  void paint(Canvas canvas, Size size) {
    final x = inset + charWidth * column;
    if (x <= 0 || x >= size.width) return;
    canvas.drawLine(Offset(x, 0), Offset(x, size.height),
        Paint()..color = color..strokeWidth = 1);
  }

  @override
  bool shouldRepaint(_PrintMargin old) =>
      old.column != column ||
      old.charWidth != charWidth ||
      old.inset != inset ||
      old.color != color;
}

class _LineNumbers extends CustomPainter {
  final double offset, height, scale;
  final int count, current;
  final TextStyle style;
  final Color accent;
  final Map<int, FoldRegion> arrows;
  final Set<int> folded;
  _LineNumbers(
      {required this.offset,
      required this.height,
      required this.count,
      required this.current,
      required this.style,
      required this.scale,
      required this.accent,
      this.arrows = const {},
      this.folded = const {}});
  @override
  void paint(Canvas canvas, Size size) {
    canvas.save();
    canvas.clipRect(Offset.zero & size);
    canvas.drawLine(
        Offset(size.width - 8, 0),
        Offset(size.width - 8, size.height),
        Paint()..color = accent.withOpacity(.12));
    final first = math.max(0, (offset / height).floor());
    final last = math.min(count, ((offset + size.height) / height).ceil() + 1);
    final arrowPaint = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1.6
      ..strokeCap = StrokeCap.round
      ..color = accent.withOpacity(.65);
    for (final entry in arrows.entries) {
      final line = entry.key;
      if (line < first || line >= last) continue;
      final y = line * height - offset;
      final cx = 9.0, cy = y + height / 2, s = 3.2;
      final isFolded = folded.contains(entry.value.startLine);
      final path = isFolded
          ? (Path()
            ..moveTo(cx - s, cy - 1.6)
            ..lineTo(cx + s, cy - 1.6)
            ..lineTo(cx, cy + 2.6)
            ..close())
          : (Path()
            ..moveTo(cx - 1.6, cy - s)
            ..lineTo(cx + 2.6, cy)
            ..lineTo(cx - 1.6, cy + s));
      canvas.drawPath(
          path,
          isFolded
              ? (Paint()
                ..color = accent.withOpacity(.85)
                ..style = PaintingStyle.fill)
              : arrowPaint);
    }
    for (var line = first; line < last; line++) {
      final painter = TextPainter(
          text: TextSpan(
              text: '${line + 1}',
              style: style.copyWith(
                  color: line == current ? accent : style.color)),
          textDirection: TextDirection.ltr,
          textScaleFactor: scale)
        ..layout();
      painter.paint(
          canvas,
          Offset(size.width - 14 - painter.width,
              line * height - offset + (height - painter.height) / 2));
    }
    canvas.restore();
  }

  @override
  bool shouldRepaint(_LineNumbers old) =>
      old.offset != offset ||
      old.height != height ||
      old.count != count ||
      old.current != current ||
      old.style != style ||
      old.scale != scale ||
      old.accent != accent ||
      old.arrows != arrows ||
      old.folded != folded;
}

/// Re-indents JavaScript sources in place, matching the native editor's
/// long-press TAB "beautify" intent (js-beautify runs in the native engine;
/// this Dart formatter keeps line structure and only fixes indentation so
/// the result is deterministic and offline). Strings, template literals and
/// comments are scanned so brackets inside them never affect depth.
/// Returns null when the file is too large to format safely.
String? formatJavaScript(String source, {String indent = '    '}) {
  if (source.length > 200000) return null;
  final newline = source.contains('\r\n') ? '\r\n' : '\n';
  final lines = source.split(RegExp(r'\r\n|\n|\r'));
  var depth = 0;
  var blockComment = false;
  var quote = 0; // ', " or ` while inside a string/template.
  var escaped = false;
  final output = <String>[];
  for (final raw in lines) {
    var line = raw.trimRight();
    final leading = line.trimLeft();
    var displayDepth = depth;
    if (!blockComment && quote == 0 && leading.isNotEmpty) {
      var closers = 0;
      while (closers < leading.length &&
          (leading.codeUnitAt(closers) == 125 ||
              leading.codeUnitAt(closers) == 41 ||
              leading.codeUnitAt(closers) == 93)) {
        closers++;
      }
      if (closers > 0) {
        displayDepth = math.max(0, depth - closers);
      } else {
        final keyword = RegExp(r'^(else|catch|finally)\b').firstMatch(leading);
        if (keyword != null && depth > 0) displayDepth = depth - 1;
      }
    }
    if (leading.isEmpty) {
      output.add('');
      continue;
    }
    var opens = 0, closes = 0;
    for (var i = 0; i < line.length; i++) {
      final c = line.codeUnitAt(i);
      if (blockComment) {
        if (c == 42 && i + 1 < line.length && line.codeUnitAt(i + 1) == 47) {
          blockComment = false;
          i++;
        }
        continue;
      }
      if (quote != 0) {
        if (escaped) {
          escaped = false;
        } else if (c == 92) {
          escaped = true;
        } else if (c == quote) {
          quote = 0;
        } else if (c == 10 && quote != 96) {
          quote = 0;
        }
        continue;
      }
      if (c == 47 && i + 1 < line.length && line.codeUnitAt(i + 1) == 47) {
        break; // line comment ends the line
      }
      if (c == 47 && i + 1 < line.length && line.codeUnitAt(i + 1) == 42) {
        blockComment = true;
        i++;
        continue;
      }
      if (c == 39 || c == 34 || c == 96) {
        quote = c;
        escaped = false;
        continue;
      }
      if (c == 123 || c == 40 || c == 91) {
        opens++;
      } else if (c == 125 || c == 41 || c == 93) {
        closes++;
      }
    }
    // A single/double quoted string never survives past its line.
    if (quote != 0 && quote != 96) quote = 0;
    depth = math.max(0, depth + opens - closes);
    output.add('${indent * displayDepth}$leading');
  }
  while (output.isNotEmpty && output.last.isEmpty) {
    output.removeLast();
  }
  return output.isEmpty ? '' : output.join(newline) + newline;
}
