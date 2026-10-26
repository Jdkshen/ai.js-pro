(function () {
    'use strict';
    const aliases = {js: 'javascript', xml: 'markup', html: 'markup', sh: 'bash', shell: 'bash', text: 'plain', txt: 'plain'};
    const labels = {javascript: 'JavaScript', markup: 'XML / HTML', json: 'JSON', java: 'Java', bash: 'Shell', css: 'CSS', plain: '纯文本'};
    const escape = value => value.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    function language(source, declared) {
        let name = aliases[(declared || '').toLowerCase()] || (declared || '').toLowerCase();
        if (!name) {
            if (source.trimStart().startsWith('<')) name = 'markup';
            else {
                try {JSON.parse(source); name = 'json';} catch (_) {name = 'javascript';}
            }
        }
        return Object.hasOwn ? (Object.hasOwn(labels, name) ? name : 'plain') :
            (Object.prototype.hasOwnProperty.call(labels, name) ? name : 'plain');
    }
    // Prism's default encoder replaces NBSP with ordinary spaces. Encode tokens ourselves
    // so visual highlighting preserves every original code point, including whitespace.
    function encode(token) {
        if (typeof token === 'string') return escape(token);
        if (Array.isArray(token)) return token.map(encode).join('');
        const names = [token.type].concat(token.alias || []).filter(x => /^[\w-]+$/.test(x));
        return '<span class="token ' + names.join(' ') + '">' + encode(token.content) + '</span>';
    }
    function render(source, declared) {
        const name = language(source, declared), grammar = window.Prism && window.Prism.languages[name];
        let html = escape(source), highlighted = false;
        if (grammar && source.length <= 64000) {
            try {html = encode(window.Prism.tokenize(source, grammar)); highlighted = true;}
            catch (_) { /* A failed or oversized grammar leaves all source text readable. */ }
        }
        return {html, language: name, label: labels[name], highlighted};
    }
    window.AIJS_DOCS_CODE = {render};
})();
