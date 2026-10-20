"nodejs";
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const out = {ok: false, checks: []};
const directory = path.join(__dirname, 'java-interop-results');
fs.mkdirSync(directory, {recursive: true});
function check(name, fn) { fn(); out.checks.push(name); }
try {
  check('primitive Class and identity-preserving array mutations', () => {
    const A = java.lang.reflect.Array;
    const type = java.lang.Integer.TYPE;
    assert.strictEqual(type.getName(), 'int');
    assert.strictEqual(type.isPrimitive(), true);
    const array = A.newInstance(type, 4);
    assert.strictEqual(array.getClass().getName(), '[I');
    assert.strictEqual(array.getClass().getComponentType().getName(), 'int');
    assert.strictEqual(array.getClass().getComponentType(), type);
    assert.strictEqual(java.util.Collections.singletonList(array).get(0), array);
    array[0] = 19;
    assert.strictEqual(A.getInt(array, 0), 19);
    A.setInt(array, 1, 37);
    assert.strictEqual(array[1], 37);
    assert.deepStrictEqual(Array.from(array), [19, 37, 0, 0]);
    const direct = new ($java.findClass('[I'))(2);
    direct[1] = 42;
    assert.strictEqual(A.getInt(direct, 1), 42);
  });
  check('Class[] parameter and reflective invocation', () => {
    const S = java.lang.String;
    assert.strictEqual(S.isAssignableFrom(S), true);
    const method = S.getMethod('valueOf', [java.lang.Integer.TYPE]);
    assert.strictEqual(method.invoke(null, [$java.boxInt(56)]), '56');
  });
  check('nested classes, including primitive static fields', () => {
    assert.strictEqual(android.view.View.OnClickListener.getName(), 'android.view.View$OnClickListener');
    assert.strictEqual(android.os.Build.VERSION.getName(), 'android.os.Build$VERSION');
    assert.strictEqual(typeof android.os.Build.VERSION.SDK_INT, 'number');
  });
  check('boxed overloads and SQLite String[] parameters', () => {
    const dbPath = path.join(directory, 'typed-arguments.db');
    if (fs.existsSync(dbPath)) fs.unlinkSync(dbPath);
    const db = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(dbPath, null);
    try {
      db.execSQL('CREATE TABLE t(age INTEGER, name TEXT)');
      const values = new android.content.ContentValues();
      const boxed = $java.boxInt(21);
      assert.strictEqual(boxed.getClass().getName(), 'java.lang.Integer');
      values.put('age', boxed);
      values.put('name', 'Node24');
      assert.strictEqual(values.getAsInteger('age'), 21);
      assert.strictEqual(db.insertOrThrow('t', null, values), 1);
      const cursor = db.rawQuery('SELECT name FROM t WHERE age > ?', ['20']);
      try {
        assert.strictEqual(cursor.moveToFirst(), true);
        assert.strictEqual(cursor.getString(0), 'Node24');
        assert.strictEqual(cursor.getCount(), 1);
      } finally { cursor.close(); }
    } finally { db.close(); }
  });
  out.ok = true;
} catch (error) {
  out.error = String(error && error.stack || error);
  console.error(error);
  process.exitCode = 1;
} finally {
  fs.writeFileSync(path.join(directory, 'result.json'), JSON.stringify(out, null, 2));
  console.log('JAVA_INTEROP_RESULT ' + JSON.stringify(out));
}
