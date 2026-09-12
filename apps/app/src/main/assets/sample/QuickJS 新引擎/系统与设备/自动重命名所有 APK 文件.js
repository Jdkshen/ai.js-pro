// @engine quickjs
// 自动重命名所有 APK 文件
// 用途：扫描内置存储里的 .apk，读取「应用名 / 版本名 / 包名」后批量重命名
// 前置：无（默认空跑只打印，确认无误后把 RENAME 改成 true 才会真改）
// 覆盖：files / Java 互操作

var RENAME = false;              // ← 想真改文件名时改成 true
var SKIP_DIR = 'Android';        // Android/data 等目录通常没权限，跳过

var pm = context.getPackageManager();
var renamed = 0;
var scanned = 0;

function labelOf(info) {
    try {
        return String(pm.getApplicationLabel(info.applicationInfo));
    } catch (error) {
        return info.packageName;
    }
}

/** 读取一个 APK 的包信息（不会安装它） */
function apkInfoOf(path) {
    var info = pm.getPackageArchiveInfo(path, 0);
    if (!info || !info.applicationInfo) {
        return null;
    }
    return {
        label: labelOf(info),
        versionName: String(info.versionName),
        packageName: String(info.packageName)
    };
}

function scan(dir) {
    var names = files.listDir(dir);
    for (var i = 0; i < names.length; i++) {
        var name = String(names[i]);
        var full = files.join(dir, name);
        if (files.isDir(full)) {
            if (name === SKIP_DIR || name.charAt(0) === '.') {
                continue;
            }
            scan(full);
            continue;
        }
        if (name.toLowerCase().indexOf('.apk') !== name.length - 4) {
            continue;
        }
        scanned++;
        var info = apkInfoOf(full);
        if (!info) {
            continue;
        }
        var newName = info.label + '_v' + info.versionName + '_' + info.packageName + '.apk';
        if (newName === name) {
            continue;            // 已经是目标格式
        }
        console.log((RENAME ? '重命名：' : '待重命名：') + name + '  ->  ' + newName);
        if (RENAME) {
            try {
                files.rename(full, newName);
                renamed++;
            } catch (error) {
                console.error('重命名失败（可能是系统目录）：' + full + ' - ' + error);
            }
        }
    }
}

console.log('开始扫描 ' + files.getSdcardPath() + '（RENAME=' + RENAME + '）');
scan(files.getSdcardPath());
console.log('共发现 ' + scanned + ' 个 APK' + (RENAME ? '，已重命名 ' + renamed + ' 个' : '，空跑结束（把 RENAME 改成 true 再跑一次）'));

// 小结：getPackageArchiveInfo 只解析文件头、不会安装；
//      重命名失败通常是没有写权限的目录（例如 /sdcard/Android/），脚本里已经跳过一层。
