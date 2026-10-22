/**
 * 圆点接力跳动 Loading 组件（Custom View：JavaAdapter + onDraw + ValueAnimator）
 * 注册标签: <DotJumpLoading />
 *
 * 属性（XML 写字符串，代码里走 set* 方法）:
 *   dotCount    圆点数量                整数，默认 5
 *   dotRadius   圆点半径                单位 dp，默认 6
 *   dotSpacing  相邻圆心间距            单位 dp，默认 32
 *   jumpHeight  起跳最大抬升高度        单位 dp，默认 24
 *   duration    一轮（两点互换）时长    单位 ms，默认 520
 *   dotColor    单色                    默认跟随主题 text 色
 *   dotColors   每点不同色              "色1|色2|色3"（不足循环取用，优先于 dotColor）
 *   autoStart   挂到窗口后自动播放       true / false，默认 true
 * 不写 w/h 时组件按内容自算尺寸（onMeasure）。
 *
 * 方法（onViewCreated 里挂到 view 上，ui.<id> 直接可调）:
 *   stop() / start() / isRunning()
 *   setDotCount(n) / setRadius(dp) / setSpacing(dp) / setJumpHeight(dp) / setDuration(ms)
 *   setColor("#fff") / setColors("a|b|c" 或 [a,b,c])
 *   setPhase(i) / setProgress(0~1) / getPhase() / getProgress()
 *
 * 用法:
 *   var DotJumpLoading = require("./widgets/DotJumpLoading.js");   // require 即完成 registerWidget
 *   ui.layout(<vertical><DotJumpLoading id="loading" w="200dp" h="80dp" dotColor="#fff"/></vertical>);
 *   ui.loading.stop();        // ui.<id> 就是自绘 View，方法直接可用
 */
