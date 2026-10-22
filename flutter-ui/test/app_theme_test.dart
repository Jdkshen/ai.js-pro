// Widget-level checks for AppTheme, the host's single control palette.
//
// These tests intentionally avoid hard-coded palette literals. They assert
// semantic relationships (everything toggle-like resolves through the same
// ColorScheme), inspect the widgets Material actually renders, and capture
// real pixels for the controls whose colors are painted by internal render
// objects (Switch, Checkbox, FilterChip, FloatingActionButton).

import 'dart:math' as math;
import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_test/flutter_test.dart';

import '../lib/app_theme.dart';

void main() {
  for (final dark in <bool>[false, true]) {
    final mode = dark ? 'dark' : 'light';

    group('AppTheme $mode', () {
      testWidgets('keeps Material 2 and exposes one primary action pair',
          (tester) async {
        final theme = AppTheme.create(dark);
        final scheme = theme.colorScheme;

        expect(theme.useMaterial3, isFalse);
        expect(scheme.brightness, dark ? Brightness.dark : Brightness.light);
        expect(scheme.primary, AppTheme.primaryFor(dark));
        expect(scheme.secondary, scheme.primary,
            reason: 'toggle/action components should not drift to a second hue');
        expect(_contrast(scheme.onPrimary, scheme.primary),
            greaterThanOrEqualTo(4.5),
            reason: 'enabled filled controls must keep readable foregrounds');

        await _pumpThemed(
          tester,
          theme,
          Builder(
            builder: (context) {
              expect(AppTheme.actionBackground(context), scheme.primary);
              expect(AppTheme.actionForeground(context), scheme.onPrimary);
              expect(
                AppTheme.actionBackground(context, enabled: false),
                scheme.onSurface.withValues(alpha: .12),
              );
              expect(
                AppTheme.actionForeground(context, enabled: false),
                scheme.onSurface.withValues(alpha: .38),
              );
              expect(theme.disabledColor,
                  scheme.onSurface.withValues(alpha: .38));
              return const SizedBox.shrink();
            },
          ),
        );
      });

      testWidgets(
          'Switch and SwitchListTile paint the same on/off/disabled colors',
          (tester) async {
        final theme = AppTheme.create(dark);
        final scheme = theme.colorScheme;
        final primary = scheme.primary;
        final onSurface = scheme.onSurface;
        final surface = scheme.surface;
        final outline = scheme.outline;
        final onTrack = Color.alphaBlend(
          primary.withValues(alpha: .38),
          surface,
        );
        final offTrack = Color.alphaBlend(
          onSurface.withValues(alpha: .20),
          surface,
        );
        final disabledThumbOverSurface = Color.alphaBlend(
          onSurface.withValues(alpha: .38),
          surface,
        );
        final disabledTrack = Color.alphaBlend(
          onSurface.withValues(alpha: .12),
          surface,
        );

        await _pumpThemed(tester, theme, _SwitchFixture(surface: surface));

        final plainOff = await _capture(
          tester,
          find.byKey(_SwitchFixture.plainKey),
        );
        final tileOff = await _capture(
          tester,
          find.byKey(_SwitchFixture.tileKey),
          crop: _switchRect(tester, _SwitchFixture.tileKey),
        );
        final plainDisabled = await _capture(
          tester,
          find.byKey(_SwitchFixture.plainDisabledKey),
        );
        final tileDisabled = await _capture(
          tester,
          find.byKey(_SwitchFixture.tileDisabledKey),
          crop: _switchRect(tester, _SwitchFixture.tileDisabledKey),
        );

        // Off: thumb is the outline, track is onSurface at 20%.
        expect(plainOff.countNear(outline), greaterThan(40),
            reason: 'off Switch thumb should use the theme outline');
        expect(plainOff.countNear(offTrack), greaterThan(40),
            reason: 'off Switch track should use the theme semantic track');
        expect(plainOff.countNear(primary), lessThan(8));
        _expectSamePixels('off', plainOff, tileOff, <Color>[
          primary,
          outline,
          offTrack,
          onTrack,
          disabledTrack,
        ]);

        // Disabled: both thumb and track must stay in the disabled family,
        // with no enabled primary leaking through.
        expect(plainDisabled.countNear(primary), lessThan(8),
            reason: 'disabled Switch must not paint an enabled primary');
        expect(plainDisabled.countNear(disabledThumbOverSurface),
            greaterThan(30),
            reason: 'disabled thumb should composite from onSurface/38%');
        expect(plainDisabled.countNear(disabledTrack), greaterThan(30),
            reason: 'disabled track should composite from onSurface/12%');
        _expectSamePixels('disabled', plainDisabled, tileDisabled, <Color>[
          primary,
          outline,
          offTrack,
          onTrack,
          disabledThumbOverSurface,
          disabledTrack,
        ]);

        // Real interaction: tapping the standalone switch changes the paint.
        await tester.tap(_switchInside(_SwitchFixture.plainKey));
        await tester.pumpAndSettle();
        final plainOn = await _capture(
          tester,
          find.byKey(_SwitchFixture.plainKey),
        );
        expect(plainOn.countNear(primary), greaterThan(80),
            reason: 'an on Switch thumb should paint the semantic primary');
        expect(plainOn.countNear(onTrack), greaterThan(30),
            reason: 'an on Switch track should be primary at 38%');
        expect(plainOn.countNear(outline), lessThan(12));

        // Tapping the tile (not only its switch) also drives a real repaint.
        await tester.tap(find.byKey(_SwitchFixture.tileKey));
        await tester.pumpAndSettle();
        final tileOn = await _capture(
          tester,
          find.byKey(_SwitchFixture.tileKey),
          crop: _switchRect(tester, _SwitchFixture.tileKey),
        );
        expect(tileOn.countNear(primary), greaterThan(80),
            reason: 'SwitchListTile should paint the same on primary');
        _expectSamePixels('on', plainOn, tileOn, <Color>[
          primary,
          outline,
          onTrack,
          offTrack,
        ]);
      });

      testWidgets('Checkbox and FilterChip selected fills share the theme',
          (tester) async {
        final theme = AppTheme.create(dark);
        final scheme = theme.colorScheme;
        final primary = scheme.primary;
        final onPrimary = scheme.onPrimary;
        final disabled = scheme.onSurface.withValues(alpha: .38);

        await _pumpThemed(
          tester,
          theme,
          _SelectionFixture(surface: scheme.surface),
        );

        final checkboxSelected = await _capture(
          tester,
          find.byKey(_SelectionFixture.checkboxSelectedKey),
        );
        final checkboxUnselected = await _capture(
          tester,
          find.byKey(_SelectionFixture.checkboxUnselectedKey),
        );
        final checkboxDisabled = await _capture(
          tester,
          find.byKey(_SelectionFixture.checkboxDisabledKey),
        );

        expect(theme.checkboxTheme.checkColor?.resolve(const <WidgetState>{}),
            onPrimary);
        expect(checkboxSelected.countNear(primary), greaterThan(60),
            reason: 'selected Checkbox fill should be the semantic primary');
        expect(checkboxUnselected.countNear(primary), lessThan(8));
        expect(checkboxDisabled.countNear(primary), lessThan(8),
            reason: 'disabled selected Checkbox must not use primary');
        expect(
          checkboxDisabled.countNear(
            Color.alphaBlend(disabled, scheme.surface),
          ),
          greaterThan(30),
          reason: 'disabled Checkbox fill should use the disabled surface tint',
        );

        final chipTheme = theme.chipTheme;
        expect(theme.useMaterial3, isFalse);
        final selectedChipFill = chipTheme.selectedColor;
        // Material 2 chips consult ChipThemeData.selectedColor. ChipThemeData.color
        // is the Material 3 path and is ignored by RawChip under useMaterial3:false.
        expect(selectedChipFill, isNotNull,
            reason: 'M2 FilterChip needs chipTheme.selectedColor; a color-only '
                'chipTheme falls back to the Material default black/white');
        final chipFill = selectedChipFill!;
        expect(chipFill, isNot(chipTheme.backgroundColor),
            reason: 'selected and unselected chips must be distinguishable');
        expect(chipFill.a, greaterThan(0));
        final chipFillOverSurface =
            Color.alphaBlend(chipFill, scheme.surface);
        expect(chipFillOverSurface, isNot(scheme.surface),
            reason: 'selected FilterChip must not disappear into the surface');

        final selectedChip = await _capture(
          tester,
          find.byKey(_SelectionFixture.chipSelectedKey),
        );
        expect(selectedChip.countNear(chipFillOverSurface), greaterThan(200),
            reason: 'FilterChip should really render the theme selectedColor');
        expect(
          _contrast(
            Color.alphaBlend(
              chipTheme.checkmarkColor ?? onPrimary,
              chipFillOverSurface,
            ),
            chipFillOverSurface,
          ),
          greaterThanOrEqualTo(4.5),
          reason: 'selected chip checkmark should stay readable',
        );

        final chipLabel = _richTextColor(
          tester,
          find.byKey(_SelectionFixture.chipSelectedKey),
        );
        expect(chipLabel, isNotNull);
        expect(
          _contrast(
            Color.alphaBlend(chipLabel!, chipFillOverSurface),
            chipFillOverSurface,
          ),
          greaterThanOrEqualTo(4.5),
          reason: 'FilterChip label on the selected fill must be readable',
        );
      });

      testWidgets('disabled Text/Elevated/Outlined/Filled buttons match their '
          'rendered Material and text styles', (tester) async {
        final theme = AppTheme.create(dark);
        final scheme = theme.colorScheme;
        final primary = scheme.primary;
        final onPrimary = scheme.onPrimary;
        final surface = scheme.surface;
        final disabledFg = scheme.onSurface.withValues(alpha: .38);
        final disabledBg = scheme.onSurface.withValues(alpha: .12);
        final disabledStates = <WidgetState>{WidgetState.disabled};

        await _pumpThemed(tester, theme, const _ButtonFixture());

        final textStyle = theme.textButtonTheme.style;
        final elevatedStyle = theme.elevatedButtonTheme.style;
        final outlinedStyle = theme.outlinedButtonTheme.style;
        final filledStyle = theme.filledButtonTheme.style;
        expect(textStyle, isNotNull);
        expect(elevatedStyle, isNotNull);
        expect(outlinedStyle, isNotNull);
        expect(filledStyle, isNotNull);

        // TextButton: only the foreground is painted.
        final textOff = _button('text-off');
        expect(_buttonTextColor(tester, textOff), disabledFg);
        expect(
          _buttonTextColor(tester, textOff),
          textStyle!.foregroundColor!.resolve(disabledStates),
        );
        final textOn = _button('text-on');
        expect(_buttonTextColor(tester, textOn), primary);
        expect(
          _contrast(_buttonTextColor(tester, textOn)!, surface),
          greaterThanOrEqualTo(4.5),
        );
        expect(
          _contrast(
            Color.alphaBlend(_buttonTextColor(tester, textOff)!, surface),
            surface,
          ),
          greaterThan(1.5),
          reason: 'disabled text should remain discernible against the surface',
        );

        // ElevatedButton: filled Material + text style.
        final elevatedOff = _button('elevated-off');
        final elevatedOffMaterial = _buttonMaterial(tester, elevatedOff);
        expect(elevatedOffMaterial.color, disabledBg);
        expect(
          elevatedOffMaterial.color,
          elevatedStyle!.backgroundColor!.resolve(disabledStates),
        );
        expect(_buttonTextColor(tester, elevatedOff), disabledFg);
        expect(
          _buttonTextColor(tester, elevatedOff),
          elevatedStyle.foregroundColor!.resolve(disabledStates),
        );
        expect(
          _contrast(
            Color.alphaBlend(
              _buttonTextColor(tester, elevatedOff)!,
              Color.alphaBlend(disabledBg, surface),
            ),
            Color.alphaBlend(disabledBg, surface),
          ),
          greaterThan(1.5),
        );

        final elevatedOn = _button('elevated-on');
        final elevatedOnMaterial = _buttonMaterial(tester, elevatedOn);
        expect(elevatedOnMaterial.color, primary);
        expect(_buttonTextColor(tester, elevatedOn), onPrimary);
        expect(
          _contrast(
            _buttonTextColor(tester, elevatedOn)!,
            elevatedOnMaterial.color!,
          ),
          greaterThanOrEqualTo(4.5),
        );

        // OutlinedButton: transparent fill, disabled foreground and border.
        final outlinedOff = _button('outlined-off');
        expect(_buttonTextColor(tester, outlinedOff), disabledFg);
        final outlinedShape = _buttonMaterial(tester, outlinedOff).shape;
        expect(outlinedShape, isA<OutlinedBorder>());
        final outlinedBorder = outlinedShape as OutlinedBorder;
        expect(
          outlinedBorder.side.color,
          disabledBg,
          reason: 'OutlinedButton disabled border should use the disabled tint',
        );
        expect(
          outlinedBorder.side.color,
          outlinedStyle!.side!.resolve(disabledStates)!.color,
        );
        expect(
          _contrast(
            Color.alphaBlend(_buttonTextColor(tester, outlinedOff)!, surface),
            surface,
          ),
          greaterThan(1.5),
        );

        final outlinedOn = _button('outlined-on');
        expect(_buttonTextColor(tester, outlinedOn), primary);
        expect(
          _contrast(_buttonTextColor(tester, outlinedOn)!, surface),
          greaterThanOrEqualTo(4.5),
        );

        // FilledButton: same filled Material contract as ElevatedButton.
        final filledOff = _button('filled-off');
        final filledOffMaterial = _buttonMaterial(tester, filledOff);
        expect(filledOffMaterial.color, disabledBg);
        expect(
          filledOffMaterial.color,
          filledStyle!.backgroundColor!.resolve(disabledStates),
        );
        expect(_buttonTextColor(tester, filledOff), disabledFg);
        expect(
          _contrast(
            Color.alphaBlend(
              _buttonTextColor(tester, filledOff)!,
              Color.alphaBlend(disabledBg, surface),
            ),
            Color.alphaBlend(disabledBg, surface),
          ),
          greaterThan(1.5),
        );

        final filledOn = _button('filled-on');
        final filledOnMaterial = _buttonMaterial(tester, filledOn);
        expect(filledOnMaterial.color, primary);
        expect(_buttonTextColor(tester, filledOn), onPrimary);
        expect(
          _contrast(
            _buttonTextColor(tester, filledOn)!,
            filledOnMaterial.color!,
          ),
          greaterThanOrEqualTo(4.5),
        );
      });

      testWidgets('FAB colors come from the Builder context helpers',
          (tester) async {
        final theme = AppTheme.create(dark);
        final scheme = theme.colorScheme;
        final primary = scheme.primary;
        final onPrimary = scheme.onPrimary;
        final surface = scheme.surface;

        late Color helperEnabledBg;
        late Color helperEnabledFg;
        late Color helperDisabledBg;
        late Color helperDisabledFg;
        await _pumpThemed(
          tester,
          theme,
          Builder(
            builder: (context) {
              helperEnabledBg = AppTheme.actionBackground(context);
              helperEnabledFg = AppTheme.actionForeground(context);
              helperDisabledBg =
                  AppTheme.actionBackground(context, enabled: false);
              helperDisabledFg =
                  AppTheme.actionForeground(context, enabled: false);
              return _FabFixture(
                surface: surface,
                enabledBg: helperEnabledBg,
                enabledFg: helperEnabledFg,
                disabledBg: helperDisabledBg,
                disabledFg: helperDisabledFg,
              );
            },
          ),
        );

        // Builder context resolves against the ambient theme only.
        expect(helperEnabledBg, primary);
        expect(helperEnabledFg, onPrimary);
        expect(helperDisabledBg, scheme.onSurface.withValues(alpha: .12));
        expect(helperDisabledFg, scheme.onSurface.withValues(alpha: .38));
        expect(theme.floatingActionButtonTheme.backgroundColor, primary);
        expect(theme.floatingActionButtonTheme.foregroundColor, onPrimary);
        expect(_contrast(helperEnabledFg, helperEnabledBg),
            greaterThanOrEqualTo(4.5));
        final disabledFabSurface =
            Color.alphaBlend(helperDisabledBg, surface);
        expect(
          _contrast(
            Color.alphaBlend(helperDisabledFg, disabledFabSurface),
            disabledFabSurface,
          ),
          greaterThan(1.5),
          reason: 'disabled FAB content should stay discernible',
        );

        // The helper-provided disabled fill must be the actual Material fill.
        final disabledFabMaterial =
            _buttonMaterial(tester, find.byKey(_FabFixture.disabledKey));
        expect(disabledFabMaterial.color, helperDisabledBg);
        expect(
          _richTextColor(tester, find.byKey(_FabFixture.disabledKey)),
          helperDisabledFg,
          reason: 'the helper foreground should reach the actual FAB icon',
        );
        final disabledFab = await _capture(
          tester,
          find.byKey(_FabFixture.disabledKey),
        );
        expect(
          disabledFab.countNear(Color.alphaBlend(helperDisabledBg, surface)),
          greaterThan(1200),
          reason: 'disabled FAB should actually paint the helper background',
        );
        expect(disabledFab.countNear(primary), lessThan(8));

        // A FAB that only carries onPressed must inherit the theme fill, so
        // the inline actionButton helper and the ambient theme cannot drift.
        final themedFabMaterial =
            _buttonMaterial(tester, find.byKey(_FabFixture.themedKey));
        expect(themedFabMaterial.color, primary);
        expect(
          _richTextColor(tester, find.byKey(_FabFixture.themedKey)),
          onPrimary,
          reason: 'the theme FAB should tint its icon with onPrimary',
        );
        final themedFab = await _capture(
          tester,
          find.byKey(_FabFixture.themedKey),
        );
        expect(themedFab.countNear(primary), greaterThan(1200));
      });
    });
  }
}

