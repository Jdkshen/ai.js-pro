// @engine quickjs
// 通知监听与全局动作
// 用途：events.observeNotification 监听通知，notifications()/quickSettings()/recents() 全局动作
// 前置：无障碍服务
// 覆盖：events

console.log('notifications 是函数 = ' + (typeof notifications === 'function')
    + ', quickSettings = ' + (typeof quickSettings === 'function')
    + ', recents = ' + (typeof recents === 'function'));

// 2) 通知监听：observeNotification() 打开观察开关，回调通过 events.on('notification') 注册
var received = [];
events.onNotification(function (notification) {
    received.push((notification && notification.packageName) + ': ' + (notification && notification.title));
    if (received.length <= 3) {
        console.log('收到通知 -> ' + received[received.length - 1]);
    }
});
var observing = events.observeNotification();
console.log('通知观察已开启 = ' + observing);

// 3) 等一会儿，期间可以手动下拉通知栏/让其它应用推条通知
sleep(2500);

// 4) 全局动作（会离开当前界面，演示后自动返回）
try {
    notifications();     // 下拉通知栏
    sleep(800);
    back();
    sleep(500);
    console.log('notifications() + back() 已完成');
} catch (error) {
    console.log('全局动作失败（无障碍未连接？）: ' + error.message);
}

// 5) 收尾：移除监听
events.removeAllListeners('notification');
console.log('累计收到 ' + received.length + ' 条通知');
console.log('=== NOTIFICATION_ACTIONS_DONE ===');
