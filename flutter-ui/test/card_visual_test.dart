import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

/// task-156（V1–V4）源码钉。
void main() {
  test('V1–V4：卡片对齐、去重复副标题、元信息单行、状态行短文案', () {
    final src = File('lib/catalog_page.dart').readAsStringSync();

    // V1 图标与首行对齐
    expect(src.contains('crossAxisAlignment: CrossAxisAlignment.start,'), isTrue);
    // V2 不再显示 summary 作副标题；改用引擎/语言且可为空
    expect(src.contains('if (resourceEngineLabel(item).isNotEmpty)'), isTrue);
    expect(src.contains('String resourceEngineLabel(Map item)'), isTrue);
    expect(src.contains('Text(summary(item),\n                            maxLines: 2,'), isFalse);
    // V3 元信息单行
    expect(src.contains('// V3（task-156）：元信息单行不换行'), isTrue);
    // V4 短文案含版本号
    expect(src.contains("nativeDetail.length <= 16"), isTrue);
    expect(src.contains("return '已装 v' + versionOf(item)"), isTrue);

    // task-157：category 层级路径只显示最后一段
    expect(src.contains('String categoryTail(String value)'), isTrue);
    expect(src.contains("Text(categoryTail(text(item, 'category')),"), isTrue);
  });
}