const Size _testSurfaceSize = Size(900, 1400);

Future<void> _pumpThemed(
  WidgetTester tester,
  ThemeData theme,
  Widget child,
) async {
  await tester.binding.setSurfaceSize(_testSurfaceSize);
  addTearDown(() => tester.binding.setSurfaceSize(null));
  await tester.pumpWidget(
    Localizations(
      locale: const Locale('en', 'US'),
      delegates: const <LocalizationsDelegate<dynamic>>[
        DefaultMaterialLocalizations.delegate,
        DefaultWidgetsLocalizations.delegate,
      ],
      child: Directionality(
        textDirection: TextDirection.ltr,
        child: Theme(
          data: theme,
          child: Material(
            color: theme.colorScheme.surface,
            child: Center(
              child: SingleChildScrollView(
                child: child,
              ),
            ),
          ),
        ),
      ),
    ),
  );
  await tester.pumpAndSettle();
}

Finder _switchInside(Key boundaryKey) => find.descendant(
      of: find.byKey(boundaryKey),
      matching: find.byType(Switch),
    );

Rect _switchRect(WidgetTester tester, Key boundaryKey) =>
    tester.getRect(_switchInside(boundaryKey));

Finder _button(String label) => find
    .ancestor(
      of: find.text(label),
      matching: find.byWidgetPredicate(
        (widget) => widget is ButtonStyleButton,
      ),
    )
    .first;

