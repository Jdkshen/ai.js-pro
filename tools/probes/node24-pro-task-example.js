"nodejs";
// Place the unchanged advanced 任务.js beside this probe as original-task.js.
const assert = require('assert');
const work = require('work_manager');
const original = {};
for (const key of Object.keys(work)) if (typeof work[key] === 'function') original[key] = work[key].bind(work);
const created = [];
let complete, fail;
const finished = new Promise((resolve, reject) => {complete = resolve; fail = reject;});
const timeout = setTimeout(() => fail(new Error('Advanced task example did not finish')), 10000);
for (const key of ['addOneTimeTask', 'addDailyTask', 'addWeeklyTask', 'addIntentTask']) {
  work[key] = async function (options) {
    try {
      const task = await original[key](options);
      assert(task && task.id > 0, key + ' must return the persisted DB id');
      created.push({id: task.id, intent: key === 'addIntentTask'});
      assert.strictEqual(task.path, options.path);
      assert(task.dateTime instanceof Date);
      assert.strictEqual(task.type, key === 'addIntentTask' ? 'IntentTask' : 'TimedTask');
      if (key === 'addDailyTask') assert.deepStrictEqual(task.daysOfWeek, [0, 1, 2, 3, 4, 5, 6]);
      if (key === 'addWeeklyTask') assert.deepStrictEqual(task.daysOfWeek, [0, 1, 3, 5]);
      if (key === 'addIntentTask') assert.strictEqual(task.action, options.action);
      return task;
    } catch (error) {fail(error); throw error;}
  };
}
work.queryIntentTasks = async function (options) {
  try {
    const tasks = await original.queryIntentTasks(options);
    if (options && options.action === 'android.intent.action.BATTERY_CHANGED') {
      assert(tasks.some(task => created.some(item => item.intent && item.id === task.id)));
      complete();
    }
    return tasks;
  } catch (error) {fail(error); throw error;}
};
(async () => {
  const before = {
    timed: (await original.queryTimedTasks()).map(task => task.id).sort(),
    intent: (await original.queryIntentTasks()).map(task => task.id).sort(),
  };
  try {
    require('./original-task.js');
    await finished;
    assert.strictEqual(created.length, 4);
    assert.strictEqual(await original.getTimedTask(created[0].id), null,
      'the unchanged sample removes its first task');
    assert.strictEqual((await original.queryTimedTasks({path: '/sdcard/脚本/每日任务.js'}))
      .filter(task => task.id === created[1].id).length, 1);
  } finally {
    clearTimeout(timeout);
    for (const task of created) {
      if (task.intent) await original.removeIntentTask(task.id);
      else await original.removeTimedTask(task.id);
    }
    const after = {
      timed: (await original.queryTimedTasks()).map(task => task.id).sort(),
      intent: (await original.queryIntentTasks()).map(task => task.id).sort(),
    };
    assert.deepStrictEqual(after, before, 'remove only probe-created tasks and preserve all existing tasks');
    console.log('JAVA_PRO_TASK_CLEANUP ' + JSON.stringify({ok: true, removed: created.length}));
  }
  console.log('JAVA_PRO_TASK_RESULT ' + JSON.stringify({ok: true, count: created.length}));
})().catch(error => {console.error(error.stack || error); process.exitCode = 1;});
