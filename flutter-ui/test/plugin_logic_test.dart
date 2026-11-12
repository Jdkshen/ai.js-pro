import 'package:flutter_test/flutter_test.dart';

import '../lib/plugin_logic.dart';

/// P1-1（task-159）：业务规则的**行为断言**（不再只靠读源码字符串）。
/// 这些规则原先写在 _CatalogPageState 里，只能靠「源码包含某字面量」的钉子间接验证。
void main() {
  test('categoryTailOf：层级路径只留末段，没有斜杠原样，空段回退', () {
    expect(categoryTailOf('Node 引擎/Node.js/Node.js中使用UI'), 'Node.js中使用UI');
    expect(categoryTailOf('Node 引擎/Node.js/Vue'), 'Vue');
    expect(categoryTailOf('Vue'), 'Vue');
    expect(categoryTailOf('  Vue  '), 'Vue');
    expect(categoryTailOf(''), '');
    expect(categoryTailOf('a/'), 'a/');
  });

  test('resourceEngineLabelOf：结构化字段优先，其次文字提示，认不出为空', () {
    expect(resourceEngineLabelOf(engine: 'Node'), 'Node');
    expect(resourceEngineLabelOf(language: 'JavaScript'), 'JavaScript');
    expect(resourceEngineLabelOf(engine: 'Node', language: 'X'), 'Node');
    expect(resourceEngineLabelOf(hintText: 'ImageProc 本地示例工程'), '');
    expect(resourceEngineLabelOf(hintText: 'Node.js 示例'), 'Node.js');
    expect(resourceEngineLabelOf(hintText: 'QuickJS 示例'), 'QuickJS');
    expect(resourceEngineLabelOf(hintText: 'Rhino 示例'), 'Rhino');
  });

  test('manifestStateOf / looksInstalledOf：读结构化 state，不按文案猜', () {
    expect(manifestStateOf({'state': 'update'}), 'update');
    expect(manifestStateOf({'detail': '已装 versionCode 16'}), '');
    expect(manifestStateOf(null), '');

    expect(looksInstalledOf(installed: false, state: 'install'), isFalse);
    expect(looksInstalledOf(installed: true, state: 'install'), isTrue);
    expect(looksInstalledOf(installed: false, state: 'update'), isTrue);
    expect(looksInstalledOf(installed: false, state: 'upToDate'), isTrue);
    expect(looksInstalledOf(installed: false, state: ''), isFalse, reason: '无结构信息不猜');
  });

  test('hasUpdateOf：state=update 才算；否则仅「清单 code > 本机 code」；缺 code 不猜', () {
    expect(hasUpdateOf(installed: true, state: 'update', remoteCode: 7, localCode: 16), isTrue);
    expect(hasUpdateOf(installed: true, state: 'install', remoteCode: 9, localCode: 1), isFalse);
    expect(hasUpdateOf(installed: true, state: 'upToDate', remoteCode: 9, localCode: 1), isFalse);
    expect(hasUpdateOf(installed: true, state: '', remoteCode: 16, localCode: 7), isTrue);
    // task-145 的真 bug：本机 code 更高（1.3.1/16 vs 清单 1.1.4/7）不该提示更新
    expect(hasUpdateOf(installed: true, state: '', remoteCode: 7, localCode: 16), isFalse);
    expect(hasUpdateOf(installed: true, state: '', remoteCode: 7, localCode: null), isFalse,
        reason: '缺 code 不猜');
    expect(hasUpdateOf(installed: false, state: '', remoteCode: 9, localCode: 1), isFalse);
  });

  test('isUpToDateOf：本机 code >= 清单 code（>= 而非 >）', () {
    expect(isUpToDateOf(installed: true, state: '', remoteCode: 7, localCode: 16), isTrue);
    expect(isUpToDateOf(installed: true, state: '', remoteCode: 16, localCode: 16), isTrue);
    expect(isUpToDateOf(installed: true, state: '', remoteCode: 16, localCode: 7), isFalse);
    expect(isUpToDateOf(installed: false, state: '', remoteCode: 7, localCode: 16), isFalse);
    expect(isUpToDateOf(installed: true, state: 'upToDate'), isTrue);
  });

  test('sizeTextOf：与页面 sizeLabel 同口径（>=1MiB 用 MB 一位小数）', () {
    expect(sizeTextOf(1024 * 1024 * 17.5), '17.5 MB');
    expect(sizeTextOf(2048), '2 KB');
    expect(sizeTextOf(0), '');
    expect(sizeTextOf(-5), '');
    expect(sizeTextOf(null), '');
  });

  test('mapText / mapNumber：缺失与空值都回退', () {
    expect(mapText({'a': 'x'}, 'a'), 'x');
    expect(mapText({'a': ''}, 'a', 'fb'), 'fb');
    expect(mapText(null, 'a', 'fb'), 'fb');
    expect(mapText({'a': 5}, 'a'), '5');
    expect(mapNumber({'v': 7}, 'v'), 7);
    expect(mapNumber({'v': 'x'}, 'v'), isNull);
    expect(mapNumber(null, 'v'), isNull);
  });
}
