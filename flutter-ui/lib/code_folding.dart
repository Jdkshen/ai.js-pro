// View-level code folding for the Flutter editor. The native editor (AI.js
// Pro style folding in CodeEditText) keeps folded source in the buffer and
// removes the visual line height; Flutter's EditableText has no replacement
// spans, so folding here swaps hidden lines for one placeholder line and
// restores the source before any save/run/undo consuming operation.
// Detection, display building and edit mapping are pure functions for tests.
import 'dart:math' as math;

class FoldRegion {
  final int startLine, endLine; // 0-based source lines, endLine > startLine
  final int startOffset, endOffset; // offsets of the brackets themselves
  const FoldRegion(
      {required this.startLine,
      required this.endLine,
      required this.startOffset,
      required this.endOffset});
  bool containsLine(int line) => line > startLine && line <= endLine;
}

class _DisplayPiece {
  final String text;
  final int sourceStart, sourceEnd;
  final FoldRegion? region; // set only for placeholder pieces
  const _DisplayPiece(this.text, this.sourceStart, this.sourceEnd,
      {this.region});
}

/// One folded view of a source document: builds the display text and maps
/// offsets between display and source coordinates.
class FoldView {
  final String source;
  final List<FoldRegion> regions;
  final Set<int> foldedStartLines;
  final List<_DisplayPiece> pieces;
  final String display;
  final Map<int, FoldRegion> foldArrows; // display line -> region

  FoldView._(this.source, this.regions, this.foldedStartLines, this.pieces,
      this.display, this.foldArrows);

