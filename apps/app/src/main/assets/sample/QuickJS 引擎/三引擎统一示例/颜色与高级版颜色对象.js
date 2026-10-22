"quickjs";
(function () {
    var api = require('color');
    var checks = [];
    function check(name, fn) { try { if (!fn()) throw new Error('assertion returned false'); checks.push({name: name, ok: true}); } catch (error) { checks.push({name: name, ok: false, error: String(error)}); } }
    check('colors.alias', function () { return colors === $colors; });
    check('colors.constants', function () { return colors.BLACK === -16777216 && colors.WHITE === -1 && colors.RED === -65536 && colors.TRANSPARENT === 0; });
    check('colors.channels', function () { var n = colors.argb(127, 18, 52, 86); return colors.alpha(n) === 127 && colors.red(n) === 18 && colors.green(n) === 52 && colors.blue(n) === 86; });
    check('colors.rgb', function () { return colors.rgb(255, 0, 0) === -65536; });
    check('colors.parseColor', function () { return colors.parseColor('#ff0000') === -65536 && colors.parseColor('red') === -65536; });
    check('colors.toString', function () { return String(colors.toString(colors.RED)).toLowerCase() === '#ffff0000'; });
    check('colors.HSVToColor', function () { return colors.HSVToColor([120, 1, 1]) === colors.GREEN; });
    check('colors.colorToHSV', function () { var output = [0, 0, 0]; colors.colorToHSV(colors.BLUE, output); return Math.abs(output[0] - 240) < 0.01 && output[1] === 1 && output[2] === 1; });
    check('colors.isSimilar.threshold0', function () { return colors.isSimilar('#123456', '#123456', 0) && !colors.isSimilar('#123456', '#123457', 0); });
    check('colors.luminance', function () { return Math.abs(colors.luminance(colors.WHITE) - 1) < 0.0001 && colors.luminance(colors.BLACK) === 0; });
    check('Color.parse', function () { var color = api.Color.parse('#123456'); return color instanceof api.Color && color.red === 18 && color.value === -15584170; });
    check('Color.invalid', function () { return api.Color.parse('#invalid') === null; });
    check('Color.alpha', function () { return api.Color.fromRGB(1, 2, 3).withOpacity(0.5).alpha === 128; });
    check('Color.HSV.roundtrip', function () { var c = api.Color.fromRGB(0, 255, 0); return api.HSVColor.fromColor(c).toColor().value === c.value; });
    check('Color.HSL.roundtrip', function () { var c = api.Color.fromRGB(255, 0, 0); return api.HSLColor.fromColor(c).toColor().value === c.value; });
    check('Color.HSV.invalid', function () { try { api.HSVColor.fromAHSV(1, 0, 2, 1); return false; } catch (error) { return error instanceof RangeError; } });
    check('Color.lerp', function () { var a = api.HSVColor.fromAHSV(1, 0, 1, 1); var b = api.HSVColor.fromAHSV(1, 120, 1, 1); return api.HSVColor.lerp(a, b, 0.5).toColor().value === colors.YELLOW; });
    var failed = checks.filter(function (row) { return !row.ok; });
    console.log('COMMON_COLOR_RESULT=' + JSON.stringify({checks: checks, passed: checks.length - failed.length, failed: failed}));
})();
