import 'dart:ui' as ui;
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import '../lib/glass.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('modern shader loads and preserves the source quadrants', () async {
    GlassRefraction.reset();
    await GlassRefraction.ensure();
    expect(GlassRefraction.failure, isNull);
    expect(GlassRefraction.state, GlassRefractionState.ready);
    final recorder = ui.PictureRecorder();
    final sourceCanvas = Canvas(recorder);
    for (final entry in <Rect, Color>{
      const Rect.fromLTWH(0, 0, 64, 16): Colors.red,
      const Rect.fromLTWH(64, 0, 64, 16): Colors.green,
      const Rect.fromLTWH(0, 16, 64, 16): Colors.blue,
      const Rect.fromLTWH(64, 16, 64, 16): Colors.yellow,
    }.entries) {
      sourceCanvas.drawRect(entry.key, Paint()..color = entry.value);
    }
    final sourcePicture = recorder.endRecording();
    final source = await sourcePicture.toImage(128, 32);
    final outputRecorder = ui.PictureRecorder();
    expect(GlassRefractionLens.paint(
      canvas: Canvas(outputRecorder),
      lens: RRect.fromRectAndRadius(
          const Rect.fromLTWH(16, 0, 96, 32), const Radius.circular(4)),
      source: source,
      program: GlassRefraction.program!,
      paintSize: const Size(128, 32), stretch: 0, dark: true,
    ), isTrue);
    final picture = outputRecorder.endRecording();
    final image = await picture.toImage(128, 32);
    final pixels = (await image.toByteData())!;
    List<int> rgb(int x, int y) => List.generate(
        3, (i) => pixels.getUint8((y * 128 + x) * 4 + i));
    final topLeft = rgb(32, 8), topRight = rgb(96, 8);
    final bottomLeft = rgb(32, 24), bottomRight = rgb(96, 24);
    expect(topLeft[0], greaterThan(topLeft[1] + 80));
    expect(topLeft[0], greaterThan(topLeft[2] + 80));
    expect(topRight[1], greaterThan(topRight[0] + 50));
    expect(bottomLeft[2], greaterThan(bottomLeft[0] + 80));
    expect(bottomRight[0], greaterThan(bottomRight[2] + 80));
    expect(bottomRight[1], greaterThan(bottomRight[2] + 80));
    image.dispose(); source.dispose(); picture.dispose(); sourcePicture.dispose();
    GlassRefraction.reset();
  });

  test('shader failure and disabled preference retain the fallback', () async {
    GlassRefraction.reset();
    GlassRefraction.programLoader = () async => throw StateError('unavailable');
    await GlassRefraction.ensure();
    expect(GlassRefraction.enabled, isFalse);
    expect(GlassRefraction.state, GlassRefractionState.unsupported);
    GlassRefraction.preference = false;
    await GlassRefraction.ensure(force: true);
    expect(GlassRefraction.state, GlassRefractionState.disabled);
    GlassRefraction.reset();
  });
}
