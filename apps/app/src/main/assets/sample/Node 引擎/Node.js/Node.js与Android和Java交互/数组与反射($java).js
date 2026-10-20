"nodejs";
// Java Class / primitive TYPE、反射数组和真实数组修改。
const assert = require('assert');
const files = require('files');
const OUT = '/sdcard/脚本/_nodeapi_probe_/12_java_array.json';
const out = {ok: false, api: '$java 数组/反射'};
try {
    const A = $java.findClass('java.lang.reflect.Array');
    const intType = $java.Packages.java.lang.Integer.TYPE;
    const array = A.newInstance(intType, 4);
    assert.strictEqual(array.length, 4);
    assert.strictEqual(array.getClass().getName(), '[I');
    assert.strictEqual(intType.isPrimitive(), true);
    array[0] = 12;
    assert.strictEqual(A.getInt(array, 0), 12);
    A.setInt(array, 1, 34);
    assert.strictEqual(array[1], 34);
    const method = java.lang.String.getMethod('valueOf', [intType]);
    assert.strictEqual(method.invoke(null, [$java.boxInt(56)]), '56');
    const List = $java.findClass('java.util.ArrayList');
    const list = new List();
    list.add('Auto.js');
    list.add('Node24');
    assert.strictEqual(list.size(), 2);
    assert.strictEqual(list.get(0), 'Auto.js');
    out.arrayNewInstance = {supported: true, length: array.length,
        className: array.getClass().getName(), values: Array.from(array)};
    out.arrayList = {size: list.size(), first: list.get(0)};
    out.ok = true;
} catch (error) {
    out.error = String(error && error.stack || error);
    console.error(error);
    process.exitCode = 1;
} finally {
    files.mkdir('/sdcard/脚本/_nodeapi_probe_');
    files.write(OUT, JSON.stringify(out));
    console.log(JSON.stringify(out));
}
