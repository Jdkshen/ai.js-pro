import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

/// task-86：SJBC 只读展示的源码钉（Dart 侧）。
///
/// 钉住三件事：① 有 sjbc 字段才显示该行；② 行里有目录 + .sjbc 计数 + 期望指纹；③ 这一段**不可编辑**
/// （不出现 TextField/Dropdown/Checkbox），真值只在 project.json。
void main() {
  test('SJBC 展示行存在、只读、且按 sjbc 字段出现', () {
    final source = File('lib/project_page.dart').readAsStringSync();
    // Lead 修正：真值在 loaded['config']['sjbc']（projectLoad 把整份 project.json 放在 config 下）。
    expect(source.contains('if (sjbcConfig() != null) sjbcRow()'), isTrue,
        reason: '没有 sjbc 字段的工程不能显示该行');
    expect(source.contains("final sjbc = config['sjbc'];"), isTrue,
        reason: 'sjbc 必须从 config 下取，不能取 loaded[\'sjbc\']');
    expect(source.contains('SJBC 资源目录：'), isTrue);
    expect(source.contains('个 .sjbc'), isTrue);
    expect(source.contains('期望指纹：'), isTrue);
    expect(source.contains('只读，真值在 project.json'), isTrue);

    final start = source.indexOf('Widget sjbcRow()');
    final end = source.indexOf('Future<int?> countSjbcFiles');
    expect(start > 0, isTrue, reason: 'sjbcRow() 必须存在');
    expect(end > start, isTrue, reason: 'countSjbcFiles() 必须在 sjbcRow() 之后');
    final block = source.substring(start, end);
    for (final forbidden in ['TextField', 'textField(', 'DropdownButton', 'Checkbox', 'Slider']) {
      expect(block.contains(forbidden), isFalse, reason: '只读展示里不能出现 ' + forbidden);
    }
  });
}
