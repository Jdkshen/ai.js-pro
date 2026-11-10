import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

/// task-124/128：加密等级文案与**实现**对齐（v2 = AES-256-GCM + 绑定签名证书）+ 旧产物提示。
void main() {
  test('加密等级口径同步、旧产物提示在、过时话术不在、不引导装插件', () {
    final source = File('lib/project_page.dart').readAsStringSync();

    expect(source.contains("'加密（AES-256-GCM，绑定签名证书）'"), isTrue);
    expect(source.contains("'编译并加密（AES-256-GCM，绑定签名证书）'"), isTrue);
    // 防回退：页面里不得再出现历史口径
    expect(source.contains('AES-256/CBC'), isFalse, reason: 'CBC 口径已作废，不该再出现');
    expect(source.contains("'不加密（用 SJBC 容器时推荐：容器自带加密）'"), isTrue);
    expect(source.contains("'Snapshot（引擎当前最高等级为 2：按「编译并加密」处理）'"), isTrue);

    expect(source.contains("ValueKey('project-encryptLevel-legacy-note')"), isTrue);
    expect(source.contains('旧产物（无随机盐、未绑定签名证书）无法在本版运行，需用本版重新打包。'), isTrue);

    for (final stale in [
      "'离线加密（可选再套一层）'",
      "'离线加密 · Dex（可选再套一层）'",
      "'离线加密 · Snapshot（等同 Dex：引擎最高等级为 2）'",
      '等同 Dex',
    ]) {
      expect(source.contains(stale), isFalse, reason: '过时话术仍在：' + stale);
    }

    final start = source.indexOf("labelText: '脚本加密等级'");
    final end = source.indexOf('project-encryptLevel-legacy-note', start);
    expect(start > 0 && end > start, isTrue);
    final block = source.substring(start, end);
    expect(block.contains('装插件'), isFalse);
    expect(block.contains('安装插件'), isFalse);
    // 只对**用户可见的档位文案**查「不写死字节数」（源码注释里可以写实现细节）
    for (final label in [
      "'不加密（用 SJBC 容器时推荐：容器自带加密）'",
      "'加密（AES-256/CBC，绑定签名证书）'",
      "'编译并加密（AES-256/CBC，绑定签名证书）'",
      "'Snapshot（引擎当前最高等级为 2：按「编译并加密」处理）'",
    ]) {
      expect(label.contains('字节'), isFalse, reason: '档位文案不该写死字节数：' + label);
    }
  });
}
