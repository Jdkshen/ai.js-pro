// @engine quickjs
// importClass 与 Packages
// 用途：与 Rhino 一致的 Java 反射：importClass / importPackage / Java.type / 裸类名
// 前置：无
// 覆盖：Java 互操作 / files

importClass('java.lang.StringBuilder');
importClass('java.io.File');

var builder = new StringBuilder();
builder.append('QuickJS');
builder.append(' + ');
builder.append('Java 反射');
console.log('StringBuilder = ' + builder.toString());

// 2) Java.type：显式取类对象
var Integer = Java.type('java.lang.Integer');
console.log('Integer.parseInt("42") + 1 = ' + (Integer.parseInt('42') + 1));
console.log('Integer.MAX_VALUE = ' + Integer.MAX_VALUE);

// 3) Packages：逐级解析，适合偶尔用一次的类
var System = Packages.java.lang.System;
console.log('System.currentTimeMillis() = ' + System.currentTimeMillis());

// 4) importPackage：批量导入一个包（与 Rhino 相同语义）
importPackage(java.util);
var list = new ArrayList();
list.add('a');
list.add('b');
console.log('ArrayList.size() = ' + list.size());

// 5) JavaBean 属性与字段：实例属性可以直接读写
var dir = new File(files.cwd());
console.log('file.path = ' + dir.path);
console.log('file.name = ' + dir.name);
console.log('dir.exists() = ' + dir.exists());

// 6) 重载解析：整数优先 int/long，Java 异常会转成脚本 Error
console.log('String.valueOf(42) = ' + Java.type('java.lang.String').valueOf(42));
try {
    Integer.parseInt('not-a-number');
} catch (error) {
    console.log('Java 异常已映射为脚本 Error: ' + error);
}

// 7) 传入 Java 数组 / 可变参数：JS 数组会被自动转换
var message = Java.type('java.lang.String').format('%s-%s', ['x', 'y']);
console.log('String.format = ' + message);
