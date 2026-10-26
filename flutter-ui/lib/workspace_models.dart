/// Workspace ordering and editor search, independent of Android and widgets.
class BrowserOptions {
  static const sorts = {
    'name': '名称',
    'modified': '修改时间',
    'type': '类型',
    'size': '大小'
  };
  static const filters = {
    'all': '全部文件',
    'scripts': '脚本',
    'projects': '项目',
    'folders': '文件夹'
  };
  static const categoryNames = {
    'files': '普通文件',
    'code': '代码文件',
    'folders': '文件夹',
    'projects': '项目'
  };
  final String sort, filter;
  final bool descending, showHidden, recursive, regex;
  // A null list retains the legacy exclusive filter until the user changes it.
  final List<String>? categories;
  const BrowserOptions(
      {this.sort = 'name',
      this.filter = 'all',
      this.descending = false,
      this.showHidden = false,
      this.recursive = false,
      this.regex = false,
      this.categories});
  factory BrowserOptions.fromJson(Map value) => BrowserOptions(
      sort: sorts.containsKey(value['sort']) ? value['sort'] : 'name',
      filter: filters.containsKey(value['filter']) ? value['filter'] : 'all',
      descending: value['descending'] == true,
      showHidden: value['showHidden'] == true,
      recursive: value['recursive'] == true,
      regex: value['regex'] == true,
      categories: value['categories'] is List
          ? (value['categories'] as List)
              .where((value) => categoryNames.containsKey(value))
              .cast<String>()
              .toSet()
              .toList()
          : null);
  Set<String> get selectedCategories =>
      categories?.toSet() ??
      (filter == 'projects'
          ? {'projects'}
          : filter == 'folders'
              ? {'folders', 'projects'}
              : filter == 'scripts'
                  ? {'code', 'folders', 'projects'}
                  : categoryNames.keys.toSet());
  bool get allCategories => selectedCategories.length == categoryNames.length;
  BrowserOptions copyWith(
          {String? sort,
          String? filter,
          bool? descending,
          bool? showHidden,
          bool? recursive,
          bool? regex,
          List<String>? categories}) =>
      BrowserOptions(
          sort: sort ?? this.sort,
          filter: filter ?? this.filter,
          descending: descending ?? this.descending,
          showHidden: showHidden ?? this.showHidden,
          recursive: recursive ?? this.recursive,
          regex: regex ?? this.regex,
          categories: categories ?? this.categories);
  Map<String, dynamic> toJson() => {
        'sort': sort,
        'filter': filter,
        'descending': descending,
        'showHidden': showHidden,
        'recursive': recursive,
        'regex': regex,
        if (categories != null) 'categories': categories
      };
}

String fileType(Map file) {
  if (file['directory'] == true)
    return file['project'] == true ? '脚本项目' : '文件夹';
  final name = file['name'].toString().toLowerCase();
  if (name.endsWith('.node.js') || name.endsWith('.mjs')) return 'Node.js';
  if (name.endsWith('.js')) return 'JavaScript';
  final dot = name.lastIndexOf('.');
  return dot < 0 ? '文件' : name.substring(dot + 1).toUpperCase();
}

bool runnable(Map file) =>
    file['directory'] != true &&
    RegExp(r'\.(js|mjs)$', caseSensitive: false)
        .hasMatch(file['name'].toString());
bool editable(Map file) =>
    file['directory'] != true &&
    RegExp(r'\.(c|cpp|cc|css|html|vue|java|js|mjs|json|kt|lua|md|py|sh|ts|xml|txt|ignore|gitignore|tsx|jsx|yaml|yml)$',
            caseSensitive: false)
        .hasMatch(file['name'].toString());

final _nameParts = RegExp(r'\d+|\D+');
final _digitsOnly = RegExp(r'^\d+$');
final _leadingZeroes = RegExp(r'^0+');

int compareNames(String a, String b) {
  final parts = _nameParts;
  final left =
      parts.allMatches(a.toLowerCase()).map((x) => x.group(0)!).toList();
  final right =
      parts.allMatches(b.toLowerCase()).map((x) => x.group(0)!).toList();
  for (var i = 0; i < left.length && i < right.length; i++) {
    var x = left[i], y = right[i];
    int result;
    if (_digitsOnly.hasMatch(x) && _digitsOnly.hasMatch(y)) {
      x = x.replaceFirst(_leadingZeroes, '');
      y = y.replaceFirst(_leadingZeroes, '');
      result = x.length.compareTo(y.length);
      if (result == 0) result = x.compareTo(y);
    } else {
      result = x.compareTo(y);
    }
    if (result != 0) return result;
  }
  final result = left.length.compareTo(right.length);
  return result == 0 ? a.compareTo(b) : result;
}

