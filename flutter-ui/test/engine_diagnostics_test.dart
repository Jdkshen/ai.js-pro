import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import '../lib/engine_diagnostics.dart';

void main() {
  Future<void> show(
    WidgetTester tester,
    Object? data, {
    bool stale = false,
  }) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: SingleChildScrollView(
            child: EngineDiagnosticsPanel(data: data, stale: stale),
          ),
        ),
      ),
    );
    await tester.tap(find.text('引擎诊断'));
    await tester.pumpAndSettle();
  }

  final known = {
    'schemaVersion': 1,
    'nodePool': {'capacity': 3, 'busy': 0, 'scope': 'current-process'},
    'engines': [
      {'engine': 'nodejs', 'available': true, 'behaviorStatus': 'unverified'},
    ],
  };

  testWidgets('availability never claims behavior compatibility', (
    tester,
  ) async {
    await show(tester, known);
    expect(find.text('Node 24：已接入'), findsOneWidget);
    expect(find.text('接口行为：尚未逐项验证'), findsOneWidget);
    expect(find.text('当前诊断无法确认所有进程的脚本占用。'), findsOneWidget);
    expect(find.text('Node 运行槽：0 / 3'), findsOneWidget);
  });

  testWidgets('unknown occupancy stays unknown', (tester) async {
    await show(tester, {
      'schemaVersion': 1,
      'nodePool': {'capacity': 3},
    });
    expect(find.text('Node 运行槽：占用未确认 / 容量 3'), findsOneWidget);
    expect(find.text('Node 运行槽：0 / 3'), findsNothing);
  });

  testWidgets('stale counters are not rendered as current zero', (
    tester,
  ) async {
    await show(tester, known, stale: true);
    expect(find.text('Node 运行槽：占用未确认 / 容量 3'), findsOneWidget);
    expect(find.text('Node 24：状态未确认'), findsOneWidget);
    expect(find.text('Node 运行槽：0 / 3'), findsNothing);
  });

  testWidgets('old or malformed responses fail to unavailable', (tester) async {
    await show(tester, 'invalid');
    expect(find.text('诊断暂不可用'), findsOneWidget);
    expect(find.textContaining('旧版宿主需要更新'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('failure evidence is labeled by scope', (tester) async {
    await show(tester, {
      ...known,
      'lastFailure': {'engine': 'nodejs', 'type': 'PoolBusyException'},
    });
    expect(
      find.text('本进程 MCP 最近失败：Node 24 · PoolBusyException'),
      findsOneWidget,
    );
  });
}
