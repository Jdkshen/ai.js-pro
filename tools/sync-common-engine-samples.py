"""Generate identical API v1 samples for Rhino, QuickJS and Node; --check detects drift."""
import argparse
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'samples/engine-common'
TARGET = ROOT / 'apps/app/src/main/assets/sample'
ENGINES = {'Rhino': 'rhino', 'QuickJS': 'quickjs', 'Node': 'nodejs'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    failures = []
    count = 0
    for source in sorted(SOURCE.glob('*.js')):
        body = source.read_text(encoding='utf8')
        for label, directive in ENGINES.items():
            target = TARGET / (label + ' 引擎') / '三引擎统一示例' / source.name
            expected = '"' + directive + '";\n' + body
            if args.check:
                if not target.is_file() or target.read_text(encoding='utf8') != expected:
                    failures.append(str(target.relative_to(ROOT)))
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(expected, encoding='utf8', newline='\n')
            count += 1
    if failures:
        raise SystemExit('Common samples differ:\n' + '\n'.join(failures))
    print(f'{count} common samples {"verified" if args.check else "generated"}')


if __name__ == '__main__':
    main()
