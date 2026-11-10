import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

/// task-132（本轮只出原型）：钉住**复用契约**——插件页不得自己拼 URL，源解析只允许在原生侧。
///
/// 这是「不另造 URL 逻辑」的可执行版本：一旦有人在 Dart 里写死下载地址，这个测试立刻红。
void main() {
  test('插件页无 URL 字面量；原生侧源解析三方法仍在（复用契约）', () {
    final page = File('lib/catalog_page.dart').readAsStringSync();
    expect(page.contains('http://'), isFalse, reason: '插件页不得自己拼 URL（源解析归原生 PluginDownloads）');
    expect(page.contains('https://'), isFalse, reason: '插件页不得自己拼 URL（源解析归原生 PluginDownloads）');
    // 既有的三态相关桥必须还在（落地时复用，不另造）
    for (final call in ["'pluginStates'", "'pluginSetEnabled'", "'installPlugin'"]) {
      expect(page.contains(call), isTrue, reason: '现有桥不能被替换掉：' + call);
    }

    final java = File('../apps/app/src/main/java/com/jdkshen/aijspro/network/PluginDownloads.java');
    expect(java.existsSync(), isTrue, reason: '找不到 PluginDownloads.java（路径变了要同步这个测试）');
    final source = java.readAsStringSync();
    for (final method in ['pluginUrl(', 'defaultSourceUrl(', 'isExpectedPackage(']) {
      expect(source.contains(method), isTrue, reason: '复用契约方法不见了：' + method);
    }
  });
}
