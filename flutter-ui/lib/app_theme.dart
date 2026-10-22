import 'package:flutter/material.dart';

/// One palette for the host's controls, including dialogs and nested routes.
class AppTheme {
  AppTheme._();

  static const seed = Color(0xff008f91);
  static Color primaryFor(bool dark) =>
      dark ? const Color(0xff80d5d5) : const Color(0xff00696b);

  static Color actionBackground(BuildContext context, {bool enabled = true}) {
    final scheme = Theme.of(context).colorScheme;
    return enabled ? scheme.primary : scheme.onSurface.withValues(alpha: .12);
  }

  static Color actionForeground(BuildContext context, {bool enabled = true}) {
    final scheme = Theme.of(context).colorScheme;
    return enabled ? scheme.onPrimary : scheme.onSurface.withValues(alpha: .38);
  }

  static ThemeData create(bool dark) {
    final generated = ColorScheme.fromSeed(
      seedColor: seed,
      brightness: dark ? Brightness.dark : Brightness.light,
    );
    final scheme = generated.copyWith(
      primary: primaryFor(dark),
      secondary: primaryFor(dark),
      onSecondary: generated.onPrimary,
    );
    final disabled = scheme.onSurface.withValues(alpha: .38);
    final disabledBackground = scheme.onSurface.withValues(alpha: .12);
    // M2 RawChip ignores ChipThemeData.color and paints Ink over canvasColor.
    // Supply its legacy fields too, with an opaque tint so the canvas behind
    // a glass card cannot change the selected color.
    final selectedChip = Color.alphaBlend(
        scheme.primary.withValues(alpha: .16), scheme.surface);
    final foreground = WidgetStateProperty.resolveWith<Color>(
      (states) =>
          states.contains(WidgetState.disabled) ? disabled : scheme.primary,
    );
    final filledForeground = WidgetStateProperty.resolveWith<Color>(
      (states) =>
          states.contains(WidgetState.disabled) ? disabled : scheme.onPrimary,
    );
    final filledBackground = WidgetStateProperty.resolveWith<Color>(
      (states) => states.contains(WidgetState.disabled)
          ? disabledBackground
          : scheme.primary,
    );
    WidgetStateProperty<Color?> overlay(Color color) =>
        WidgetStateProperty.resolveWith((states) {
          if (states.contains(WidgetState.disabled)) return Colors.transparent;
          if (states.contains(WidgetState.pressed) ||
              states.contains(WidgetState.focused)) {
            return color.withValues(alpha: .12);
          }
          if (states.contains(WidgetState.hovered)) {
            return color.withValues(alpha: .08);
          }
          return null;
        });
    final filled = ButtonStyle(
      foregroundColor: filledForeground,
      backgroundColor: filledBackground,
      overlayColor: overlay(scheme.onPrimary),
    );
    final plain = ButtonStyle(
      foregroundColor: foreground,
      overlayColor: overlay(scheme.primary),
    );
    final selectedFill = WidgetStateProperty.resolveWith<Color>((states) {
      if (states.contains(WidgetState.disabled)) return disabled;
      return states.contains(WidgetState.selected)
          ? scheme.primary
          : Colors.transparent;
    });
    return ThemeData(
      // Keep the established component geometry across Flutter upgrades.
      useMaterial3: false,
      brightness: scheme.brightness,
      colorScheme: scheme,
      scaffoldBackgroundColor: Colors.transparent,
      fontFamily: 'sans-serif',
      disabledColor: disabled,
      iconTheme: IconThemeData(
        color: dark ? const Color(0xffa5afb2) : const Color(0xff8d97ac),
      ),
      appBarTheme: const AppBarTheme(
        backgroundColor: Colors.transparent,
        elevation: 0,
      ),
      switchTheme: SwitchThemeData(
        thumbColor: WidgetStateProperty.resolveWith((states) {
          if (states.contains(WidgetState.disabled)) return disabled;
          return states.contains(WidgetState.selected)
              ? scheme.primary
              : scheme.outline;
        }),
        trackColor: WidgetStateProperty.resolveWith((states) {
          if (states.contains(WidgetState.disabled)) {
            return disabledBackground;
          }
          return states.contains(WidgetState.selected)
              ? scheme.primary.withValues(alpha: .38)
              : scheme.onSurface.withValues(alpha: .20);
        }),
        overlayColor: overlay(scheme.primary),
      ),
      checkboxTheme: CheckboxThemeData(
        fillColor: selectedFill,
        checkColor: WidgetStatePropertyAll(scheme.onPrimary),
      ),
      chipTheme: ChipThemeData(
        selectedColor: selectedChip,
        backgroundColor: scheme.surfaceContainerHighest,
        disabledColor: Color.alphaBlend(disabledBackground, scheme.surface),
        color: WidgetStateProperty.resolveWith((states) {
          if (states.contains(WidgetState.disabled)) {
            return Color.alphaBlend(disabledBackground, scheme.surface);
          }
          return states.contains(WidgetState.selected)
              ? selectedChip
              : scheme.surfaceContainerHighest;
        }),
        checkmarkColor: scheme.primary,
        labelStyle: TextStyle(color: WidgetStateColor.resolveWith((states) =>
            states.contains(WidgetState.disabled) ? disabled : scheme.onSurface)),
        side: BorderSide(color: scheme.outline.withValues(alpha: .5)),
      ),
      radioTheme: RadioThemeData(
        fillColor: WidgetStateProperty.resolveWith((states) {
          if (states.contains(WidgetState.disabled)) return disabled;
          return states.contains(WidgetState.selected)
              ? scheme.primary
              : scheme.outline;
        }),
      ),
      sliderTheme: SliderThemeData(
        activeTrackColor: scheme.primary,
        thumbColor: scheme.primary,
        overlayColor: scheme.primary.withValues(alpha: .12),
        inactiveTrackColor: scheme.primary.withValues(alpha: .20),
        disabledActiveTrackColor: disabled,
        disabledInactiveTrackColor: disabledBackground,
        disabledThumbColor: disabled,
      ),
      textButtonTheme: TextButtonThemeData(style: plain),
      outlinedButtonTheme: OutlinedButtonThemeData(
        style: plain.copyWith(
          side: WidgetStateProperty.resolveWith(
            (states) => BorderSide(
              color: states.contains(WidgetState.disabled)
                  ? disabledBackground
                  : scheme.outline,
            ),
          ),
        ),
      ),
      elevatedButtonTheme: ElevatedButtonThemeData(style: filled),
      filledButtonTheme: FilledButtonThemeData(style: filled),
      floatingActionButtonTheme: FloatingActionButtonThemeData(
        backgroundColor: scheme.primary,
        foregroundColor: scheme.onPrimary,
        focusColor: scheme.onPrimary.withValues(alpha: .12),
        hoverColor: scheme.onPrimary.withValues(alpha: .08),
        splashColor: scheme.onPrimary.withValues(alpha: .12),
      ),
      snackBarTheme: const SnackBarThemeData(
        behavior: SnackBarBehavior.floating,
      ),
    );
  }
}
