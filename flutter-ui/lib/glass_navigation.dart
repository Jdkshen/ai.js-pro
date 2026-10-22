import 'dart:math' as math;
import 'dart:ui' as ui;
import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'app_theme.dart';
import 'glass.dart';

/// AI.js Pro's GlassTabLayout interaction: relative drag, continuous page,
/// then nearest-page snapping. The lens paints vector icons, never screenshots.
///
/// The lens material itself is a snapshot of the bar's own pixels, which the
/// runtime shader (see [GlassRefraction]) resamples into a glass lens. When the
/// shader cannot be used the lens keeps its original painted appearance.
class GlassNavigation extends StatefulWidget {
  final PageController controller;
  final List<IconData> icons;
  final List<String> labels;
  final int selected;
  final bool enabled;
  final ValueChanged<int> onSelect;
  const GlassNavigation(
      {Key? key,
      required this.controller,
      required this.icons,
      required this.labels,
      required this.selected,
      required this.enabled,
      required this.onSelect})
      : super(key: key);
  @override
  State<GlassNavigation> createState() => _GlassNavigationState();
}

class _GlassNavigationState extends State<GlassNavigation> {
  Drag? drag;
  double lastX = 0;

  /// The bar's own pixels. The boundary deliberately excludes the lens, so the
  /// lens can never refract an image of itself.
  final GlobalKey materialKey = GlobalKey();
  ui.Image? material;

  /// Identity of the captured material (theme/size/page/selection).
  String? materialSignature;
  int materialRetries = 0;
  bool capturing = false;
  bool captureQueued = false;
  bool captureScheduled = false;
  PageController? attached;
  int lastRound = 0;

  @override
  void initState() {
    super.initState();
    _attach();
    // Shader loading is asynchronous: re-check the material once it resolves.
    // On failure the bar simply keeps its original painted look.
    GlassRefraction.ensure().then((_) {
      if (mounted) _scheduleMaterial();
    });
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    // Brightness and text scale change what the bar is made of.
    _scheduleMaterial();
  }

