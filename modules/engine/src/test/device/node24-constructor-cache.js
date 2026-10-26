// Real-host regression: constructor descriptors must enable class metadata reuse,
// while preserving each object's receiver and mutable field values.
require('rhino').install();
const assert = require('node:assert/strict');
function constructDescriptor() {
    const raw = __autojsHost(JSON.stringify({kind:'java', op:'newRef',
        cls:'android.graphics.Rect', args:[1,2,3,4]}));
    const result = typeof raw === 'string' ? JSON.parse(raw) : raw;
    assert.equal(result.ok, true);
    return result.value;
}
const first = constructDescriptor(), second = constructDescriptor();
assert(first.$type > 0);
assert.equal(first.$type, second.$type);
assert.notEqual(first.$obj, second.$obj);

const a = new android.graphics.Rect(1,2,3,4);
const b = new android.graphics.Rect(5,6,12,14);
assert.equal(a.width(), 2);
assert.equal(b.width(), 7);
assert.equal(a.left, 1);
assert.equal(b.left, 5);
a.left = 0;
assert.equal(a.width(), 3);
assert.equal(b.left, 5);
const x = new java.lang.StringBuilder(), y = new java.lang.StringBuilder();
x.append('first'); y.append('second');
assert.equal(String(x.toString()), 'first');
assert.equal(String(y.toString()), 'second');
console.log('CONSTRUCTOR_CACHE_PASS');
