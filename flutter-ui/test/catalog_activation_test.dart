import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import '../lib/bridge.dart';
import '../lib/main.dart';

const _catalogKey = ValueKey('catalog-under-test');

Map<String, dynamic> _item(String name) => <String, dynamic>{
  'id': name,
  'name': name,
  'summary': '本地示例工程',
  'category': 'Node',
  'local': true,
  'kind': 'project',
  'directory': true,
  'assetPath': 'sample/$name',
  'size': 1024,
};

Future<void> _show(
  WidgetTester tester,
  Future<List<dynamic>> Function(bool) loader, {
  bool active = true,
  bool plugin = false,
  int revision = 0,
}) {
  return tester.pumpWidget(
    MaterialApp(
      home: Scaffold(
        body: CatalogPage(
          key: _catalogKey,
          plugin: plugin,
          revision: revision,
          active: active,
          loader: loader,
        ),
      ),
    ),
  );
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  late List<String> bridgeCalls;
  setUp(() {
    bridgeCalls = <String>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockDecodedMessageHandler<String>(LocalBridge.channel, (
          message,
        ) async {
          final method = (jsonDecode(message!) as Map)['method'] as String;
          bridgeCalls.add(method);
          final Object data;
          switch (method) {
            case 'importRecords':
              data = <dynamic>[];
              break;
            case 'pluginStates':
              data = <String, dynamic>{'disabled': <String>[]};
              break;
            default:
              throw StateError('Unexpected platform request: $method');
          }
          return jsonEncode(<String, dynamic>{'ok': true, 'data': data});
        });
  });
  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockDecodedMessageHandler<String>(LocalBridge.channel, null);
  });

  testWidgets(
    'offscreen resource and plugin pages perform no scans or platform reads',
    (tester) async {
      var scans = 0;
      Future<List<dynamic>> loader(bool plugin) async {
        scans++;
        return <dynamic>[];
      }

      await _show(tester, loader, active: false);
      await tester.pump();
      expect(scans, 0);
      expect(bridgeCalls, isEmpty);
      await _show(tester, loader, active: false, plugin: true);
      await tester.pump();
      expect(scans, 0);
      expect(bridgeCalls, isEmpty);
    },
  );

  testWidgets(
    'activation loads once and cached tab switches retain the catalogue',
    (tester) async {
      var scans = 0;
      Future<List<dynamic>> loader(bool plugin) async {
        scans++;
        return <dynamic>[_item('cached-project')];
      }

      await _show(tester, loader, active: false);
      await _show(tester, loader);
      await tester.pumpAndSettle();
      expect(scans, 1);
      expect(find.text('cached-project'), findsOneWidget);
      for (var i = 0; i < 3; i++) {
        await _show(tester, loader, active: false);
        await _show(tester, loader);
        await tester.pumpAndSettle();
      }
      expect(scans, 1);
      expect(find.text('cached-project'), findsOneWidget);
      await tester.tap(find.byTooltip('刷新内置目录'));
      await tester.pumpAndSettle();
      expect(scans, 2, reason: 'explicit refresh must still read fresh data');
    },
  );

  testWidgets(
    'a hidden revision change is loaded only when that page is activated',
    (tester) async {
      var scans = 0;
      Future<List<dynamic>> loader(bool plugin) async {
        scans++;
        return <dynamic>[_item('revision-$scans')];
      }

      await _show(tester, loader);
      await tester.pumpAndSettle();
      await _show(tester, loader, active: false, revision: 1);
      await tester.pump();
      expect(scans, 1);
      await _show(tester, loader, revision: 1);
      await tester.pumpAndSettle();
      expect(scans, 2);
      expect(find.text('revision-2'), findsOneWidget);
    },
  );

  testWidgets('pending first load coalesces refresh and tab re-entry', (
    tester,
  ) async {
    var scans = 0;
    final pending = Completer<List<dynamic>>();
    Future<List<dynamic>> loader(bool plugin) {
      scans++;
      return pending.future;
    }

    await _show(tester, loader);
    await tester.tap(find.byTooltip('刷新内置目录'));
    await tester.pump();
    await _show(tester, loader, active: false);
    await _show(tester, loader);
    expect(scans, 1);
    pending.complete(<dynamic>[_item('completed-once')]);
    await tester.pumpAndSettle();
    expect(find.text('completed-once'), findsOneWidget);
    expect(scans, 1);
  });

  testWidgets('an older request cannot overwrite a newer revision', (
    tester,
  ) async {
    final requests = <Completer<List<dynamic>>>[];
    Future<List<dynamic>> loader(bool plugin) {
      final pending = Completer<List<dynamic>>();
      requests.add(pending);
      return pending.future;
    }

    await _show(tester, loader);
    await _show(tester, loader, revision: 1);
    expect(requests.length, 2);
    requests[1].complete(<dynamic>[_item('fresh-revision')]);
    await tester.pumpAndSettle();
    requests[0].complete(<dynamic>[_item('obsolete-revision')]);
    await tester.pumpAndSettle();
    expect(find.text('fresh-revision'), findsOneWidget);
    expect(find.text('obsolete-revision'), findsNothing);
  });

  testWidgets('a failed scan remains retryable', (tester) async {
    var scans = 0;
    Future<List<dynamic>> loader(bool plugin) async {
      if (++scans == 1) throw StateError('scan failed');
      return <dynamic>[_item('retried-project')];
    }

    await _show(tester, loader);
    await tester.pumpAndSettle();
    expect(find.textContaining('scan failed'), findsOneWidget);
    await tester.tap(find.text('重试'));
    await tester.pumpAndSettle();
    expect(scans, 2);
    expect(find.text('retried-project'), findsOneWidget);
  });
}
