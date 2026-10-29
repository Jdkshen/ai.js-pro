"""Fail on stripped Retrofit API generics in the actual APK's DEX files."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess


def cached_jar(cache, group, artifact, version):
    matches = sorted((cache / 'caches/modules-2/files-2.1' / group / artifact / version).glob('*/*.jar'))
    if not matches:
        raise SystemExit(f'Missing build dependency {group}:{artifact}:{version}; run the Android build first')
    return str(matches[0])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    args = parser.parse_args()
    if not args.apk.is_file():
        raise SystemExit(f'APK not found: {args.apk}')
    cache = Path(os.environ.get('GRADLE_USER_HOME', str(Path.home() / '.gradle')))
    jars = [cached_jar(cache, 'org.smali', 'dexlib2', '2.5.2'),
            cached_jar(cache, 'com.google.guava', 'guava', '27.1-android')]
    java_home = os.environ.get('JAVA_HOME')
    java = str(Path(java_home) / 'bin' / ('java.exe' if os.name == 'nt' else 'java')) if java_home else shutil.which('java')
    if not java:
        raise SystemExit('JDK 17+ is required for the final APK signature audit')
    source = Path(__file__).resolve().parent / 'apk-audit/CheckRetrofitSignatures.java'
    result = subprocess.run([java, '-Xmx512m', '--class-path', os.pathsep.join(jars),
                             str(source), str(args.apk.resolve())], check=False)
    raise SystemExit(result.returncode)


if __name__ == '__main__':
    main()
