// P1-1（task-159）：把写在 _CatalogPageState 里的业务规则抽成纯函数 —— 不依赖 Widget/Context，
// 因此可被 test/ 直接 import 做「给定输入 ⇒ 期望输出」的行为断言（不再只靠读源码字符串）。
// 原则：输入显式、输出与抽取前逐字一致。

/// Map 取字符串（等价页面里的 text(item, key, fallback)）。
String mapText(Map? map, String key, [String fallback = '']) {
  final value = map == null ? null : map[key];
  if (value == null) return fallback;
  final text = value.toString();
  return text.isEmpty ? fallback : text;
}

/// Map 取数值（非 num 或缺失 ⇒ null）。
num? mapNumber(Map? map, String key) {
  final value = map == null ? null : map[key];
  return value is num ? value : null;
}

/// category 是宿主运行时给的层级路径（如「Node 引擎/Node.js/Node.js中使用UI」）
/// ⇒ 只显示最后一段（没有 / 原样；末段为空回退原值）。
String categoryTailOf(String value) {
  final trimmed = value.trim();
  if (trimmed.isEmpty) return '';
  final index = trimmed.lastIndexOf('/');
  if (index < 0) return trimmed;
  final tail = trimmed.substring(index + 1).trim();
  return tail.isEmpty ? trimmed : tail;
}

/// 卡片次级信息 = 引擎/语言（结构化字段优先，其次名称/分类文字提示，认不出返回空）。
String resourceEngineLabelOf({
  String engine = '',
  String language = '',
  String runtime = '',
  String hintText = '',
}) {
  for (final value in [engine, language, runtime]) {
    if (value.isNotEmpty) return value;
  }
  final hint = hintText.toLowerCase();
  if (hint.contains('node')) return 'Node.js';
  if (hint.contains('quickjs')) return 'QuickJS';
  if (hint.contains('rhino')) return 'Rhino';
  return '';
}

/// 清单的结构化状态：install（未安装）/ update（已装可更新）/ upToDate / 其它。
String manifestStateOf(Map? manifest) => mapText(manifest, 'state');

/// 是否按「已安装」对待：本机标记已装，或清单 state 表明已装。
/// state == install 明确是未安装；无结构信息时不猜（false）。
bool looksInstalledOf({required bool installed, required String state}) {
  if (installed) return true;
  if (state == 'install') return false;
  if (state == 'update') return true;
  if (state == 'upToDate' || state == 'uptodate') return true;
  return false;
}

/// 是否可更新：结构化 state 优先（不覆盖原生 PluginUpdatePlanner）；
/// 否则仅当两侧都有 versionCode 且清单更大时才判定（缺 code 不猜）。
bool hasUpdateOf({
  required bool installed,
  required String state,
  num? remoteCode,
  num? localCode,
}) {
  if (state == 'update') return true;
  if (state == 'install' || state == 'upToDate' || state == 'uptodate') {
    return false;
  }
  if (!installed) return false;
  if (remoteCode == null || localCode == null || localCode <= 0) return false;
  return remoteCode > localCode;
}

/// 是否「已装且已最新」（本机 code >= 清单 code；缺 code 不判定）。
bool isUpToDateOf({
  required bool installed,
  required String state,
  num? remoteCode,
  num? localCode,
}) {
  if (state == 'upToDate' || state == 'uptodate') return true;
  if (state == 'install' || state == 'update') return false;
  if (!installed) return false;
  if (remoteCode == null || localCode == null || localCode <= 0) return false;
  return localCode >= remoteCode;
}

/// 字节 → 可读体积（与页面里 sizeLabel 逐字一致）。
String sizeTextOf(Object? bytes) {
  if (bytes is! num || bytes <= 0) return '';
  final mib = bytes / (1024 * 1024);
  final big = mib.toStringAsFixed(1) + ' MB';
  return mib >= 1 ? big : (bytes / 1024).toStringAsFixed(0) + ' KB';
}