  @override
  void didUpdateWidget(GlassNavigation oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.controller != widget.controller) {
      _detach();
      _attach();
    }
    if (oldWidget.selected != widget.selected ||
        oldWidget.icons != widget.icons ||
        oldWidget.labels != widget.labels ||
        oldWidget.enabled != widget.enabled) {
      _scheduleMaterial();
    }
  }

  void _attach() {
    attached = widget.controller;
    widget.controller.addListener(_pageTick);
    lastRound = currentPage().round();
  }

  void _detach() {
    attached?.removeListener(_pageTick);
    attached = null;
  }

  /// The bar's own pixels change when the highlighted tab crosses a half page.
  void _pageTick() {
    final int round = currentPage().round();
    if (round == lastRound) return;
    lastRound = round;
    _scheduleMaterial();
  }

  @override
  void dispose() {
    // ScrollPosition owns and disposes its drag activity with PageView. Calling
    // cancel/goIdle here would notify an already detached Material ancestor.
    // Snapshots are left to the engine's handle finalizer: they can still be
    // referenced by the frame being rasterised, and a disposed image is fatal.
    _detach();
    drag = null;
    super.dispose();
  }

  void _scheduleMaterial() {
    if (!mounted || !GlassRefraction.enabled) {
      // Refraction can be switched on before the shader was ever attempted
      // (or after a reset); try once, never retry a failed attempt.
      if (mounted &&
          GlassRefraction.preference &&
          GlassRefraction.state == GlassRefractionState.idle) {
        GlassRefraction.ensure().then((_) {
          if (mounted) _scheduleMaterial();
        });
      }
      return;
    }
    if (captureScheduled) return;
    captureScheduled = true;
    final WidgetsBinding binding = WidgetsBinding.instance;
    binding.addPostFrameCallback((_) {
      captureScheduled = false;
      if (mounted) captureMaterial();
    });
    // A post-frame callback does not request a frame by itself. Without this
    // the material would only be captured the next time something else paints,
    // so an idle bar would keep the fallback look after the shader resolves.
    if (!binding.hasScheduledFrame) binding.scheduleFrame();
  }

  Future<void> captureMaterial() async {
    if (!mounted || !GlassRefraction.enabled) return;
    final RenderObject? object =
        materialKey.currentContext?.findRenderObject();
    if (object is! RenderRepaintBoundary) return;
    final String signature = '${Theme.of(context).brightness == Brightness.dark ? 'd' : 'l'}|'
        '${object.size.width.round()}x${object.size.height.round()}|'
        '${currentPage().round()}|${widget.selected}';
    if (signature == materialSignature) return;
    if (capturing) {
      captureQueued = true;
      return;
    }
    materialSignature = signature;
    capturing = true;
    final ui.Image? image = await GlassRefraction.snapshot(object);
    capturing = false;
    if (!mounted) return;
    // 折射素材抓取失败会静默回落，设备上无从判断；留一条诊断。
    if (image == null) {
      debugPrint('GlassRefraction: 素材快照失败，回落原有外观'
          '（${GlassRefraction.state}${GlassRefraction.failure == null ? '' : ' / ${GlassRefraction.failure}'}）');
      // 首帧时导航条可能还没绘制出 layer，toImage 会空断言。签名已记账，
      // 不清掉就永远不会再抓 —— 折射会永久回落。清签名并安排有限次重试。
      materialSignature = null;
      if (materialRetries < 8) {
        materialRetries++;
        _scheduleMaterial();
      }
    } else {
      materialRetries = 0;
      setState(() => material = image);
    }
    if (captureQueued) {
      captureQueued = false;
      await captureMaterial();
    }
  }

  void finish() {
    if (drag == null || !widget.controller.hasClients) return;
    final nearest =
        widget.controller.page!.round().clamp(0, widget.labels.length - 1);
    final active = drag;
    drag = null;
    // Nav displacement is amplified by the viewport/cell ratio. Do not amplify
    // release velocity too: the native reference also snaps to the nearest page.
    active!.end(DragEndDetails(velocity: Velocity.zero, primaryVelocity: 0));
    widget.controller.animateToPage(nearest,
        duration: const Duration(milliseconds: 220),
        curve: Curves.easeOutCubic);
  }

  double currentPage() => widget.controller.hasClients &&
          widget.controller.position.hasContentDimensions
      ? widget.controller.page ?? widget.selected.toDouble()
      : widget.selected.toDouble();

  /// One tappable tab per label, unchanged from the painted bar.
  Widget tabs() => Row(
      children: List.generate(
          widget.labels.length,
          (i) => Expanded(
              child: Semantics(
                  key: ValueKey('workspace-tab-$i'),
                  label: widget.labels[i],
                  button: true,
                  enabled: widget.enabled,
                  onTap: widget.enabled ? () => widget.onSelect(i) : null,
                  selected: widget.selected == i,
                  child: ExcludeSemantics(
                      child: InkWell(
                          borderRadius: BorderRadius.circular(24),
                          onTap:
                              widget.enabled ? () => widget.onSelect(i) : null,
                          child: const SizedBox(height: 48)))))));

  @override
  Widget build(BuildContext context) => LayoutBuilder(builder: (context, box) {
        final bool dark = Theme.of(context).brightness == Brightness.dark;
        final bool rtl = Directionality.of(context) == TextDirection.rtl;
        double page() => currentPage().clamp(0.0, widget.labels.length - 1.0);
        final ui.Image? source = GlassRefraction.enabled ? material : null;
        final ui.FragmentProgram? shader =
            GlassRefraction.enabled ? GlassRefraction.program : null;
        return MediaQuery(
            data: MediaQuery.of(context).copyWith(
                gestureSettings: const DeviceGestureSettings(touchSlop: 8)),
            child: GestureDetector(
                key: const ValueKey('workspace-navigation'),
                behavior: HitTestBehavior.opaque,
                dragStartBehavior: DragStartBehavior.down,
                onHorizontalDragStart: !widget.enabled
                    ? null
                    : (details) {
                        if (!widget.controller.hasClients) return;
                        FocusScope.of(context).unfocus();
                        lastX = details.localPosition.dx;
                        drag = widget.controller.position
                            .drag(details, () => drag = null);
                      },
                onHorizontalDragUpdate: !widget.enabled
                    ? null
                    : (details) {
                        final scale = widget.controller.hasClients &&
                                widget.controller.position.hasViewportDimension
                            ? widget.controller.position.viewportDimension /
                                (box.maxWidth / widget.labels.length)
                            : 1.0;
                        final delta =
                            -(details.localPosition.dx - lastX) * scale;
                        lastX = details.localPosition.dx;
                        drag?.update(DragUpdateDetails(
                            sourceTimeStamp: details.sourceTimeStamp,
                            delta: Offset(delta, 0),
                            primaryDelta: delta,
                            globalPosition: details.globalPosition,
                            localPosition: details.localPosition));
                      },
                onHorizontalDragEnd: !widget.enabled ? null : (_) => finish(),
                onHorizontalDragCancel: !widget.enabled ? null : finish,
                child: Stack(children: <Widget>[
                  // Material layer: the bar's own pixels, captured for the lens.
                  RepaintBoundary(
                      key: materialKey,
                      child: Container(
                          color: GlassBackground.colourFor(dark),
                          child: GlassSurface(
                              radius: 24,
                              padding: EdgeInsets.zero,
                              border: false,
                              child: AnimatedBuilder(
                                  animation: widget.controller,
                                  builder: (context, _) => CustomPaint(
                                      painter: _NavMaterialPainter(
                                          page: page(),
                                          dark: dark,
                                          rtl: rtl,
                                          icons: widget.icons),
                                      child: tabs()))))),
                  // Lens layer: refracted pixels when the shader is usable,
                  // otherwise the original painted lens.
                  Positioned.fill(
                      child: IgnorePointer(
                          child: AnimatedBuilder(
                              animation: widget.controller,
                              builder: (context, _) => CustomPaint(
                                  size: Size.infinite,
                                  painter: _LensPainter(
                                      page: page(),
                                      dark: dark,
                                      rtl: rtl,
                                      icons: widget.icons,
                                      material: source,
                                      program: shader)))))
                ])));
      });
}

