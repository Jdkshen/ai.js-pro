"nodejs";
const assert = require('assert');
(async () => {
  assert.strictEqual(typeof Intl, 'object');
  assert(process.versions.icu, 'Embedded Node must report its linked ICU');
  assert(/\P{ASCII}/u.test('中文🙂'));
  assert(!/\P{ASCII}/u.test('ASCII 123'));
  assert(/\p{Script=Han}+/u.test('中文'));
  assert(/\p{Emoji}/u.test('🙂'));
  assert.strictEqual(new Intl.NumberFormat('de-DE').format(1234.5), '1.234,5');
  const parts = new Intl.DateTimeFormat('en-GB', {timeZone: 'Asia/Shanghai', hour: '2-digit', hourCycle: 'h23'})
    .formatToParts(new Date('2026-01-01T00:00:00Z'));
  assert.strictEqual(parts.find(part => part.type === 'hour').value, '08');
  const child = await require('shell').exec('node -e '+
    "'if(!/\\P{ASCII}/u.test(\"中文\")||typeof Intl!==\"object\")process.exit(2);console.log(process.versions.icu)'");
  assert.strictEqual(child.code, 0, JSON.stringify(child));
  assert.strictEqual(child.result.trim(), process.versions.icu);
  console.log('NODE_ICU_RESULT '+JSON.stringify({ok: true, icu: process.versions.icu,
    unicode: process.versions.unicode, propertyEscapes: true, locales: true, timezone: true, cli: true}));
})().catch(error => { console.error(error.stack || error); process.exitCode = 1; });
