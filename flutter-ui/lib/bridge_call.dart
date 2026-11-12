// P2-3（task-160）：桥调用的**唯一管道** —— 统一「失败可读、不静默」，
// 收敛各页面自己写的 try/catch + readable 前缀清洗 + 「ok==false 却不报错」这类样板。
//
// 约定（这就是本类要钉住的契约）：
//   1) 桥抛异常   ⇒ ok=false，error = readable(异常)（**绝不静默**）；
//   2) 桥回 {ok:false, error/message} ⇒ ok=false，error = 桥给的原因（缺失则给兜底文案）；
//   3) 其它       ⇒ ok=true，raw = 原始返回（调用点既有判定逻辑**不变**）。
import 'bridge.dart';

/// 桥调用结果。ok=false 时 [error] 一定非空（可读中文）。
class BridgeReply {
  final bool ok;
  final Object? raw;
  final String error;

  const BridgeReply._(this.ok, this.raw, this.error);

  /// 回包是 Map 时转成 Map（否则空 Map）。
  Map<String, dynamic> get data =>
      raw is Map ? Map<String, dynamic>.from(raw as Map) : <String, dynamic>{};

  /// 回包自带 ok==true（有些桥把成功写在包里，而不是抛异常）。
  bool get dataOk => raw is Map && (raw as Map)['ok'] == true;
}

class BridgeCall {
  /// 调用桥。**不会抛出**：异常与「ok:false」都收敛成 ok=false + 可读原因，
  /// 由调用点决定怎么显示（页面各自的 notify / DegradedLine / operationError 均可）。
  static Future<BridgeReply> run(String method,
      [Map<String, dynamic> args = const {}]) async {
    try {
      final value = await LocalBridge.call(method, args);
      if (value is Map && value['ok'] == false) {
        final reason = (value['error'] ?? value['message'] ?? '').toString().trim();
        return BridgeReply._(
            false,
            value,
            reason.isEmpty
                ? '桥调用失败（$method 未提供原因）'
                : reason);
      }
      return BridgeReply._(true, value, '');
    } catch (error) {
      return BridgeReply._(false, null, readable(error));
    }
  }

  /// 与 [run] **同一管道**，但失败时抛可读 `StateError`（成功返回原始回包，与 `LocalBridge.call` 一致）。
  ///
  /// 给「调用点在 try/catch 里自己 `readable(e)`」的既有代码做**纯改名迁移**：
  /// 文案逐字不变 —— `LocalBridge.call` 在 `ok != true` 时本来就抛 `StateError(error)`（bridge.dart:106），
  /// 而 `readable` 幂等（161 字再洗一次仍是 161 字）⇒ `readable(StateError(reply.error)) == reply.error` ✓。
  static Future<dynamic> value(String method,
      [Map<String, dynamic> args = const {}]) async {
    final reply = await run(method, args);
    if (!reply.ok) throw StateError(reply.error);
    return reply.raw;
  }

  /// 异常 → 一行可读中文（去 StateError/Exception 前缀并截断）。
  /// **保证不抛**（B 修 / task-161）：连 `error.toString()` 都抛时返回固定兜底文案。
  static String readable(Object error) {
    try {
      return _readableInner(error);
    } catch (_) {
      return '未知错误';
    }
  }

  static String _readableInner(Object error) {
    var message = error.toString().trim();
    if (message.startsWith('Bad state: ')) {
      message = message.substring('Bad state: '.length);
    } else if (message.startsWith('Exception: ')) {
      message = message.substring('Exception: '.length);
    }
    if (message.length > 160) {
      message = message.substring(0, 160) + '…';
    }
    return message.isEmpty ? '未知错误' : message;
  }
}