void _paintGlyph(Canvas canvas, Size size, IconData icon, double x,
    bool highlight, bool dark) {
  final TextPainter text = TextPainter(
      textDirection: TextDirection.ltr,
      text: TextSpan(
          text: String.fromCharCode(icon.codePoint),
          style: TextStyle(
              fontFamily: icon.fontFamily,
              package: icon.fontPackage,
              fontSize: 22,
              color: highlight
                  ? AppTheme.primaryFor(dark)
                  : (dark
                      ? const Color(0xffa5afb2)
                      : const Color(0xff858c95)))));
  text.layout();
  text.paint(canvas,
      Offset(x - text.width / 2, (size.height - text.height) / 2));
}

void _paintGlyphs(Canvas canvas, Size size, List<IconData> icons, double page,
    bool dark, bool rtl) {
  final double cell = size.width / icons.length;
  for (var i = 0; i < icons.length; i++) {
    final double x = (rtl ? icons.length - .5 - i : i + .5) * cell;
    _paintGlyph(canvas, size, icons[i], x, i == page.round(), dark);
  }
}

/// The bar's own material: the vector icons the lens will refract.
class _NavMaterialPainter extends CustomPainter {
  final double page;
  final bool dark, rtl;
  final List<IconData> icons;
  _NavMaterialPainter(
      {required this.page,
      required this.dark,
      required this.rtl,
      required this.icons});
  @override
  void paint(Canvas canvas, Size size) {
    _paintGlyphs(canvas, size, icons, page, dark, rtl);
  }

  @override
  bool shouldRepaint(_NavMaterialPainter old) =>
      page != old.page ||
      dark != old.dark ||
      rtl != old.rtl ||
      icons != old.icons;
}

class _LensPainter extends CustomPainter {
  final double page;
  final bool dark, rtl;
  final List<IconData> icons;
  final ui.Image? material;
  final ui.FragmentProgram? program;
  _LensPainter(
      {required this.page,
      required this.dark,
      required this.rtl,
      required this.icons,
      this.material,
      this.program});

  @override
  void paint(Canvas canvas, Size size) {
    if (icons.isEmpty || size.width <= 0 || size.height <= 0) return;
    final cell = size.width / icons.length;
    final center = Offset(
        (rtl ? icons.length - .5 - page : page + .5) * cell, size.height / 2);
    final stretch = math.sin((page - page.floor()) * math.pi);
    final width = math.min(cell * (.66 + .30 * stretch), size.width - 8);
    final height = math.min(size.height - 8, 34 + 4 * stretch);
    final lens = RRect.fromRectAndRadius(
        Rect.fromCenter(center: center, width: width, height: height),
        const Radius.circular(24));

    final ui.Image? source = material;
    final ui.FragmentProgram? shader = program;
    bool refracted = false;
    if (source != null && shader != null) {
      // Returns false (and paints nothing) whenever refraction cannot be
      // trusted: unsupported device, stale snapshot, shader mismatch.
      refracted = GlassRefractionLens.paint(
          canvas: canvas,
          lens: lens,
          source: source,
          program: shader,
          paintSize: size,
          stretch: stretch,
          dark: dark);
    }
    if (!refracted) {
      // 三处失败点里最后一处：素材和着色器都在，但装配/几何不变量没过。
      if (source != null && shader != null) {
        debugPrint('GlassRefraction: 镜片装配失败，回落原有外观');
      }
      canvas.drawRRect(
          lens,
          Paint()
            ..shader = LinearGradient(
                    begin: Alignment.topLeft,
                    end: Alignment.bottomRight,
                    colors: dark
                        ? const [Color(0xff344947), Color(0xff263936)]
                        : const [Color(0xffecf7f2), Color(0xffdcebe6)])
                .createShader(lens.outerRect));
      canvas.save();
      canvas.clipRRect(lens);
      canvas.translate(center.dx, center.dy);
      canvas.scale(1.06 + .32 * stretch, 1.06 + .16 * stretch);
      canvas.translate(-center.dx, -center.dy);
      _paintGlyphs(canvas, size, icons, page, dark, rtl);
      canvas.restore();
    }
    canvas.drawRRect(
        lens.deflate(.5),
        Paint()
          ..style = PaintingStyle.stroke
          ..strokeWidth = 1
          ..color = Colors.white.withOpacity(dark ? .20 : .8));
  }

  @override
  bool shouldRepaint(_LensPainter old) =>
      page != old.page ||
      dark != old.dark ||
      rtl != old.rtl ||
      icons != old.icons ||
      material != old.material ||
      program != old.program;
}