var DotJumpLoading = (function () {
    importClass(android.graphics.Paint);
    importClass(android.animation.ValueAnimator);
    importClass(android.animation.Animator);
    importClass(android.view.animation.LinearInterpolator);

    const scale = context.getResources().getDisplayMetrics().density;

    /**
     * dp 转 px
     * @param {number} dp dp 数值
     * @returns {number} 像素（四舍五入）
     */
    function dp2px(dp) {
        return Math.floor(dp * scale + 0.5);
    }

    /**
     * 取数值属性，非法值回落默认
     * @param {*} value 原始值（XML 属性或代码传入）
     * @param {number} fallback 兜底值
     * @param {boolean=} allowZero 是否允许 0
     * @returns {number} 合法数值
     */
    function num(value, fallback, allowZero) {
        var parsed = Number(value);
        if (isNaN(parsed) || parsed < 0) {
            return fallback;
        }
        if (parsed === 0 && allowZero !== true) {
            return fallback;
        }
        return parsed;
    }

    /**
     * 归一化圆点数量
     * @param {*} value 原始值
     * @returns {number} 1~64 的整数，非法取 5
     */
    function fixDotCount(value) {
        var parsed = Math.round(Number(value));
        if (isNaN(parsed) || parsed < 1) {
            return 5;
        }
        return Math.min(parsed, 64);
    }

    /**
     * 归一化动画时长
     * @param {*} value 原始值
     * @returns {number} 60~10000 ms，非法取 520
     */
    function fixDuration(value) {
        var parsed = Number(value);
        if (isNaN(parsed) || parsed < 60) {
            return 520;
        }
        return Math.min(parsed, 10000);
    }

    /**
     * 把颜色集合拆成数组（支持 "a|b|c"、[a,b,c]、"a,b,c"）
     * @param {string|Array} source 颜色集合
     * @returns {string[]} 颜色字符串数组（可能为空）
     */
    function splitColors(source) {
        if (!source) {
            return [];
        }
        if (typeof source === "object" && typeof source.length === "number") {
            return Array.prototype.slice.call(source);
        }
        return String(source).split(/[|,]/).map(function (item) {
            return item.trim();
        }).filter(function (item) {
            return item.length > 0;
        });
    }

    /**
     * 主题正文色，取不到时用白色
     * @returns {string} 颜色文本
     */
    function themeTextColor() {
        if (typeof $getTheme === "function") {
            var theme = $getTheme();
            if (theme && theme.text) {
                return theme.text;
            }
        }
        return "#FFFFFF";
    }

    /**
     * 按数量生成每点颜色 int 数组（不足循环取用）
     * @param {string[]} palette 颜色文本数组
     * @param {number} dotCount 圆点数量
     * @param {string} fallbackColor 单色兜底（颜色文本）
     * @returns {number[]} 长度 = dotCount
     */
    function buildColors(palette, dotCount, fallbackColor) {
        var result = [];
        for (var index = 0; index < dotCount; index++) {
            var text = palette.length === 0 ? fallbackColor : palette[index % palette.length];
            result.push(colors.parseColor(text));
        }
        return result;
    }

    util.extend(DotJumpLoading, ui.Widget);

    /**
     * 组件定义：状态放在 this._state（每个实例独立），render 交给 JavaAdapter 自绘 View
     */
    function DotJumpLoading() {
        ui.Widget.call(this);
        this._state = {
            dotCount: 5,
            radiusPx: dp2px(6),
            spacingPx: dp2px(32),
            jumpHeightPx: dp2px(24),
            stepDuration: 520,
            autoStart: true,
            colorText: null,        // 单色文本（null = 跟随主题）
            colorPalette: [],       // 每点颜色文本
            colors: [],             // 解析后的 int 数组
            phase: 1,
            progress: 0,
            running: false,
            animator: null,
            centers: []
        };

        // 属性回调在 onViewCreated 之后执行，因此这里的 view 已经有 set* 方法（实测顺序:
        // render → onViewCreated → defineAttr → onFinishInflation）
        this.defineAttr("dotCount", (view, attr, value) => {
            view.setDotCount(value);
        });
        this.defineAttr("dotRadius", (view, attr, value) => {
            view.setRadius(value);
        });
        this.defineAttr("dotSpacing", (view, attr, value) => {
            view.setSpacing(value);
        });
        this.defineAttr("jumpHeight", (view, attr, value) => {
            view.setJumpHeight(value);
        });
        this.defineAttr("duration", (view, attr, value) => {
            view.setDuration(value);
        });
        this.defineAttr("dotColor", (view, attr, value) => {
            view.setColor(value);
        });
        this.defineAttr("dotColors", (view, attr, value) => {
            view.setColors(value);
        });
        this.defineAttr("autoStart", (view, attr, value) => {
            view.setAutoStart(value === "true" || value === true);
        });
    }

    // 自绘 View：onMeasure 自算尺寸 + onDraw 画圆点 + 生命周期自动启停
    function DotLoadingView(state) {
        var hostView = null;

        this.bindHost = (view) => {
            hostView = view;
        };

        this.onMeasure = (widthSpec, heightSpec) => {
            var desiredWidth = (state.dotCount - 1) * state.spacingPx + state.radiusPx * 2;
            var desiredHeight = 2 * (state.radiusPx + state.jumpHeightPx);
            hostView.setMeasuredDimension(
                resolveSpec(widthSpec, desiredWidth),
                resolveSpec(heightSpec, desiredHeight)
            );
        };

        this.onDraw = (canvas) => {
            var width = hostView.getWidth();
            var height = hostView.getHeight();
            if (width <= 0 || height <= 0 || state.dotCount < 1) {
                return;
            }
            var spacing = state.spacingPx;
            var startX = (width - spacing * (state.dotCount - 1)) / 2;
            var baseY = height / 2;

            // 起跳点 phase 与让位点 phase+1（末位回绕到第一个）
            var jumpDot = state.phase;
            if (jumpDot < 1 || jumpDot > state.dotCount) {
                jumpDot = 1;
            }
            var passDot = jumpDot + 1;
            if (passDot > state.dotCount) {
                passDot = 1;
            }
            var jumpStartX = startX + (jumpDot - 1) * spacing;
            var jumpEndX = startX + (passDot - 1) * spacing;
            var progress = state.progress;
            var availableLift = Math.max(0, baseY - state.radiusPx);
            var arcLift = Math.sin(progress * Math.PI) * Math.min(state.jumpHeightPx, availableLift);

            var paint = state.paint;
            for (var index = 0; index < state.dotCount; index++) {
                var dotX = startX + index * spacing;
                var dotY = baseY;
                if (index + 1 === jumpDot) {
                    dotX = jumpStartX + (jumpEndX - jumpStartX) * progress;
                    dotY = baseY - arcLift;
                } else if (index + 1 === passDot) {
                    dotX = jumpEndX + (jumpStartX - jumpEndX) * progress;
                }
                paint.setColor(state.colors[index]);
                canvas.drawCircle(dotX, dotY, state.radiusPx, paint);
            }
        };

        this.onAttachedToWindow = () => {
            hostView.invalidate();
            if (state.autoStart) {
                startLoading(hostView, state);
            }
        };

        this.onDetachedFromWindow = () => {
            stopLoading(state);
        };
    }

    /**
     * 按 MeasureSpec 求最终尺寸
     * @param {number} spec MeasureSpec
     * @param {number} desired 期望尺寸（px）
     * @returns {number} 最终尺寸（px）
     */
    function resolveSpec(spec, desired) {
        var specMode = android.view.View.MeasureSpec.getMode(spec);
        var specSize = android.view.View.MeasureSpec.getSize(spec);
        if (specMode === android.view.View.MeasureSpec.EXACTLY) {
            return specSize;
        }
        if (specMode === android.view.View.MeasureSpec.AT_MOST) {
            return Math.min(Math.ceil(desired), specSize);
        }
        return Math.ceil(desired);
    }

    /**
     * 跳点索引前进一格（到末尾回到第 1 格）并复位进度
     * @param {Object} state 组件状态
     * @returns {void}
     * @sideeffect 改 state.phase / state.progress
     */
    function advancePhase(state) {
        var nextPhase = state.phase + 1;
        state.phase = nextPhase > state.dotCount ? 1 : nextPhase;
        state.progress = 0;
    }

    /**
     * 创建驱动动画的 ValueAnimator（已存在则复用）
     * @param {Object} view 自绘 View
     * @param {Object} state 组件状态
     * @returns {Object} ValueAnimator
     * @sideeffect 首次调用创建 animator 并挂 update / repeat 监听
     */
    function ensureAnimator(view, state) {
        if (state.animator) {
            return state.animator;
        }
        var animator = ValueAnimator.ofFloat(0, 1);
        animator.setDuration(state.stepDuration);
        animator.setInterpolator(new LinearInterpolator());
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setRepeatMode(ValueAnimator.RESTART);
        animator.addUpdateListener(new JavaAdapter(ValueAnimator.AnimatorUpdateListener, {
            onAnimationUpdate: function (runningAnimator) {
                var animated = runningAnimator.getAnimatedValue();
                state.progress = typeof animated === "number" ? animated : Number(animated.floatValue());
                view.invalidate();
            }
        }));
        animator.addListener(new JavaAdapter(Animator.AnimatorListener, {
            onAnimationStart: function () {},
            onAnimationEnd: function () {},
            onAnimationCancel: function () {},
            onAnimationRepeat: function () {
                advancePhase(state);
                view.invalidate();
            }
        }));
        state.animator = animator;
        return animator;
    }

    /**
     * 启动跳动
     * @param {Object} view 自绘 View
     * @param {Object} state 组件状态
     * @returns {boolean} true=正在播放；false=点数不足未启动
     * @sideeffect 创建/启动 animator，改 state.running
     */
    function startLoading(view, state) {
        if (state.dotCount < 2) {
            state.running = false;
            return false;
        }
        var animator = ensureAnimator(view, state);
        if (!animator.isStarted()) {
            animator.start();
        }
        state.running = true;
        return true;
    }

    /**
     * 停止跳动（画面停在当前帧）
     * @param {Object} state 组件状态
     * @returns {void}
     * @sideeffect 取消 animator，改 state.running
     */
    function stopLoading(state) {
        state.running = false;
        if (state.animator && state.animator.isStarted()) {
            state.animator.cancel();
        }
    }

    DotJumpLoading.prototype.render = function () {
        // 自绘实现挂在 this._impl 上，onViewCreated 里再把 view 交给它（onDraw 需要 getWidth）
        this._impl = new DotLoadingView(this._state);
        return JavaAdapter(android.view.View, this._impl, activity);
    };

    // 此时属性还没解析（defineAttr 在其后），这里只挂方法和默认表现
    DotJumpLoading.prototype.onViewCreated = function (view) {
        var state = this._state;

        var paint = new Paint();
        paint.setAntiAlias(true);
        paint.setStyle(Paint.Style.FILL);
        state.paint = paint;

        // ★ 关键：让自绘实现拿到这个 view（onDraw/onMeasure 里要 getWidth / setMeasuredDimension）
        this._impl.bindHost(view);

        // ---- 挂到 view 上的 API（defineAttr 与 ui.<id> 都能直接调）----

        /**
         * 重新解析颜色表
         * @returns {void}
         * @sideeffect 写 state.colors
         */
        function refreshColors() {
            var fallbackText = state.colorText || themeTextColor();
            state.colors = buildColors(state.colorPalette, state.dotCount, fallbackText);
        }

        view.setDotCount = function (value) {
            state.dotCount = fixDotCount(value);
            refreshColors();
            if (state.dotCount < 2) {
                state.phase = 1;
                state.progress = 0;
                stopLoading(state);
            } else if (state.phase > state.dotCount) {
                state.phase = 1;
            }
            view.requestLayout();
            view.invalidate();
        };

        view.setRadius = function (dp) {
            state.radiusPx = dp2px(num(dp, 6));
            view.requestLayout();
            view.invalidate();
        };

        view.setSpacing = function (dp) {
            state.spacingPx = dp2px(num(dp, 32));
            view.requestLayout();
            view.invalidate();
        };

        view.setJumpHeight = function (dp) {
            state.jumpHeightPx = dp2px(num(dp, 24, true));
            view.requestLayout();
            view.invalidate();
        };

        view.setDuration = function (ms) {
            state.stepDuration = fixDuration(ms);
            if (state.animator) {
                state.animator.setDuration(state.stepDuration);
            }
        };

        view.setColor = function (value) {
            state.colorPalette = [];
            state.colorText = value ? String(value) : null;
            refreshColors();
            view.invalidate();
        };

        view.setColors = function (value) {
            state.colorPalette = splitColors(value);
            refreshColors();
            view.invalidate();
        };

        view.setAutoStart = function (enabled) {
            state.autoStart = enabled !== false;
            if (state.autoStart) {
                if (view.isAttachedToWindow()) {
                    startLoading(view, state);
                }
                return;
            }
            stopLoading(state);
            view.invalidate();
        };

        view.setPhase = function (value) {
            var parsed = Math.round(Number(value));
            if (isNaN(parsed)) {
                return;
            }
            state.phase = Math.min(Math.max(parsed, 1), state.dotCount);
            view.invalidate();
        };

        view.setProgress = function (value) {
            var parsed = Number(value);
            if (isNaN(parsed)) {
                return;
            }
            state.progress = Math.min(Math.max(parsed, 0), 1);
            view.invalidate();
        };

        view.start = function () {
            return startLoading(view, state);
        };

        view.stop = function () {
            stopLoading(state);
            view.invalidate();
        };

        view.isRunning = function () {
            return state.running;
        };

        view.getPhase = function () {
            return state.phase;
        };

        view.getProgress = function () {
            return state.progress;
        };

        view.getDotCount = function () {
            return state.dotCount;
        };

        refreshColors();
    };

    ui.registerWidget("DotJumpLoading", DotJumpLoading);
    return DotJumpLoading;
})();

module.exports = DotJumpLoading;
