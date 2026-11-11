import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

/// task-134：插件统一目录——四态（未安装/下载安装/可更新/停用）+ 进度 + 可读失败原因。
///
/// 只读源码钉（页面不编译也能跑）：桥名集中、URL 不在 Dart 侧、失败不静默、卸载去向写清。
void main() {
  test('四态与下载：桥名集中、无 URL、失败可读、卸载去向写清', () {
    final source = File('lib/catalog_page.dart').readAsStringSync();

    // 桥名集中（与 W2 定稿对齐时只改这一处）
    expect(source.contains("pluginCatalogMethod = 'pluginCatalog'"), isTrue);
    expect(source.contains("pluginDownloadMethod = 'pluginDownloadAndInstall'"), isTrue);
    expect(source.contains("pluginProgressMethod = 'pluginDownloadProgress'"), isTrue);
    // 只传 id / fileName（URL 由原生解析）
    expect(source.contains("'id': id,"), isTrue);
    expect(source.contains("'fileName': text(manifest, 'file'),"), isTrue);
    // 只查**下载调用这一段**不得出现 url（页面上别处可能有 url 字段，那是既有逻辑）
    final callStart = source.indexOf('LocalBridge.call(pluginDownloadMethod');
    expect(callStart > 0, isTrue);
    final callEnd = source.indexOf('});', callStart);
    final callBlock = source.substring(callStart, callEnd);
    expect(callBlock.contains('url'), isFalse, reason: '下载调用不得传 url（源解析归原生）');
    expect(source.contains('http://') || source.contains('https://'), isFalse,
        reason: '页面不得出现 URL 字面量');

    // 四态文案
    expect(source.contains("'下载中 ' + percent + '%"), isTrue);
    expect(source.contains("'可更新到 v' + remote"), isTrue);
    expect(source.contains("'未安装 · 可下载 v'"), isTrue);
    expect(source.contains("'下载失败：' + failure"), isTrue);
    // 四类可读原因码
    for (final code in ["case 'network':", "case 'checksum':", "case 'package':", "case 'permission':"]) {
      expect(source.contains(code), isTrue, reason: '缺少原因码映射：' + code);
    }
    expect(source.contains('校验不符（sha256 与清单不一致，未安装）'), isTrue);
    expect(source.contains('停用后仍占空间，卸载请到系统应用管理'), isTrue);
    // 清单拿不到要显式说
    expect(source.contains("ValueKey('plugin-manifest-error')"), isTrue);
    // task-135：必须**首屏可见**（列表头），否则真机看不到
    expect(source.contains("ValueKey('plugin-manifest-error-header')"), isTrue);
    expect(source.contains("ValueKey('plugin-manifest-loading')"), isTrue);
    // task-137：失败态 = 无转圈 + 源 + 怎么办 + 重试
    expect(source.contains('pluginManifestError == null &&'), isTrue,
        reason: '清单失败时不得再显示永不结束的居中转圈');
    expect(source.contains("ValueKey('plugin-manifest-retry')"), isTrue);
    expect(source.contains("ValueKey('plugin-manifest-source')"), isTrue);
    expect(source.contains('当前更新源：'), isTrue);
    expect(source.contains('多半是更新源不可达'), isTrue);
    expect(source.contains('要公网下载请在「设置 → 更新源」'), isTrue);
    expect(source.contains("LocalBridge.call('updateSource')"), isTrue,
        reason: '源必须复用既有 updateSource 桥，不另造');
    expect(source.contains("throw StateError('清单读取超时（桥未响应）')"), isTrue,
        reason: '桥不响应必须落到可见降级，不能永远停在「加载中」');
    expect(source.contains('插件目录不可用：'), isTrue);
    // 下载按钮与重启提示
    expect(source.contains("ValueKey('plugin-download-action')"), isTrue);
    expect(source.contains("' · 需重启应用生效'"), isTrue);
    expect(source.contains("requiresRestart"), isTrue);
  });
}
