"""Join source audits, runtime exports and per-file compiler evidence.

An exported function is intentionally not labelled behaviorally verified.
"""
import argparse
import csv
import json
import re
from collections import Counter
from pathlib import Path


def write_csv(path, rows, keys):
    with path.open('w', encoding='utf-8-sig', newline='') as stream:
        writer = csv.DictWriter(stream, keys, extrasaction='ignore')
        writer.writeheader()
        for row in rows:
            writer.writerow({key: json.dumps(row.get(key), ensure_ascii=False) if isinstance(row.get(key), (list, dict)) else row.get(key, '') for key in keys})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', default='output/three-engine-audit-20261019')
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    folder = root / args.directory

    def load(name):
        return json.loads((folder / name).read_text(encoding='utf-8'))

    inventory = load('inventory.json')
    source_text = {row['path']: row['source'] for row in load('sources.json')}
    compiled = {row['path']: row for row in load('compile-result.json')['files']}
    catalogs = {engine: load(engine + '-catalog.json') for engine in ('rhino', 'quickjs', 'node')}
    rhino, node = load('rhino-audit.json'), load('node-audit.json')
    source_samples = {row['path']: row for row in rhino['files']}
    for row in node['samples']:
        source_samples['Node 引擎/' + row['path']] = row

    export_names = {engine: {row['name']: row for row in data['rows']} for engine, data in catalogs.items()}

    def exported(engine, name):
        aliases = catalogs[engine]['aliases']
        for _ in range(8):
            if name in export_names[engine]:
                return export_names[engine][name]
            replacement = next((alias['target'] + name[len(alias['name']):] for alias in aliases if name.startswith(alias['name'] + '.')), None)
            if not replacement or replacement == name:
                return None
            name = replacement
        return None

    samples = []
    quickjs_files = []
    for row in inventory['files']:
        item = dict(row)
        source = source_samples.get(row['path'], {})
        item['source_review'] = source.get('status', source.get('verdict', 'inventory_and_reference_scan'))
        item['source_conclusion'] = source.get('conclusion', '')
        item['runtime_scope'] = source.get('scope_status', {})
        item['compiler'] = compiled.get(row['path'], {})
        item['behavior_verified'] = False
        if row['engine'] == 'quickjs' and row['kind'] == 'script':
            namespaces = {name for name, info in export_names['quickjs'].items() if '.' not in name and ':' not in name and info['kind'] != 'absent'}
            candidate_calls = [name for name in row.get('member_calls', []) if name.split('.')[0] in namespaces]
            # These names shadow the global OCR namespace with a session object.
            # Keep the binding as source evidence, without asserting inference passed.
            session_names = re.findall(r'\b(?:const|let|var)\s+(\w+)\s*=\s*\$ocr\.create\s*\(', source_text[row['path']])
            session_calls = {name + '.' + method for name in session_names for method in ('detect', 'release', 'isReleased')}
            item['reviewed_local_calls'] = [name for name in candidate_calls if name in session_calls]
            item['local_api_bindings'] = {name: '$ocr.create() session; source reviewed, inference not executed' for name in session_names}
            candidate_calls = [name for name in candidate_calls if name not in session_calls]
            item['known_api_spelling'] = [name for name in candidate_calls if exported('quickjs', name)]
            item['review_api_spelling'] = [name for name in candidate_calls if not exported('quickjs', name)]
            item['source_review'] = 'compiled; source and API spelling reviewed; behavior not exhaustively executed'
            quickjs_files.append(item)
        samples.append(item)

    api_rows = []
    for engine, audit in (('rhino', rhino), ('node', node)):
        for module in audit['modules']:
            for method in module.get('methods', []):
                api_rows.append(dict(engine=engine, module=module['name'], name=method['name'],
                                     source_status=method['status'], status_basis='source audit',
                                     scope=method.get('scope', module.get('scope', {})),
                                     evidence=method.get('evidence', module.get('entry', '')),
                                     limitations=method.get('limit', method.get('limits', '')),
                                     behavior_verified=False))
            for nested_name, nested in module.get('nested', {}).items():
                for method in nested.get('methods', []):
                    name = method if isinstance(method, str) else method.get('name', '')
                    api_rows.append(dict(engine=engine, module=module['name'], name=nested_name+'.'+name,
                                         source_status='see nested source note', status_basis='dev-1094 source audit',
                                         evidence=module.get('source', module.get('entry', '')),
                                         limitations=nested.get('status_note', ''), behavior_verified=False))
            for name in module.get('missing_exports', []):
                api_rows.append(dict(engine=engine, module=module['name'], name=name,
                                     source_status='missing in dev-1094 audit', status_basis='dev-1094 source audit',
                                     evidence=module.get('source', ''), behavior_verified=False))
    for engine, catalog in catalogs.items():
      for row in catalog['rows']:
        if row['kind'] not in ('function', 'accessor', 'absent', 'java-method'):
            continue
        module = row['name'].split('.')[0]
        api_rows.append(dict(engine=engine, module=module, name=row['name'],
                             source_status='explicitly unsupported' if row.get('explicitUnsupported') else 'absent' if row['kind'] == 'absent' else 'exported; behavior not fully verified',
                             status_basis='actual runtime export and function-source inspection', scope={'runtime': 'export inventory; see process-specific findings'},
                             evidence=row.get('sourcePreview', 'QuickJS bootstrap/host or modern-module adapter'),
                             limitations=catalog.get('limitations', {}).get(module.replace('require:', ''), {}), behavior_verified=False))

    for row in api_rows:
        if row['engine'] == 'node' and row['module'] == 'color':
            row['post_review'] = 'dev-1095 restores basic aliases/numeric parse/toInt and repairs HSV/HSL edge cases; current exports and 6 device cases recorded separately'
        elif row['engine'] == 'rhino' and row['name'] == 'isShuttingDown':
            row['post_review'] = 'dev-1095 fixes isStopped alias; tested on device'
    summary = dict(files=len(samples), javascript=sum(r['kind'] == 'script' for r in samples),
                   projects=sum(r['kind'] == 'project' for r in samples),
                   compiler=load('compile-result.json')['summary'],
                   api_rows=dict(Counter(r['engine'] for r in api_rows)),
                   export_rows={engine: len(data['rows']) for engine, data in catalogs.items()},
                   export_errors={engine: data['errors'] for engine, data in catalogs.items()},
                   source_review_status=dict(Counter(r['source_review'] for r in samples)),
                   notes=['Source-audit states are preliminary static judgments, not per-API behavioral passes.',
                          'Runtime catalogs include aliases and overloads; row counts are not unique API totals.',
                          'Rhino delegate timed out after writing valid JSON; primary integrated and verified coverage.',
                          'Node source audit is a dev-1094 snapshot; subsequent fixes are recorded in the main report.'])
    (folder / 'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding='utf-8')
    (folder / 'api-ledger.json').write_text(json.dumps(api_rows, ensure_ascii=False, indent=2), encoding='utf-8')
    (folder / 'sample-ledger.json').write_text(json.dumps(samples, ensure_ascii=False, indent=2), encoding='utf-8')
    (folder / 'quickjs-audit.json').write_text(json.dumps(dict(files=quickjs_files, exports=catalogs['quickjs']), ensure_ascii=False, indent=2), encoding='utf-8')
    write_csv(folder / 'api-ledger.csv', api_rows, ['engine','module','name','source_status','status_basis','scope','evidence','limitations','post_review','behavior_verified'])
    write_csv(folder / 'sample-ledger.csv', samples, ['engine','path','kind','source_kind','runtime_engine','source_review','source_conclusion','runtime_scope','compiler','issues','review_api_spelling','local_api_bindings','reviewed_local_calls','behavior_verified'])
    print(json.dumps({key: value for key, value in summary.items() if key not in ('source_review_status', 'export_errors', 'notes')}, ensure_ascii=False))
    candidates = [(row['path'], row['review_api_spelling']) for row in quickjs_files if row['review_api_spelling']]
    print('QuickJS API spelling candidates:', json.dumps(candidates, ensure_ascii=False))


if __name__ == '__main__':
    main()
