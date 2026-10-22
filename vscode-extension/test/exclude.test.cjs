'use strict';
const assert = require('node:assert/strict');
const { test } = require('node:test');
const { buildSync } = require('esbuild');
const path = require('node:path');
const { createRequire } = require('node:module');

const filename = path.join(__dirname, '../src/exclude.ts');
const bundled = buildSync({
  entryPoints: [filename],
  bundle: true,
  platform: 'node',
  format: 'cjs',
  write: false,
}).outputFiles[0].text;
const excludeModule = { exports: {} };
const localRequire = createRequire(filename);
new Function('module', 'exports', 'require', bundled)(excludeModule, excludeModule.exports, localRequire);
const { createExcludeMatcher } = excludeModule.exports;

function matcher(patterns) {
  return createExcludeMatcher(patterns);
}

test('legacy exact names still match at any depth', () => {
  const m = matcher(['node_modules', '.git', 'dist']);
  assert.equal(m.ignoresDirectory('node_modules'), true);
  assert.equal(m.ignoresDirectory('packages/app/node_modules'), true);
  assert.equal(m.ignoresDirectory('a/b/.git'), true);
  assert.equal(m.ignoresDirectory('src'), false);
  // 文件在已被剪枝的目录下时不会进入 ignoresFile；目录遍历会先跳过 node_modules。
  assert.equal(m.ignoresFile('src/main.js'), false);
});

test('name glob matches basenames at any depth', () => {
  const m = matcher(['*.apk', '*.tmp']);
  assert.equal(m.ignoresFile('app.apk'), true);
  assert.equal(m.ignoresFile('out/release/app.apk'), true);
  assert.equal(m.ignoresFile('app.apk.txt'), false);
  assert.equal(m.ignoresFile('a/b/c.tmp'), true);
  const question = matcher(['file?.js']);
  assert.equal(question.ignoresFile('file1.js'), true);
  assert.equal(question.ignoresFile('file10.js'), false);
});

test('root-relative path globs match only inside the prefix', () => {
  const m = matcher(['assets/**']);
  assert.equal(m.ignoresDirectory('assets'), true);
  assert.equal(m.ignoresFile('assets/images/a.png'), true);
  assert.equal(m.ignoresFile('sub/assets/images/a.png'), false);
  const out = matcher(['out/*.log']);
  assert.equal(out.ignoresFile('out/a.log'), true);
  assert.equal(out.ignoresFile('out/sub/a.log'), false);
});

test('double-star matches any depth and can prune nested modules', () => {
  const m = matcher(['**/node_modules/**']);
  assert.equal(m.ignoresDirectory('node_modules'), true);
  assert.equal(m.ignoresDirectory('a/b/node_modules'), true);
  assert.equal(m.ignoresFile('node_modules/index.js'), true);
  assert.equal(m.ignoresFile('a/b/node_modules/deep/x.js'), true);
  assert.equal(m.ignoresFile('a/b/index.js'), false);
  const tmp = matcher(['**/*.tmp']);
  assert.equal(tmp.ignoresFile('x.tmp'), true);
  assert.equal(tmp.ignoresFile('a/b/x.tmp'), true);
});

test('directory-only patterns do not hide same-named files', () => {
  const m = matcher(['cache/']);
  assert.equal(m.ignoresDirectory('cache'), true);
  assert.equal(m.ignoresDirectory('a/cache'), true);
  assert.equal(m.ignoresFile('cache'), false);
  // cache/ 只剪枝目录；目录内文件由遍历时的目录剪枝覆盖，不要求 ignoresFile 命中。
  assert.equal(m.ignoresFile('a/cache/data.bin'), false);
});

test('normalizes separators, leading ./ or /, and ignores empty patterns', () => {
  const m = matcher(['', '  ', '.', './assets/**', '\\build']);
  assert.equal(m.ignoresFile('assets/a.png'), true);
  assert.equal(m.ignoresDirectory('build'), true);
  assert.equal(matcher([]).ignoresFile('anything.apk'), false);
  const windows = matcher(['models']);
  assert.equal(windows.ignoresDirectory('a\\b\\models'), true);
});
