"nodejs";
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const Lifetime = $java.findClass('org.autojs.source.engine.android.Node24DexLifetime');
const refs = new java.util.ArrayList();
const count = 8;
function field(type, name) {
  while (type) {
    try {const f = type.getDeclaredField(name); f.setAccessible(true); return f;}
    catch (error) {type = type.getSuperclass();}
  }
  throw new Error('Missing field: ' + name);
}
(async () => {
  for (let attempt=0; attempt<40 && Lifetime.pendingLoaderCount()>0; ++attempt) {
    java.lang.System.gc(); java.lang.System.runFinalization();
    await new Promise(resolve=>setTimeout(resolve,100));
  }
  const baseline = Lifetime.closedDexCount();
  for (let index = 0; index < count; ++index) {
    const Spec = class extends java.util.ArrayList {size() {return super.size();}};
    Object.defineProperty(Spec, 'name', {value: 'DexLifetime' + index});
    const Generated = await $java.defineClass(Spec, {packageName: 'org.aijsprobe.dexlife'});
    const instance = new Generated();
    instance.add('real generated class');
    assert.strictEqual(instance.size(), 1);
    const loader = instance.getClass().getClassLoader();
    refs.add(new java.lang.ref.WeakReference(loader));
    const list = field(loader.getClass(), 'pathList').get(loader);
    for (const element of field(list.getClass(), 'dexElements').get(list)) {
      const dex = field(element.getClass(), 'dexFile').get(element);
      if (dex) refs.add(new java.lang.ref.WeakReference(dex));
    }
  }
  java.lang.System.getProperties().put('org.aijsprobe.dexlife.refs', refs);
  const result = {ok: true, count, pid: android.os.Process.myPid(), baseline,
    pending: Lifetime.pendingLoaderCount(), refs: refs.size()};
  assert.strictEqual(result.pending, count);
  fs.writeFileSync(path.join(__dirname, 'dex-created.json'), JSON.stringify(result));
  console.log('NODE_DEX_CREATE_RESULT ' + JSON.stringify(result));
})().catch(error => {console.error(error.stack || error); process.exitCode = 1;});
