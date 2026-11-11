import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

/// task-152（R2-1/R2-2/R2-3）源码钉：抽屉标题单行 + 副标题两行、开发者调试只留短状态、
/// 连接明细带 label 且用副标题样式、抽屉底部留白。
void main() {
  test('R2：抽屉与连接明细的排版约束', () {
    final drawer = File('lib/service_drawer.dart').readAsStringSync();
    final device = File('lib/device_page.dart').readAsStringSync();
    final conn = File('lib/computer_connection.dart').readAsStringSync();

    // R2-1：标题单行 + 副标题最多两行；开发者调试副标题不再拼协议描述
    expect(drawer.contains('task-152：副标题最多两行，超出省略'), isTrue);
    expect(drawer.contains("return '服务器"), isTrue);
    expect(drawer.contains('protocol == null ? '), isFalse,
        reason: '开发者调试副标题不该再拼协议描述（真机三行 ✗）');

    // R2-2：明细带 label + 统一副标题样式
    expect(conn.contains('协议类型 '), isTrue);
    expect(conn.contains('发现方式 '), isTrue);
    expect(conn.contains('插件版本 '), isTrue);
    expect(device.contains('task-152：统一成副标题样式'), isTrue);
    expect(device.contains('onSurfaceVariant'), isTrue);

    // R2-3：抽屉底部留白
    expect(drawer.contains('padding: const EdgeInsets.only(bottom: 28)'), isTrue);

    // 顺手：deprecated withOpacity 在这三个文件里必须已清
    for (final pair in [['service_drawer', drawer], ['device_page', device], ['computer_connection', conn]]) {
      expect(pair[1].contains('withOpacity('), isFalse, reason: pair[0] + ' 仍有 withOpacity');
    }
  });
}
