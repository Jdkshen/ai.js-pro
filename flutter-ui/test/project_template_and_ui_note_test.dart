import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

/// task-115：模板来源 + UI 脚本提示（只读展示）的源码钉。
void main() {
  test('模板来源行只读、label 为空不显示；UI 脚本提示只读', () {
    final source = File('lib/project_page.dart').readAsStringSync();

    expect(source.contains("ValueKey('project-build-template-source')"), isTrue);
    expect(source.contains("'打包模板：'"), isTrue);
    // label 为空/缺失 ⇒ 整行不显示（不猜）
    expect(
        source.contains("current['templateLabel'] is String &&") &&
            source.contains("(current['templateLabel'] as String).isNotEmpty"),
        isTrue,
        reason: '拿不到标签必须整行不显示');

    expect(source.contains("ValueKey('project-ui-script-note')"), isTrue);
    expect(
        source.contains('UI 脚本（含 ui.layout）在打包版里由宿主 Activity 运行；纯 auto 脚本无界面'),
        isTrue);
    // 文案不许写「必须装插件」这种没证据的话
    expect(source.contains('必须装插件'), isFalse);

    // 两处都只读：块里不能出现可编辑控件
    for (final key in ['project-build-template-source', 'project-ui-script-note']) {
      final at = source.indexOf(key);
      final from = at - 200 < 0 ? 0 : at - 200;
      final to = at + 200 > source.length ? source.length : at + 200;
      final block = source.substring(from, to);
      // 注意：只用「输入/勾选控件」当判据 —— 这两处旁边就有合法的下拉（加密等级），不能误伤
      for (final forbidden in ['TextField', 'textField(', 'Checkbox']) {
        expect(block.contains(forbidden), isFalse, reason: key + ' 附近不能出现 ' + forbidden);
      }
    }
  });
}
