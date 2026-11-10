import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

/// task-96：打包页「大小构成」只读行的源码钉。
///
/// 钉住：① 行的数据来自桥方法 apkBreakdown（只读）；② 四个分类标签都在；
/// ③ **拿不到数据就不显示**（null/非列表/全为 0 → SizedBox.shrink）；④ 这里没有可编辑控件。
void main() {
  test('大小构成行只读、四类标签齐全、拿不到就不显示', () {
    final source = File('lib/project_page.dart').readAsStringSync();
    expect(source.contains("ValueKey('project-build-breakdown')"), isTrue);
    expect(source.contains("LocalBridge.call('apkBreakdown'"), isTrue);
    for (final label in ['引擎与模板', '内建 OCR', 'OpenCV', '你的工程']) {
      expect(source.contains(label), isTrue, reason: '缺少分类标签 ' + label);
    }
    expect(source.contains('大小构成（未压缩）：'), isTrue);
    expect(source.contains('return const SizedBox.shrink();'), isTrue,
        reason: '拿不到数据必须整行不显示');

    final start = source.indexOf('FutureBuilder<Map<String, dynamic>?>(');
    final end = source.indexOf("SelectableText('SHA-256");
    expect(start > 0 && end > start, isTrue);
    final block = source.substring(start, end);
    for (final forbidden in ['TextField', 'textField(', 'DropdownButton', 'Checkbox', 'Slider']) {
      expect(block.contains(forbidden), isFalse, reason: '只读行里不能出现 ' + forbidden);
    }
  });
}