  /// Detects balanced bracket pairs ({} () []) that span more than one line.
  /// Strings, template literals and comments are skipped so brackets inside
  /// them never create regions. Caps protect typing responsiveness.
  static List<FoldRegion> detect(String source) {
    if (source.length > 200000) return const [];
    final lineStarts = <int>[0];
    for (var i = 0; i < source.length; i++) {
      if (source.codeUnitAt(i) == 10) lineStarts.add(i + 1);
    }
    int lineOf(int offset) {
      var low = 0, high = lineStarts.length - 1;
      while (low < high) {
        final middle = (low + high + 1) ~/ 2;
        if (lineStarts[middle] <= offset) {
          low = middle;
        } else {
          high = middle - 1;
        }
      }
      return low;
    }

    final regions = <FoldRegion>[];
    final stack = <List<int>>[]; // [codeUnit, offset]
    var blockComment = false;
    var quote = 0;
    var escaped = false;
    for (var i = 0; i < source.length && regions.length < 600; i++) {
      final c = source.codeUnitAt(i);
      if (blockComment) {
        if (c == 42 && i + 1 < source.length && source.codeUnitAt(i + 1) == 47) {
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
      if (c == 47 && i + 1 < source.length && source.codeUnitAt(i + 1) == 47) {
        final end = source.indexOf('\n', i + 2);
        i = end < 0 ? source.length : end;
        continue;
      }
      if (c == 47 && i + 1 < source.length && source.codeUnitAt(i + 1) == 42) {
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
        stack.add([c, i]);
      } else if (c == 125 || c == 41 || c == 93) {
        if (stack.isEmpty) continue;
        final top = stack.last;
        final paired = (top[0] == 123 && c == 125) ||
            (top[0] == 40 && c == 41) ||
            (top[0] == 91 && c == 93);
        if (!paired) continue;
        stack.removeLast();
        final startLine = lineOf(top[1]);
        final endLine = lineOf(i);
        if (endLine > startLine) {
          regions.add(FoldRegion(
              startLine: startLine,
              endLine: endLine,
              startOffset: top[1],
              endOffset: i));
        }
      }
    }
    return regions;
  }

  /// Regions whose start line is not inside another folded region, so the
  /// gutter never offers a fold the user cannot see.
  static List<FoldRegion> visibleRegions(
      List<FoldRegion> regions, Set<int> folded) {
    final sorted = [...regions]
      ..sort((a, b) => a.startLine.compareTo(b.startLine));
    final result = <FoldRegion>[];
    var hiddenUntil = -1;
    for (final region in sorted) {
      if (region.startLine <= hiddenUntil) continue;
      result.add(region);
      if (folded.contains(region.startLine)) {
        hiddenUntil = region.endLine;
      }
    }
    return result;
  }

  static FoldView build(String source, List<FoldRegion> regions,
      Set<int> foldedStartLines) {
    final lineStarts = <int>[0];
    for (var i = 0; i < source.length; i++) {
      if (source.codeUnitAt(i) == 10) lineStarts.add(i + 1);
    }
    int lineEnd(int line) {
      if (line + 1 < lineStarts.length) return lineStarts[line + 1];
      return source.length;
    }

    String lineText(int line) => source
        .substring(lineStarts[line], lineEnd(line))
        .replaceFirst(RegExp(r'[\r\n]+$'), '');

    final active = <int, FoldRegion>{};
    for (final region in regions) {
      if (foldedStartLines.contains(region.startLine)) {
        active[region.startLine] = region;
      }
    }
    final pieces = <_DisplayPiece>[];
    final arrows = <int, FoldRegion>{};
    // Arrows appear on every visible region start line, folded or not, so
    // the gutter can both fold and unfold; nested regions under a folded
    // parent stay hidden until the parent opens again.
    final visible = visibleRegions(regions, foldedStartLines);
    var line = 0;
    var hiddenUntil = -1;
    var visibleIndex = 0;
    while (line < lineStarts.length) {
      if (line <= hiddenUntil) {
        line++;
        continue;
      }
      FoldRegion? region;
      if (visibleIndex < visible.length &&
          visible[visibleIndex].startLine == line) {
        region = visible[visibleIndex];
        visibleIndex++;
      }
      pieces.add(_DisplayPiece(lineText(line), lineStarts[line], lineEnd(line)));
      if (region != null) {
        arrows[pieces.length - 1] = region;
        if (active.containsKey(region.startLine)) {
          final hiddenStart = lineEnd(line);
          final hiddenEnd = lineEnd(region.endLine);
          final placeholder = '⋯ ${region.endLine - region.startLine} 行';
          pieces.add(_DisplayPiece(placeholder, hiddenStart, hiddenEnd,
              region: region));
          hiddenUntil = region.endLine;
          line = region.endLine + 1;
          continue;
        }
      }
      line++;
    }
    return FoldView._(
        source,
        regions,
        {...foldedStartLines},
        pieces,
        pieces.map((piece) => piece.text).join('\n'),
        arrows);
  }

  int displayToSource(int displayOffset) {
    var offset = 0;
    for (final piece in pieces) {
      final end = offset + piece.text.length;
      if (displayOffset <= end) {
        if (piece.region != null) return piece.sourceEnd;
        final delta = math.min(displayOffset - offset, piece.text.length);
        return math.min(piece.sourceStart + delta, piece.sourceEnd);
      }
      offset = end + 1; // the newline between display lines
    }
    return source.length;
  }

  int sourceToDisplay(int sourceOffset) {
    var offset = 0;
    for (final piece in pieces) {
      final end = offset + piece.text.length;
      if (sourceOffset < piece.sourceStart) return offset;
      if (sourceOffset <= piece.sourceEnd) {
        if (piece.region != null) return end;
        return offset +
            math.min(sourceOffset - piece.sourceStart, piece.text.length);
      }
      offset = end + 1;
    }
    return offset;
  }

  /// True when [from, to) falls inside a placeholder line, where edits
  /// cannot be mapped back to source safely.
  bool touchesPlaceholder(int from, int to) {
    var offset = 0;
    for (final piece in pieces) {
      final end = offset + piece.text.length;
      if (piece.region != null && from < end && to > offset) return true;
      offset = end + 1;
    }
    return false;
  }

  /// Applies a whole-buffer display edit back onto the source. Returns null
  /// when the edit touched a placeholder line; callers must then discard the
  /// keystroke and unfold instead of risking data loss.
  String? absorb(String nextDisplay) {
    final previous = display;
    if (nextDisplay == previous) return source;
    var prefix = 0;
    final limit = math.min(previous.length, nextDisplay.length);
    while (prefix < limit &&
        previous.codeUnitAt(prefix) == nextDisplay.codeUnitAt(prefix)) {
      prefix++;
    }
    var suffix = 0;
    while (suffix < limit - prefix &&
        previous.codeUnitAt(previous.length - 1 - suffix) ==
            nextDisplay.codeUnitAt(nextDisplay.length - 1 - suffix)) {
      suffix++;
    }
    final previousEnd = previous.length - suffix;
    final nextEnd = nextDisplay.length - suffix;
    if (touchesPlaceholder(prefix, previousEnd)) return null;
    final sourceStart = displayToSource(prefix);
    final sourceEnd = displayToSource(previousEnd);
    final inserted = nextDisplay.substring(prefix, nextEnd);
    return source.replaceRange(sourceStart, sourceEnd, inserted);
  }
}
