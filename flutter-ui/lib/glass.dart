import 'dart:ui';
import 'dart:ui' as ui;
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';

class GlassSurface extends StatelessWidget {
  final Widget child;
  final EdgeInsets padding;
  final double radius;
  final bool blur;
  // 导航条铺满整屏时不需要 1dp 描边，否则每个标签会被内缩 1dp（215 而非 216 px）。
  final bool border;
  const GlassSurface(
      {Key? key,
      required this.child,
      this.padding = const EdgeInsets.all(16),
      this.radius = 18,
      this.blur = true,
      this.border = true})
      : super(key: key);
  @override
  Widget build(BuildContext context) {
    final dark = Theme.of(context).brightness == Brightness.dark;
    final surface = Container(
        padding: padding,
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(radius),
          gradient: LinearGradient(
              begin: Alignment.topLeft,
              end: Alignment.bottomRight,
              colors: dark
                  ? [
                      Colors.white.withOpacity(.12),
                      Colors.white.withOpacity(.035)
                    ]
                  : [
                      Colors.white.withOpacity(.80),
                      Colors.white.withOpacity(.38)
                    ]),
          border: border
              ? Border.all(color: Colors.white.withOpacity(dark ? .19 : .9))
              : null),
        child: child);
    return Container(
        decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(radius),
            boxShadow: [
              BoxShadow(
                  color: Colors.black.withOpacity(dark ? .10 : .035),
                  blurRadius: 8,
                  offset: const Offset(0, 2))
            ]),
        child: ClipRRect(
            borderRadius: BorderRadius.circular(radius),
            child: blur
                ? BackdropFilter(
                    filter: ImageFilter.blur(sigmaX: 16, sigmaY: 16),
                    child: surface)
                : surface));
  }
}

class GlassBackground extends StatelessWidget {
  final Widget child;
  const GlassBackground({Key? key, required this.child}) : super(key: key);

  /// Window colour behind every glass surface. The refraction snapshot is
  /// baked over this colour so the lens material matches the screen.
  static Color colourFor(bool dark) =>
      dark ? const Color(0xff1c2022) : const Color(0xfff7f7f8);

  @override
  Widget build(BuildContext context) {
    final dark = Theme.of(context).brightness == Brightness.dark;
    return AnnotatedRegion<SystemUiOverlayStyle>(
        value: SystemUiOverlayStyle(
          statusBarColor: colourFor(dark),
          statusBarIconBrightness: dark ? Brightness.light : Brightness.dark,
          systemNavigationBarColor: colourFor(dark),
          systemNavigationBarIconBrightness:
              dark ? Brightness.light : Brightness.dark,
        ),
        child: Container(color: colourFor(dark), child: child));
  }
}

/// 折射是否可用。
enum GlassRefractionState {
  /// 还没尝试装载（首帧之前）。
  idle,

  /// 着色器已编译，导航镜片走 GPU 像素折射。
  ready,

  /// 总开关关闭，完全不动着色器。
  disabled,

  /// 本机/本引擎装载或编译失败，回落到原有外观。
  unsupported,
}

/// 测试注入点：返回 null 视为设备不支持，抛异常视为装载/编译失败。
typedef GlassRefractionProgramLoader = Future<ui.FragmentProgram?> Function();

/// 测试注入点：替代 RepaintBoundary 截图。
typedef GlassRefractionSnapshotLoader = Future<ui.Image?> Function();

/// RuntimeShader 像素折射的总闸门：能力探测、开关与降级都在这里，
/// 任何一步失败都只是让导航回到原有外观，绝不让界面崩掉。
class GlassRefraction {
  GlassRefraction._();

  /// 与 pubspec.yaml 的 flutter/shaders 登记项一致。
  static const String shaderAsset = 'assets/shaders/glass_refraction.frag';

  /// 快照按逻辑像素 1:1 取，着色器的 uv 直接等于绘制坐标 / 贴图尺寸。
  static const double snapshotPixelRatio = 1.0;

  /// 主题/设置接线的总开关；false 时连着色器都不装载。
  static bool preference = true;
  static GlassRefractionState state = GlassRefractionState.idle;
  static ui.FragmentProgram? program;

  /// 最后一次失败原因（诊断用，不参与渲染）。
  static String? failure;

  static GlassRefractionProgramLoader? programLoader;
  static GlassRefractionSnapshotLoader? snapshotLoader;

  static Future<void>? _loading;

  /// 着色器已就绪。
  static bool get ready => program != null;

  /// 本次绘制是否应该走像素折射。
  static bool get enabled => preference && program != null;

  /// 幂等装载：重复调用共享同一次结果，不会重复编译着色器。
  static Future<void> ensure({bool force = false}) {
    if (!preference) {
      program = null;
      state = GlassRefractionState.disabled;
      return Future<void>.value();
    }
    if (program != null && !force) return Future<void>.value();
    if (!force && _loading != null) return _loading!;
    final Future<void> pending = _load();
    _loading = pending;
    return pending;
  }

