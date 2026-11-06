"""Inventory every bundled sample without executing it.

The device compiler probe consumes sources.json. A reference scan is evidence
for review, not a JavaScript parser or proof that a sample can run.
"""
import argparse
import hashlib
import json
import re
from collections import Counter
from pathlib import Path


def resolve_relative(source, request):
    base = source.parent / request
    for candidate in (base, Path(str(base) + '.js'), base / 'index.js'):
        if candidate.is_file():
            return candidate
    package = base / 'package.json'
    if package.is_file():
        try:
            main = json.loads(package.read_text(encoding='utf-8-sig')).get('main', 'index.js')
            target = base / main
            if target.is_file():
                return target
        except (ValueError, OSError):
            pass
    return None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', default='output/three-engine-audit-20261019')
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    samples = root / 'apps/app/src/main/assets/sample'
    output = (root / args.output).resolve()
    output.mkdir(parents=True, exist_ok=True)
    rows, sources = [], []
    for path in sorted(samples.rglob('*')):
        if not path.is_file():
            continue
        relative = path.relative_to(samples).as_posix()
        engine = {'Rhino 引擎': 'rhino', 'QuickJS 引擎': 'quickjs', 'Node 引擎': 'node'}[path.relative_to(samples).parts[0]]
        kind = 'script' if path.suffix.lower() == '.js' else 'project' if path.name == 'project.json' else 'resource'
        row = dict(path=relative, engine=engine, kind=kind, bytes=path.stat().st_size)
        if kind in ('script', 'project'):
            data = path.read_text(encoding='utf-8-sig')
            row['sha256'] = hashlib.sha256(path.read_bytes()).hexdigest()
            row['issues'] = []
            if kind == 'script':
                head = data[:2048]
                row['directives'] = re.findall(r'(?:["\'])(ui(?:\s+nodejs)?|nodejs|quickjs|rhino)(?:["\'])\s*;', head)
                row['engine_comment'] = re.findall(r'@engine\s+(\w+)', head)
                declared = next((x for x in row['directives'] if x in ('rhino', 'quickjs', 'nodejs')), None)
                runtime_engine = {'rhino': 'rhino', 'quickjs': 'quickjs', 'nodejs': 'node'}.get(declared, engine)
                row['runtime_engine'] = runtime_engine
                frontend = '/React/src/' in relative or '/Vue3+CLI/src/' in relative
                row['source_kind'] = 'frontend-build-source' if frontend else 'engine-script'
                row['requires_xml_preprocessor'] = bool(re.search(r'\bui\s*\.\s*(?:layout|inflate)\s*\(\s*<', data))
                # Literal discovery deliberately keeps commented references for human review.
                row['literal_requires'] = sorted(set(re.findall(r'\brequire\s*\(\s*["\']([^"\']+)["\']\s*\)', data)))
                row['relative_requires'] = []
                for request in row['literal_requires']:
                    if not request.startswith('.'):
                        continue
                    resolved = resolve_relative(path, request)
                    row['relative_requires'].append(dict(request=request, resolved=str(resolved.relative_to(root).as_posix()) if resolved else None))
                    if not resolved:
                        row['issues'].append(dict(kind='relative_require_unresolved', value=request, confidence='review_required'))
                row['member_calls'] = sorted(set('.'.join(pair) for pair in re.findall(r'(?<![\w$.])([$\w]+)\.([$\w]+)\s*\(', data)))
                sources.append(dict(path=relative, engine=runtime_engine, category_engine=engine, source_kind=row['source_kind'], source=data, sha256=row['sha256']))
            else:
                try:
                    config = json.loads(data)
                    row['main'] = config.get('main')
                    row['type'] = config.get('type')
                    if config.get('main') and not (path.parent / config['main']).is_file():
                        row['issues'].append(dict(kind='project_main_missing', value=config['main'], confidence='confirmed'))
                except ValueError as error:
                    row['issues'].append(dict(kind='invalid_json', value=str(error), confidence='confirmed'))
        rows.append(row)
    summary = {engine: dict(Counter(row['kind'] for row in rows if row['engine'] == engine)) for engine in ('rhino', 'quickjs', 'node')}
    report = dict(scope='all bundled sample files', summary=summary, files=rows)
    (output / 'inventory.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    (output / 'sources.json').write_text(json.dumps(sources, ensure_ascii=False), encoding='utf-8')
    print(json.dumps(summary, ensure_ascii=False))
    print('Script/project issues:', sum(len(row.get('issues', [])) for row in rows))


if __name__ == '__main__':
    main()
