import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';

import '../lib/bridge.dart';

/// P3-1（task-162）：callEnvelope() 三态 + call() 的 message 优先。
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  void mock(Map<String, dynamic> envelope) {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockDecodedMessageHandler<String>(
            LocalBridge.channel, (message) async => jsonEncode(envelope));
  }

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockDecodedMessageHandler<String>(LocalBridge.channel, null);
  });

  test('失败：ok=false + reasonCode + message ⇒ 信封原样返回；call() 抛 message', () async {
    mock({
      'ok': false,
      'reasonCode': 'notFound',
      'message': '清单里没有这个插件',
      'error': '兼容镜像文案',
    });
    final envelope = await LocalBridge.callEnvelope('pluginInstall');
    expect(envelope['ok'], isFalse);
    expect(envelope['reasonCode'], 'notFound');
    expect(envelope['message'], '清单里没有这个插件');

    Object? thrown;
    try {
      await LocalBridge.call('pluginInstall');
    } catch (error) {
      thrown = error;
    }
    expect(thrown, isNotNull);
    expect(thrown.toString().contains('清单里没有这个插件'), isTrue);
    expect(thrown.toString().contains('兼容镜像文案'), isFalse);
  });

  test('空结果：ok=true + empty=true ⇒ empty 真能读到', () async {
    mock({'ok': true, 'empty': true});
    final envelope = await LocalBridge.callEnvelope('trashList');
    expect(envelope['ok'], isTrue);
    expect(envelope['empty'], isTrue);
    expect(envelope.containsKey('data'), isFalse);
    expect(await LocalBridge.call('trashList'), isNull);
  });

  test('成功：ok=true + data + paging ⇒ 两者都能读到；call() 仍只回 data', () async {
    mock({
      'ok': true,
      'data': {'items': [1, 2]},
      'paging': {'next': 'abc'},
    });
    final envelope = await LocalBridge.callEnvelope('localCatalog');
    expect((envelope['data'] as Map)['items'], [1, 2]);
    expect((envelope['paging'] as Map)['next'], 'abc');
    final data = await LocalBridge.call('localCatalog');
    expect((data as Map)['items'], [1, 2]);
  });

  test('call() 无 message 时退回 error；两者都缺才用「操作失败」', () async {
    mock({'ok': false, 'error': '只有 error'});
    Object? onlyError;
    try {
      await LocalBridge.call('x');
    } catch (error) {
      onlyError = error;
    }
    expect(onlyError.toString().contains('只有 error'), isTrue);

    mock({'ok': false});
    Object? noneAtAll;
    try {
      await LocalBridge.call('y');
    } catch (error) {
      noneAtAll = error;
    }
    expect(noneAtAll.toString().contains('操作失败'), isTrue);
  });
}