  static Future<void> _load() async {
    final GlassRefractionProgramLoader loader =
        programLoader ?? _loadShaderAsset;
    try {
      final ui.FragmentProgram? loaded = await loader();
      program = loaded;
      state = loaded == null
          ? GlassRefractionState.unsupported
          : GlassRefractionState.ready;
      failure = loaded == null ? '着色器不可用' : null;
      if (loaded == null) {
        debugPrint('GlassRefraction: 着色器不可用，回落原有外观');
      } else {
        debugPrint('GlassRefraction: 着色器已就绪');
      }
    } catch (error) {
      // 引擎没有 SPIR-V/SkSL 支持、资源缺失、编译失败都走这里。
      program = null;
      state = GlassRefractionState.unsupported;
      failure = error.toString();
      debugPrint('GlassRefraction: 着色器装载失败，回落原有外观 — $error');
    } finally {
      _loading = null;
    }
  }

  static Future<ui.FragmentProgram?> _loadShaderAsset() async {
    return ui.FragmentProgram.fromAsset(shaderAsset);
  }

  /// 取一帧导航条自身的像素作为折射素材；任何失败都返回 null（回落）。
  static Future<ui.Image?> snapshot(RenderRepaintBoundary? boundary) async {
    final GlassRefractionSnapshotLoader? loader = snapshotLoader;
    try {
      if (loader != null) return await loader();
      if (boundary == null) {
        failure = '没有可截图的边界';
        return null;
      }
      if (!boundary.attached || !boundary.hasSize || boundary.layer == null) {
        failure = '导航材质尚未绘制';
        return null;
      }
      final image = await boundary.toImage(pixelRatio: snapshotPixelRatio);
      failure = null;
      return image;
    } catch (error) {
      // 静默回落会让设备上的失败无从判断；原因记在 failure 里并由调用方打日志。
      failure = error.toString();
      return null;
    }
  }

  /// 测试与主题切换复位。
  static void reset() {
    preference = true;
    state = GlassRefractionState.idle;
    program = null;
    failure = null;
    programLoader = null;
    snapshotLoader = null;
    _loading = null;
  }
}

/// 折射镜片的着色器装配。uniform 顺序与
/// assets/shaders/glass_refraction.frag 的声明顺序一一对应；
/// 改动任何一边都必须同时改另一边，test/glass_refraction_test.dart 会盯着。
class GlassRefractionLens {
  GlassRefractionLens._();

  /// vec2 lensSize + vec2 lensOrigin + vec2 textureSize + 5 个 float。
  static const int floatUniformCount = 11;

  /// 镜片中心放大倍率，与原有矢量图标局部放大（1.06 + .32 * stretch）同一套取值。
  static const double zoomBase = 1.06;
  static const double zoomStretch = .32;

  /// 边缘压缩量：0 = 平板玻璃，越大边缘把相邻像素挤得越紧。
  static const double bend = .46;

  /// 边缘色散：红蓝通道按采样半径错开的比例。
  static const double dispersion = .035;

  /// FlutterFragCoord 在 Skia / Impeller 上统一使用局部绘制坐标。
  /// 取反只会让镜片素材垂直镜像，不会位移，见着色器里的说明。
  static const double flipY = 1.0;

  static double zoomFor(double stretch) => zoomBase + zoomStretch * stretch;

  static double rimFor(bool dark) => dark ? .10 : .16;

  /// 把 [source] 的像素按镜片形变重采样到 [lens] 里。
  ///
  /// 返回 false 表示这次不能折射（着色器不支持、素材尺寸过期、装配异常），
  /// 调用方必须回落到原有外观；本方法本身绝不向外抛异常。
  static bool paint({
    required Canvas canvas,
    required RRect lens,
    required ui.Image source,
    required ui.FragmentProgram program,
    required Size paintSize,
    required double stretch,
    required bool dark,
  }) {
    final Rect rect = lens.outerRect;
    final Size size = rect.size;
    if (size.width < 2 || size.height < 2) return false;
    if (paintSize.width < 2 || paintSize.height < 2) return false;
    // 快照必须和当前绘制尺寸一致，否则坐标映射不可信（例如刚旋转过屏幕）。
    if ((source.width - paintSize.width).abs() > .5 ||
        (source.height - paintSize.height).abs() > .5) {
      return false;
    }
    if (rect.left < -.5 ||
        rect.top < -.5 ||
        rect.right > paintSize.width + .5 ||
        rect.bottom > paintSize.height + .5) {
      return false;
    }
    ui.FragmentShader? shader;
    try {
      shader = program.fragmentShader();
      final uniforms = <double>[
            size.width,
            size.height,
            rect.left,
            rect.top,
            source.width.toDouble(),
            source.height.toDouble(),
            zoomFor(stretch),
            bend,
            dispersion,
            rimFor(dark),
            flipY,
          ];
      for (var i = 0; i < uniforms.length; i++) {
        shader.setFloat(i, uniforms[i]);
      }
      shader.setImageSampler(0, source);
    } catch (_) {
      shader?.dispose();
      return false;
    }
    canvas.save();
    canvas.clipRRect(lens);
    canvas.drawRect(rect, Paint()..shader = shader);
    canvas.restore();
    shader.dispose();
    return true;
  }
}
