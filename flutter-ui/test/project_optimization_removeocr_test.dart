import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

/// task-103：optimization.removeOcr 进「打包优化」列表 + Node.js 选项标收益 —— 源码钉。
///
/// 钉住四件事：① 开关存在且走**同一套** optimization 路径（不造第二份真值）；
/// ② 文案带可核收益；③ 默认不勾（初始 map 里**没有** removeOcr ⇒ 没有该键就不写回）；
/// ④ Node.js 的 disabled 描述标了省多少。
void main() {
  test('removeOcr 开关：存在/文案/默认 false/同一路径', () {
    final source = File('lib/project_page.dart').readAsStringSync();

    expect(source.contains("'removeOcr': '移除内建 OCR（省约 17 MB 未压缩）'"), isTrue,
        reason: '开关必须存在且标题带收益');
    expect(source.contains('project-optimization-'), isTrue,
        reason: '走既有 optimization 复选项渲染路径（ValueKey 前缀）');
    expect(source.contains("'optimization': optimization"), isTrue,
        reason: '保存必须走同一套 optimization 路径');
    expect(source.contains('libppocr / libmlkit'), isTrue,
        reason: '描述要点名剔除什么');
    expect(source.contains(r'$ocr'), isTrue, reason: '描述要写明 \$ocr 会点名提示未随包');

    // 默认不勾 + 没有该键不写回：初始 optimization map 里不能有 removeOcr
    final mapStart = source.indexOf('Map<String, bool> optimization = {');
    final mapEnd = source.indexOf('};', mapStart);
    expect(mapStart > 0 && mapEnd > mapStart, isTrue);
    final initialMap = source.substring(mapStart, mapEnd);
    expect(initialMap.contains('removeOcr'), isFalse,
        reason: '默认不勾：初始 map 不含 removeOcr ⇒ 加载时没有该键就不写回 project.json');
    for (final key in [
      'unusedResources',
      'removeOpenCv',
      'removeAccessibilityService',
      'obfuscateComponents'
    ]) {
      expect(initialMap.contains(key), isTrue, reason: '既有 4 个键不能被改动：' + key);
    }

    expect(source.contains("'disabled': '不包含 Node.js 运行库（产物少约 1.4 MB）'"), isTrue,
        reason: 'Node.js disabled 选项要标收益');

    // 回填：加载时只在文件有该键时才进 map（否则不写回）
    expect(source.contains("if (rawOptimization.containsKey('removeOcr'))"), isTrue,
        reason: 'removeOcr 必须在加载时按「文件里有没有」回填');
  });
}
