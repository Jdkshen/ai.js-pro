// @engine quickjs
// 本地存储与全局动作
// 用途：LocalStorage 持久化与 press/longClick/recents 全局动作
// 前置：无障碍服务（全局动作部分）
// 覆盖：storages / 手势/输入

var store = storages.create('quickjs_demo');
var hits = Number(store.get('hits', 0)) + 1;
store.put('hits', hits);
store.put('payload', { engine: 'quickjs', at: Date.now() });
console.log('hits = ' + hits + '（每次运行 +1，可在文件里看到持久化）');
console.log('payload = ' + JSON.stringify(store.get('payload')));
console.log('contains("hits") = ' + store.contains('hits'));

// 2) 全局动作：按压 / 长按（坐标按当前屏幕，效果是真实的触摸）
press(120, 300, 120);
sleep(400);
console.log('press(120, 300, 120) 完成');

longClick(200, 400);
sleep(400);
console.log('longClick(200, 400) 完成');

// 3) 最近任务 + 返回（无障碍全局动作）
try {
    recents();
    sleep(800);
    back();
    sleep(500);
    console.log('recents() + back() 完成');
} catch (error) {
    console.log('最近任务失败（无障碍未连接？）: ' + error.message);
}

// 4) 清理：演示用的键可以清掉，也可保留观察持久化
if (hits >= 5) {
    store.remove('hits');
    console.log('已清理 hits（累计已到 5 次）');
}
console.log('=== STORAGE_ACTIONS_DONE ===');
