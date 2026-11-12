// P3-1（task-164）：原因码常量表 + 未知码回退 message。
// 契约：读取顺序定死 reasonCode ?? errorCode ?? reason ?? code；骨架 8 码 + 领域码登记；
// 未知码必须回退 message（强制条款）。纯 Dart，可被 test/ 直接 import。

/// 信封取值（等价页面里的 text(map, key, fallback)）。
String envelopeText(Map? map, String key, [String fallback = '']) {
  final value = map == null ? null : map[key];
  if (value == null) return fallback;
  final text = value.toString();
  return text.isEmpty ? fallback : text;
}

/// 消费侧读取顺序（P3-1 定死）。过渡期原生可能只写 errorCode（兼容镜像）。
String readReasonCode(Map? result) {
  for (final key in const ['reasonCode', 'errorCode', 'reason', 'code']) {
    final value = envelopeText(result, key);
    if (value.isNotEmpty) return value;
  }
  return '';
}

/// 骨架 8 码（契约 v1，只能追加）+ 领域码（本项目登记，一条一码）。
const Map<String, String> reasonCodeTexts = <String, String>{
  'invalidArg': '参数不合法',
  'notFound': '找不到目标（可能已被删除或改名）',
  'permission': '权限不足（系统未允许）',
  'unsupported': '当前环境不支持该操作',
  'busy': '设备正忙（请稍后重试）',
  'timeout': '超时（请稍后重试）',
  'network': '网络不可达',
  'internal': '内部错误（详情见日志）',
  'checksum': '校验不符（内容与清单不一致）',
  'package': '安装包与清单不一致（包名不符）',
  'missing': '清单未收录',
  'source': '下载源不可用（可换源后重试）',
  'manifest': '清单不可用（可刷新或换源后重试）',
};

/// 下载链路专用文案（逐字保留原实现说法；与通用表刻意分开）。
const Map<String, String> downloadReasonTexts = <String, String>{
  'network': '网络不可达（下载源连不上）',
  'checksum': '校验不符（sha256 与清单不一致，未安装）',
  'package': '安装包与清单不一致（包名不符，未安装）',
  'permission': '系统未允许安装（请在系统里允许本应用安装未知来源应用）',
  'missing': '清单未收录该插件',
};

/// 下载进度失败时的「网络中断」文案（与上表 network 刻意不同 ⇒ 两个键都保留）。
const String networkInterruptedText = '下载中断（网络，可换网络或换源后重试）';

/// 码 → 展示文案：表命中即用（逐字不变）；表里没有 ⇒ 回退 message（强制条款）；
/// message 也空 ⇒ 退回通用表；再没有 ⇒ 固定兜底。
String reasonTextFor(String code,
    {String message = '', Map<String, String> table = downloadReasonTexts}) {
  final known = table[code];
  if (known != null && known.isNotEmpty) return known;
  if (message.isNotEmpty) return message;
  final generic = reasonCodeTexts[code];
  if (generic != null && generic.isNotEmpty) return generic;
  return '未知原因';
}
