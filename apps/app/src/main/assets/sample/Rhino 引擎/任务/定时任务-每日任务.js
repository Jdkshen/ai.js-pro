"rhino";
let task = $work_manager.addDailyTask({
    path: files.path('./任务逻辑.js'),
    time: '10:00'
});
toastLog("定时任务预定成功: " + task);
// 可以到任务管理中查看这个定时任务