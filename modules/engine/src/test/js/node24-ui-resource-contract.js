'use strict';
const assert = require('assert'), fs = require('fs'), path = require('path'), vm = require('vm');
const source = fs.readFileSync(path.resolve(__dirname, '../../main/assets/node24/ui.js'), 'utf8');
function load(host) {
    let ui;
    const requests = [], context = vm.createContext({ console, setTimeout, clearTimeout, setInterval, clearInterval,
        __ajsRegisterModule: (name, factory) => {
            assert.equal(name, 'ui');
            ui = factory({ host: request => { requests.push(request); return host(request); } });
        }
    });
    vm.runInContext(source, context, { filename: 'node24/ui.js' });
    return { ui, requests };
}
for (const host of [() => { throw Error('bridge closed'); }, () => ({ ok: false, error: 'missing resources' }),
    () => 'invalid json', () => ({ ok: true }), () => ({ ok: true, id: -1 })]) {
    const { ui } = load(host);
    assert.equal(ui.R.drawable.absent, 0, 'missing icons must never become Android NO_ID (-1)');
    assert.equal(ui.R.layout.absent, 0);
    assert.equal(ui.R.style.absent, 0);
}
const { ui, requests } = load(request => ({ ok: true, id: request.name === 'missing' ? 0 : 0x7f080123 }));
assert.equal(ui.R.drawable.real_icon, 0x7f080123);
assert.equal(ui.R.drawable.missing, 0);
assert.equal(ui.R.layout.real_layout, 0x7f080123);
const before = requests.length;
assert.equal(ui.R.then, undefined);
assert.equal(ui.R.drawable.toJSON, undefined);
assert.equal(ui.R[Symbol.iterator], undefined);
assert.equal(requests.length, before, 'proxy introspection must not call the resource bridge');
console.log('PASS Node UI resources: bridge errors, absent IDs, legacy -1, valid IDs and proxy introspection');
