"nodejs";
// 数组与反射($java)
// 演示 $java.findClass("[I")、java.lang.reflect.Array.newInstance 与 $autojs.java 包路径访问。
const files = require('files');
const OUT = '/sdcard/脚本/_nodeapi_probe_/12_java_array.json';
function writeResult(value) {
    try { files.mkdir('/sdcard/脚本/_nodeapi_probe_'); } catch (ignored) { }
    try { files.write(OUT, JSON.stringify(value)); } catch (error) { console.error('写结果失败：', error); }
    console.log(JSON.stringify(value));
}

try {
    const out = { ok: true, api: '$java 数组/反射' };

    // Node24 的 Java 桥只支持按完整类名/数组描述符取类，不支持 JS 继承 Java 类。
    try {
        const intArrayClass = $java.findClass('[I');
        out.intArrayClass = String(intArrayClass);
        // 实测：Array.newInstance 在当前单向 Node→Java 桥下不能把 Class/primitive TYPE
        // 参数写过去（宿主报“不能把普通 JS 对象传给 Java”），示例如实记录并降级。
        try {
            const reflectArray = $java.findClass('java.lang.reflect.Array');
            const intType = $java.Packages.java.lang.Integer.TYPE;
            const array = reflectArray.newInstance(intType, 4);
            out.arrayNewInstance = {
                supported: true, length: array.length, className: array.getClass().getName()
            };
        } catch (arrayError) {
            out.arrayNewInstance = {
                supported: false,
                error: String(arrayError && arrayError.message || arrayError),
                usage: 'Java 反射 Array.newInstance 需要 Class 参数；当前 Node24 桥未开放 Class/primitive 入参'
            };
        }
    } catch (error) { out.arrayError = String(error && error.stack || error); }

    // 普通集合类：说明 $autojs.java / $java 包路径访问的兼容用途。
    try {
        const ArrayList = $java.findClass('java.util.ArrayList');
        const list = new ArrayList();
        list.add('Auto.js');
        list.add('Node24');
        out.arrayList = { size: list.size(), first: String(list.get(0)) };
    } catch (error) { out.arrayListError = String(error && error.stack || error); }

    out.ok = !out.arrayError;
    out.note = '用于第三方工程里依赖 Java 数组/反射的兼容场景；findClass 需要完整类名或 [I 这类数组描述符；Array.newInstance 当前桥限制已如实记录';
    writeResult(out);
} catch (error) {
    writeResult({ ok: false, api: '$java 数组/反射', error: String(error && error.stack || error) });
}
