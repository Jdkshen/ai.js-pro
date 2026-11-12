part of 'main.dart';

/// P0-2（task-158）：降级/失败文案的**唯一来源** —— 改文案只改这里。
///
/// 注意：这些字面量此前被多个测试逐字断言 ⇒ 抽取时**文案逐字不变**，只改「在哪定义」。
class UiCopy {
  UiCopy._();

  /// 插件清单不可用（后接桥给的原因）。
  static const String pluginCatalogUnavailable = '插件目录不可用：';

  /// 大小构成读不到（后接原因，末尾自带右括号）。
  static const String breakdownUnavailable = '大小构成：暂不可用（';

  /// 下载/安装失败（后接可读原因）。
  static const String downloadFailed = '下载失败：';

  /// 打包提示字段缺失（两条拼起来就是完整句）。
  static const String packagingWarningsUnavailable = '打包提示：不可用（';
  static const String packagingWarningsUnavailableDetail =
      '桥上没返回 warnings 字段，请更新到带该字段的版本）';
}

/// 统一的「降级行」：小字提示，可选错误色（用主题色，不引入硬编码色）。
class DegradedLine extends StatelessWidget {
  final String text;
  final bool error;
  /// 默认不限制（保持与抽取前逐字一致；需要截断的调用点显式传）。
  final int? maxLines;

  const DegradedLine(this.text,
      {super.key, this.error = false, this.maxLines});

  @override
  Widget build(BuildContext context) => Text(
      text,
      maxLines: maxLines,
      overflow: TextOverflow.ellipsis,
      style: TextStyle(
          fontSize: 12,
          color: error ? Theme.of(context).colorScheme.error : null));
}
