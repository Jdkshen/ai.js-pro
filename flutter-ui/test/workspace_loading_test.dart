import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import '../lib/bridge.dart';
import '../lib/glass.dart';
import '../lib/main.dart';

const browserKey = ValueKey('browser');

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  late List<String> calls;
  Completer<Map<String, dynamic>>? preferences;

  setUp(() {
    calls = [];
    preferences = null;
    GlassRefraction.preference = false;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockDecodedMessageHandler<String>(LocalBridge.channel, (message) async {
      final request = jsonDecode(message!) as Map;
      final method = request['method'] as String;
      calls.add(method);
      final dynamic data;
      switch (method) {
        case 'browserPreferences':
          data = preferences == null ? <String, dynamic>{} : await preferences!.future;
          break;
        case 'sampleLibrary':
        case 'list':
        case 'importRecords':
          data = <dynamic>[];
          break;
        case 'bootstrap':
          data = {'scripts': '/scripts', 'samples': '/samples', 'dark': false};
          break;
        case 'deviceState':
          data = {'storageGranted': true, 'overlayGranted': true, 'accessibilityEnabled': true};
          break;
        case 'localCatalog':
          data = {'items': <dynamic>[]};
          break;
        case 'tasks':
          data = {'running': <dynamic>[], 'scheduled': <dynamic>[]};
          break;
        case 'nativePackagingRequest':
        case 'pendingAction':
        case 'drawerPreferences':
        case 'settings':
          data = <String, dynamic>{};
          break;
        case 'fileClipboard':
          data = {'paths': <String>[]};
          break;
        default:
          throw StateError('Unexpected bridge request: $method');
      }
      return jsonEncode({'ok': true, 'data': data});
    });
  });

  tearDown(() {
    GlassRefraction.reset();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockDecodedMessageHandler<String>(LocalBridge.channel, null);
  });

  Future<void> show(WidgetTester tester, {bool active = true, int revision = 0}) =>
      tester.pumpWidget(MaterialApp(home: Scaffold(body: FileBrowser(
          key: browserKey, root: '/samples', title: '示例', readOnly: true,
          active: active, revision: revision))));

  Future<void> close(WidgetTester tester) => tester.pumpWidget(const SizedBox());

  testWidgets('first list waits for preferences and runs exactly once', (tester) async {
    preferences = Completer<Map<String, dynamic>>();
    await show(tester);
    await tester.pump(const Duration(milliseconds: 100));
    expect(calls.where((x) => x == 'list'), isEmpty);
    preferences!.complete({'descending': true});
    await tester.pumpAndSettle();
    expect(calls.where((x) => x == 'list').length, 1);
    expect(calls.where((x) => x == 'sampleLibrary').length, 1);
    await close(tester);
  });

  testWidgets('hidden pages defer scans and multiple revisions until activation', (tester) async {
    await show(tester, active: false);
    await tester.pump(const Duration(seconds: 3));
    await show(tester, active: false, revision: 1);
    await show(tester, active: false, revision: 2);
    expect(calls.where((x) => x == 'list'), isEmpty);
    expect(calls.where((x) => x == 'sampleLibrary'), isEmpty);
    await show(tester, revision: 2);
    await tester.pumpAndSettle();
    expect(calls.where((x) => x == 'list').length, 1);
    await close(tester);
  });

  testWidgets('an empty sample library is cached across automatic polls', (tester) async {
    await show(tester);
    await tester.pumpAndSettle();
    for (var i = 0; i < 3; i++) {
      await tester.pump(const Duration(seconds: 2));
      await tester.pumpAndSettle();
    }
    expect(calls.where((x) => x == 'list').length, 4);
    expect(calls.where((x) => x == 'sampleLibrary').length, 1);
    await tester.tap(find.byTooltip('刷新文件'));
    await tester.pumpAndSettle();
    expect(calls.where((x) => x == 'sampleLibrary').length, 2,
        reason: 'explicit refresh must still discover new downloadable samples');
    await close(tester);
  });

  testWidgets('activation before preferences finish still makes only one scan', (tester) async {
    preferences = Completer<Map<String, dynamic>>();
    await show(tester, active: false);
    await show(tester);
    expect(calls.where((x) => x == 'list'), isEmpty);
    preferences!.complete({});
    await tester.pumpAndSettle();
    expect(calls.where((x) => x == 'list').length, 1);
    await close(tester);
  });

  testWidgets('brand cover mounts workspace immediately and blocks early taps', (tester) async {
    var builds = 0, taps = 0;
    await tester.pumpWidget(MaterialApp(home: SplashGate(
        hold: const Duration(seconds: 1),
        child: Builder(builder: (_) {
          builds++;
          return Center(child: TextButton(onPressed: () => taps++, child: const Text('workspace')));
        }))));
    expect(builds, 1, reason: 'workspace initialization must overlap the cover');
    await tester.tap(find.text('workspace'), warnIfMissed: false);
    expect(taps, 0);
    await tester.pump(const Duration(seconds: 1));
    await tester.pumpAndSettle();
    await tester.tap(find.text('workspace'));
    expect(taps, 1);
    expect(builds, 1, reason: 'revealing the workspace must retain its state');
    await close(tester);
  });

  testWidgets('tab data loads after the animation and skips intermediate pages', (tester) async {
    await tester.pumpWidget(MaterialApp(home: Home(onTheme: (_) {})));
    await tester.pumpAndSettle();
    calls.clear();
    Finder tab(String label) => find.byWidgetPredicate((widget) =>
        widget is Semantics && widget.properties.label == label);
    await tester.tap(tab('资源'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 100));
    expect(calls.where((x) => x == 'list' || x == 'localCatalog'), isEmpty,
        reason: 'intermediate example page and animation midpoint must not scan');
    await tester.pumpAndSettle();
    expect(calls.where((x) => x == 'localCatalog').length, 1);
    expect(calls.where((x) => x == 'list'), isEmpty);
    await close(tester);
  });
}
