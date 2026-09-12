// @engine quickjs
// SQLite 数据库增删改查
// 用途：用 Android 原生 SQLiteDatabase 建表 / 插入 / 查询 / 更新 / 删除 / 事务（引擎没有内置 sqlite 模块）
// 前置：无；示例每次运行前会重建内置存储根目录下的 quickjs-sqlite-demo.db
// 覆盖：Java 互操作 / files

importClass('android.database.sqlite.SQLiteDatabase');
importClass('android.content.ContentValues');

var dbPath = files.join(files.getSdcardPath(), 'quickjs-sqlite-demo.db');
if (files.exists(dbPath)) {
    files.remove(dbPath);
}
console.log('数据库文件 = ' + dbPath);

var db = SQLiteDatabase.openOrCreateDatabase(dbPath, null);
db.execSQL('CREATE TABLE IF NOT EXISTS STUDENT(' +
    'id INTEGER PRIMARY KEY AUTOINCREMENT, ' +
    'name TEXT NOT NULL, ' +
    'age INTEGER NOT NULL, ' +
    'score INTEGER)');

// ---------------- 插入 ----------------
function insertStudent(name, age, score) {
    var values = new ContentValues();
    values.put('name', name);
    values.put('age', age);
    if (score !== undefined) {
        values.put('score', score);
    }
    return db.insert('STUDENT', null, values);   // 返回新行的 rowId
}

console.log('插入 张三 -> rowId ' + insertStudent('张三', 18, 90));
console.log('插入 李四 -> rowId ' + insertStudent('李四', 19, 60));
console.log('插入 王五 -> rowId ' + insertStudent('王五', 20));

// ---------------- 查询 ----------------
function dumpAll(title) {
    console.log('--- ' + title + ' ---');
    var cursor = db.rawQuery('SELECT id, name, age, score FROM STUDENT ORDER BY id', null);
    try {
        while (cursor.moveToNext()) {
            console.log('  #' + cursor.getInt(0) + ' ' + cursor.getString(1) +
                ' 年龄=' + cursor.getInt(2) + ' 分数=' + cursor.getString(3));
        }
    } finally {
        cursor.close();
    }
}

dumpAll('插入后');

// ---------------- 更新（带占位参数）----------------
var values = new ContentValues();
values.put('score', 70);
var updated = db.update('STUDENT', values, 'name = ?', ['李四']);
console.log('更新李四 -> 影响 ' + updated + ' 行');

// ---------------- 事务 ----------------
db.beginTransaction();
try {
    var bonus = new ContentValues();
    bonus.put('score', 100);
    db.update('STUDENT', bonus, 'age = 19', null);
    db.delete('STUDENT', 'age = 20', null);
    db.setTransactionSuccessful();
    console.log('事务提交：给 19 岁加满 100 分，并删掉 20 岁的记录');
} finally {
    db.endTransaction();
}

dumpAll('事务后');

// ---------------- 删除与收尾 ----------------
console.log('删除年龄=18 -> 影响 ' + db.delete('STUDENT', 'age = 18', null) + ' 行');
db.close();
console.log('数据库已关闭，文件保留在 ' + dbPath);

// 小结：游标列下标从 0 开始；whereArgs 直接传 JS 数组；
//      事务必须 setTransactionSuccessful() 才会提交，endTransaction() 放在 finally 里。
