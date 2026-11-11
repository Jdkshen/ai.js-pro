import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

/// task-153（R3-1/R3-2）源码钉：文件/项目 ⋮ 菜单分组 + 分隔线 + 危险组（红、最后）+ 底部留白。
void main() {
  test('R3：菜单分组与危险操作置底', () {
    final src = File('lib/browser.dart').readAsStringSync();

    // R3-1：至少两条分隔线（项目/文件/详情/危险 分组）
    final dividers = RegExp(r'const Divider\(height: 1\)').allMatches(src).length;
    expect(dividers >= 3, isTrue, reason: '分组分隔线不足：' + dividers.toString());

    // 危险组：红色 + 在「复制路径/文件详情」之后
    final dangerAt = src.indexOf("// R3-1：危险操作单独一组、放最后、危险色");
    final detailsAt = src.indexOf("title: const Text('文件详情')");
    expect(dangerAt > 0 && detailsAt > 0 && dangerAt > detailsAt, isTrue,
        reason: '删除必须在详情之后（危险组最后）');
    expect(src.contains('color: Theme.of(context).colorScheme.error'), isTrue);

    // 文件组里不再直接出现「删除」的同级项（已挪到危险组）
    expect(src.contains("title: Text('删除',"), isTrue,
        reason: '删除已改成带危险色的样式');

    // R3-2：底部留白
    expect(src.contains('// R3-2：底部留白（长菜单不被系统栏压住）'), isTrue);
  });
}