List<Map<String, dynamic>> browserFiles(
    List<Map<String, dynamic>> files, BrowserOptions options, String query) {
  final text = query.trim().toLowerCase();
  RegExp? pattern;
  if (text.isNotEmpty && options.regex) {
    try {
      pattern = RegExp(query.trim(), caseSensitive: false);
    } on FormatException {
      return [];
    }
  }
  final result = files.where((file) {
    final name = file['name'].toString();
    if (!options.showHidden && name.startsWith('.')) return false;
    if (pattern != null
        ? !pattern.hasMatch(name)
        : !name.toLowerCase().contains(text)) return false;
    if (options.categories != null) {
      final category = file['directory'] == true
          ? file['project'] == true
              ? 'projects'
              : 'folders'
          : editable(file)
              ? 'code'
              : 'files';
      return options.categories!.contains(category);
    }
    switch (options.filter) {
      case 'scripts':
        return file['directory'] == true || runnable(file);
      case 'projects':
        return file['directory'] == true && file['project'] == true;
      case 'folders':
        return file['directory'] == true;
      default:
        return true;
    }
  }).toList();
  result.sort((a, b) {
    if ((a['directory'] == true) != (b['directory'] == true))
      return a['directory'] == true ? -1 : 1;
    int order;
    switch (options.sort) {
      case 'modified':
        order = ((a['modified'] as num?) ?? 0)
            .compareTo((b['modified'] as num?) ?? 0);
        break;
      case 'size':
        order = ((a['size'] as num?) ?? 0).compareTo((b['size'] as num?) ?? 0);
        break;
      case 'type':
        order = fileType(a).compareTo(fileType(b));
        break;
      default:
        order = compareNames(a['name'].toString(), b['name'].toString());
    }
    if (order == 0)
      order = compareNames(a['name'].toString(), b['name'].toString());
    return options.descending ? -order : order;
  });
  return result;
}

String modifiedDate(dynamic value) {
  final millis = (value as num?)?.toInt() ?? 0;
  if (millis <= 0) return '';
  final date = DateTime.fromMillisecondsSinceEpoch(millis);
  String two(int n) => n.toString().padLeft(2, '0');
  return '${date.year}-${two(date.month)}-${two(date.day)} ${two(date.hour)}:${two(date.minute)}:${two(date.second)}';
}

/// Shares the native workspace rules so a dialog can explain a rejected name
/// before submitting it. Android repeats every check against the actual disk.
String? workspaceNameError(String value, Iterable<String> existing,
    {String? original, bool script = false}) {
  final name = value.trim();
  if (name.isEmpty) return '请输入名称';
  if (name == '.' ||
      name == '..' ||
      RegExp(r'[\\/:*?"<>|\x00-\x1f\x7f]').hasMatch(name) ||
      name.startsWith('.autojs-glass-') ||
      name.startsWith('.flutter-')) {
    return '名称不能含斜杠、控制字符或点路径';
  }
  // UTF-8 byte count without a platform dependency; surrogate pairs are four bytes.
  final bytes = name.runes.fold<int>(
      0,
      (n, code) =>
          n +
          (code <= 0x7f
              ? 1
              : code <= 0x7ff
                  ? 2
                  : code <= 0xffff
                      ? 3
                      : 4));
  if (bytes > 240) return '名称过长（最多 240 字节）';
  if (script &&
      !RegExp(r'.+\.(js|mjs)$', caseSensitive: false).hasMatch(name)) {
    return '脚本名需要以 .js 或 .mjs 结尾';
  }
  if (name != original && existing.contains(name)) return '同名文件或文件夹已存在';
  return null;
}

class SourceMatch {
  final int start, end;
  const SourceMatch(this.start, this.end);
}

List<SourceMatch> sourceMatches(String source, String query) {
  if (query.isEmpty) return const [];
  // RegExp preserves source offsets even when Unicode case folding changes length.
  return RegExp(RegExp.escape(query), caseSensitive: false)
      .allMatches(source)
      .take(10000)
      .map((match) => SourceMatch(match.start, match.end))
      .toList();
}
