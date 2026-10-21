"nodejs";
const assert = require('assert');
(async () => {
  const plugins = require('plugins');
  try {
    const builder = await plugins.load('com.jdkshen.apkbuilder.plugin');
    assert.strictEqual(builder.capabilities.templateReady, true);
    assert(builder.templateSize() > 20 * 1024 * 1024);
    assert.strictEqual(builder.templateSize(), builder.capabilities.templateSize);
    await plugins.unload('com.jdkshen.apkbuilder.plugin');
    const chess = await plugins.load('com.jdkshen.xiangqi.plugin');
    assert.strictEqual(chess.engine.available(), true);
    assert(chess.rules, chess.rulesError);
    assert(require('fs').existsSync(require('path').join(chess.paths.scriptDir, 'index.js')));
    await plugins.unloadAll();
    console.log('NODE_INSTALLED_PLUGINS_RESULT '+JSON.stringify({ok:true,
      builderTemplateBytes: builder.capabilities.templateSize, chessRules:true, chessNative:true}));
  } finally { await plugins.unloadAll(); }
})().catch(error => {console.error(error.stack || error); process.exitCode=1;});