Material _buttonMaterial(WidgetTester tester, Finder button) =>
    tester.widget<Material>(
      find.descendant(of: button, matching: find.byType(Material)).first,
    );

Color? _buttonTextColor(WidgetTester tester, Finder button) =>
    _richTextColor(tester, button);

Color? _richTextColor(WidgetTester tester, Finder parent) {
  final richTextFinder =
      find.descendant(of: parent, matching: find.byType(RichText));
  if (richTextFinder.evaluate().isEmpty) return null;
  return tester.widget<RichText>(richTextFinder.first).text.style?.color;
}

Future<_Shot> _capture(
  WidgetTester tester,
  Finder boundaryFinder, {
  Rect? crop,
}) async {
  final boundary =
      tester.renderObject<RenderRepaintBoundary>(boundaryFinder);
  final origin = boundary.localToGlobal(Offset.zero);
  final shot = await tester.runAsync(() async {
    final image = await boundary.toImage(pixelRatio: 1);
    try {
      final data = await image.toByteData(format: ui.ImageByteFormat.rawRgba);
      if (data == null) {
        throw StateError('toByteData returned no pixels');
      }
      return _Shot(
        image.width,
        image.height,
        data.buffer.asUint8List(data.offsetInBytes, data.lengthInBytes),
      );
    } finally {
      image.dispose();
    }
  });
  if (shot == null) {
    throw StateError('runAsync returned no screenshot');
  }
  if (crop == null) return shot;
  return shot.crop(crop.shift(-origin));
}

