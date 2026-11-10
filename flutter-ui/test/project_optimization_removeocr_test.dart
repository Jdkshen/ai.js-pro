import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

/// task-108：removeOcr 的 round-trip 复查（默认不勾/勾上写 true/取消勾选删键）+ 文案口径统一。
void main() {
  test('removeOcr：默认不勾、勾上写 true、取消勾选删键、口径统一到产物', () {
    final source = File('lib/project_page.dart').readAsStringSync();

    // 文案：主口径 = 产物；未压缩作为补充说明（同一屏里两种口径都写清单位）
    expect(source.contains("'removeOcr': '移除内建 OCR（产物少约 7.8 MB）'"), isTrue);
    expect(source.contains('产物少约 7.8 MB（未压缩约 17 MB）'), isTrue);
    expect(source.contains("'disabled': '不包含 Node.js 运行库（产物少约 1.4 MB）'"), isTrue);

    // 取消勾选 = 删键（避免「没勾过的工程被写进 removeOcr=false」）
    expect(source.contains('next.remove(\'removeOcr\');'), isTrue,
        reason: '取消勾选必须删键，而不是写 false');
    expect(source.contains("if (entry.key == 'removeOcr' && value != true)"), isTrue,
        reason: '删键只针对 removeOcr，既有 4 键行为不变');
    expect(source.contains('next[entry.key] = value == true;'), isTrue,
        reason: '其它键仍走原有 true/false 写法');

    // 默认不勾 + 没有该键不写回
    final mapStart = source.indexOf('Map<String, bool> optimization = {');
    final mapEnd = source.indexOf('};', mapStart);
    expect(mapStart > 0 && mapEnd > mapStart, isTrue);
    expect(source.substring(mapStart, mapEnd).contains('removeOcr'), isFalse);

    // 加载侧：只有文件里已有该键才回填
    expect(source.contains("if (rawOptimization.containsKey('removeOcr'))"), isTrue);
  });
}
