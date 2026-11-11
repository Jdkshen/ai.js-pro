import 'dart:io';
import 'dart:math' as math;

import 'package:flutter_test/flutter_test.dart';

/// task-154：颜色对比度审计（WCAG 2.1）。**数值可复算**：
/// contrast = (L1 + 0.05) / (L2 + 0.05)，L = 0.2126R + 0.7152G + 0.0722B（sRGB 线性化后）。
double _lin(int channel) {
  final c = channel / 255.0;
  return c <= 0.03928 ? c / 12.92 : math.pow((c + 0.055) / 1.055, 2.4).toDouble();
}

double luminance(List<int> rgb) =>
    0.2126 * _lin(rgb[0]) + 0.7152 * _lin(rgb[1]) + 0.0722 * _lin(rgb[2]);

double ratio(List<int> a, List<int> b) {
  final la = luminance(a);
  final lb = luminance(b);
  final hi = math.max(la, lb);
  final lo = math.min(la, lb);
  return (hi + 0.05) / (lo + 0.05);
}

/// 半透明前景叠加到不透明背景（black87 / white70 这类要用）。
List<int> over(List<int> fg, double alpha, List<int> bg) => [
      (fg[0] * alpha + bg[0] * (1 - alpha)).round(),
      (fg[1] * alpha + bg[1] * (1 - alpha)).round(),
      (fg[2] * alpha + bg[2] * (1 - alpha)).round(),
    ];

void main() {
  const pink = [0xfd, 0x99, 0x9a];
  const green = [0x99, 0xcc, 0x99];
  const white = [255, 255, 255];
  const black = [0, 0, 0];

  test('C1 任务页头像：黑 87% 文字在柔和底色上 ≥ 4.5:1', () {
    final c1 = ratio(over(black, .87, pink), pink);
    final c2 = ratio(over(black, .87, green), green);
    // ignore: avoid_print
    print('black87 on #fd999a = ' + c1.toStringAsFixed(2) + ':1');
    // ignore: avoid_print
    print('black87 on #99cc99 = ' + c2.toStringAsFixed(2) + ':1');
    expect(c1 >= 4.5, isTrue);
    expect(c2 >= 4.5, isTrue);
    // 反向钉：原来的白字确实不达标（防止改回去）
    expect(ratio(white, pink) < 4.5, isTrue);
    expect(ratio(white, green) < 4.5, isTrue);
  });

  test('抽屉头部：白字在加深后的青绿渐变上 ≥ 4.5:1（最浅端也要达标）', () {
    const start = [0x2e, 0x6f, 0x6a];
    const end = [0x12, 0x51, 0x4c];
    final cs = ratio(white, start);
    final ce = ratio(white, end);
    // ignore: avoid_print
    print('white on #2e6f6a = ' + cs.toStringAsFixed(2) + ':1');
    // ignore: avoid_print
    print('white on #12514c = ' + ce.toStringAsFixed(2) + ':1');
    expect(cs >= 4.5, isTrue);
    expect(ce >= 4.5, isTrue);
    // 旧值记录：白字 on #56b9b1 / #008f91 不达标（说明为什么要加深）
    expect(ratio(white, [0x56, 0xb9, 0xb1]) < 4.5, isTrue);
    expect(ratio(white, [0x00, 0x8f, 0x91]) < 4.5, isTrue);
  });

  test('源码钉：C1/C2 与抽屉头部的修法确实落到代码里', () {
    final task = File('lib/task_page.dart').readAsStringSync();
    final browser = File('lib/browser.dart').readAsStringSync();
    final drawer = File('lib/service_drawer.dart').readAsStringSync();

    expect(task.contains('fontSize: 22, color: Colors.black87'), isTrue,
        reason: 'C1 必须改成深色文字');
    expect(browser.contains('brightness == Brightness.dark'), isTrue,
        reason: 'C2 遮罩必须按主题取色');
    expect(browser.contains('Colors.white.withValues(alpha: .08)'), isTrue);
    expect(drawer.contains('Color(0xff2e6f6a), Color(0xff12514c)'), isTrue);
    expect(drawer.contains('Colors.white70'), isFalse,
        reason: 'white70 在青绿底上不达标');
  });
}
