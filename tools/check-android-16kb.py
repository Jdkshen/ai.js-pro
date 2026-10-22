"""Check 64-bit Android ELF load/RELRO layout and APK native-library ZIP alignment.

No third-party allowlist: also inspect the extracted Node runtime and CLI assets.
An isolated RELRO LOAD need not end on a page boundary when the rounded page
contains no writable data (Android's documented exception).
"""
import argparse
import hashlib
import json
import struct
import zipfile
from pathlib import Path

PAGE = 16384


def inspect_elf(data):
    if len(data) < 64 or data[:5] != b'\x7fELF\x02' or data[5] not in (1, 2):
        raise ValueError('not a valid ELF64 header')
    endian = '<' if data[5] == 1 else '>'
    machine = struct.unpack_from(endian + 'H', data, 18)[0]
    offset = struct.unpack_from(endian + 'Q', data, 32)[0]
    size, count = struct.unpack_from(endian + 'HH', data, 54)
    if size < 56 or not count or offset + size * count > len(data):
        raise ValueError('invalid ELF program-header table')
    headers = [struct.unpack_from(endian + 'IIQQQQQQ', data, offset + i * size)
               for i in range(count)]
    loads = [h for h in headers if h[0] == 1]
    issues = []
    if not loads:
        issues.append('no PT_LOAD segments')
    for h in loads:
        if h[7] < PAGE or h[7] & (h[7] - 1) or (h[3] - h[2]) % PAGE:
            issues.append(f'PT_LOAD not 16KB aligned: align={h[7]:#x}, offset={h[2]:#x}, vaddr={h[3]:#x}')
    relro = []
    for h in headers:
        if h[0] != 0x6474e552:
            continue
        end = h[3] + h[6]
        rounded_end = (end + PAGE - 1) // PAGE * PAGE
        overlaps = any(l[1] & 2 and max(l[3], end) < min(l[3] + l[6], rounded_end)
                       for l in loads)
        relro.append({'end': end, 'endAligned': end % PAGE == 0,
                      'roundedProtectionOverlapsWritableData': overlaps})
        if overlaps:
            issues.append(f'PT_GNU_RELRO rounded to 16KB protects writable data after {end:#x}')
    return {'machine': machine, 'loadAlignments': sorted({h[7] for h in loads}),
            'relro': relro, 'issues': issues}


def check(path, abi='arm64-v8a'):
    machine = {'arm64-v8a': 183, 'x86_64': 62}[abi]
    records = []
    if zipfile.is_zipfile(path):
        with zipfile.ZipFile(path) as archive, path.open('rb') as apk:
            for entry in archive.infolist():
                native = entry.filename.startswith('lib/' + abi + '/') and entry.filename.endswith('.so')
                extracted = entry.filename.startswith('assets/') and (
                    entry.filename.endswith('.so') or entry.filename.endswith('/node'))
                if not (native or extracted):
                    continue
                data = archive.read(entry)
                # Other ABI assets may coexist in a universal APK.
                if extracted and ('/' + abi + '/') not in entry.filename:
                    if len(data) < 64 or data[:5] != b'\x7fELF\x02':
                        continue
                    if struct.unpack_from('<H' if data[5] == 1 else '>H', data, 18)[0] != machine:
                        continue
                record = {'name': entry.filename, 'bytes': len(data),
                          'sha256': hashlib.sha256(data).hexdigest()}
                try:
                    record.update(inspect_elf(data))
                    if record['machine'] != machine:
                        record['issues'].append('ELF machine does not match APK ABI')
                except (ValueError, struct.error) as error:
                    record['issues'] = [str(error)]
                if native and entry.compress_type == zipfile.ZIP_STORED:
                    apk.seek(entry.header_offset)
                    header = apk.read(30)
                    name_size, extra_size = struct.unpack_from('<HH', header, 26)
                    data_offset = entry.header_offset + 30 + name_size + extra_size
                    record['zipDataOffset'] = data_offset
                    if data_offset % PAGE:
                        record['issues'].append('uncompressed APK native library is not ZIP 16KB aligned')
                records.append(record)
    else:
        data = path.read_bytes()
        records.append({'name': path.name, 'bytes': len(data), **inspect_elf(data)})
    return {'path': str(path.resolve()), 'abi': abi, 'libraries': records,
            'ok': bool(records) and all(not r['issues'] for r in records)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('path', type=Path)
    parser.add_argument('--abi', choices=['arm64-v8a', 'x86_64'], default='arm64-v8a')
    parser.add_argument('--report', type=Path)
    args = parser.parse_args()
    result = check(args.path, args.abi)
    for record in result['libraries']:
        print(('FAIL ' if record['issues'] else 'OK   ') + record['name'])
        for issue in record['issues']:
            print('     ' + issue)
    print(f"16KB: {'PASS' if result['ok'] else 'FAIL'}, {len(result['libraries'])} ELF files, no allowlist")
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf8')
    raise SystemExit(0 if result['ok'] else 1)


if __name__ == '__main__':
    main()
