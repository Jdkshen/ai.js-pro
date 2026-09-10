// @engine quickjs
// QuickJS Device 模块完整测试
var pass = 0, fail = 0;
function assert(name, cond) {
    if (cond) { pass++; console.log('✅ ' + name); }
    else { fail++; console.error('❌ ' + name); }
}

// 只读属性
assert('device.width 是数字', typeof device.width === 'number' && device.width > 0);
assert('device.height 是数字', typeof device.height === 'number' && device.height > 0);
assert('device.model 是字符串', typeof device.model === 'string' && device.model.length > 0);
assert('device.brand 是字符串', typeof device.brand === 'string' && device.brand.length > 0);
assert('device.sdkInt 是数字', typeof device.sdkInt === 'number' && device.sdkInt >= 21);
assert('device.release 是字符串', typeof device.release === 'string');
assert('device.manufacturer 是字符串', typeof device.manufacturer === 'string');

console.log('  设备: ' + device.brand + ' ' + device.model + ', Android ' + device.release + ' (SDK ' + device.sdkInt + ')');
console.log('  屏幕: ' + device.width + ' x ' + device.height);

// 方法
assert('isScreenOn 返回布尔', typeof device.isScreenOn() === 'boolean');
console.log('  屏幕亮: ' + device.isScreenOn());

assert('getBattery 返回数字', typeof device.getBattery() === 'number');
console.log('  电量: ' + device.getBattery().toFixed(1) + '%');

assert('isCharging 返回布尔', typeof device.isCharging() === 'boolean');
console.log('  充电中: ' + device.isCharging());

assert('getBrightness 返回数字', typeof device.getBrightness() === 'number');
console.log('  亮度: ' + device.getBrightness());

assert('getBrightnessMode 返回数字', typeof device.getBrightnessMode() === 'number');
console.log('  亮度模式: ' + device.getBrightnessMode());

// vibrate 测试 (100ms 短震动)
device.vibrate(100);
assert('vibrate 无异常', true);

// cancelVibration 测试
device.cancelVibration();
assert('cancelVibration 无异常', true);

console.log('\n=== DEVICE 测试完成: ' + pass + ' 通过, ' + fail + ' 失败 ===');
