// @engine quickjs
// 文件夹变更监听（轮询）
// 用途：监听一个目录里的文件新增 / 修改 / 删除（引擎没有 FileObserver 绑定，轮询最省事也最稳）
// 前置：无；目录会自动创建在内置存储根目录 quickjs-watch-demo/
// 覆盖：files / Java 互操作

importClass('java.io.File');

var dir = files.join(files.getSdcardPath(), 'quickjs-watch-demo');
files.ensureDir(dir + '/');
console.log('监听目录 = ' + dir);

/** 拍一张快照：文件名 -> 类型/大小/最后修改时间 */
function snapshot() {
    var map = {};
    var names = files.listDir(dir);
    for (var i = 0; i < names.length; i++) {
        var file = new File(files.join(dir, names[i]));
        map[names[i]] = file.isDirectory() ? 'dir'
            : (file.length() + 'B@' + file.lastModified());
    }
    return map;
}

/** 比较两张快照，返回变化描述 */
function diff(before, after) {
    var changes = [];
    var name;
    for (name in after) {
        if (before[name] === undefined) {
            changes.push('新增 ' + name);
        }
    }
    for (name in before) {
        if (after[name] === undefined) {
            changes.push('删除 ' + name);
        } else if (before[name] !== after[name]) {
            changes.push('修改 ' + name);
        }
    }
    return changes;
}

// ---------------- 1) 自测：制造三次变化并检测 ----------------
var last = snapshot();

function report(step) {
    var now = snapshot();
    var changes = diff(last, now);
    console.log('[' + step + '] ' + (changes.length ? changes.join('，') : '无变化'));
    last = now;
}

files.write(files.join(dir, 'a.txt'), 'hello');
sleep(1100);
report('新建 a.txt');

files.write(files.join(dir, 'a.txt'), 'hello quickjs');
sleep(1100);
report('改写 a.txt');

files.remove(files.join(dir, 'a.txt'));
sleep(1100);
report('删除 a.txt');

// ---------------- 2) 真实监听：10 秒内盯着目录 ----------------
console.log('--- 开始实时监听 10 秒，可以自己去这个目录里丢个文件 ---');
var deadline = Date.now() + 10000;
while (Date.now() < deadline) {
    sleep(500);
    report('监听中');
}
console.log('监听结束');

// 小结：轮询间隔越短越灵敏、越费电；只关心内容变化时可以用 length + lastModified 组合判断。
