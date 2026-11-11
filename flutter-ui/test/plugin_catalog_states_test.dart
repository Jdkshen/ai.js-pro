import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

/// task-134：插件统一目录——四态（未安装/下载安装/可更新/停用）+ 进度 + 可读失败原因。
///
/// 只读源码钉（页面不编译也能跑）：桥名集中、URL 不在 Dart 侧、失败不静默、卸载去向写清。
void main() {
  test('四态与下载：桥名集中、无 URL、失败可读、卸载去向写清', () {
    final source = File('lib/catalog_page.dart').readAsStringSync();

    // 桥名集中（与 W2 定稿对齐时只改这一处）
    expect(source.contains("pluginCatalogMethod = 'pluginCatalog'"), isTrue);
    // 真实桥名（Lead dev-1501 真机验过）：pluginInstall / pluginInstallStatus
    expect(source.contains("pluginInstallMethod = 'pluginInstall'"), isTrue);
    expect(source.contains("pluginInstallStatusMethod = 'pluginInstallStatus'"), isTrue);
    // 轻量节流：60s 内不重复拉；下拉/重试强制
    expect(source.contains('Duration(seconds: 60)'), isTrue);
    expect(source.contains('loadPluginCatalog(force: true)'), isTrue);
    expect(source.contains('lastPluginCatalogAt'), isTrue);
    // 状态驱动：success/failed + reasonCode + message
    expect(source.contains("state == 'success'"), isTrue);
    expect(source.contains("state == 'failed' || state == 'error'"), isTrue);
    expect(source.contains("text(result, 'reasonCode'"), isTrue);
    // 只传 id / fileName（URL 由原生解析）
    expect(source.contains("'id': id,"), isTrue);
    expect(source.contains("'id': id});"), isTrue, reason: '安装只传 id');
    // 只查**下载调用这一段**不得出现 url（页面上别处可能有 url 字段，那是既有逻辑）
    final callStart = source.indexOf('LocalBridge.call(pluginInstallMethod');
    expect(callStart > 0, isTrue);
    final callEnd = source.indexOf('});', callStart);
    final callBlock = source.substring(callStart, callEnd);
    expect(callBlock.contains('url'), isFalse, reason: '下载调用不得传 url（源解析归原生）');
    expect(source.contains('http://') || source.contains('https://'), isFalse,
        reason: '页面不得出现 URL 字面量');

    // 四态文案
    expect(source.contains("'下载中 ' + percent + '%"), isTrue);
    expect(source.contains("'可更新到 v' + remote"), isTrue);
    expect(source.contains("'未安装 · 可下载 v'"), isTrue);
    expect(source.contains("'下载失败：' + failure"), isTrue);
    // 四类可读原因码
    for (final code in ["case 'network':", "case 'checksum':", "case 'package':", "case 'permission':"]) {
      expect(source.contains(code), isTrue, reason: '缺少原因码映射：' + code);
    }
    expect(source.contains('校验不符（sha256 与清单不一致，未安装）'), isTrue);
    expect(source.contains('停用后仍占空间，卸载请到系统应用管理'), isTrue);
    // 清单拿不到要显式说
    expect(source.contains("ValueKey('plugin-manifest-error')"), isTrue);
    // task-135：必须**首屏可见**（列表头），否则真机看不到
    expect(source.contains("ValueKey('plugin-manifest-error-header')"), isTrue);
    expect(source.contains("ValueKey('plugin-manifest-loading')"), isTrue);
    // task-137：失败态 = 无转圈 + 源 + 怎么办 + 重试
    expect(source.contains('pluginManifestError == null &&'), isTrue,
        reason: '清单失败时不得再显示永不结束的居中转圈');
    expect(source.contains("ValueKey('plugin-manifest-retry')"), isTrue);
    expect(source.contains("ValueKey('plugin-manifest-source')"), isTrue);
    // task-138：已下载（待安装）态 + 详情（大小/需重启/说明）+ 安装复用既有 installPlugin
    expect(source.contains("'已下载（待安装'"), isTrue);
    expect(source.contains("ValueKey('plugin-install-downloaded')"), isTrue);
    expect(source.contains("LocalBridge.call('installPlugin', {"), isTrue,
        reason: '安装必须复用既有 installPlugin 桥（交系统安装器），不新增静默安装路径');
    // 卡片态也要有「下载/更新」与「安装已下载」入口（不只是详情里）
    expect(source.contains("ValueKey('plugin-card-download')"), isTrue,
        reason: '卡片上要有 下载安装/更新 按钮');
    expect(source.contains("ValueKey('plugin-card-install')"), isTrue);
    expect(source.contains("key: const ValueKey('plugin-card-download'),"), isTrue);
    expect(source.contains("key: const ValueKey('plugin-card-install'),"), isTrue);
    // 进入插件 tab 自动查清单（VS Code 式自动检查更新）
    expect(source.contains('每次进入插件 tab 都自动查一次清单'), isTrue);
    expect(source.contains("ValueKey('plugin-detail-size')"), isTrue);
    expect(source.contains("ValueKey('plugin-detail-restart')"), isTrue);
    expect(source.contains("ValueKey('plugin-detail-notes')"), isTrue);
    // task-141：notes 多行 + 展开/收起；来源行；无 withOpacity
    expect(source.contains("ValueKey('plugin-detail-notes-toggle')"), isTrue);
    expect(source.contains('maxLines: expandedNotes.contains(package(item))'), isTrue,
        reason: 'notes 必须保留换行并支持展开');
    expect(source.contains("ValueKey('plugin-detail-source')"), isTrue);
    expect(source.contains("'清单来源：' + manifestSourceLabel"), isTrue);
    // task-142：2 列卡片网格 + 真实图标（清单 iconPath 优先，缺失走占位）
    expect(source.contains('SliverGrid('), isTrue, reason: '插件页要是卡片网格');
    expect(source.contains('SliverGridDelegateWithMaxCrossAxisExtent('), isTrue);
    expect(source.contains('maxCrossAxisExtent: 190'), isTrue);
    expect(source.contains("manifestOf(item)?['iconPath']"), isTrue,
        reason: '必须消费清单的 iconPath');
    expect(source.contains('Image.file(File(iconPath)'), isTrue);
    expect(source.contains('errorBuilder: (_, __, ___) => fallback()'), isTrue,
        reason: '图标拿不到要走占位');
    expect(source.contains("ValueKey('plugin-card-error')"), isTrue);
    expect(source.contains('LinearProgressIndicator('), isTrue);
    // task-144：有进展就继续；两种失败文案区分；详情也有进度
    expect(source.contains('const stallLimit = Duration(seconds: 120)'), isTrue);
    expect(source.contains('const pollInterval = Duration(seconds: 2)'), isTrue);
    expect(source.contains('stalled = Duration.zero'), isTrue,
        reason: '有进展必须重新计时（不判失败）');
    expect(source.contains('下载中断（网络，可换网络或换源后重试）'), isTrue);
    expect(source.contains('安装状态轮询超时（长时间无进展，可重试）'), isTrue);
    expect(source.contains("ValueKey('plugin-detail-progress')"), isTrue);
    // task-145：列表 = 清单全部（含未安装）；≥ 判最新；清旧失败态
    expect(source.contains("final known = <String>{"), isTrue,
        reason: '必须把清单里未安装的也并进列表');
    expect(source.contains("          'installed': false, // 清单里有、本机没有 ⇒ 未安装"), isTrue);
    expect(source.contains('bool pluginHasUpdate(Map item)'), isTrue);
    expect(source.contains('return remote > local;'), isTrue,
        reason: '只有清单 code > 本机 code 才算可更新');
    expect(source.contains('return local >= remote;'), isTrue,
        reason: '本机 code ≥ 清单 code ⇒ 已是最新（≥ 而非 >）');
    expect(source.contains("'已安装 · 已是最新'"), isTrue);
    // task-146：notes 折叠必须有 overflow；未安装不显示「下载中/没有本地安装包」
    expect(source.contains('overflow: TextOverflow.fade,'), isTrue);
    expect(source.contains("downloading[package(item)]!['receivedBytes'] is num"), isTrue);
    expect(source.contains('activeDownload'), isTrue);
    // task-147：卡片分层/单行 + 固定高度 + 底部留白 + permission 去设置
    // task-149：最坏组合（图标+名称2行+版本+包名+状态行+按钮）⇒ 264
    expect(source.contains('mainAxisExtent: 264'), isTrue, reason: '卡片等高且不被切半');
    expect(source.contains('bool pluginLooksInstalled(Map item)'), isTrue);
    // task-150：读结构化 state（detail 只显示、不参与判定）
    expect(source.contains("text(manifestOf(item) ?? const {}, 'state')"), isTrue);
    expect(source.contains("state == 'install'"), isTrue);
    expect(source.contains("state == 'update'"), isTrue);
    expect(source.contains("state == 'upToDate'"), isTrue);
    expect(source.contains("manifestOf(item)!['actionable'] == true"), isTrue);
    expect(source.contains("detail.contains('已是最新')"), isFalse,
        reason: 'detail 不得再参与判定（只用于显示）');
    expect(source.contains("pluginManifestState(item) == 'install' ||"), isTrue,
        reason: 'state=install（未安装）才给下载安装按钮；state=其他 ⇒ 不给');
    expect(source.contains('72 + 16 + MediaQuery.of(context).padding.bottom'), isTrue,
        reason: '底部要留出底栏 + 16');
    expect(source.contains("code == 'permission'"), isTrue);
    expect(source.contains('系统未允许安装未知来源应用'), isTrue);
    expect(source.contains("ValueKey('plugin-open-install-settings')"), isTrue);
    expect(source.contains("LocalBridge.call('openInstallPermissionSettings'"), isTrue);
    expect(source.contains("'去设置'"), isTrue);
    // task-148：用原生 message/actionLabel；action 也能触发；ok=false 要提示
    expect(source.contains("text(status, 'action')"), isTrue);
    expect(source.contains("action == 'openInstallPermissionSettings'"), isTrue);
    expect(source.contains("text(status, 'actionLabel', '去设置')"), isTrue);
    expect(source.contains('permissionActionLabel[package(item)] ??'), isTrue);
    expect(source.contains('打不开设置页'), isTrue);
    expect(source.contains("data['canInstall'] == true"), isTrue);
    expect(source.contains('else if (manifestOf(item) == null)'), isTrue);
    expect(source.contains('downloadError.clear();'), isTrue,
        reason: '重新拉清单要清掉旧失败态');
    expect(source.contains("'下载中 ' +"), isTrue);
    expect(source.contains('withOpacity('), isFalse,
        reason: '5 条 withOpacity 必须清成 withValues(alpha:)');
    expect(source.contains('Android 不允许静默安装'), isTrue);
    expect(source.contains('当前更新源：'), isTrue);
    expect(source.contains('多半是更新源不可达'), isTrue);
    expect(source.contains('要公网下载请在「设置 → 更新源」'), isTrue);
    expect(source.contains("LocalBridge.call('updateSource')"), isTrue,
        reason: '源必须复用既有 updateSource 桥，不另造');
    // task-143：兜底超时 25s（原生预算 ~18–20s ⇒ 不抢先）；有原生 timeoutMs 就按它 + 5s
    expect(source.contains('Duration(seconds: 25)'), isTrue);
    expect(source.contains('pluginCatalogTimeout'), isTrue);
    expect(source.contains("data['timeoutMs']"), isTrue);
    expect(source.contains("throw StateError('清单读取超时（桥未响应）')"), isTrue,
        reason: '桥不响应必须落到可见降级，不能永远停在「加载中」');
    expect(source.contains('插件目录不可用：'), isTrue);
    // 下载按钮与重启提示
    expect(source.contains("ValueKey('plugin-download-action')"), isTrue);
    expect(source.contains("' · 需重启应用生效'"), isTrue);
    expect(source.contains("requiresRestart"), isTrue);
  });
}