class _Shot {
  const _Shot(this.width, this.height, this.rgba);

  final int width;
  final int height;
  final Uint8List rgba;

  int countNear(Color color, {int tolerance = 4}) {
    final argb = color.toARGB32();
    final alpha = (argb >> 24) & 0xff;
    final red = (argb >> 16) & 0xff;
    final green = (argb >> 8) & 0xff;
    final blue = argb & 0xff;
    var count = 0;
    for (var offset = 0; offset < rgba.length; offset += 4) {
      if ((rgba[offset] - red).abs() <= tolerance &&
          (rgba[offset + 1] - green).abs() <= tolerance &&
          (rgba[offset + 2] - blue).abs() <= tolerance &&
          (rgba[offset + 3] - alpha).abs() <= tolerance) {
        count++;
      }
    }
    return count;
  }

  List<String> topColors({int limit = 8}) {
    final counts = <int, int>{};
    for (var offset = 0; offset < rgba.length; offset += 4) {
      final argb = (rgba[offset + 3] << 24) |
          (rgba[offset] << 16) |
          (rgba[offset + 1] << 8) |
          rgba[offset + 2];
      counts[argb] = (counts[argb] ?? 0) + 1;
    }
    final sorted = counts.entries.toList()
      ..sort((a, b) => b.value.compareTo(a.value));
    return sorted
        .take(limit)
        .map((entry) => '${entry.key.toRadixString(16)}:${entry.value}')
        .toList();
  }

