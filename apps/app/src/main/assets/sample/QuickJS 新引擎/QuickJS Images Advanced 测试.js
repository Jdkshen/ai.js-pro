// @engine quickjs
// QuickJS Images 高级功能测试 (rotate/threshold/blur)
var pass = 0, fail = 0;
function assert(name, cond) {
    if (cond) { pass++; console.log('  PASS: ' + name); }
    else { fail++; console.error('  FAIL: ' + name); }
}

console.log('=== Images Advanced Test ===');

if (!requestScreenCapture('portrait')) {
    throw new Error('Screen capture permission denied');
}
console.log('Screen capture OK');

var frame = captureScreen();
assert('captureScreen returns frame', frame !== null && frame.width > 0);

// Test rotate
var rotated = images.rotate(frame, 90);
assert('rotate(90) returns new frame', rotated !== null);
assert('rotate(90) swaps dimensions', rotated.width === frame.height && rotated.height === frame.width);
rotated.recycle();

var rotated180 = images.rotate(frame, 180);
assert('rotate(180) preserves dimensions', rotated180.width === frame.width && rotated180.height === frame.height);
rotated180.recycle();

var rotated270 = images.rotate(frame, 270);
assert('rotate(270) swaps dimensions', rotated270.width === frame.height && rotated270.height === frame.width);
rotated270.recycle();

// Test grayscale
var gray = images.grayscale(frame);
assert('grayscale returns frame', gray !== null);
gray.recycle();

// Test threshold
var thresh = images.threshold(frame, 128, 255, 0);
assert('threshold returns frame', thresh !== null);
thresh.recycle();

// Test blur
var blurred = images.blur(frame, 5);
assert('blur returns frame', blurred !== null);
blurred.recycle();

var blurred3 = images.blur(frame, 3);
assert('blur(ksize=3) returns frame', blurred3 !== null);
blurred3.recycle();

// Test scale
var scaled = images.scale(frame, 0.5);
assert('scale(0.5) returns frame', scaled !== null);
assert('scale(0.5) halves dimensions', scaled.width === Math.round(frame.width / 2) && scaled.height === Math.round(frame.height / 2));
scaled.recycle();

// Test save
var savePath = files.cwd() + '/test_advanced.png';
images.save(frame, savePath, 'png', 100);
assert('save creates file', files.exists(savePath));
files.remove(savePath);

// Test copy
var copy = images.copy(frame);
assert('copy returns frame', copy !== null);
assert('copy has same dimensions', copy.width === frame.width && copy.height === frame.height);
copy.recycle();

frame.recycle();

console.log('\n=== Result: ' + pass + ' passed, ' + fail + ' failed ===');
if (fail === 0) console.log('IMAGES_ADVANCED_OK');
