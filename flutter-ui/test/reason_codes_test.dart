import 'package:flutter_test/flutter_test.dart';

import '../lib/reason_codes.dart';

/// P3-1（task-164）：原因码常量表 + 读取顺序 + 未知码回退 message。
void main() {
  test('读取顺序定死：reasonCode ?? errorCode ?? reason ?? code', () {
    expect(readReasonCode({'reasonCode': 'a', 'errorCode': 'b'}), 'a');
    expect(readReasonCode({'errorCode': 'b'}), 'b', reason: '过渡期只有 errorCode 也要能读到');
    expect(readReasonCode({'reason': 'c'}), 'c');
    expect(readReasonCode({'code': 'd'}), 'd');
    expect(readReasonCode({'reasonCode': ''}), '');
    expect(readReasonCode({}), '');
    expect(readReasonCode(null), '');
  });

  test('骨架 8 码 + 领域码都在册，且每条都有中文', () {
    for (final code in const [
      'invalidArg', 'notFound', 'permission', 'unsupported',
      'busy', 'timeout', 'network', 'internal',
    ]) {
      expect(reasonCodeTexts[code], isNotNull, reason: '骨架码缺登记：' + code);
      expect(reasonCodeTexts[code]!.isNotEmpty, isTrue);
    }
    for (final code in const [
      'checksum', 'package', 'missing', 'source', 'manifest',
    ]) {
      expect(reasonCodeTexts[code], isNotNull, reason: '领域码缺登记：' + code);
      expect(reasonCodeTexts[code]!.isNotEmpty, isTrue);
    }
  });

  test('下载链路 5 条文案逐字不变；两个 network 键刻意不同', () {
    expect(downloadReasonTexts['network'], '网络不可达（下载源连不上）');
    expect(downloadReasonTexts['checksum'], '校验不符（sha256 与清单不一致，未安装）');
    expect(downloadReasonTexts['package'], '安装包与清单不一致（包名不符，未安装）');
    expect(downloadReasonTexts['permission'], '系统未允许安装（请在系统里允许本应用安装未知来源应用）');
    expect(downloadReasonTexts['missing'], '清单未收录该插件');
    expect(networkInterruptedText, '下载中断（网络，可换网络或换源后重试）');
    expect(networkInterruptedText == downloadReasonTexts['network'], isFalse);
  });

  test('未知码必须回退 message（不隐藏、也不显示原码）', () {
    expect(reasonTextFor('brandNewCode', message: '原生给的可读原因'), '原生给的可读原因');
    expect(reasonTextFor('brandNewCode'), '未知原因');
    expect(reasonTextFor('', message: '只有 message'), '只有 message');
    // 表内码仍用表内文案（逐字）
    expect(reasonTextFor('missing', message: '不该用这句'), '清单未收录该插件');
    // 表里没有但通用表有 ⇒ 用通用表
    expect(reasonTextFor('busy', table: const {}), '设备正忙（请稍后重试）');
    // 绝不返回原码
    expect(reasonTextFor('brandNewCode') == 'brandNewCode', isFalse);
  });
}
