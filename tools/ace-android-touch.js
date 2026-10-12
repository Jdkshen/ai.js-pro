/* Retained Babel helpers used only by the Android velocity regression module. */
function _defineProperty2(e,t,n){return t in e?Object.defineProperty(e,t,{value:n,enumerable:!0,configurable:!0,writable:!0}):e[t]=n,e}function _toConsumableArray(e){return _arrayWithoutHoles(e)||_iterableToArray(e)||_unsupportedIterableToArray(e)||_nonIterableSpread()}function _nonIterableSpread(){throw new TypeError("Invalid attempt to spread non-iterable instance.\nIn order to be iterable, non-array objects must have a [Symbol.iterator]() method.")}function _unsupportedIterableToArray(e,t){if(!e)return;if(typeof e=="string")return _arrayLikeToArray(e,t);var n=Object.prototype.toString.call(e).slice(8,-1);n==="Object"&&e.constructor&&(n=e.constructor.name);if(n==="Map"||n==="Set")return Array.from(e);if(n==="Arguments"||/^(?:Ui|I)nt(?:8|16|32)(?:Clamped)?Array$/.test(n))return _arrayLikeToArray(e,t)}function _iterableToArray(e){if(typeof Symbol!="undefined"&&Symbol.iterator in Object(e))return Array.from(e)}function _arrayWithoutHoles(e){if(Array.isArray(e))return _arrayLikeToArray(e)}function _arrayLikeToArray(e,t){if(t==null||t>e.length)t=e.length;for(var n=0,r=new Array(t);n<t;n++)r[n]=e[n];return r}function ownKeys(e,t){var n=Object.keys(e);if(Object.getOwnPropertySymbols){var r=Object.getOwnPropertySymbols(e);t&&(r=r.filter(function(t){return Object.getOwnPropertyDescriptor(e,t).enumerable})),n.push.apply(n,r)}return n}function _objectSpread(e){for(var t=1;t<arguments.length;t++){var n=arguments[t]!=null?arguments[t]:{};t%2?ownKeys(Object(n),!0).forEach(function(t){_defineProperty(e,t,n[t])}):Object.getOwnPropertyDescriptors?Object.defineProperties(e,Object.getOwnPropertyDescriptors(n)):ownKeys(Object(n)).forEach(function(t){Object.defineProperty(e,t,Object.getOwnPropertyDescriptor(n,t))})}return e}function _defineProperty(e,t,n){return t in e?Object.defineProperty(e,t,{value:n,enumerable:!0,configurable:!0,writable:!0}):e[t]=n,e}
/* Android touch selection, native action mode, magnifier and frame-paced inertia.
 * Retained from the verified dev-890 port; built into Ace 1.44.0. */
