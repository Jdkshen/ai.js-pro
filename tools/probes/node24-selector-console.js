"nodejs";
// Run on a real Android accessibility tree; no clicks or external file changes.
const { accessibility, select } = require('accessibility');
const { format } = require('node:util');

(async function () {
    await accessibility.enableService({ toast: false });
    const nodes = await select().where(node => node.depth >= 0).atMost(12).all();
    if (!nodes.length) throw new Error('Accessibility tree is empty');
    const output = format('节点 %O', nodes);
    const legacy = select().findOnce();
    const root = nodes[0].parent() || nodes[0];
    const checks = {
        realNodes: nodes.length > 0,
        compactLength: output.length < nodes.length * 1000 + 100,
        compactLines: output.split('\n').length <= nodes.length + 2,
        noPrivateTree: !output.includes('_tree'),
        snapshotText: nodes.every(node => node.text === String(node.raw().text || '')),
        snapshotPath: nodes.every(node => JSON.stringify(node.path) === JSON.stringify(node.raw().path)),
        navigation: Array.isArray(root.children()) && root.tree().length >= 1,
        legacyInspect: legacy !== null && format('%O', legacy).startsWith('UiObject '),
        legacyRaw: legacy !== null && legacy.text() === String(legacy.raw().text || '')
    };
    console.log('真实节点摘要:', nodes);
    console.log('SELECTOR_CONSOLE_RESULT=' + JSON.stringify({
        total: Object.keys(checks).length,
        passed: Object.values(checks).filter(Boolean).length,
        checks, nodes: nodes.length, characters: output.length, lines: output.split('\n').length
    }));
    if (Object.values(checks).some(value => !value)) throw new Error('Node inspection contract failed');
})().catch(error => { console.error(error.stack || error); process.exitCode = 1; });
