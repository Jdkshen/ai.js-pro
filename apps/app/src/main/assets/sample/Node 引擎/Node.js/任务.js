"nodejs";

const workManager = require('work_manager');
const path = require('path');
const taskScript = path.join(__dirname, '定时任务逻辑.js');

// 默认演示完成后清理本次新建任务；设为 true 可在任务管理页保留观察。
const KEEP_TASKS = false;
async function main() {
    const createdTimed = [], createdIntent = [];
    try {
    // 可以到任务管理中查看以下任务

    // 一次性任务
    const task1 = await workManager.addOneTimeTask({
        path: taskScript,
        time: '2077-1-1T20:00:00'
    });
    createdTimed.push(task1.id);
    console.log('一次性任务预定成功:', task1, ', id:', task1.id);
    const found = await workManager.getTimedTask(task1.id);
    if (!(task1.id > 0) || !found || found.id !== task1.id) throw new Error('持久化任务 ID/回读不一致');
    console.log("根据id查询任务: ", found);

    // 每日任务
    const task2 = await workManager.addDailyTask({
        path: taskScript,
        time: '10:00'
    });
    createdTimed.push(task2.id);
    console.log('每日任务预定成功:', task2);

    // 每周任务
    const task3 = await workManager.addWeeklyTask({
        path: taskScript,
        time: '13:00',
        daysOfWeek: ['日', '一', '三', '五']
    });
    createdTimed.push(task3.id);
    console.log('每周任务预定成功:', task3, ', 每周执行日期:', task3.daysOfWeek);

    // 意图任务：广播
    const task4 = await workManager.addIntentTask({
        path: taskScript,
        action: 'android.intent.action.BATTERY_CHANGED'
    });
    createdIntent.push(task4.id);
    console.log("广播任务设置成功:", task4, ', action:', task4.action);

    // 取消任务1
    console.log("删除任务1: ", await workManager.removeTimedTask(task1.id));

    // 查询所有定时任务
    console.log("所有定时任务:", await workManager.queryTimedTasks());
    // 查询所有意图任务
    console.log("所有意图任务:", await workManager.queryIntentTasks());

    // 按路径查询任务
    console.log('按路径查询:', await workManager.queryTimedTasks({
        path: taskScript
    }));

    // 按意图Action查询任务
    console.log("按意图Action查询: ", await workManager.queryIntentTasks({
        action: 'android.intent.action.BATTERY_CHANGED'
    }));
    console.log('NODE_TASKS_SAMPLE_OK');
    } finally {
        if (!KEEP_TASKS) {
            for (const id of createdIntent) await workManager.removeIntentTask(id);
            for (const id of createdTimed) await workManager.removeTimedTask(id);
            console.log('本次演示任务已清理');
        }
    }
}

main().catch(console.error);