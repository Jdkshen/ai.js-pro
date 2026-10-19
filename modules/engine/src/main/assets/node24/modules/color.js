/*
 * Node24 内置模块：color（Pro 语义完整版，task-146）。
 *
 * 与 Pro assets/v8/built_in_modules/color.js 对齐：Color / HSVColor / HSLColor / toColorInt。
 * 兼容 prelude 基础别名（parseColor/argb/rgb/alpha/red/green/blue/isSimilar）与 Color.toInt()；
 * Color.parse 字符串失败返回 null，严格 module.parseColor 对无效输入抛 TypeError。
 * 本文件是独立 IIFE，可由 Node24Prelude 在 prelude 之后拼接加载（见同目录 REGISTER-PATCH.md），
 * 或由测试工程 require 后手动注册。不修改 prelude.js。
 */
(function () {
  'use strict';
  if (typeof globalThis.__ajsRegisterModule !== 'function') { return; }
  var UINT32 = 4294967296;
  var INT32_MAX = 0x7fffffff;
  var INT32_MIN = -0x80000000;
  var UINT32_MAX = 0xffffffff;

  function toUInt32(n) { return n < 0 ? n + UINT32 : n; }
  function requireNumber(value, label) {
    if (typeof value !== 'number' || !isFinite(value)) {
      throw new TypeError('[Auto.js] color.' + label + ' 必须是有限数字，收到：' + String(value));
    }
    return value;
  }
  function requireInteger(value, label) {
    requireNumber(value, label);
    if (Math.floor(value) !== value) { throw new TypeError('[Auto.js] color.' + label + ' 必须是整数，收到：' + value); }
    return value;
  }
  function requireColorInt(value, label) {
    requireInteger(value, label);
    // 颜色整数同时接受 signed int32 和无符号 32 位写法（0xFFFFFFFF -> -1）；
    // 超出范围拒绝，不静默截断。
    if (value < INT32_MIN || value > UINT32_MAX) {
      throw new RangeError('[Auto.js] color.' + label + ' 超出 32 位 ARGB 范围：' + value);
    }
    return value | 0;
  }
  function requireIntRange(value, label, min, max) {
    requireInteger(value, label);
    if (value < min || value > max) {
      throw new RangeError('[Auto.js] color.' + label + ' 必须在 ' + min + '..' + max + ' 之间，收到：' + value);
    }
    return value;
  }
  function assertRange(value, label, min, max) {
    requireNumber(value, label);
    if (value < min || value > max) {
      throw new RangeError('[Auto.js] color.' + label + ' 必须在 ' + min + '..' + max + ' 之间，收到：' + value);
    }
    return value;
  }
  function toColorInt(number) {
    return requireColorInt(number, 'toColorInt');
  }

  /* ------------------------------ Color ------------------------------ */
  function Color(value) {
    if (!(this instanceof Color)) { return new Color(value); }
    this._value = requireColorInt(value, 'Color');
  }
  Object.defineProperties(Color.prototype, {
    value: { enumerable: true, get: function () { return this._value; } },
    alpha: { enumerable: true, get: function () { return (this._value >>> 24) & 0xff; } },
    opacity: { enumerable: true, get: function () { return this.alpha / 0xff; } },
    red: { enumerable: true, get: function () { return (this._value >>> 16) & 0xff; } },
    green: { enumerable: true, get: function () { return (this._value >>> 8) & 0xff; } },
    blue: { enumerable: true, get: function () { return this._value & 0xff; } }
  });
  Color.prototype.toString = function () {
    return 'Color(0x' + ('00000000' + toUInt32(this._value).toString(16)).slice(-8) + ')';
  };
  Color.prototype.toInt = function () { return this._value; };
  Color.prototype.valueOf = function () { return this._value; };
  Color.prototype.equals = function (other) {
    try { return other instanceof Color && other.value === this._value; } catch (ignored) { return false; }
  };
  Color.prototype.withAlpha = function (alpha) {
    return Color.fromARGB(requireInteger(alpha, 'alpha'), this.red, this.green, this.blue);
  };
  Color.prototype.withOpacity = function (opacity) { return this.withAlpha(Math.round(requireNumber(opacity, 'opacity') * 0xff)); };
  Color.prototype.withRed = function (red) { return Color.fromARGB(this.alpha, red, this.green, this.blue); };
  Color.prototype.withGreen = function (green) { return Color.fromARGB(this.alpha, this.red, green, this.blue); };
  Color.prototype.withBlue = function (blue) { return Color.fromARGB(this.alpha, this.red, this.green, blue); };
  Color.prototype.isSimilarTo = function (other, options) {
    if (!(other instanceof Color)) { throw new TypeError('[Auto.js] color.Color.isSimilarTo 需要 Color 对象'); }
    var opts = options || {};
    var threshold = opts.threshold == null ? 16 : requireNumber(opts.threshold, 'threshold');
    var ignoreAlpha = opts.ignoreAlphaChannel == null ? true : !!opts.ignoreAlphaChannel;
    var channels = Math.abs(other.red - this.red) < threshold
      && Math.abs(other.green - this.green) < threshold
      && Math.abs(other.blue - this.blue) < threshold;
    if (!ignoreAlpha) { channels = channels && Math.abs(other.alpha - this.alpha) < threshold; }
    return channels;
  };
  Color.fromARGB = function (a, r, g, b) {
    return new Color((((a & 0xff) << 24) | ((r & 0xff) << 16) | ((g & 0xff) << 8) | (b & 0xff)) & 0xffffffff);
  };
  Color.fromRGB = function (r, g, b) { return Color.fromARGB(0xff, r, g, b); };
  Color.fromGray = function (gray) { return Color.fromARGB(0xff, gray, gray, gray); };
  Color.fromRGBO = function (r, g, b, opacity) {
    return new Color((((Math.round(requireNumber(opacity, 'opacity') * 0xff) & 0xff) << 24)
      | ((r & 0xff) << 16) | ((g & 0xff) << 8) | (b & 0xff)) & 0xffffffff);
  };
  function parseColorString(text) {
    if (text.charAt(0) !== '#') { return null; }
    var hex = text.substring(1);
    if (!/^[0-9a-fA-F]+$/.test(hex)) { return null; }
    if (hex.length === 6) { return new Color((0xff000000 | parseInt(hex, 16))); }
    if (hex.length === 8) { return new Color(parseInt(hex, 16)); }
    return null;
  }
  Color.parse = function (value) {
    if (value instanceof Color) { return value; }
    if (typeof value === 'number') { return new Color(value); }
    // 字符串解析失败保持 Pro 语义：返回 null（严格入口见 module.parseColor）。
    if (typeof value === 'string') { return parseColorString(value); }
    // prelude/image 里旧 Color 实例仍带 toInt()；转成新 Color，绝不返回旧类实例。
    if (value && typeof value === 'object' && typeof value.toInt === 'function') {
      return new Color(value.toInt());
    }
    throw new TypeError('[Auto.js] color.Color.parse 需要颜色字符串、整数或 Color 对象，收到：'
      + Object.prototype.toString.call(value));
  };
  // 模块级基础别名：颜色对象统一由新 Color 类构造；无效字符串/类型明确抛 TypeError。
  function parseColorOrThrow(value, label) {
    var parsed = Color.parse(value);
    if (parsed == null) {
      throw new TypeError('[Auto.js] color.' + label + ' 无法解析颜色：' + String(value));
    }
    return parsed;
  }
  // 兼容旧写法（prelude 曾提供 argb/rgb）；语义同 fromARGB/fromRGB，返回新 Color 实例。
  Color.argb = Color.fromARGB;
  Color.rgb = Color.fromRGB;

  /* ----------------------------- HSV/HSL ----------------------------- */
  function getHue(red, green, blue, max, delta) {
    var hue;
    if (max === 0.0) { hue = 0.0; }
    else if (max === red) { hue = 60.0 * (((green - blue) / delta) % 6); }
    else if (max === green) { hue = 60.0 * (((blue - red) / delta) + 2); }
    else { hue = 60.0 * (((red - green) / delta) + 4); }
    if (isNaN(hue)) { hue = 0.0; }
    // max 为红色且 blue > green 时上面的 % 6 会给负角度；HSV/HSL 的 hue 约定是 0..360。
    if (hue < 0) { hue += 360.0; }
    return hue;
  }
  function colorFromHue(alpha, hue, chroma, secondary, match) {
    var red; var green; var blue;
    if (hue < 60) { red = chroma; green = secondary; blue = 0; }
    else if (hue < 120) { red = secondary; green = chroma; blue = 0; }
    else if (hue < 180) { red = 0; green = chroma; blue = secondary; }
    else if (hue < 240) { red = 0; green = secondary; blue = chroma; }
    else if (hue < 300) { red = secondary; green = 0; blue = chroma; }
    else { red = chroma; green = 0; blue = secondary; }
    return Color.fromARGB(Math.round(alpha * 0xff), Math.round((red + match) * 0xff),
      Math.round((green + match) * 0xff), Math.round((blue + match) * 0xff));
  }
  function clamp(value, min, max) { return value < min ? min : (value > max ? max : value); }
  function lerpDouble(a, b, t) { return a * (1 - t) + b * t; }

  function HSVColor(alpha, hue, saturation, value) {
    this.alpha = alpha; this.hue = hue; this.saturation = saturation; this.value = value;
  }
  HSVColor.fromAHSV = function (alpha, hue, saturation, value) {
    return new HSVColor(assertRange(alpha, 'alpha', 0, 1), assertRange(hue, 'hue', 0, 360),
      assertRange(saturation, 'saturation', 0, 1), assertRange(value, 'value', 0, 1));
  };
  HSVColor.fromColor = function (color) {
    var red = color.red / 255; var green = color.green / 255; var blue = color.blue / 255;
    var max = Math.max(red, Math.max(green, blue)); var min = Math.min(red, Math.min(green, blue));
    var delta = max - min;
    return HSVColor.fromAHSV(color.alpha / 255, getHue(red, green, blue, max, delta),
      max === 0 ? 0 : delta / max, max);
  };
  HSVColor.prototype.withAlpha = function (alpha) { return HSVColor.fromAHSV(alpha, this.hue, this.saturation, this.value); };
  HSVColor.prototype.withHue = function (hue) { return HSVColor.fromAHSV(this.alpha, hue, this.saturation, this.value); };
  HSVColor.prototype.withSaturation = function (s) { return HSVColor.fromAHSV(this.alpha, this.hue, s, this.value); };
  HSVColor.prototype.withValue = function (v) { return HSVColor.fromAHSV(this.alpha, this.hue, this.saturation, v); };
  HSVColor.prototype.toColor = function () {
    var chroma = this.saturation * this.value;
    var secondary = chroma * (1 - Math.abs(((this.hue / 60) % 2) - 1));
    return colorFromHue(this.alpha, this.hue, chroma, secondary, this.value - chroma);
  };
  HSVColor.prototype._scaleAlpha = function (factor) { return this.withAlpha(this.alpha * factor); };
  HSVColor.lerp = function (a, b, t) {
    if (!a && !b) { return undefined; }
    if (!a) { return b._scaleAlpha(t); }
    if (!b) { return a._scaleAlpha(1 - t); }
    return HSVColor.fromAHSV(clamp(lerpDouble(a.alpha, b.alpha, t), 0, 1),
      lerpDouble(a.hue, b.hue, t) % 360, clamp(lerpDouble(a.saturation, b.saturation, t), 0, 1),
      clamp(lerpDouble(a.value, b.value, t), 0, 1));
  };
  HSVColor.prototype.equals = function (other) {
    return other instanceof HSVColor && other.alpha === this.alpha && other.hue === this.hue
      && other.saturation === this.saturation && other.value === this.value;
  };
  HSVColor.prototype.toString = function () {
    return 'HSVColor(' + this.alpha + ', ' + this.hue + ', ' + this.saturation + ', ' + this.value + ')';
  };

  function HSLColor(alpha, hue, saturation, lightness) {
    this.alpha = alpha; this.hue = hue; this.saturation = saturation; this.lightness = lightness;
  }
  HSLColor.fromAHSL = function (alpha, hue, saturation, lightness) {
    return new HSLColor(assertRange(alpha, 'alpha', 0, 1), assertRange(hue, 'hue', 0, 360),
      assertRange(saturation, 'saturation', 0, 1), assertRange(lightness, 'lightness', 0, 1));
  };
  HSLColor.fromColor = function (color) {
    var red = color.red / 255; var green = color.green / 255; var blue = color.blue / 255;
    var max = Math.max(red, Math.max(green, blue)); var min = Math.min(red, Math.min(green, blue));
    var delta = max - min; var lightness = (max + min) / 2;
    // HSL 饱和度分母是 1-|2L-1|（不是 |1-(2L-1)|）；写错会让 #009688 这类颜色往返失真。
    var saturation = delta === 0 ? 0
      : clamp(delta / (1 - Math.abs(2 * lightness - 1)), 0, 1);
    return HSLColor.fromAHSL(color.alpha / 255, getHue(red, green, blue, max, delta), saturation, lightness);
  };
  HSLColor.prototype.withAlpha = function (alpha) { return HSLColor.fromAHSL(alpha, this.hue, this.saturation, this.lightness); };
  HSLColor.prototype.withHue = function (hue) { return HSLColor.fromAHSL(this.alpha, hue, this.saturation, this.lightness); };
  HSLColor.prototype.withSaturation = function (s) { return HSLColor.fromAHSL(this.alpha, this.hue, s, this.lightness); };
  HSLColor.prototype.withLightness = function (l) { return HSLColor.fromAHSL(this.alpha, this.hue, this.saturation, l); };
  HSLColor.prototype.toColor = function () {
    var chroma = (1 - Math.abs(2 * this.lightness - 1)) * this.saturation;
    var secondary = chroma * (1 - Math.abs((this.hue / 60) % 2 - 1));
    return colorFromHue(this.alpha, this.hue, chroma, secondary, this.lightness - chroma / 2);
  };
  HSLColor.prototype._scaleAlpha = function (factor) { return this.withAlpha(this.alpha * factor); };
  HSLColor.lerp = function (a, b, t) {
    if (!a && !b) { return undefined; }
    if (!a) { return b._scaleAlpha(t); }
    if (!b) { return a._scaleAlpha(1 - t); }
    return HSLColor.fromAHSL(clamp(lerpDouble(a.alpha, b.alpha, t), 0, 1),
      lerpDouble(a.hue, b.hue, t) % 360, clamp(lerpDouble(a.saturation, b.saturation, t), 0, 1),
      clamp(lerpDouble(a.lightness, b.lightness, t), 0, 1));
  };
  HSLColor.prototype.equals = function (other) {
    return other instanceof HSLColor && other.alpha === this.alpha && other.hue === this.hue
      && other.saturation === this.saturation && other.lightness === this.lightness;
  };
  HSLColor.prototype.toString = function () {
    return 'HSLColor(' + this.alpha + ', ' + this.hue + ', ' + this.saturation + ', ' + this.lightness + ')';
  };

  var colorModule = {
    Color: Color,
    HSVColor: HSVColor,
    HSLColor: HSLColor,
    toColorInt: toColorInt,
    // prelude 基础别名恢复：返回新 Color 实例（不是同名旧 Color）。
    parseColor: function (value) { return parseColorOrThrow(value, 'parseColor'); },
    argb: function (alpha, red, green, blue) {
      return Color.fromARGB(alpha, red, green, blue);
    },
    rgb: function (red, green, blue) { return Color.fromRGB(red, green, blue); },
    alpha: function (value) { return parseColorOrThrow(value, 'alpha').alpha; },
    red: function (value) { return parseColorOrThrow(value, 'red').red; },
    green: function (value) { return parseColorOrThrow(value, 'green').green; },
    blue: function (value) { return parseColorOrThrow(value, 'blue').blue; },
    // RGB 三通道最大差值 <= threshold；alpha 不参与比较（threshold 0..255，默认 0）。
    isSimilar: function (first, second, threshold) {
      threshold = threshold == null ? 0 : requireIntRange(threshold, 'threshold', 0, 255);
      var a = parseColorOrThrow(first, 'isSimilar.first');
      var b = parseColorOrThrow(second, 'isSimilar.second');
      return Math.abs(a.red - b.red) <= threshold
        && Math.abs(a.green - b.green) <= threshold
        && Math.abs(a.blue - b.blue) <= threshold;
    }
  };
  globalThis.__ajsRegisterModule('color', function () { return colorModule; });
  if (globalThis.__ajsBuiltinModules) { globalThis.__ajsBuiltinModules.color = colorModule; }
  if (globalThis.$autojs) { globalThis.$autojs.color = colorModule; }
})();
