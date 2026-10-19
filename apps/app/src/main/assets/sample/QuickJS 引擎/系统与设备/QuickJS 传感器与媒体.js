"quickjs";
// 传感器与媒体
// 用途：sensors 注册/注销与数据回调、media 音量与音乐控制
// 前置：无（部分设备没有对应传感器）
// 覆盖：sensors / media / timers

var available = sensors.list();
console.log('可用传感器: ' + JSON.stringify(available));

// list() 返回厂商设备名称；是否支持标准类型以 register() 的返回值判断。
var accelerometer = sensors.register('accelerometer', sensors.delay.normal);
if (accelerometer !== null) {
    var samples = [];
    try {
        accelerometer.on('change', function (event) {
            if (!event.values || event.values.length < 3 || !event.timestamp) return;
            samples.push({ x: Number(event.values[0].toFixed(3)), y: event.values[1], z: event.values[2] });
        });
        sleep(1200);
    } finally {
        accelerometer.unregister();
    }
    console.log('加速度样本 ' + samples.length + ' 条，首条 = ' + JSON.stringify(samples[0] || null));
    if (!samples.length) throw new Error('加速度计已注册，但未收到有效数据');
} else {
    console.log('设备没有加速度计，跳过');
}

// 不支持的传感器返回 null。
console.log('不支持的传感器返回值 = ' + sensors.register('__no_such_sensor__'));

// 2) 媒体：音量读写（静音/恢复保持原值）
var originalVolume = media.getVolume(media.MUSIC_STREAM_INDEX);
var maxVolume = media.getMaxVolume(media.MUSIC_STREAM_INDEX);
console.log('音乐音量 = ' + originalVolume + '/' + maxVolume);
try {
    media.setVolume(media.MUSIC_STREAM_INDEX, Math.max(0, originalVolume - 1));
    sleep(200);
    console.log('调整后音量 = ' + media.getVolume(media.MUSIC_STREAM_INDEX));
} finally {
    media.setVolume(media.MUSIC_STREAM_INDEX, originalVolume);
}

// 3) 音乐播放控制（没有媒体文件时只验证接口可用）
console.log('playMusic 是函数 = ' + (typeof media.playMusic === 'function')
    + ', stopMusic = ' + (typeof media.stopMusic === 'function')
    + ', pauseMusic = ' + (typeof media.pauseMusic === 'function')
    + ', scanFile = ' + (typeof media.scanFile === 'function'));

// 4) 事件总线可以配合传感器做节流统计
var ticks = 0;
var timer = setInterval(function () { ticks++; }, 100);
sleep(450);
clearInterval(timer);
console.log('定时器节流采样 ' + ticks + ' 次');
console.log('=== SENSORS_MEDIA_DONE ===');