ace.define("ace/mouse/scroller.js", [], function (e, t, n) {
    "use strict";
    function p() { return Date.now(); }
    function d(e, t, n) { this.mInterpolator = e, this.mFlywheel = t, this.mFinished = !0, this.mPpi = 440, this.mFlingFriction = n, this.mPhysicalCoeff = this.computeDeceleration(.84), this.mDeceleration = this.computeDeceleration(n), this.mCurrenVelocity = 0, this.mVelocity = 0, this.mDuration = 0, this.mStartTime = 0, this.mStartX = 0, this.mStartY = 0, this.mDistance = 0, this.mMinX = 0, this.mMinY = 0, this.mMaxX = 0, this.mMaxY = 0, this.mFinalX = 0, this.mFinalY = 0; }
    function v() { var e = 0, t = 0; for (var n = 0; n < l; n++) {
        var r = n / l, i = 1, s, u, p;
        for (;;) {
            s = e + (i - e) / 2, p = 3 * s * (1 - s), u = p * ((1 - s) * a + s * f) + s * s * s;
            if (Math.abs(u - r) < 1e-5)
                break;
            u > r ? i = s : e = s;
        }
        c[n] = p * ((1 - s) * o + s) + s * s * s;
        var d = 1, v, m;
        for (;;) {
            v = t + (d - t) / 2, p = 3 * v * (1 - v), m = p * ((1 - v) * o + v) + v * v * v;
            if (Math.abs(m - r) < 1e-5)
                break;
            m > r ? d = v : t = v;
        }
        h[n] = p * ((1 - v) * a + v * f) + v * v * v;
    } c[l] = h[l] = 1; }
    var r = 9.80665, i = Math.log(.78) / Math.log(.9), s = .35, o = .5, u = 1, a = o * s, f = 1 - u * (1 - s), l = 100, c = new Array(l + 1), h = new Array(l + 1);
    v(), d.createDefault = function () { return new d(null, !0, .015); }, d.prototype.fling = function (e, t, n, r, i, s, o, u) { if (this.mFlywheel && !this.mFinished) {
        var a = this.getCurrVelocity(), f = this.mFinalX - this.mStartX, l = this.mFinalY - this.mStartY, c = Math.hypot(f, l), h = f / c, d = l / c, v = h * a, m = d * a;
        Math.signum(n) == Math.signum(v) && Math.signum(r) == Math.signum(m) && (n += v, r += m);
    } this.mFinished = !1; var g = Math.hypot(n, r); this.mVelocity = g, this.mDuration = this.getSplineFlingDuration(g), this.mStartTime = p(), this.mStartX = e, this.mStartY = t; var y = g == 0 ? 1 : n / g, b = g == 0 ? 1 : r / g, w = this.getSplineFlingDistance(g); this.mDistance = parseInt(w * Math.signum(g)), this.mMinX = i, this.mMaxX = s, this.mMinY = o, this.mMaxY = u, this.mFinalX = e + Math.round(w * y), this.mFinalX = Math.min(this.mFinalX, this.mMaxX), this.mFinalX = Math.max(this.mFinalX, this.mMinX), this.mFinalY = t + Math.round(w * b), this.mFinalY = Math.min(this.mFinalY, this.mMaxY), this.mFinalY = Math.max(this.mFinalY, this.mMinY); }, d.prototype.computeDeceleration = function (e) { return r * 39.37 * this.mPpi * e; }, d.prototype.getCurrVelocity = function () { return this.mCurrenVelocity; }, d.prototype.getSplineFlingDuration = function (e) { var t = this.getSplineDeceleration(e), n = i - 1; return parseInt(1e3 * Math.exp(t / n)); }, d.prototype.getSplineFlingDistance = function (e) { var t = this.getSplineDeceleration(e), n = i - 1; return this.mFlingFriction * this.mPhysicalCoeff * Math.exp(i / n * t); }, d.prototype.getSplineDeceleration = function (e) { return Math.log(s * Math.abs(e) / (this.mFlingFriction * this.mPhysicalCoeff)); }, d.prototype.computeScrollOffset = function () { if (this.mFinished)
        return !1; var e = parseInt(p() - this.mStartTime); if (e < this.mDuration) {
        var t = e / this.mDuration, n = parseInt(l * t), r = 1, i = 0;
        if (n < l) {
            var s = n / l, o = (n + 1) / l, u = c[n], a = c[n + 1];
            i = (a - u) / (o - s), r = u + (t - s) * i;
        }
        this.mCurrVelocity = i * this.mDistance / this.mDuration * 1e3, this.mCurrX = this.mStartX + Math.round(r * (this.mFinalX - this.mStartX)), this.mCurrX = Math.min(this.mCurrX, this.mMaxX), this.mCurrX = Math.max(this.mCurrX, this.mMinX), this.mCurrY = this.mStartY + Math.round(r * (this.mFinalY - this.mStartY)), this.mCurrY = Math.min(this.mCurrY, this.mMaxY), this.mCurrY = Math.max(this.mCurrY, this.mMinY), this.mCurrX == this.mFinalX && this.mCurrY == this.mFinalY && (this.mFinished = !0);
    }
    else
        this.mCurrX = this.mFinalX, this.mCurrY = this.mFinalY, this.mFinished = !0; return !0; }, d.prototype.getCurrX = function () { return this.mCurrX; }, d.prototype.getCurrY = function () { return this.mCurrY; }, d.prototype.isFinished = function () { return this.mFinished; }, d.prototype.abortAnimation = function () { this.mCurrX = this.mFinalX, this.mCurrY = this.mFinalY, this.mFinished = !0; }, t.Scroller = d;
});
ace.define("ace/mouse/regression.js", [], function (e, t, n) { function i(e, t) { var n = [], r = []; e.forEach(function (e, i) { e[1] !== null && (r.push(e), n.push(t[i])); }); var i = r.reduce(function (e, t) { return e + t[1]; }, 0), s = i / r.length, o = r.reduce(function (e, t) { var n = t[1] - s; return e + n * n; }, 0), u = r.reduce(function (e, t, r) { var i = n[r], s = t[1] - i[1]; return e + s * s; }, 0); return 1 - u / o; } function s(e, t) { var n = e, r = e.length - 1, i = [t]; for (var s = 0; s < r; s++) {
    var o = s;
    for (var u = s + 1; u < r; u++)
        Math.abs(n[s][u]) > Math.abs(n[s][o]) && (o = u);
    for (var a = s; a < r + 1; a++) {
        var f = n[a][s];
        n[a][s] = n[a][o], n[a][o] = f;
    }
    for (var l = s + 1; l < r; l++)
        for (var c = r; c >= s; c--)
            n[c][l] -= n[c][s] * n[s][l] / n[s][s];
} for (var h = r - 1; h >= 0; h--) {
    var p = 0;
    for (var d = h + 1; d < r; d++)
        p += n[d][h] * i[d];
    i[h] = (n[r][h] - p) / n[h][h];
} return i; } function o(e, t) { var n = Math.pow(10, t); return Math.round(e * n) / n; } function a() { var e = function (t, n) { return _objectSpread(_objectSpread({ _round: o }, t), {}, _defineProperty2({}, n, function (e, t) { return u[n](e, _objectSpread(_objectSpread({}, r), t)); })); }; return Object.keys(u).reduce(e, {}); } var r = { order: 2, precision: 2, period: null }, u = { linear: function (t, n) { var r = [0, 0, 0, 0, 0], s = 0; for (var u = 0; u < t.length; u++)
        t[u][1] !== null && (s++, r[0] += t[u][0], r[1] += t[u][1], r[2] += t[u][0] * t[u][0], r[3] += t[u][0] * t[u][1], r[4] += t[u][1] * t[u][1]); var a = s * r[2] - r[0] * r[0], f = s * r[3] - r[0] * r[1], l = a === 0 ? 0 : o(f / a, n.precision), c = o(r[1] / s - l * r[0] / s, n.precision), h = function (t) { return [o(t, n.precision), o(l * t + c, n.precision)]; }, p = t.map(function (e) { return h(e[0]); }); return { points: p, predict: h, equation: [l, c], r2: o(i(t, p), n.precision), string: c === 0 ? "y = ".concat(l, "x") : "y = ".concat(l, "x + ").concat(c) }; }, exponential: function (t, n) { var r = [0, 0, 0, 0, 0, 0]; for (var s = 0; s < t.length; s++)
        t[s][1] !== null && (r[0] += t[s][0], r[1] += t[s][1], r[2] += t[s][0] * t[s][0] * t[s][1], r[3] += t[s][1] * Math.log(t[s][1]), r[4] += t[s][0] * t[s][1] * Math.log(t[s][1]), r[5] += t[s][0] * t[s][1]); var u = r[1] * r[2] - r[5] * r[5], a = Math.exp((r[2] * r[3] - r[5] * r[4]) / u), f = (r[1] * r[4] - r[5] * r[3]) / u, l = o(a, n.precision), c = o(f, n.precision), h = function (t) { return [o(t, n.precision), o(l * Math.exp(c * t), n.precision)]; }, p = t.map(function (e) { return h(e[0]); }); return { points: p, predict: h, equation: [l, c], string: "y = ".concat(l, "e^(").concat(c, "x)"), r2: o(i(t, p), n.precision) }; }, logarithmic: function (t, n) { var r = [0, 0, 0, 0], s = t.length; for (var u = 0; u < s; u++)
        t[u][1] !== null && (r[0] += Math.log(t[u][0]), r[1] += t[u][1] * Math.log(t[u][0]), r[2] += t[u][1], r[3] += Math.pow(Math.log(t[u][0]), 2)); var a = (s * r[1] - r[2] * r[0]) / (s * r[3] - r[0] * r[0]), f = o(a, n.precision), l = o((r[2] - f * r[0]) / s, n.precision), c = function (t) { return [o(t, n.precision), o(o(l + f * Math.log(t), n.precision), n.precision)]; }, h = t.map(function (e) { return c(e[0]); }); return { points: h, predict: c, equation: [l, f], string: "y = ".concat(l, " + ").concat(f, " ln(x)"), r2: o(i(t, h), n.precision) }; }, power: function (t, n) { var r = [0, 0, 0, 0, 0], s = t.length; for (var u = 0; u < s; u++)
        t[u][1] !== null && (r[0] += Math.log(t[u][0]), r[1] += Math.log(t[u][1]) * Math.log(t[u][0]), r[2] += Math.log(t[u][1]), r[3] += Math.pow(Math.log(t[u][0]), 2)); var a = (s * r[1] - r[0] * r[2]) / (s * r[3] - Math.pow(r[0], 2)), f = (r[2] - a * r[0]) / s, l = o(Math.exp(f), n.precision), c = o(a, n.precision), h = function (t) { return [o(t, n.precision), o(o(l * Math.pow(t, c), n.precision), n.precision)]; }, p = t.map(function (e) { return h(e[0]); }); return { points: p, predict: h, equation: [l, c], string: "y = ".concat(l, "x^").concat(c), r2: o(i(t, p), n.precision) }; }, polynomial: function (t, n) { var r = [], u = [], a = 0, f = 0, l = t.length, c = n.order + 1; for (var h = 0; h < c; h++) {
        for (var p = 0; p < l; p++)
            t[p][1] !== null && (a += Math.pow(t[p][0], h) * t[p][1]);
        r.push(a), a = 0;
        var d = [];
        for (var v = 0; v < c; v++) {
            for (var m = 0; m < l; m++)
                t[m][1] !== null && (f += Math.pow(t[m][0], h + v));
            d.push(f), f = 0;
        }
        u.push(d);
    } u.push(r); var g = s(u, c).map(function (e) { return o(e, n.precision); }), y = function (t) { return [o(t, n.precision), o(g.reduce(function (e, n, r) { return e + n * Math.pow(t, r); }, 0), n.precision)]; }, b = t.map(function (e) { return y(e[0]); }), w = "y = "; for (var E = g.length - 1; E >= 0; E--)
        E > 1 ? w += "".concat(g[E], "x^").concat(E, " + ") : E === 1 ? w += "".concat(g[E], "x + ") : w += g[E]; return { string: w, points: b, predict: y, equation: _toConsumableArray(g).reverse(), r2: o(i(t, b), n.precision) }; } }; n.exports = a(); });
