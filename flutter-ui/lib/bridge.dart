import 'dart:convert';
import 'dart:async';

import 'package:flutter/services.dart';

/// JSON over StringCodec keeps this frontend independent of obfuscated Java names.
class LocalBridge {
  static const channel = BasicMessageChannel<String>(
    'autojs.local.bridge',
    StringCodec(),
  );

  static void Function()? onNativePackagingReady;
  static void Function()? onPendingAction;
  static final Stream<dynamic> logEvents = const EventChannel(
    'autojs.local.logs',
  ).receiveBroadcastStream();
  static bool _notificationsInstalled = false;

  /// F2（task-48）：原生→Dart 的**状态推送**五类事件。
  ///
  /// 订阅方**只做数据刷新**（收到事件后重新调用对应桥方法拉数据），不要在这里改 UI 布局/导航；
  /// 事件载荷形如 {"event":"taskChanged","reason":"script_started","path":"main.js","state":"running"}。
  /// 节流由原生侧做（150ms 合并、同 key 只发一次），这里拿到的已经是合并后的结果。
  static final Stream<Map<String, dynamic>> taskChanged = _eventsOf('taskChanged');
  static final Stream<Map<String, dynamic>> accessibilityState = _eventsOf(
    'accessibilityState',
  );
  static final Stream<Map<String, dynamic>> debugConnectionState = _eventsOf(
    'debugConnectionState',
  );
  static final Stream<Map<String, dynamic>> prefsChanged = _eventsOf(
    'prefsChanged',
  );
  static final Stream<Map<String, dynamic>> processResumed = _eventsOf(
    'processResumed',
  );

  static final Map<String, StreamController<Map<String, dynamic>>>
  _controllers = <String, StreamController<Map<String, dynamic>>>{};

  /// 五个事件名（与 docs/guides/Flutter本地桥API.md / tools/flutter-bridge-contract.mjs 对齐）。
  static const Set<String> pushedEventNames = <String>{
    'taskChanged',
    'accessibilityState',
    'debugConnectionState',
    'prefsChanged',
    'processResumed',
  };

  static Stream<Map<String, dynamic>> _eventsOf(String name) {
    return _controllers
        .putIfAbsent(
          name,
          () => StreamController<Map<String, dynamic>>.broadcast(),
        )
        .stream;
  }

  static void _dispatch(Map<String, dynamic> value) {
    final event = value['event'];
    if (event is! String) return;
    final controller = _controllers[event];
    // 未知事件忽略（原生侧可能先于 Dart 增加事件）。
    controller?.add(value);
  }

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
        if (value is Map) {
          _dispatch(Map<String, dynamic>.from(value));
        }
      } catch (_) {}
      return '{}';
    });
  }

  static Future<dynamic> call(
    String method, [
    Map<String, dynamic>? arguments,
  ]) async {
    final value = await _send(method, arguments);
    if (value['ok'] != true) {
      // P3-1（task-162）：message 是权威字段（error 只是兼容镜像）⇒ message 优先，
      // 缺 message 再退回 error，最后才是「操作失败」。
      throw StateError(value['message']?.toString() ??
          value['error']?.toString() ??
          '操作失败');
    }
    return value['data'];
  }

  /// P3-1（task-162）：返回整张信封（ok / reasonCode / message / data / empty / paging）。
  ///
  /// 与 call 的区别：不把失败折叠成异常、也不丢信封字段 —— 需要区分
  /// 「失败（ok=false + reasonCode + message）」「空结果（ok=true + empty=true）」
  /// 「成功（ok=true + data）」的调用点用它。纯新增：call 与既有调用点行为不变。
  static Future<Map<String, dynamic>> callEnvelope(
    String method, [
    Map<String, dynamic>? arguments,
  ]) async {
    final value = await _send(method, arguments);
    if (value['ok'] == null) {
      // 缺 ok ⇒ 不是本契约的回包（老桥/异常实现）⇒ 按失败处理，但保留原始字段。
      return <String, dynamic>{
        ...value,
        'ok': false,
        'message': value['message']?.toString() ??
            value['error']?.toString() ??
            '操作失败',
      };
    }
    return value;
  }

  /// 发一帧并解成 Map（call 与 callEnvelope 共用；行为与原来逐字一致）。
  static Future<Map<String, dynamic>> _send(
    String method, [
    Map<String, dynamic>? arguments,
  ]) async {
    final slow =
        method.startsWith('import') ||
        method == 'shizukuEnableAccessibility' ||
        method == 'toolkitCall' ||
        // 示例包按需下载最大约 9MB，20s 默认超时不够（原注释，Lead 审阅时补回）。
        method == 'share' ||
        method == 'shareFile';
    final reply = await channel
        .send(jsonEncode({'method': method, 'args': arguments ?? {}}))
        .timeout(
          slow ? const Duration(minutes: 5) : const Duration(seconds: 20),
        );
    if (reply == null) throw StateError('Android 功能接口尚未连接');
    return jsonDecode(reply) as Map<String, dynamic>;
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
