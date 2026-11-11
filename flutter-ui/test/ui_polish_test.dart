import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

/// task-151（P0/P1 UI 打磨）源码钉：列表底部留白、任务页空态与 FAB、示例图标区分。
void main() {
  test('P0/P1：底部留白 ≥ FAB+16、任务空态、无任务不显示停止 FAB、示例图标区分', () {
    final task = File('lib/task_page.dart').readAsStringSync();
    final browser = File('lib/browser.dart').readAsStringSync();
    final catalog = File('lib/catalog_page.dart').readAsStringSync();

    // P0-1 列表底部留白
    expect(browser.contains('padding: const EdgeInsets.only(bottom: 120)'), isTrue,
        reason: '浏览器列表要给 FAB 留出空间');
    expect(catalog.contains('padding: const EdgeInsets.fromLTRB(6, 0, 6, 120)'), isTrue,
        reason: '示例列表要给 FAB 留出空间');

    // P0-2 任务页空态 + FAB
    expect(task.contains("ValueKey('task-empty-all')"), isTrue);
    expect(task.contains('暂无运行中的任务'), isTrue);
    expect(task.contains('运行脚本后会出现在这里'), isTrue);
    expect(
        task.contains('(loaded && active.isEmpty && pending.isEmpty) ? null : Semantics('),
        isTrue,
        reason: '没有任务可停时不该显示含义不明的 X FAB');

    // P1-4 示例图标：缩小 + 按引擎/语言区分
    expect(catalog.contains('Icon(resourceIcon(item)'), isTrue);
    expect(catalog.contains('size: 20, // task-151'), isTrue);
    expect(catalog.contains('IconData resourceIcon(Map item)'), isTrue);
    for (final engine in ['node', 'quickjs', 'rhino']) {
      expect(catalog.contains("hint.contains('" + engine + "')"), isTrue,
          reason: '缺少引擎图标分支：' + engine);
    }
  });
}