ace.define("ace/mouse/velocity-tracker.js", [], function (e, t) {
    "use strict";
    var n = 2, r = 100, i = function () { function e(e, t, n) { this.x = e, this.y = t, this.timeStamp = n; } return e; }();
    t.Point = i;
    var s = e("./regression.js"), o = function () { function e() { this.clear(); } function t(e) { if (e.length < 2)
        return 0; var t = e >= 3 ? 2 : 1, n = s.polynomial(e, { order: t }); return n.equation[n.equation.length - 2]; } return e.prototype.clear = function () { this.points = new Array, this.vx = 0, this.vy = 0, this.kotlin = "", this.js = ""; }, e.prototype.addMovement = function (e, t) { typeof t == "undefined" && (t = Date.now()), this.points.push(new i(e.x, e.y, t)); }, e.prototype.getPoint = function (e) { return this.points[e]; }, e.prototype.computeVelocity = function (e) { if (this.points.length < 2)
        return this.vx = 0, this.vy = 0, !1; var n = this.points[this.points.length - 1], i = new Array, s = new Array; for (var o = this.points.length - 1; o >= 0; o--) {
        var u = this.points[o], a = n.timeStamp - u.timeStamp;
        if (a > r)
            break;
        i.push([-a, u.x]), s.push([-a, u.y]);
    } return this.points.length < 2 ? (this.vx = 0, this.vy = 0, !1) : (this.vx = -t(i) * e, this.vy = -t(s) * e, !0); }, e.prototype.getXVelocity = function () { return this.vx; }, e.prototype.getYVelocity = function (e, t) { return this.vy; }, e; }();
    t.VelocityTracker = o;
}), Math.signum || (Math.signum = function (e) { return e === 0 ? 0 : e > 0 ? 1 : -1; });
ace.define("ace/mouse/magnifier.js", [], function (e, t, n) {
    "use strict";
    t.Magnifier = function (e, t) { function a(e, t, n, r, i) { if (r < n)
        return null; var s = Math.floor(n + (r - n) / 2), o = i(e[s], t); return o === 0 ? s : o < 0 ? a(e, t, n, s - 1, i) : a(e, t, s + 1, r, i); } var n = 8, r = 56, i = 48; this.zoom = 1; var s = document.createElement("div"); s.id = "magnifier", s.style.display = "none", e.appendChild(s), this.container = s; var o = document.createElement("div"); o.id = "magnifier-content", s.appendChild(o), this.element = o; var u = document.createElement("div"); s.appendChild(u), u.id = "magnifier-progress", u.style.display = "none", this.progress = u, this.shown = !1, this.$selectionStart = this.$selectionEnd = 0, this.$lines = [], this.$delayedShowTimer = null, this.show = function (e, n, r) { e = t.session.documentToScreenPosition(e), this.shown || (this.container.style.display = "block"), e.row != this.row && this.$updateActiveLine(e.row); for (var i = 0; i < this.$lines.length; i++)
        this.$updateSelection(this.$lines[i]); this.$moveToPosition(e), this.$updateLocation(n), this.shown = !0; }, this.$updateLocation = function (e) { var n = r, i = e.y / this.zoom - n - this.element.offsetHeight / this.zoom; i < 0 && (i = e.y / this.zoom + n * 1.5); var s = Math.max(this.element.offsetWidth + t.renderer.gutterWidth, e.x) / this.zoom; this.container.style.left = s + "px", this.container.style.top = i + "px"; }, this.$delayedShow = function (e, t) { this.$delayedShowTimer ? clearTimeout(this.$delayedShowTimer) : this.progress.style.display = "block"; var n = this; this.$delayedShowTimer = setTimeout(function () { n.$delayedShowTimer = null, n.$updateActiveLine(e.row), n.progress.style.display = "none", n.show(e, t, !0); }, i); }, this.hide = function () { this.$delayedShowTimer && (clearTimeout(this.$delayedShowTimer), this.$delayedShowTimer = null), this.shown && (this.container.style.display = "none"), this.shown = !1; }, this.$moveToPosition = function (e) { var n = t.renderer.layerConfig.characterWidth, r = this.element.offsetWidth, i = -n * e.column + r / 2; for (var s = 0; s < this.$lines.length; s++) {
        var o = this.$lines[s];
        o.element.style.transform = "translateY(" + o.translateY + ") translateX(" + i + "px)", o.selection && (o.selection.element.style.transform = "translateY(" + o.translateY + ") translateX(" + (i + n * o.selection.range.start.column) + "px)");
    } }, this.$updateSelection = function (e) { var n = t.selection.getRange(); if (n.isEmpty() || e.row < n.start.row || e.row > n.end.row) {
        e.selection && (e.selection.element.style.display = "none");
        return;
    } var r = document.querySelector(".ace_selection"); if (!e.selection) {
        if (!r)
            return;
        e.selection = {}, e.selection.element = this.$createSelectionElement(e, r), this.element.insertBefore(e.selection.element, e.element);
    } e.selection.element.style.display = "block"; var i = n.clipRows(e.row, e.row); i.start.row != i.end.row && i.start.row == e.row && (i.end.row = e.row, i.end.column = t.session.getLine(e.row).length), i = i.toScreenRange(t.session), e.selection.range = i; var s = t.renderer.layerConfig.characterWidth, o = e.selection.element.style; o.left = 0, o.width = (i.end.column - i.start.column) * s + "px", r && (o.background = window.getComputedStyle(r).background); }, this.$createSelectionElement = function (e, t) { var n = t.cloneNode(!0), r = n.style; return r.position = "absolute", r.top = e.top + 2 + "px", n; }, this.$updateActiveLine = function (e) { this.$removeLineElement(); var r = t.renderer.$textLayer.$lines.cells, i = a(r, e, 0, r.length - 1, function (e, t) { return t - e.row; }); if (i == null)
        return; var s = this.element.offsetHeight; this.$lines = [], i > 0 && this.$addLine(r[i - 1], e - 1, s / 2 - n, "-100%"), this.$addLine(r[i], e, s / 2, "-50%"), i < r.length - 1 && this.$addLine(r[i + 1], e + 1, s / 2 + n, "0%"), this.row = e; }, this.$addLine = function (e, t, n, r) { var i = { element: e.element.cloneNode(!0), row: t, top: n, translateY: r }; this.$setUpLineStyle(i, i.element.style), this.$lines.push(i), this.element.appendChild(i.element); }, this.$setUpLineStyle = function (e, t) { t.position = "absolute", t.top = e.top + "px"; }, this.$removeLineElement = function () { for (var e = 0; e < this.$lines.length; e++)
        this.element.removeChild(this.$lines[e].element), this.$lines[e].selection && this.element.removeChild(this.$lines[e].selection.element); this.$lines = []; }; };
});
ace.define("ace/mouse/touch_handler", [], function (e, t, n) {
    "use strict";
    var r = e("./mouse_event").MouseEvent, i = e("../lib/event"), s = e("../lib/dom");
    t.addTouchListeners = function (t, n, o) { function T() { return { minX: 0, maxX: M.layerConfig.width - M.$size.scrollerWidth + m, minY: 0, maxY: M.layerConfig.maxHeight - M.$size.scrollerHeight + m }; } function K(e) { return Q(e, "ace_gutter"); } function Q(e, t) { if (e == n.container)
        return s.hasCssClass(e, t); for (;;) {
        if (s.hasCssClass(e, t))
            return !0;
        e = e.parentNode;
        if (!e || e == n.container)
            return !1;
    } } function G() { _ && cancelAnimationFrame(_); _ = null; if (!H || !l.computeVelocity(1e3))
        return !1; var e = l.getYVelocity(), t = l.getXVelocity(); if (Math.abs(t) < p || Math.abs(t) < Math.abs(e))
        t = 0; if (Math.abs(e) < p || Math.abs(e) < Math.abs(t))
        e = 0; if (t == 0 && e == 0)
        return !1; var r = T(); return c.fling(M.getScrollLeft(), M.getScrollTop(), t, e * 2, r.minX, r.maxX, r.minY, r.maxY), _ = requestAnimationFrame(function frame() { if (c.computeScrollOffset()) {
        M.scrollTo(c.getCurrX(), c.getCurrY());
        _ = requestAnimationFrame(frame);
    }
    else {
        _ = null;
        n.startActionModeIfSelectionInView(!1);
    } }), !0; } function Y() { var e = !document.hasFocus || !document.hasFocus() || !n.isFocused() && document.activeElement == (n.textInput && n.textInput.getElement()); e && window.focus(), n.focus(), window.port && window.port.showKeyboard(), typeof autojs != "undefined" && autojs.showSoftInput(); } function Z() { j = null; var e = n.selection.getRange(), t = n.selection.cursor, r = n.renderer.getClientPosition(t), i = n.renderer.layerConfig.lineHeight, s = !0; if (e.isEmpty())
        s = !1;
    else {
        var o = n.selection.anchor, u = n.renderer.getClientPosition(o), a = Math.min(r.y, u.y) - d, f = Math.max(r.y, u.y) + i + d;
        s = C > a && C < f;
        if (t.row == o.row && s) {
            var l = Math.min(r.x, u.x) - d, c = Math.max(r.x, u.x) + d;
            s = k > l && k < c;
        }
    } s ? B = "long_tap" : (n.selection.moveToPosition(L), n.selection.selectWord(), B = "cursor"), n.startActionMode(), typeof autojs != "undefined" && autojs.performLongPressFeedback(); } function et(e) { var t = n.selection.getWordRange(e.row, e.column); tt(e, t); } function tt(e, t) { var r, i = e, s = t.comparePoint(i); s == -1 ? (r = t.end, i = t.start) : (r = t.start, i = t.end), n.selection.setSelectionAnchor(r.row, r.column), n.selection.selectToPosition(i), n.renderer.scrollCursorIntoView(), n.startActionModeIfSelectionInView(!0); } function nt(e) { if (e.touches.length > 2)
        return ""; if (e.touches.length == 2)
        return "zoom"; var t = n.renderer.scroller.getBoundingClientRect(), r = z * n.renderer.layerConfig.offset, i = z * n.renderer.scrollLeft, s = n.selection.isEmpty(), o = n.selection.cursor, u = n.renderer.$cursorLayer.getPixelPosition(o, !0), a = rt(e.clientX - z * t.left - z * u.left + i, e.clientY - z * t.top - z * u.top + r); if (s)
        return a < v ? "cursor" : "scroll"; var f = n.selection.anchor, l = n.renderer.$cursorLayer.getPixelPosition(f, !0), c = rt(e.clientX - z * t.left - z * l.left + i, e.clientY - z * t.top - z * l.top + r); return a < v && c < v ? a < c ? "cursor" : "anchor" : c < v ? "anchor" : a < v ? "cursor" : "scroll"; } function rt(e, t) { var r = n.renderer.layerConfig.lineHeight, i = n.renderer.layerConfig.lineHeight; return e /= i, t = t / r - .75, e * e + t * t; } function it(e, t) { return Math.sqrt(e * e + t * t); } function st() { var e = n.selection.cursor, t = n.selection.anchor; if (t.row > e.row || t.column > e.column) {
        var r = e;
        e = t, t = r;
    } var i = { row: t.row, column: t.column - 5 }, s = { row: e.row, column: e.column + 5 }; n.renderer.scrollSelectionIntoView(i, s); } var u = e("./velocity-tracker.js").VelocityTracker, a = e("./scroller.js").Scroller, f = e("./magnifier.js").Magnifier, l = new u, c = a.createDefault(), h = new f(t, n), p = 50, d = 8, v = 3.5, m = 200, g = 500, y = .4, b = 2, w = .5, E = 10, S = 350, x = 300, N = 0, C = 0, k = 0, L = null, A = 0, O = 0, M = n.renderer, _ = null, D = 0, P = 0, H = !1, B = "", j = null, F = !1, I = 0, q = 1, R = 0, U = 0, z = 1, W = !1, X = 0, V = 0, $ = 0, J = n.container; n.on("changeSession", function () { j && clearTimeout(j), j = null, _ && cancelAnimationFrame(_), _ = null, c.abortAnimation(), B = "", W = !1, H = !1, h.hide(), X = 0; }); i.addListener(J, "touchstart", function (e) { if (n.popup || K(e.target))
        return; var t = e.touches, i = t[0], s = e.clientX = i.clientX, o = e.clientY = i.clientY; l.clear(), c.isFinished() ? H = !1 : (H = !0, c.abortAnimation()), _ && cancelAnimationFrame(_), _ = null, C = o, k = s, A = M.getScrollTop(), O = M.getScrollLeft(), D = s, P = o, N = e.timeStamp, F = !1; var u = new r(e, n); L = u.getDocumentPosition(), l.addMovement({ x: s, y: o }, e.timeStamp), B = nt(e), j && clearTimeout(j), B == "zoom" ? (I = it(t[1].clientX - t[0].clientX, t[1].clientY - t[0].clientY), R = (t[0].clientX + t[1].clientX) / 2, U = (t[0].clientY + t[1].clientY) / 2, q = z) : e.touches.length == 1 && (j = setTimeout(Z, g), W = !0), n.finishActionMode(), e.preventDefault(); }, n), i.addListener(J, "touchend", function (e) { j && clearTimeout(j), h.hide(); if (B == "zoom") {
        B = "";
        return;
    } if (W && e.timeStamp - N < S) {
        var t = e.timeStamp, r = e.changedTouches[0];
        if (t - X < x && rt(V - k, $ - C) < 2) {
            X = t, V = r.clientX, $ = r.clientY, et(L), e.preventDefault();
            return;
        }
        X = t, V = r.clientX, $ = r.clientY, n.selection.moveToPosition(L), n.renderer.scrollCursorIntoView(), Y();
    } if (B == "cursor" && n.selection.getRange().isEmpty()) {
        n.startActionMode(!1);
        return;
    } if (B == "anchor" || B == "cursor") {
        n.startActionModeIfSelectionInView(!0);
        return;
    } if (B == "scroll" && !G()) {
        n.startActionModeIfSelectionInView(!1);
        return;
    } }, n), i.addListener(J, "touchmove", function (e) { var t = e.touches; if (B == "zoom") {
        var i = it(Math.abs(R - t[0].clientX) + Math.abs(R - t[1].clientX), Math.abs(U - t[0].clientY) + Math.abs(U - t[1].clientY)), s = i - I, u = (I + s * w) / I;
        u = Math.round(u * E) / E;
        var a = Math.max(y, Math.min(b, u * q));
        z = a, h.zoom = z, document.body.style.zoom = a, n.renderer.onResize();
        return;
    } if (t.length > 1 || B == "zoom") {
        W = !1, j && clearTimeout(j), n.finishActionMode();
        return;
    } var f = t[0], c = e.clientX = f.clientX, p = e.clientY = f.clientY, v = rt(c - k, p - C) < 2; if (v && B == "scroll" && !F)
        return; if (v && (j || B == "long_tap"))
        return; n.finishActionMode(), W = !1, j && clearTimeout(j); if (B == "scroll") {
        var m = A - (p - C), g = O - (c - k), S = T();
        g = Math.min(S.maxX, Math.max(S.minX, g)), m = Math.min(S.maxY, Math.max(S.minY, m)), M.scrollTo(g, m), F = !0, n._emit("touchscroll"), l.addMovement({ x: c, y: p }, e.timeStamp), !H && (Math.abs(D - c) > d || Math.abs(P - p) > d) && (H = !0);
    }
    else {
        var x = new r(e, n), N = x.getDocumentPosition();
        B == "cursor" ? (n.selection.moveCursorToPosition(N), o.$magnifierEnabled && h.show(N, { x: c, y: p })) : B == "anchor" && (n.selection.setSelectionAnchor(N.row, N.column), o.$magnifierEnabled && h.show(N, { x: c, y: p }));
        var L = c > k ? 1 : -1;
        n.renderer.scrollCursorIntoView({ row: N.row, column: N.column + 5 * L }), e.preventDefault();
    } D = c, P = p; }, n); };
});