  _Shot crop(Rect logicalRect) {
    final left = logicalRect.left.round().clamp(0, width);
    final top = logicalRect.top.round().clamp(0, height);
    final right = logicalRect.right.round().clamp(left, width);
    final bottom = logicalRect.bottom.round().clamp(top, height);
    final cropWidth = right - left;
    final cropHeight = bottom - top;
    final bytes = Uint8List(cropWidth * cropHeight * 4);
    for (var y = 0; y < cropHeight; y++) {
      final sourceStart = ((top + y) * width + left) * 4;
      bytes.setRange(
        y * cropWidth * 4,
        (y + 1) * cropWidth * 4,
        rgba,
        sourceStart,
      );
    }
    return _Shot(cropWidth, cropHeight, bytes);
  }
}

void _expectSamePixels(
  String state,
  _Shot standalone,
  _Shot listTile,
  List<Color> semanticColors,
) {
  for (final color in semanticColors) {
    final plainCount = standalone.countNear(color);
    final tileCount = listTile.countNear(color);
    final allowed = math.max(8, (math.max(plainCount, tileCount) * .12).ceil());
    expect(
      (plainCount - tileCount).abs(),
      lessThanOrEqualTo(allowed),
      reason: '$state Switch/SwitchListTile disagree on $color pixels: '
          '$plainCount vs $tileCount',
    );
  }
}

