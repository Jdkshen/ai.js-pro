// 数据库操作示例（Rhino / sqlite 模块）
// 演示 open / create / insert / select / update / delete / transaction

var db = sqlite.open('example_notes', 1);

db.exec('CREATE TABLE IF NOT EXISTS notes (id INTEGER PRIMARY KEY, title TEXT, body TEXT, done INTEGER DEFAULT 0)');
db.exec("INSERT OR REPLACE INTO notes (id, title, body, done) VALUES (1, '第一个', '你好数据库', 0)");
db.exec("INSERT OR REPLACE INTO notes (id, title, body, done) VALUES (2, '第二个', 'QuickJS 也支持 sqlite', 0)");

// 查询并逐行取值（.get(列名)）
var result = db.exec('SELECT id, title, body FROM notes ORDER BY id');
log('共 ' + result.rows.length + ' 行');
for (var i = 0; i < result.rows.length; i++) {
    var row = result.rows.item(i);
    log('  #' + row.get('id') + ' ' + row.get('title') + ': ' + row.get('body'));
}

// 更新
db.exec("UPDATE notes SET done = 1 WHERE id = 1");
var updated = db.select('SELECT done FROM notes WHERE id = 1');
log('更新后 done = ' + (updated.rows.length > 0 ? updated.rows.item(0).get('done') : '?'));

// 删除
db.exec('DELETE FROM notes WHERE id = 2');
log('删除后剩余 ' + db.select('SELECT COUNT(*) AS c FROM notes').rows.item(0).get('c') + ' 行');

// 事务
db.transaction(function (tx) {
    tx.exec("INSERT INTO notes (id, title, body) VALUES (3, '事务行', '来自 transaction')");
}, function () {}, function () {
    log('事务已提交');
    db.close();
});
