# -*- coding: utf-8 -*-
"""Generate the on-device member probe script from the cross-check table.

Input : .artifacts/pro-api-status.json
Output: .artifacts/pro-api-probe.js (QuickJS)  +  .artifacts/pro-api-probe-rhino.js

For every module / object name referenced by the PDF the probe enumerates the
visible members -- own properties, the prototype chain, the `.prototype` of
class-like values, and Java reflection (getMethods/getFields up the superclass
chain) -- then prints them as `PROBE_MEM|<name>|<chunkIdx>|<csv>` lines.  Bare
names (no dot) are only checked for existence (`PROBE_GLB|name|type`).

Logcat truncates very long lines and its buffer is small, hence the chunking
(CHUNK bytes per line) and the PROBE_BEGIN / PROBE_END markers.
"""
import io
import json
import os

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
STATUS = os.path.join(REPO, '.artifacts', 'pro-api-status.json')
OUT = os.path.join(REPO, '.artifacts', 'pro-api-probe.js')

JS = r'''// @engine %(engine)s
var MODULES = %(modules)s;
var GLOBALS = %(globals)s;
var CHUNK = 600;

var BUILTIN = {
    '__defineGetter__': 1, '__defineSetter__': 1, '__lookupGetter__': 1,
    '__lookupSetter__': 1, '__proto__': 1, 'constructor': 1, 'hasOwnProperty': 1,
    'isPrototypeOf': 1, 'propertyIsEnumerable': 1, 'toLocaleString': 1,
    'toString': 1, 'valueOf': 1, 'apply': 1, 'call': 1, 'bind': 1,
    'caller': 1, 'arguments': 1, 'prototype': 1
};

function out(arr, n) {
    if (typeof n !== 'string' || !n.length || BUILTIN[n]) return;
    if (arr.indexOf(n) < 0) arr.push(n);
}

function collect(obj, arr) {
    var i;
    try {
        var own = Object.getOwnPropertyNames(obj);
        for (i = 0; i < own.length; i++) out(arr, own[i]);
    } catch (e) { }
    try {
        var p = Object.getPrototypeOf(obj);
        var guard = 0;
        while (p && guard++ < 6) {
            var pn = Object.getOwnPropertyNames(p);
            for (i = 0; i < pn.length; i++) out(arr, pn[i]);
            p = Object.getPrototypeOf(p);
        }
    } catch (e) { }
    try {
        if (typeof obj === 'function' && obj.prototype) {
            var qn = Object.getOwnPropertyNames(obj.prototype);
            for (i = 0; i < qn.length; i++) out(arr, qn[i]);
        }
    } catch (e) { }
    try {
        if (obj.getClass) {
            var cls = obj.getClass();
            var depth = 0;
            while (cls && depth++ < 8) {
                var ms = cls.getMethods();
                for (i = 0; i < ms.length; i++) out(arr, '' + ms[i].getName());
                var fs = cls.getFields();
                for (i = 0; i < fs.length; i++) out(arr, '' + fs[i].getName());
                cls = cls.getSuperclass();
            }
        }
    } catch (e) { }
    try {
        // Rhino exposes a Java *class* wrapper as `.class` (java.lang.Class)
        var jc = obj['class'];
        if (jc && jc.getMethods) {
            var dm = jc.getMethods();
            for (i = 0; i < dm.length; i++) out(arr, '' + dm[i].getName());
            var df = jc.getFields();
            for (i = 0; i < df.length; i++) out(arr, '' + df[i].getName());
        }
    } catch (e) { }
}

function resolve(path) {
    var parts = path.split('.');
    var cur = global;
    for (var i = 0; i < parts.length; i++) {
        if (cur === null || typeof cur === 'undefined') return null;
        try { cur = cur[parts[i]]; } catch (e) { return null; }
    }
    return (typeof cur === 'undefined') ? null : cur;
}

function report(name, members) {
    var s = members.join(',');
    var idx = 0;
    var i = 0;
    while (i < s.length || idx === 0) {
        console.log('PROBE_MEM|' + name + '|' + idx + '|' + s.substring(i, i + CHUNK));
        i += CHUNK;
        idx++;
        if (i >= s.length) break;
    }
}

console.log('PROBE_BEGIN');
var mi, gi, name, obj, members;
for (mi = 0; mi < MODULES.length; mi++) {
    name = MODULES[mi];
    obj = resolve(name);
    if (obj === null) {
        console.log('PROBE_MOD|' + name + '|MISSING|');
    } else {
        members = [];
        collect(obj, members);
        report(name, members);
    }
    console.log('PROBE_DONE|' + name);
}
for (gi = 0; gi < GLOBALS.length; gi++) {
    var g = GLOBALS[gi];
    var v = null;
    try { v = global[g]; } catch (e) { }
    console.log('PROBE_GLB|' + g + '|' + (typeof v === 'undefined' ? 'MISSING' : typeof v));
}
console.log('PROBE_END');
'''


def main():
    rows = json.load(io.open(STATUS, encoding='utf-8'))
    modules, globals_ = [], []
    for r in rows:
        api = r['api']
        if api.startswith('$'):
            base = api.split('.')[0]
            if base not in modules:
                modules.append(base)
        if '.' in api:
            mod = api.rsplit('.', 1)[0]
            if mod not in modules:
                modules.append(mod)
        elif api not in globals_:
            globals_.append(api)
    for extra in ('ui.imageCache', 'floaty.window', 'events.bus', 'threads.currentThread',
                  'console', 'globalconsole', 'runtime'):
        if extra not in modules:
            modules.append(extra)
    modules = sorted(m for m in modules if not m.startswith('2.'))
    globals_ = sorted(g for g in globals_ if g.isascii() and ' ' not in g)

    js = JS % {'engine': 'quickjs',
               'modules': json.dumps(modules, ensure_ascii=False),
               'globals': json.dumps(globals_, ensure_ascii=False)}
    io.open(OUT, 'w', encoding='utf-8', newline='\n').write(js)
    io.open(OUT.replace('.js', '-rhino.js'), 'w', encoding='utf-8', newline='\n').write(
        js.replace('// @engine quickjs', '// @engine rhino', 1))
    # UI-context variants: some modules (canvas, the `<console>` proxies, ...) only
    # exist inside a `"ui";` script, so a plain-script miss is not conclusive.
    for src, suffix in ((js, '-ui.js'), (js.replace('// @engine quickjs', '// @engine rhino', 1),
                                        '-rhino-ui.js')):
        head, rest = src.split('\n', 1)
        io.open(OUT.replace('.js', suffix), 'w', encoding='utf-8', newline='\n').write(
            head + '\n"ui";\n' + rest)
    print('modules: %d  globals: %d' % (len(modules), len(globals_)))
    print('-> %s (+ rhino / ui / rhino-ui variants)' % OUT)
    print('modules:', ', '.join(modules))


if __name__ == '__main__':
    main()
