# -*- coding: utf-8 -*-
"""Parse the path-existence probe logs into one JSON per engine.

usage: parse-device-paths.py <log file> [<log file> ...] --engine <name>
out  : .artifacts/pro-api-paths-<engine>.json   { "present": {api: "function"|"value"}, "total": n }

Lines look like `PROBE_PATH|ui.layout|function`; anything not listed is missing.
"""
import io
import json
import os
import re
import sys

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
MARK = re.compile(r'(PROBE_[A-Z_]+(?:\|.*)?)$')


def main():
    args = sys.argv[1:]
    if '--engine' in args:
        i = args.index('--engine')
        engine = args[i + 1]
        logs = args[:i]
    else:
        engine, logs = 'rhino', args
    present, total = {}, 0
    for log in logs:
        text = io.open(log, encoding='utf-8', errors='replace').read()
        for raw in text.split('\n'):
            m = MARK.search(raw.rstrip('\r'))
            if not m:
                continue
            line = m.group(1)
            if line.startswith('PROBE_PATH|'):
                _, api, kind = line.split('|', 2)
                present[api] = 'value' if present.get(api) == 'value' else kind
            elif line.startswith('PROBE_PATH_MISSING|'):
                total = max(total, int(line.split('|', 1)[1]))
    out = {'engine': engine, 'present': present, 'missing_count': total,
           'present_count': len(present)}
    path = os.path.join(REPO, '.artifacts', 'pro-api-paths-%s.json' % engine)
    io.open(path, 'w', encoding='utf-8').write(json.dumps(out, ensure_ascii=False, indent=1))
    print('%-10s 存在 %d / 检查 %d -> %s' % (engine, len(present), total, os.path.basename(path)))


if __name__ == '__main__':
    main()
