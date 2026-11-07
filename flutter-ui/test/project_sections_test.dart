import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import '../lib/app_theme.dart';
import '../lib/bridge.dart';
import '../lib/glass.dart';
import '../lib/main.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  late Map<String, dynamic> config;
  late List<Map> writes;
  setUp(() {
    GlassRefraction.preference = false;
    config = {
      'name': '项目演示', 'packageName': 'com.example.preview', 'main': 'main.js',
      'versionName': '1.0', 'versionCode': 1, 'encryptLevel': 0,
      'abis': ['arm64-v8a'], 'assets': ['assets'],
      'features': {'nodejs': 'enabled'},
      'permissionConfig': {'manifestPermissions': ['android.permission.INTERNET'], 'requestListOnStartup': []}
    };
    writes = [];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockDecodedMessageHandler<String>(
      LocalBridge.channel, (message) async {
        final request = jsonDecode(message!) as Map;
        dynamic data;
        if (request['method'] == 'projectLoad' || request['method'] == 'projectSave') {
          if (request['method'] == 'projectSave') {
            writes.add(request['args']);
            config.addAll(Map<String, dynamic>.from(request['args']['value']));
          }
          data = {'path': '/scripts/demo', 'revision': 'r${writes.length}', 'config': config, 'engine': 'node'};
        } else if (request['method'] != 'projectBuildStatus') {
          throw StateError('Unexpected request: ${request['method']}');
        }
        return jsonEncode({'ok': true, 'data': data});
      });
  });
  tearDown(() {
    GlassRefraction.reset();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockDecodedMessageHandler<String>(LocalBridge.channel, null);
  });

  Future<void> show(WidgetTester tester, {bool dark = false}) async {
    await tester.binding.setSurfaceSize(const Size(393, 852));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    await tester.pumpWidget(MaterialApp(theme: AppTheme.create(dark), home: const ProjectPage(path: '/scripts/demo')));
    await tester.pumpAndSettle();
  }

  testWidgets('saving basic information preserves collapsed permissions and runtime options', (tester) async {
    await show(tester);
    expect(find.byKey(const ValueKey('project-permission-android.permission.INTERNET')), findsNothing);
    expect(find.byKey(const ValueKey('project-permission-android.permission.INTERNET'), skipOffstage: false), findsOneWidget);
    await tester.ensureVisible(find.byKey(const ValueKey('project-name')));
    await tester.enterText(find.byKey(const ValueKey('project-name')), '新项目名称');
    await tester.tap(find.text('保存配置'));
    await tester.pumpAndSettle();
    expect(writes.single['value'], {'name': '新项目名称'});
    expect(config['permissionConfig']['manifestPermissions'], ['android.permission.INTERNET']);
    expect(config['features'], {'nodejs': 'enabled'});
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('an invalid field in a collapsed group is still validated and revealed', (tester) async {
    await show(tester, dark: true);
    await tester.ensureVisible(find.byKey(const ValueKey('project-main')));
    await tester.enterText(find.byKey(const ValueKey('project-main')), '');
    await tester.ensureVisible(find.byKey(const ValueKey('project-section-脚本和加密')));
    await tester.tap(find.byKey(const ValueKey('project-section-脚本和加密')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('project-main')), findsNothing);
    await tester.tap(find.text('保存配置'));
    await tester.pumpAndSettle();
    expect(writes, isEmpty);
    expect(find.byKey(const ValueKey('project-main')), findsOneWidget);
    expect(find.text('请输入相对路径'), findsOneWidget);
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('log bridge switch is saved as launchConfig.logBridge', (tester) async {
    await show(tester);
    final section = find.byKey(const ValueKey('project-section-启动界面'), skipOffstage: false);
    for (var i = 0; i < 12 && section.evaluate().isEmpty; i++) {
      await tester.drag(find.byType(ListView).first, const Offset(0, -400));
      await tester.pumpAndSettle();
    }
    expect(section, findsOneWidget);
    await tester.ensureVisible(section);
    await tester.pumpAndSettle();
    await tester.tap(section);
    await tester.pumpAndSettle();
    final bridge = find.byKey(const ValueKey('project-logBridge'), skipOffstage: false);
    expect(bridge, findsOneWidget);
    expect(find.text('VSCode 日志', skipOffstage: false), findsOneWidget);
    expect(tester.widget<SwitchListTile>(bridge).value, isFalse);
    await tester.ensureVisible(bridge);
    await tester.pumpAndSettle();
    await tester.tap(bridge);
    await tester.pumpAndSettle();
    expect(tester.widget<SwitchListTile>(bridge).value, isTrue);
    await tester.tap(find.text('保存配置'));
    await tester.pumpAndSettle();
    expect(writes.single['value'], {'launchConfig': {'logBridge': true}});
    expect(config['launchConfig']['logBridge'], true);
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox());
  });
}