double _contrast(Color foreground, Color background) {
  final a = _relativeLuminance(foreground);
  final b = _relativeLuminance(background);
  final lighter = math.max(a, b);
  final darker = math.min(a, b);
  return (lighter + .05) / (darker + .05);
}

double _relativeLuminance(Color color) {
  final argb = color.toARGB32();
  final red = ((argb >> 16) & 0xff) / 255.0;
  final green = ((argb >> 8) & 0xff) / 255.0;
  final blue = (argb & 0xff) / 255.0;
  return .2126 * _linear(red) +
      .7152 * _linear(green) +
      .0722 * _linear(blue);
}

double _linear(double channel) => channel <= .03928
    ? channel / 12.92
    : math.pow((channel + .055) / 1.055, 2.4).toDouble();

class _SwitchFixture extends StatefulWidget {
  const _SwitchFixture({required this.surface});

  static const plainKey = Key('plain-switch');
  static const plainDisabledKey = Key('plain-switch-disabled');
  static const tileKey = Key('tile-switch');
  static const tileDisabledKey = Key('tile-switch-disabled');

  final Color surface;

  @override
  State<_SwitchFixture> createState() => _SwitchFixtureState();
}

class _SwitchFixtureState extends State<_SwitchFixture> {
  bool _plain = false;
  bool _tile = false;

  @override
  Widget build(BuildContext context) {
    final surface = widget.surface;
    return SizedBox(
      width: 720,
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: <Widget>[
          Row(
            mainAxisSize: MainAxisSize.min,
            children: <Widget>[
              RepaintBoundary(
                key: _SwitchFixture.plainKey,
                child: Material(
                  color: surface,
                  child: Switch(
                    value: _plain,
                    onChanged: (value) => setState(() => _plain = value),
                  ),
                ),
              ),
              const SizedBox(width: 24),
              RepaintBoundary(
                key: _SwitchFixture.plainDisabledKey,
                child: Material(
                  color: surface,
                  child: const Switch(value: true, onChanged: null),
                ),
              ),
            ],
          ),
          RepaintBoundary(
            key: _SwitchFixture.tileKey,
            child: Material(
              color: surface,
              child: SwitchListTile(
                value: _tile,
                onChanged: (value) => setState(() => _tile = value),
                title: const Text('tile switch'),
                contentPadding: const EdgeInsets.symmetric(horizontal: 8),
                dense: true,
              ),
            ),
          ),
          RepaintBoundary(
            key: _SwitchFixture.tileDisabledKey,
            child: Material(
              color: surface,
              child: const SwitchListTile(
                value: true,
                onChanged: null,
                title: Text('tile disabled'),
                contentPadding: EdgeInsets.symmetric(horizontal: 8),
                dense: true,
              ),
            ),
          ),
        ],
      ),
    );
  }

}

