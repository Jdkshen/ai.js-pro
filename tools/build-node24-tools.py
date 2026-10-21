"""Build the small Android Node CLI and bundle an installed, pinned npm distribution."""
import argparse, hashlib, json, pathlib, subprocess, zipfile

root = pathlib.Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--npm', default='C:/Program Files/nodejs/node_modules/npm')
parser.add_argument('--ndk', default='C:/Android/ndk-r26d')
args = parser.parse_args()
npm = pathlib.Path(args.npm).resolve()
version = json.loads((npm / 'package.json').read_text(encoding='utf-8'))['version']
if version != '11.13.0':
    raise SystemExit('Expected npm 11.13.0; review before upgrading the bundled package')
runtime = root / 'modules/engine/src/main/assets/source-engine-upgrade/node24/arm64-v8a'
output = root / 'modules/engine/src/main/assets/node24/tools'
output.mkdir(parents=True, exist_ok=True)
clang = pathlib.Path(args.ndk) / 'toolchains/llvm/prebuilt/windows-x86_64/bin/aarch64-linux-android26-clang++.cmd'
subprocess.run([str(clang), str(root / 'modules/engine/src/main/node24/node24_cli.cpp'),
    '-L' + str(runtime), '-lnode', '-Wl,--export-dynamic', '-Wl,-rpath,$ORIGIN', '-Wl,-z,max-page-size=16384',
    '-o', str(output / 'node')], check=True)
with zipfile.ZipFile(output / 'npm.zip', 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
    for file in sorted(npm.rglob('*')):
        if file.is_file():
            info = zipfile.ZipInfo('npm/' + file.relative_to(npm).as_posix(), date_time=(2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, file.read_bytes())
pin = {'npmVersion': version, 'libnode.so': hashlib.sha256((runtime / 'libnode.so').read_bytes()).hexdigest()}
for name in ('node', 'npm.zip'):
    pin[name] = hashlib.sha256((output / name).read_bytes()).hexdigest()
(output / 'tools.json').write_text(json.dumps(pin, indent=2) + '\n', encoding='utf-8')
print(json.dumps(pin))
