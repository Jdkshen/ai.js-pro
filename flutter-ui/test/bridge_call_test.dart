import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';

import '../lib/bridge.dart';
import '../lib/bridge_call.dart';

/// toString() 会抛的对象：用于验证 readable / run 的「永不抛」无例外。
class _ThrowingToString {
  @override
  String toString() => throw StateError('toString 炸了');
}

/// P2-3（task-160）：BridgeCall 的行为断言（成功 / 桥抛异常 / 回包 ok:false）。
/// 核心契约：失败绝不静默 —— ok=false 时 error 一定非空且可读。
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  void mockReply(Map<String, dynamic> Function(String method) body) {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockDecodedMessageHandler<String>(LocalBridge.channel, (message) async {
      final request = jsonDecode(message!) as Map;
      return jsonEncode(body(request['method'] as String));
    });
  }

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockDecodedMessageHandler<String>(LocalBridge.channel, null);
  });

  test('成功：ok=true、data 可读、error 为空', () async {
    mockReply((method) => {'ok': true, 'data': {'running': [], 'scheduled': []}});
    final reply = await BridgeCall.run('tasks');
    expect(reply.ok, isTrue);
    expect(reply.error, isEmpty);
    expect(reply.data['running'], isA<List>());
  });

  test('回包 ok:false：ok=false + 用桥给的原因；缺原因给兜底', () async {
    mockReply((method) => method == 'withReason'
        ? {'ok': false, 'error': '清单不可用'}
        : {'ok': false});
    final withReason = await BridgeCall.run('withReason');
    expect(withReason.ok, isFalse);
    expect(withReason.error.contains('清单不可用'), isTrue);
    final noReason = await BridgeCall.run('noReason');
    expect(noReason.ok, isFalse);
    // 桥层可能在 ok:false 时直接抛（此时由 run 收敛成可读 error）⇒ 只钉「非静默」这一点
    expect(noReason.error, isNotEmpty, reason: '失败必须有可读原因（不静默）');
  });

  test('readable：连 toString 都抛也不抛（永不抛无例外）；run 同样不抛', () async {
    final bad = _ThrowingToString();
    expect(BridgeCall.readable(bad), isNotEmpty,
        reason: 'toString 抛时必须给兜底文案，而不是把异常抛出去');
    expect(BridgeCall.readable(bad), '未知错误');

    // 桥抛这个「toString 会抛」的对象 ⇒ run 必须收敛成 ok=false + 可读原因
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockDecodedMessageHandler<String>(LocalBridge.channel, (message) async {
      throw bad;
    });
    final reply = await BridgeCall.run('boom');
    expect(reply.ok, isFalse);
    expect(reply.error, isNotEmpty);
  });

  test('readable：前缀清洗 + 截断 + 空值兜底', () {
    expect(BridgeCall.readable(StateError('x')), 'x');
    expect(BridgeCall.readable(Exception('y')), 'y');
    expect(BridgeCall.readable(''), '未知错误');
    final long = 'a' * 200;
    expect(BridgeCall.readable(long).endsWith('\u2026'), isTrue);
  });
}
