"rhino";
console.show();

// 预定一个任务
let task = $work_manager.addDisposableTask({
    path: '/sdcard/脚本/随便预定一个任务.js',
    date: Date.now() + 10 * 60 * 1000
});
log("预定成功:", task);
// 获取任务id
let id = task.id;
log("根据id查询定时任务: id = %d: ", id, $work_manager.getTimedTask(id));
// 取消定时任务
log("删除该任务: ", $work_manager.removeTimedTask(id));
log("[删除任务后]根据id查询定时任务: id = %d: ", id, $work_manager.getTimedTask(id));

// 查询所有任务
log("所有定时任务: ", $work_manager.queryTimedTasks({}));

// 查询所有意图（广播）任务
log("所有意图任务: ", $work_manager.queryIntentTasks({}));

// 按路径查询任务
$work_manager.queryTimedTasks({
    path: '/sdcard/脚本/随便预定一个任务.js'
}, tasks => {
    // 查询结果是 Java List，不是 JS 原生数组；用 size()/get() 转成数组，后续可直接用 length/forEach
    let taskArray = [];
    for (let i = 0; i < tasks.size(); i++) {
        taskArray.push(tasks.get(i));
    }
    log("按路径查询任务: ", taskArray, "数量: ", taskArray.length);
});

// 按意图Action查询任务
log("按意图Action查询任务: ", $work_manager.queryIntentTasks({
    action: 'android.intent.action.BATTERY_CHANGED'
}));

// 保持脚本运行
setInterval(() => {}, 100000);