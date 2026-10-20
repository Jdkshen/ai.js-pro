"nodejs";
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const out = {ok: false, checks: []};
const directory = path.join(__dirname, 'java-inheritance-results');
fs.mkdirSync(directory, {recursive: true});
async function check(name, fn) { await fn(); out.checks.push(name); }
(async () => {
  await check('real SQLiteOpenHelper subclass and synchronous onCreate callback', async () => {
    const dbPath = path.join(directory, 'generated.db');
    if (fs.existsSync(dbPath)) fs.unlinkSync(dbPath);
    let created = 0, constructed = 0;
    const Helper = await $java.defineClass(class Helper extends android.database.sqlite.SQLiteOpenHelper {
      constructor(ctx) { super(ctx, dbPath, null, 1); constructed++; }
      onCreate(db) { created++; db.execSQL('CREATE TABLE t(id INTEGER PRIMARY KEY, name TEXT)'); }
      onUpgrade(db, oldVersion, newVersion) { throw new Error('Unexpected database upgrade'); }
    }, {packageName: 'org.aijsprobe'});
    const helper = new Helper($autojs.androidContext);
    assert.strictEqual(constructed, 1);
    assert.strictEqual(helper.getClass().getName(), 'org.aijsprobe.Helper');
    const db = helper.getWritableDatabase();
    try {
      assert.strictEqual(created, 1);
      $java.setThreadMode(db, 'io');
      const values = new android.content.ContentValues();
      values.put('name', 'generated callback');
      assert.strictEqual(await db.insertOrThrow('t', null, values), 1);
      const cursor = await db.rawQuery('SELECT name FROM t WHERE id = ?', ['1']);
      try {
        assert.strictEqual(cursor.moveToFirst(), true);
        assert.strictEqual(cursor.getString(0), 'generated callback');
      } finally { cursor.close(); }
    } finally { await db.close(); helper.close(); }
  });
  await check('Java calls JS overrides, retains this, forwards super and propagates errors', async () => {
    const List = await $java.defineClass(class List extends java.util.ArrayList {
      constructor() { super(); this.additions = 0; }
      add(value) {
        if (value === 'boom') throw new Error('intentional override failure');
        this.additions++;
        return super.add(value);
      }
    }, {packageName: 'org.aijsprobe'});
    const list = new List();
    assert.strictEqual(java.util.Collections.addAll(list, ['a', 'b']), true);
    assert.strictEqual(list.additions, 2);
    assert.strictEqual(list.size(), 2);
    assert.throws(() => java.util.Collections.addAll(list, ['boom']), /intentional override failure/);
    assert.strictEqual(java.util.Collections.addAll(list, ['c']), true);
    assert.strictEqual(list.additions, 3);
    assert.strictEqual(list.size(), 3);
  });
  await check('IO thread mode leaves Node timers running', async () => {
    let ticked = false;
    const timer = setTimeout(() => { ticked = true; }, 10);
    $java.setDefaultThreadMode(java.lang.Thread, 'io');
    try { await java.lang.Thread.sleep(120); }
    finally { $java.setDefaultThreadMode(java.lang.Thread, 'current'); clearTimeout(timer); }
    assert.strictEqual(ticked, true);
  });
  await check('UI thread callbacks, nested draw/click and Canvas thread affinity', async () => {
    let clicked = 0, drawn = 0, resolveUi, rejectUi;
    const Button = await $java.defineClass(class Button extends android.widget.Button {
      constructor(ctx) {
        super(ctx); this.setWillNotDraw(false); this.setOnClickListener(this);
        this.paint = new android.graphics.Paint(); this.paint.setColor(android.graphics.Color.RED);
      }
      onClick(view) {
        assert.strictEqual(view, this);
        assert.strictEqual(java.lang.Thread.currentThread().getName(), 'main');
        clicked++;
      }
      onDraw(canvas) {
        assert.strictEqual(java.lang.Thread.currentThread().getName(), 'main');
        super.onDraw(canvas); canvas.drawCircle(32, 32, 16, this.paint); drawn++;
      }
    }, {packageName: 'org.aijsprobe', implements: [android.view.View.OnClickListener]});
    const UiJob = await $java.defineClass(class UiJob extends java.lang.Object {
      run() {
        try {
          assert.strictEqual(java.lang.Thread.currentThread().getName(), 'main');
          const button = new Button($autojs.androidContext);
          button.layout(0, 0, 64, 64);
          assert.strictEqual(button.performClick(), true);
          const bitmap = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888);
          try {
            button.draw(new android.graphics.Canvas(bitmap));
            assert.strictEqual(bitmap.getPixel(32, 32), android.graphics.Color.RED);
          } finally { bitmap.recycle(); }
          assert.strictEqual(clicked, 1);
          assert.strictEqual(drawn, 1);
          const view = new android.view.View($autojs.androidContext);
          let functions = 0, interfaces = 0;
          view.setOnClickListener(v => { assert.strictEqual(v, view); functions++; });
          assert.strictEqual(view.performClick(), true);
          view.setOnClickListener(new android.view.View.OnClickListener({
            onClick(v) { assert.strictEqual(v, view); interfaces++; }
          }));
          assert.strictEqual(view.performClick(), true);
          assert.strictEqual(functions, 1);
          assert.strictEqual(interfaces, 1);
          resolveUi();
        } catch (error) { rejectUi(error); }
      }
    }, {packageName: 'org.aijsprobe', implements: [java.lang.Runnable]});
    await new Promise((resolve, reject) => {
      const timeout = setTimeout(() => reject(new Error('UI callback timeout')), 12000);
      resolveUi = () => { clearTimeout(timeout); resolve(); };
      rejectUi = error => { clearTimeout(timeout); reject(error); };
      new android.os.Handler(android.os.Looper.getMainLooper()).post(new UiJob());
    });
  });
  out.ok = true;
})().catch(error => {
  out.error = String(error && error.stack || error);
  console.error(error); process.exitCode = 1;
}).finally(() => {
  fs.writeFileSync(path.join(directory, 'result.json'), JSON.stringify(out, null, 2));
  console.log('JAVA_INHERITANCE_RESULT ' + JSON.stringify(out));
});
