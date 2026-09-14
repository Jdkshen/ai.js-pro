# -*- coding: utf-8 -*-
"""Parse the on-device member probe log into JSON.

usage: parse-device-probe.py <log file> <engine>   (engine: rhino|quickjs)
out  : .artifacts/pro-api-device-<engine>.json
       { "modules": {name: [member, ...]}, "missing": [name, ...],
         "globals": {name: type}, "truncated": bool }
"""
import io
import json
import os
import re
import sys

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
MARK = re.compile(r'(PROBE_[A-Z]+(?:\|.*)?)$')


def main():
    log_path, engine = sys.argv[1], sys.argv[2]
    modules, order, missing, globals_, seen_end = {}, [], [], {}, False
    pending = {}
    text = io.open(log_path, encoding='utf-8', errors='replace').read()
    for raw in text.split('\n'):
        m = MARK.search(raw.rstrip('\r'))
        if not m:
            continue
        line = m.group(1)
        if 'PROBE_END' in line:
            seen_end = True
            continue
        if line.startswith('PROBE_MOD|'):
            _, name, flag, _rest = line.split('|', 3)
            if flag == 'MISSING':
                missing.append(name)
            continue
        if line.startswith('PROBE_GLB|'):
            _, name, kind = line.split('|', 2)
            globals_[name] = kind
            continue
        if not line.startswith('PROBE_MEM|'):
            continue
        payload = line[len('PROBE_MEM|'):]
        name, idx, csv = payload.split('|', 2)
        pending.setdefault(name, {})[int(idx)] = csv

    for name, chunks in pending.items():
        csv = ''.join(chunks[k] for k in sorted(chunks))
        parts = csv.split('|', 2)
        members = [x for x in parts[-1].split(',') if x]
        modules[name] = sorted(set(members))
        order.append(name)

    out = {
        'engine': engine,
        'source': os.path.basename(log_path),
        'modules': modules,
        'missing': sorted(missing),
        'globals': globals_,
        'truncated': not seen_end,
    }
    path = os.path.join(REPO, '.artifacts', 'pro-api-device-%s.json' % engine)
    io.open(path, 'w', encoding='utf-8').write(json.dumps(out, ensure_ascii=False, indent=1))

    print('engine      :', engine)
    print('module      :', len(modules), ' missing:', len(missing))
    print('globals     :', len(globals_), ' seen:', sum(1 for v in globals_.values() if v != 'MISSING'))
    print('complete    :', seen_end)
    print('-> %s' % path)
    for name in order[:15]:
        print('   %-16s %3d  %s' % (name, len(modules[name]), ', '.join(modules[name][:10])))
    if len(order) > 15:
        print('   ... (%d more)' % (len(order) - 15))


if __name__ == '__main__':
    main()
