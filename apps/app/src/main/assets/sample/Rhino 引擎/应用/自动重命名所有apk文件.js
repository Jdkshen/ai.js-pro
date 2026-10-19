"rhino";
// 默认只预览本示例旁演示目录中的 APK；确认列表后才启用重命名。
var RENAME = false;
var dir = files.path("./apk-rename-demo");
files.ensureDir(dir + "/");
listDir(dir);


function listDir(dir) {
    // console.log("搜索文件夹: " + dir);
    let children = files.listDir(dir);
    for (let i = 0; i < children.length; i++) {
        let file = children[i];
        let fullPath = files.join(dir, file);
        if (files.isDir(fullPath)) {
            listDir(fullPath);
            continue;
        }
        if (file.endsWith(".apk")) {
            let apkInfo = app.getApkInfo(fullPath);
            if (apkInfo) {
                rename(dir, fullPath, apkInfo);
            }
        }
    }
}

function rename(dir, file, apkInfo) {
    let newName = apkInfo.applicationInfo.label + "_v" + apkInfo.versionName + "_" +
        apkInfo.packageName + ".apk";
    if (RENAME) {
        if (!files.rename(file, newName)) throw new Error("重命名失败：" + file);
    }
    console.info((RENAME ? "重命名: " : "预览: ") + "%s => %s", file, newName);
}