class _SelectionFixture extends StatelessWidget {
  const _SelectionFixture({required this.surface});

  static const checkboxSelectedKey = Key('checkbox-selected');
  static const checkboxUnselectedKey = Key('checkbox-unselected');
  static const checkboxDisabledKey = Key('checkbox-disabled');
  static const chipSelectedKey = Key('filter-chip-selected');

  final Color surface;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: 720,
      child: Wrap(
        spacing: 32,
        crossAxisAlignment: WrapCrossAlignment.center,
        children: <Widget>[
          RepaintBoundary(
            key: checkboxSelectedKey,
            child: ColoredBox(
              color: surface,
              child: Checkbox(value: true, onChanged: (_) {}),
            ),
          ),
          RepaintBoundary(
            key: checkboxUnselectedKey,
            child: ColoredBox(
              color: surface,
              child: Checkbox(value: false, onChanged: (_) {}),
            ),
          ),
          RepaintBoundary(
            key: checkboxDisabledKey,
            child: ColoredBox(
              color: surface,
              child: const Checkbox(value: true, onChanged: null),
            ),
          ),
          RepaintBoundary(
            key: chipSelectedKey,
            child: ColoredBox(
              color: surface,
              child: FilterChip(
                label: const Text('filter selected'),
                selected: true,
                onSelected: (_) {},
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _ButtonFixture extends StatelessWidget {
  const _ButtonFixture();

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: 520,
      child: Wrap(
        spacing: 12,
        runSpacing: 12,
        children: <Widget>[
          TextButton(onPressed: () {}, child: const Text('text-on')),
          const TextButton(onPressed: null, child: Text('text-off')),
          ElevatedButton(onPressed: () {}, child: const Text('elevated-on')),
          const ElevatedButton(onPressed: null, child: Text('elevated-off')),
          OutlinedButton(onPressed: () {}, child: const Text('outlined-on')),
          const OutlinedButton(onPressed: null, child: Text('outlined-off')),
          FilledButton(onPressed: () {}, child: const Text('filled-on')),
          const FilledButton(onPressed: null, child: Text('filled-off')),
        ],
      ),
    );
  }
}

class _FabFixture extends StatelessWidget {
  const _FabFixture({
    required this.surface,
    required this.enabledBg,
    required this.enabledFg,
    required this.disabledBg,
    required this.disabledFg,
  });

  static const helperEnabledKey = Key('fab-helper-enabled');
  static const disabledKey = Key('fab-helper-disabled');
  static const themedKey = Key('fab-theme');

  final Color surface;
  final Color enabledBg;
  final Color enabledFg;
  final Color disabledBg;
  final Color disabledFg;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: 520,
      child: Wrap(
        spacing: 32,
        crossAxisAlignment: WrapCrossAlignment.center,
        children: <Widget>[
          RepaintBoundary(
            key: helperEnabledKey,
            child: ColoredBox(
              color: surface,
              child: FloatingActionButton(
                heroTag: null,
                onPressed: () {},
                backgroundColor: enabledBg,
                foregroundColor: enabledFg,
                child: const Icon(Icons.add),
              ),
            ),
          ),
          RepaintBoundary(
            key: disabledKey,
            child: ColoredBox(
              color: surface,
              child: FloatingActionButton(
                heroTag: null,
                onPressed: null,
                backgroundColor: disabledBg,
                foregroundColor: disabledFg,
                child: const Icon(Icons.add),
              ),
            ),
          ),
          RepaintBoundary(
            key: themedKey,
            child: ColoredBox(
              color: surface,
              child: FloatingActionButton(
                heroTag: null,
                onPressed: () {},
                child: const Icon(Icons.add),
              ),
            ),
          ),
        ],
      ),
    );
  }
}
