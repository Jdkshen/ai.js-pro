// @engine quickjs
// 文件夹变更监听（FileObserver）：QuickJS 引擎已支持 files.observe，
// 事件名与 Rhino 的 $files.observe（以及 Auto.js Pro）完全一致：
// create / delete / modify / moved_from / moved_to / open / close_write / attrib / ...
// 前置：无；目录会自动创建在内置存储根目录 quickjs-watch-demo/
// 另见：同目录「文件夹变更监听（轮询）.js」——不依赖 FileObserver 的轮询实现

var dir = files.join(files.getSdcardPath(), 'quickjs-watch-demo');
files.ensureDir(dir + '/');

// 监听该目录（FileObserver 只监听一级目录，不递归子目录）
var watcher = files.observe(dir);
console.log('监听目录 = ' + watcher.getPath() + '，isWatching = ' + watcher.isWatching());

// "any" 回调收 (事件名, 相对路径)；也可以 watcher.on("create", function (path) { ... }) 单独监听
watcher.on("any", function (event, path) {
    console.log('事件: ' + event + ' 相对路径: ' + path);
});

// 创建一个文件，看看是否能监听到
var target = files.join(dir, '_test_for_file_observer.txt');
files.create(target);
sleep(1000);
// 一秒后删除文件
files.remove(target);
sleep(1000);

// 停止监听；停止后可以由 startWatching() 重新开始
watcher.stopWatching();
console.log('停止监听，isWatching = ' + watcher.isWatching());
