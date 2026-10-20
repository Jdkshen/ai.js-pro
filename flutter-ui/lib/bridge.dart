import 'dart:convert';
import 'dart:async';
import 'package:flutter/services.dart';

/// JSON over StringCodec keeps this frontend independent of obfuscated Java names.
class LocalBridge {
  static const channel =
      BasicMessageChannel<String>('autojs.local.bridge', StringCodec());

  static void Function()? onNativePackagingReady;
  static void Function()? onPendingAction;
  static final Stream<dynamic> logEvents =
      const EventChannel('autojs.local.logs').receiveBroadcastStream();
  static bool _notificationsInstalled = false;
  static void listenForNativePackaging() {
    if (_notificationsInstalled) return;
    _notificationsInstalled = true;
    channel.setMessageHandler((message) async {
      try {
        final value = jsonDecode(message ?? '{}');
        if (value is Map && value['event'] == 'nativePackagingReady') {
          onNativePackagingReady?.call();
        } else if (value is Map && value['event'] == 'pendingAction') {
          onPendingAction?.call();
        }
      } catch (_) {}
      return '{}';
    });
  }

  static Future<dynamic> call(String method,
      [Map<String, dynamic>? arguments]) async {
    // 导入选择器与 Shizuku 授权都要等用户操作，统一放宽；其余桥保持 20s。
    final slow = method.startsWith('import') ||
        method == 'shizukuEnableAccessibility' ||
        // 示例包按需下载最大约 9MB，20s 默认超时不够。
        method == 'sampleDownload';
    final reply = await channel
        .send(jsonEncode({'method': method, 'args': arguments ?? {}}))
        .timeout(slow
            ? const Duration(minutes: 5)
            : const Duration(seconds: 20));
    if (reply == null) throw StateError('Android 功能接口尚未连接');
    final value = jsonDecode(reply) as Map<String, dynamic>;
    if (value['ok'] != true)
      throw StateError(value['error']?.toString() ?? '操作失败');
    return value['data'];
  }

  static Future<Map<String, dynamic>> bootstrap() async {
    for (var attempt = 0; attempt < 12; attempt++) {
      try {
        return Map<String, dynamic>.from(await call('bootstrap'));
      } catch (_) {
        if (attempt == 11) rethrow;
      }
      await Future<void>.delayed(const Duration(milliseconds: 300));
    }
    throw StateError('无法连接 Android 运行引擎');
  }
}
