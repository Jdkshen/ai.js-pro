import 'package:flutter/material.dart';

/// Shows only observed state. An unknown pool occupancy must never become zero.
class EngineDiagnosticsPanel extends StatelessWidget {
  const EngineDiagnosticsPanel({super.key, this.data, this.stale = false});

  final Object? data;
  final bool stale;

  @override
  Widget build(BuildContext context) {
    final value = data is Map ? data as Map : const {};
    final valid = value['schemaVersion'] == 1 && value['unavailable'] != true;
    final pool = value['nodePool'] is Map ? value['nodePool'] as Map : const {};
    final engines = value['engines'] is List
        ? value['engines'] as List
        : const [];
    final capacity = _count(pool['capacity']);
    final reportedBusy = stale ? null : _count(pool['busy']);
    final busy =
        capacity != null && reportedBusy != null && reportedBusy <= capacity
        ? reportedBusy
        : null;
    final runtime = value['runtime'] is Map
        ? value['runtime'] as Map
        : const {};
    final failure = value['lastFailure'] is Map
        ? value['lastFailure'] as Map
        : const {};
    final prepareMs = stale ? null : _count(runtime['prepareMs']);

    return ExpansionTile(
      key: const ValueKey('engine-diagnostics'),
      leading: const Icon(Icons.monitor_heart_outlined),
      title: const Text('引擎诊断'),
      subtitle: Text(
        stale
            ? '状态未确认，请刷新'
            : valid
            ? '查看运行槽与接口验证状态'
            : '诊断暂不可用',
      ),
      childrenPadding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
      children: [
        if (!valid)
          const Text('请刷新状态；旧版宿主需要更新后才能提供引擎诊断。')
        else
          Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                capacity == null
                    ? 'Node 运行槽：容量与占用未确认'
                    : busy == null
                    ? 'Node 运行槽：占用未确认 / 容量 $capacity'
                    : 'Node 运行槽：$busy / $capacity',
              ),
              const SizedBox(height: 6),
              const Text('MCP 任务数只统计 MCP 发起的任务，其他入口的脚本可能仍在运行。'),
              if (pool['scope'] != 'all-processes') ...[
                const SizedBox(height: 6),
                const Text('当前诊断无法确认所有进程的脚本占用。'),
              ],
              for (final item in engines.whereType<Map>()) ...[
                const SizedBox(height: 8),
                Text(
                  '${_name(item['engine'])}：${stale ? '状态未确认' : _availability(item['available'])}',
                ),
                Text(
                  item['behaviorStatus'] == 'verified' && !stale
                      ? '接口行为：有验收记录，范围见对应版本报告'
                      : '接口行为：尚未逐项验证',
                ),
              ],
              if (prepareMs != null) ...[
                const SizedBox(height: 8),
                Text('上次运行时准备：${prepareMs}ms'),
              ],
              if (failure.isNotEmpty && !stale) ...[
                const SizedBox(height: 8),
                Text(
                  '本进程 MCP 最近失败：${_name(failure['engine'])} · ${failure['type'] ?? '未知类型'}',
                ),
              ],
              const SizedBox(height: 8),
              const Text('接口兼容性以对应版本的行为验收为准。'),
            ],
          ),
      ],
    );
  }

  static int? _count(Object? value) =>
      value is num && value.isFinite && value >= 0 && value == value.toInt()
      ? value.toInt()
      : null;

  static String _name(Object? engine) => switch (engine) {
    'node' || 'nodejs' => 'Node 24',
    'quickjs' => 'QuickJS',
    'rhino' => 'Rhino',
    _ => '未知引擎',
  };

  static String _availability(Object? value) => switch (value) {
    true => '已接入',
    false => '不可用',
    _ => '状态未确认',
  };
}
