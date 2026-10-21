'use strict';
const assert = require('assert');
const path = require('path');
globalThis.__autojsHost = raw => {
  const request = JSON.parse(raw);
  return JSON.stringify({ok: true, value: request.kind === 'engines' && request.action === 'my'
    ? {id: 1, engine: 'Node24JavaScriptEngine', execArgv: {}} : null});
};
require(path.resolve(__dirname, '../../main/assets/node24/prelude.js'));
const config = $autojs.engines.myEngine().executionConfig;
config.setProjectConfig({buildInfo: {buildId: 'real-build', buildNumber: 3, buildTime: 123456, isRelease: true}});
const info = config.getProjectConfig().buildInfo;
assert.strictEqual(info.isRelease(), true);
assert.strictEqual(info.getBuildId(), 'real-build');
assert.strictEqual(info.getBuildNumber(), 3);
assert.strictEqual(info.getBuildTime(), 123456);
assert.strictEqual(JSON.parse(JSON.stringify(info)).isRelease, true);
info.setRelease(false);
assert.strictEqual(info.isRelease(), false);
assert.strictEqual(JSON.parse(JSON.stringify(info)).isRelease, false);
config.setProjectConfig({buildInfo: {build_id: 'legacy', build_number: 4, build_time: 456}});
assert.strictEqual(config.getProjectConfig().buildInfo.isRelease(), false, 'missing release flag must stay development');
assert.strictEqual(config.getProjectConfig().buildInfo.getBuildId(), 'legacy');
config.setProjectConfig({});
assert.strictEqual(config.getProjectConfig().buildInfo, undefined, 'no fabricated release build info');
console.log('PASS project BuildInfo: release/development, getters, legacy keys and JSON round trip');
