// Offline regression tests for syntax color, exact source text and native theme colors.
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm'), assert = require('node:assert/strict'), crypto = require('node:crypto');
const root = path.join(__dirname, '../apps/app/src/main/assets/docs');
const context = {console}; context.window = context; context.Prism = {manual: true, disableWorkerMessageHandler: true};
vm.createContext(context);
const upstream = JSON.parse(fs.readFileSync(path.join(root, 'vendor/prism/UPSTREAM.json')));
upstream.files.forEach(f => {
    const bytes = fs.readFileSync(path.join(root, 'vendor/prism', f.file));
    assert.equal(crypto.createHash('sha256').update(bytes).digest('hex'), f.sha256, f.file);
    if (f.file.endsWith('.js')) vm.runInContext(bytes.toString(), context, {timeout: 1000});
});
vm.runInContext(fs.readFileSync(path.join(root, 'api-reference-highlight.js'), 'utf8'), context);
const text = html => html.replace(/<[^>]*>/g, '').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&');
const cases = [
    ['js', '// 中文 😀\r\nconst x = "<script> &lt;";\n\tif (x) log(x);\nconst t = `hello ${x}`;\n', ['keyword', 'string', 'comment', 'function']],
    ['javascript', 'const x = "a\u00a0b";\n', ['keyword', 'string']],
    ['xml', '<vertical padding="16"><text text="中文 &amp; 😀"/></vertical>', ['tag', 'attr-name', 'attr-value']],
    ['json', '{"name":"中文", "count":3, "ok":true}', ['property', 'string', 'number', 'boolean']],
    ['java', 'public class Demo { int n = 3; String name = "中文"; }', ['keyword', 'class-name', 'number']],
    ['sh', '# comment\necho "中文"\n', ['comment', 'string']],
    ['text', '<script>throw Error("not executed")</script>', []],
];
let checks = 0;
for (const [language, source, tokens] of cases) {
    const result = context.AIJS_DOCS_CODE.render(source, language);
    assert.equal(text(result.html), source, language + ' preserves every code point'); checks++;
    tokens.forEach(token => {assert.ok(result.html.includes('token ' + token), language + ' token ' + token); checks++;});
}
assert.equal(context.AIJS_DOCS_CODE.render('x'.repeat(64001), 'js').highlighted, false); checks++;
assert.equal(context.AIJS_DOCS_CODE.render('<root/>').language, 'markup'); checks++;
assert.equal(context.AIJS_DOCS_CODE.render('{"n":1}').language, 'json'); checks++;
assert.equal(context.AIJS_DOCS_CODE.render('x', '__proto__').language, 'plain'); checks++;
const style = {}, element = {dataset: {}, style: {setProperty: (key, value) => {style[key] = value;}}};
context.URLSearchParams = URLSearchParams; context.location = {search: '?theme=light&accent=%23ffffff'};
context.document = {documentElement: element}; context.matchMedia = () => ({matches: false, addEventListener() {}});
vm.runInContext(fs.readFileSync(path.join(root, 'api-reference-theme.js'), 'utf8'), context);
const lum = hex => {const c = hex.slice(1).match(/../g).map(x => parseInt(x, 16) / 255).map(x => x <= .04045 ? x / 12.92 : ((x + .055) / 1.055) ** 2.4); return c[0] * .2126 + c[1] * .7152 + c[2] * .0722;};
for (const theme of ['light', 'dark']) for (const accent of ['#ffffff', '#000000', '#009688', '#ffff00', '#6a1b9a']) {
    context.AIJS_DOCS_SET_APPEARANCE({theme, accent});
    assert.equal(element.dataset.theme, theme); const a = lum(style['--accent']), b = lum(theme === 'dark' ? '#242a2d' : '#f0f4f4');
    assert.ok((Math.max(a, b) + .05) / (Math.min(a, b) + .05) >= 4.5, theme + accent); checks++;
}
context.AIJS_DOCS_NATIVE = true; context.URL = URL;
context.location.href = 'file:///android_asset/docs/api-reference.html?theme=dark';
context.history = {state: {query: 'files.read', visible: 40}, replaceState(state, _, url) {this.state = state; context.location.href = url;}};
context.AIJS_DOCS_SET_APPEARANCE({theme: 'light', accent: '#009688'});
assert.equal(new URL(context.location.href).searchParams.get('theme'), 'light'); checks++;
assert.equal(context.history.state.query, 'files.read'); checks++;
const css = fs.readFileSync(path.join(root, 'api-reference.css'), 'utf8');
for (const [theme, colors] of [['light', css.match(/:root\{--syntax-comment:([^}]+)\}/)[1]], ['dark', css.match(/:root\[data-theme=dark\]\{--syntax-comment:([^}]+)\}/)[1]]]) {
    for (const color of colors.match(/#[0-9a-f]{6}/g)) {
        const a = lum(color), b = lum(theme === 'dark' ? '#171c1e' : '#f0f4f4');
        assert.ok((Math.max(a, b) + .05) / (Math.min(a, b) + .05) >= 4.5, 'token contrast ' + theme + color); checks++;
    }
}
console.log('API_CODE_PASS', checks, 'checks; vendored hashes, JS/XML/JSON/Java/Shell, exact text, theme contrast');
