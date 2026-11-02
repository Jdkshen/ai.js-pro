"""Build the optional ARM64 C/C++ component from a verified Android-host NDK archive.

No payload is placed in app assets. The output ZIP/manifest can be served by LAN or HTTPS.
The pinned upstream is lzhiyong/termux-ndk android-ndk-r29-aarch64.tar.xz (static musl hosts).
"""
from pathlib import Path, PurePosixPath
import argparse, hashlib, json, shutil, tarfile, zipfile

UPSTREAM_SHA = '02e10e4ddfe8deaeb0bd0cf29d04c981ed5bc8a5d6b560ebb9e7661f472d684b'
UPSTREAM_URL = 'https://github.com/lzhiyong/termux-ndk/releases/download/android-ndk/android-ndk-r29-aarch64.tar.xz'
PREFIX = 'android-ndk-r29/toolchains/llvm/prebuilt/linux-x86_64/'
VERSION = 'r29-arm64-api26-v1'

def selected(name):
    binaries = {'bin/clang-21': 'libexec/clang', 'bin/lld': 'libexec/ld.lld', 'bin/llvm-ar': 'bin/llvm-ar'}
    if name == 'android-ndk-r29/NOTICE': return 'licenses/NDK-NOTICE.txt'
    if name == 'android-ndk-r29/source.properties': return 'source.properties'
    if not name.startswith(PREFIX): return None
    rel = name[len(PREFIX):]
    if rel in binaries: return binaries[rel]
    if rel == 'NOTICE': return 'licenses/LLVM-NOTICE.txt'
    if rel == 'sysroot/NOTICE': return 'licenses/SYSROOT-NOTICE.txt'
    if rel.startswith(('sysroot/usr/include/', 'lib/clang/21/include/')): return rel
    if rel.startswith('lib/clang/21/lib/linux/'):
        # Native ARM64 builtins/unwinder only; sanitizers, debugger and other ABIs are optional later.
        if rel.endswith(('libclang_rt.builtins-aarch64-android.a', '/aarch64/libunwind.a', '/aarch64/libatomic.a')): return rel
    pre = 'sysroot/usr/lib/aarch64-linux-android/'
    if rel.startswith(pre):
        tail = rel[len(pre):]
        if '/' not in tail or tail.startswith('26/'): return rel
    return None

def wrapper(cxx):
    mode = '--driver-mode=g++ ' if cxx else ''
    static = ('aijs_cpp_link=1\nfor aijs_cpp_arg in "$@"; do\n'
              '  case "$aijs_cpp_arg" in -c|-S|-E|-M|-MM|-fsyntax-only|--version|--help|-help|-dumpversion|-dumpmachine|-print-*) aijs_cpp_link=0 ;; esac\ndone\n'
              'if [ "$aijs_cpp_link" -eq 1 ]; then set -- -static-libstdc++ "$@"; fi\n') if cxx else ''
    return ('#!/system/bin/sh\n'
            'aijs_cpp_root="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)" || exit 1\n'
            + static +
            'exec "$aijs_cpp_root/libexec/clang" ' + mode + '--target=aarch64-linux-android26 '
            '--sysroot="$aijs_cpp_root/sysroot" -resource-dir="$aijs_cpp_root/lib/clang/21" '
            '"$@"\n').encode()

def put(target, name, content):
    info = zipfile.ZipInfo(name, date_time=(1980,1,1,0,0,0))
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = 0o100644 << 16
    target.writestr(info, content)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    with args.source.open('rb') as source:
        digest = hashlib.file_digest(source, 'sha256').hexdigest()
    if digest != UPSTREAM_SHA: raise SystemExit('Upstream archive SHA-256 mismatch')
    args.output.mkdir(parents=True, exist_ok=True)
    archive = args.output / ('cpp-' + VERSION + '.zip')
    temp = archive.with_suffix('.zip.part')
    files = []; total = 0
    with tarfile.open(args.source, 'r:xz') as tar, zipfile.ZipFile(temp, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=6) as target:
        for member in tar:
            name = selected(member.name)
            if name is None or not member.isfile(): continue
            path = PurePosixPath(name)
            if path.is_absolute() or '..' in path.parts or '\\' in name: raise ValueError(name)
            # Stream only selected regular files; archive links are never extracted or executed.
            with tar.extractfile(member) as source, target.open(name, 'w', force_zip64=True) as output:
                shutil.copyfileobj(source, output, 65536)
            files.append(name); total += member.size
            if name in ['libexec/clang', 'libexec/ld.lld', 'bin/llvm-ar']: print('PACKED', name, member.size, flush=True)
        for name, content in [('bin/clang', wrapper(False)), ('bin/clang++', wrapper(True))]:
            put(target, name, content); files.append(name); total += len(content)
        content = ("AI.js Pro optional C/C++ component\n"
                   "Upstream: " + UPSTREAM_URL + "\nUpstream SHA256: " + UPSTREAM_SHA + "\n"
                   "ARM64 Android API26 target; Android-host clang/LLD; C++ links libc++ statically by default.\n"
                   "NDK, LLVM and sysroot notices are in licenses/. No JDK/Gradle/complete SDK.\n"
                   "Only native ARM64 and API26 stubs are retained; cross ABI, sanitizers, debugger and CMake/Ninja are excluded.\n").encode()
        put(target, 'README.txt', content); files.append('README.txt'); total += len(content)
    required = {'libexec/clang','libexec/ld.lld','bin/llvm-ar','bin/clang','bin/clang++',
                'sysroot/usr/include/stdio.h','sysroot/usr/include/c++/v1/iostream',
                'sysroot/usr/lib/aarch64-linux-android/26/crtbegin_dynamic.o',
                'lib/clang/21/lib/linux/libclang_rt.builtins-aarch64-android.a'}
    if not required.issubset(files): raise SystemExit('Missing required files: ' + repr(required - set(files)))
    temp.replace(archive)
    with archive.open('rb') as source: sha = hashlib.file_digest(source,'sha256').hexdigest()
    manifest = {'schemaVersion':1,'id':'cpp','version':VERSION,'abi':'arm64-v8a','minSdk':28,
                'url':'./'+archive.name,'sha256':sha,'size':archive.stat().st_size,'unpackedSize':total,
                'executables':['libexec/clang','libexec/ld.lld','bin/llvm-ar','bin/clang','bin/clang++'],
                'source':UPSTREAM_URL,'sourceSha256':UPSTREAM_SHA,'licenseNotices':'licenses/',
                'target':'aarch64-linux-android26','scope':'C/C++ ELF and shared libraries; native ARM64 only'}
    (args.output/'cpp-arm64-v1.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    (args.output/'cpp-arm64-v1-files.json').write_text(json.dumps(files,indent=2),encoding='utf-8')
    print(json.dumps(manifest),flush=True)

if __name__ == '__main__': main()
