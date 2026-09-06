// Zip 压缩与解压（Rhino / zips 模块）
// 演示 zip / unzip / list

var dir = files.cwd() + '/zip_demo';
files.ensureDir(dir + '/a.txt'); // 原版语义：确保文件父目录存在
files.write(dir + '/a.txt', '这是文件 A');
files.write(dir + '/b.log', 'log 内容 123');

var zipPath = files.cwd() + '/zip_demo.zip';
var ok = zips.zip(dir, zipPath);
log('压缩成功: ' + ok + ' (' + zipPath + ')');

log('压缩包内容: ' + zips.list(zipPath).join(', '));

var outDir = files.cwd() + '/zip_unzip';
files.ensureDir(outDir);
log('解压成功: ' + zips.unzip(zipPath, outDir));
log('解压后 a.txt = ' + files.read(outDir + '/a.txt'));

// 清理
files.remove(dir + '/a.txt');
files.remove(dir + '/b.log');
files.remove(dir);
files.remove(zipPath);
files.remove(outDir + '/a.txt');
files.remove(outDir + '/b.log');
files.remove(outDir);
