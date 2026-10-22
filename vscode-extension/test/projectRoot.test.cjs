'use strict';
const assert = require('node:assert/strict');
const { test } = require('node:test');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { buildSync } = require('esbuild');

const filename = path.join(__dirname, '../src/projectRoot.ts');
const bundled = buildSync({ entryPoints: [filename], bundle: true, platform: 'node', format: 'cjs', write: false }).outputFiles[0].text;
const mod = { exports: {} };
new Function('module', 'exports', 'require', bundled)(mod, mod.exports, require);
const { findProjectRoot } = mod.exports;

function makeTree(structure) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'aijspro-project-root-'));
  for (const [rel, content] of Object.entries(structure)) {
    const abs = path.join(root, rel);
    fs.mkdirSync(path.dirname(abs), { recursive: true });
    fs.writeFileSync(abs, content);
  }
  return root;
}

test('root with project.json is the project root', () => {
  const root = makeTree({ 'project.json': '{}', 'main.js': '' });
  try { assert.equal(findProjectRoot(root), root); } finally { fs.rmSync(root, { recursive: true, force: true }); }
});

test('parent with exactly one project child resolves to the child', () => {
  const root = makeTree({ 'xiangqi-draw/project.json': '{}', 'HANDOVER.md': '' });
  try {
    assert.equal(findProjectRoot(root), path.join(root, 'xiangqi-draw'));
  } finally { fs.rmSync(root, { recursive: true, force: true }); }
});

test('parent with multiple project children stays unresolved', () => {
  const root = makeTree({ 'a/project.json': '{}', 'b/project.json': '{}' });
  try { assert.equal(findProjectRoot(root), undefined); } finally { fs.rmSync(root, { recursive: true, force: true }); }
});

test('no project child stays unresolved and hidden dirs are ignored', () => {
  const root = makeTree({ '.vscode/project.json': '{}', 'docs/readme.md': '' });
  try { assert.equal(findProjectRoot(root), undefined); } finally { fs.rmSync(root, { recursive: true, force: true }); }
});

test('real xiangqi-draw workspace resolves to nested project', () => {
  const workspace = path.join(process.env.USERPROFILE || '', 'Downloads', 'xiangqi-draw');
  if (!fs.existsSync(workspace)) return;
  const resolved = findProjectRoot(workspace);
  assert.equal(resolved, path.join(workspace, 'xiangqi-draw'));
});
