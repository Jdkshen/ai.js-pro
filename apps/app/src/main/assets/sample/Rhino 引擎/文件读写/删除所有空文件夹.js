"rhino";
// 默认只操作当前示例旁的演示目录；可在这里指定自己确认过的目录。
var root = files.path('./empty-folders-demo');
files.ensureDir(root + '/');
if (confirm("删除以下演示目录内的空文件夹（保留根目录）：\n" + root)) {
    toast("请点击右上角打开日志");
    deleteAllEmptyDirs(root);
    toast("全部完成！");
}

function deleteAllEmptyDirs(dir) {
    var list = files.listDir(dir);
    if (!list) { log('无法读取目录：' + dir); return; }
    var len = list.length;
    for (let i = 0; i < len; i++) {
        var child = files.join(dir, list[i]);
        if (files.isDir(child)) {
            deleteAllEmptyDirs(child);
        }
    }
    // 子目录清空后再检查父目录，避免空的多层目录残留。
    var remaining = files.listDir(dir);
    if (dir !== root && remaining && remaining.length === 0) {
        log("删除目录 " + dir + " " + (files.remove(dir) ? "成功" : "失败"));
    }
}
