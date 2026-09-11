// @engine quickjs
// io 模块与文本文件
// 用途：files.open / 全局 open / io 模块的按行读写与追加
// 前置：无
// 覆盖：files

var path = files.join(files.cwd(), '__io_demo.txt');
if (files.exists(path)) files.remove(path);

// 1) 写：'w' 打开即清空（与 Rhino 的 PFiles.open 一致）
var writer = open(path, 'w');           // 全局 open = files.open
writer.writeline('第一行');
writer.writelines(['第二行', '第三行']);  // 每项一行
writer.close();
console.log('写入后内容:\n' + files.read(path));

// 2) 读：'r' 支持 read / read(size) / readline / readlines，共用同一游标
var reader = io.open(path, 'r');         // io.open 同上，多参数按 mode/encoding/bufferSize 透传
console.log('readline = ' + reader.readline());
console.log('read(2)  = ' + reader.read(2));
console.log('剩余     = ' + JSON.stringify(reader.read()));
reader.close();

var lines = files.open(path, 'r').readlines();
console.log('readlines 行数 = ' + lines.length + ' -> ' + JSON.stringify(lines));

// 3) 追加：'a'
var appender = files.open(path, 'a');
appender.writeline('追加行');
appender.close();

// 4) 未知模式返回 null（与 Rhino 行为一致）
console.log('未知模式 files.open(path, "x") = ' + files.open(path, 'x'));

// 5) 收尾
console.log('最终内容:\n' + files.read(path));
files.remove(path);
console.log('清理完成, exists = ' + files.exists(path));
