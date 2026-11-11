import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

/// task-155：抽屉「创建桌面快捷方式」入口的源码钉。
void main() {
  test('入口存在且带 key；桥名与三种文案齐全（不静默）', () {
    final src = File('lib/service_drawer.dart').readAsStringSync();

    expect(src.contains("ValueKey('drawer-create-shortcut')"), isTrue);
    expect(src.contains('Icons.add_to_home_screen_outlined'), isTrue);
    expect(src.contains("'创建桌面快捷方式'"), isTrue);
    expect(src.contains("LocalBridge.call('createLauncherShortcut', const {})"), isTrue);

    // 三种结果都要有可读文案（成功=请求、系统不支持、失败/异常）
    expect(src.contains('已请求添加到主屏幕：请在系统弹窗中确认'), isTrue);
    expect(src.contains('当前系统/桌面不支持快捷方式'), isTrue);
    expect(src.contains('创建桌面快捷方式失败'), isTrue);
    // 如实：requested 只代表请求，不代表已创建
    expect(src.contains("data['requested'] == true"), isTrue);
    expect(src.contains("data['supported'] == false"), isTrue);
  });
}
