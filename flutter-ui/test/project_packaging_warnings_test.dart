import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

/// task-110：把「打包警告」搬进页面的源码钉。
void main() {
  test('打包提示块：存在、可折叠、空不显示、缺失要说出来', () {
    final source = File('lib/project_page.dart').readAsStringSync();
    expect(source.contains("ValueKey('project-build-warnings-'"), isTrue);
    expect(source.contains("'打包提示（'"), isTrue);
    expect(source.contains('打包提示：不可用（桥上没返回 warnings 字段'), isTrue,
        reason: '拿不到字段必须显式说明，不许静默');
    expect(source.contains("if (status == 'success')"), isTrue);
    expect(source.contains("(current['warnings'] as List).isEmpty"), isTrue);
    expect(source.contains('const SizedBox.shrink()'), isTrue, reason: '没有提示就不显示');

    final start = source.indexOf("ValueKey('project-build-warnings-'");
    final end = source.indexOf('打包提示：不可用（桥上没返回 warnings 字段', start);
    expect(start > 0 && end > start, isTrue);
    final block = source.substring(start, end);
    for (final forbidden in ['TextField', 'textField(', 'Checkbox', 'DropdownButton']) {
      expect(block.contains(forbidden), isFalse, reason: '提示块里不能出现 ' + forbidden);
    }
  });
}
