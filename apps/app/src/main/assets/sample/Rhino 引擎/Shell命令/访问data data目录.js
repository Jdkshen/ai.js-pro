// @engine rhino
// ⚠ 需要 Auto.js Pro 专属 API：ES6 模板字符串（当前 Rhino 1.7.7 不支持，请改成字符串拼接）（当前引擎暂未实现，直接运行会报错）
// 在android11以上，使用 /data_mirror/data_ce/null/0代替/data/user/0和/data/data
let dataDir = device.sdkInt >= 30 ? '/data_mirror/data_ce/null/0'
    : '/data/data';
log(shell(`ls -a ${dataDir}`, true));

// 如果你使用magisk，也可以自定义shell启动进程命令，用mount master参数来使用全局命名空间
// 或者在magisk设置中调整默认设置
log(shell('ls /data/data', { cmd: 'su --mount-master' }));
