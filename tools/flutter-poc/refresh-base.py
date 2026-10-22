"""Vendor the matched Android release engine and classes from a pinned Flutter SDK."""
import argparse
import hashlib
import json
import shutil
import subprocess
import zipfile
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', required=True, type=Path)
    parser.add_argument('--strip', required=True, type=Path)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[2]
    version = json.loads((args.sdk / 'bin/cache/flutter.version.json').read_text())
    engine = (args.sdk / 'bin/internal/engine.version').read_text().strip()
    if version['engineRevision'] != engine or version['channel'] != 'stable':
        raise SystemExit('SDK version/engine mismatch or non-stable SDK')
    base = repo / 'flutter-payload-base'
    library = base / 'jniLibs/arm64-v8a/libflutter.so'
    library.parent.mkdir(parents=True, exist_ok=True)
    embedding = base / 'flutter_embedding_release.jar'
    artifact = args.sdk / 'bin/cache/artifacts/engine/android-arm64-release/flutter.jar'
    with zipfile.ZipFile(artifact) as source:
        library.write_bytes(source.read('lib/arm64-v8a/libflutter.so'))
        with zipfile.ZipFile(embedding, 'w', compression=zipfile.ZIP_DEFLATED) as dest:
            for item in source.infolist():
                if item.filename.endswith('.class') or item.filename.startswith('META-INF/'):
                    dest.writestr(item, source.read(item))
    subprocess.run([str(args.strip), '--strip-unneeded', str(library)], check=True)
    shutil.copy2(args.sdk / 'LICENSE', base / 'LICENSE')
    shutil.copy2(artifact.with_name('license.flutter_jar_zip.md'), base / 'license.flutter_jar_zip.md')
    report = {key: version[key] for key in ['frameworkVersion', 'frameworkRevision',
                                          'engineRevision', 'dartSdkVersion', 'channel']}
    report['source'] = 'Flutter SDK android-arm64-release/flutter.jar (matched engine and embedding)'
    report['artifacts'] = {
        str(path.relative_to(base).as_posix()): {
            'bytes': path.stat().st_size,
            'sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
        } for path in [library, embedding]
    }
    (base / 'runtime.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf8')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
