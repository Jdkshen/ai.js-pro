#!/usr/bin/env python3
"""Reproducible r2ghidra 6.2.4 build for radare2 6.2.4 on Android arm64 (API 26, 16 KiB pages).

Pinned inputs
  r2ghidra 6.2.4 tag tarball          sha256 8cdd84c2c94992a41b6dedf74c8c7e0a01951246c7ea1556d350ef3dab1c3fa4
  ghidra-native revision 483ae94...   sha256 916adfe4c35866e2c654bfb2942ea420b19dc8811d891ce426ac5adfee74907f
  r2ghidra_sleigh-6.2.4.zip           sha256 64c441a3ac9bf5df41a3ccb8abeec171931160e8c85544b1db5a6100d670e5cd
  radare2 SDK 6.2.4 (ABI 152)         .artifacts/reverse-suite-20261103/sdk/radare2
  Android NDK r26d                    C:/Android/ndk-r26d

Output layout (under r2ghidra-build/dist)
  lib/core_ghidra.so        r2 core plugin, renamed from libcore_r2ghidra.so
  share/r2ghidra/*.sla etc  pinned upstream 6.2.4 sleigh data (flat directory)
  manifest.json             versions, hashes, compiler and verification results

Why this script drives the compile commands itself: ninja 1.13.1 on this Windows
sandbox deterministically stalls after starting two compiler children (both the
.cmd NDK wrappers and direct clang.exe reproduce it), while the same commands run
fine under Python/cmd.  The script still uses Meson to configure and to emit the
exact compile/archive/link command lines, so it is not a hand-written build.
`--driver ninja` is available for hosts where ninja behaves normally.

No device, Gradle, git or network operation is performed unless a pinned source
archive is missing and --fetch is passed.
"""
from __future__ import annotations

import argparse
import concurrent.futures
import hashlib
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import time
import urllib.request
import zipfile
import uuid
from pathlib import Path

R2GHIDRA_VERSION = "6.2.4"
GHIDRA_NATIVE_REV = "483ae94bcbc661a77667e52f1eff75928cb6aa2e"
R2GHIDRA_URL = f"https://github.com/radareorg/r2ghidra/archive/refs/tags/{R2GHIDRA_VERSION}.tar.gz"
GHIDRA_URL = f"https://github.com/radareorg/ghidra-native/archive/{GHIDRA_NATIVE_REV}.tar.gz"
SLEIGH_URL = f"https://github.com/radareorg/r2ghidra/releases/download/{R2GHIDRA_VERSION}/r2ghidra_sleigh-{R2GHIDRA_VERSION}.zip"

HASHES = {
    f"r2ghidra-{R2GHIDRA_VERSION}.tar.gz": "8cdd84c2c94992a41b6dedf74c8c7e0a01951246c7ea1556d350ef3dab1c3fa4",
    f"ghidra-native-{GHIDRA_NATIVE_REV[:7]}.tar.gz": "916adfe4c35866e2c654bfb2942ea420b19dc8811d891ce426ac5adfee74907f",
    f"r2ghidra_sleigh-{R2GHIDRA_VERSION}.zip": "64c441a3ac9bf5df41a3ccb8abeec171931160e8c85544b1db5a6100d670e5cd",
}

HERE = Path(__file__).resolve().parent
BUILD_ROOT = HERE / "r2ghidra-build"
SRC = BUILD_ROOT / f"r2ghidra-{R2GHIDRA_VERSION}"
GHIDRA_SRC = SRC / "subprojects" / "ghidra-native"
BUILD = BUILD_ROOT / "build"
DIST = BUILD_ROOT / "dist"
SLEIGH_DIR = BUILD_ROOT / "sleigh" / f"r2ghidra_sleigh-{R2GHIDRA_VERSION}"
PATCH_DIR = SRC / "subprojects" / "packagefiles" / "ghidra-native" / "patches"


def log(msg: str) -> None:
    print(msg, flush=True)

def safe_remove(path: Path) -> None:
    checked=path.resolve();boundary=BUILD_ROOT.resolve()
    if checked==boundary or not checked.is_relative_to(boundary):raise ValueError(f'unsafe build cleanup: {checked}')
    shutil.rmtree(checked,ignore_errors=True)


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def verify_inputs(fetch: bool) -> None:
    for name, expected in HASHES.items():
        path = BUILD_ROOT / name
        if not path.exists():
            if not fetch:
                raise SystemExit(f"missing pinned input: {path} (pass --fetch to download)")
            url=next(url for prefix,url in [('r2ghidra-',R2GHIDRA_URL),('ghidra-native-',GHIDRA_URL),('r2ghidra_sleigh-',SLEIGH_URL)] if name.startswith(prefix))
            log(f"fetching {url}")
            urllib.request.urlretrieve(url, path)
        actual = sha256(path)
        if actual != expected:
            raise SystemExit(f"hash mismatch for {path.name}: {actual} != {expected}")
        log(f"pinned ok {name}: {actual}")


def extract_inputs() -> None:
    if not SRC.exists():
        log(f"extracting {SRC.name}")
        import tarfile

        with tarfile.open(BUILD_ROOT / f"r2ghidra-{R2GHIDRA_VERSION}.tar.gz") as tf:
            tf.extractall(BUILD_ROOT)
    if not GHIDRA_SRC.exists():
        log("extracting ghidra-native into subprojects")
        import tarfile

        tmp = BUILD_ROOT / "_ghidra_extract"
        safe_remove(tmp)
        with tarfile.open(BUILD_ROOT / f"ghidra-native-{GHIDRA_NATIVE_REV[:7]}.tar.gz") as tf:
            tf.extractall(tmp)
        root = next(p for p in tmp.iterdir() if p.is_dir())
        assert root.resolve().is_relative_to(BUILD_ROOT.resolve()) and GHIDRA_SRC.resolve().is_relative_to(BUILD_ROOT.resolve())
        shutil.move(str(root), str(GHIDRA_SRC))
        safe_remove(tmp)
        overlay=SRC/'subprojects/packagefiles/ghidra-native'
        shutil.copytree(overlay,GHIDRA_SRC,dirs_exist_ok=True)
    if not SLEIGH_DIR.exists():
        log("extracting pinned sleigh data")
        with zipfile.ZipFile(BUILD_ROOT / f"r2ghidra_sleigh-{R2GHIDRA_VERSION}.zip") as zf:
            zf.extractall(BUILD_ROOT / "sleigh")


def _split_lines(text: str) -> list[str]:
    return text.splitlines(keepends=True)


def _apply_hunk(lines: list[str], old: list[str], new: list[str], start: int) -> tuple[list[str], bool]:
    n = len(old)
    candidates = range(max(0, start - 80), min(len(lines), start + 80) + 1)
    for i in candidates:
        if lines[i:i + n] == old:
            return lines[:i] + new + lines[i + n:], True
    if n == 0:
        i = min(max(0, start), len(lines))
        return lines[:i] + new + lines[i:], True
    idx = "\n".join(lines).find("\n".join(x.rstrip("\r\n") for x in old))
    return lines, False


def apply_patch_file(root: Path, patch: Path) -> list[str]:
    """Apply a unified diff without git/msys patch (msys patch cannot create signal pipes here)."""
    changed: list[str] = []
    raw = patch.read_text(encoding="utf-8", errors="replace")
    if raw and not raw.endswith("\n"):raw+="\n"
    if "GIT binary patch" in raw:
        raise RuntimeError(f"binary patch unsupported: {patch}")
    lines = raw.splitlines(keepends=True)
    i = 0
    while i < len(lines):
        if not lines[i].startswith("--- "):
            i += 1
            continue
        if i + 1 >= len(lines) or not lines[i + 1].startswith("+++ "):
            raise RuntimeError(f"bad patch header in {patch}")
        target = lines[i + 1][4:].strip().split("\t")[0]
        target = target[2:] if target.startswith("b/") else target
        i += 2
        hunks = []
        while i < len(lines) and not lines[i].startswith("--- "):
            m = re.match(r"@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@", lines[i])
            if not m:
                i += 1
                continue
            old_start = int(m.group(1)) - 1
            i += 1
            old: list[str] = []
            new: list[str] = []
            while i < len(lines) and not lines[i].startswith(("@@ ", "--- ")):
                ln = lines[i]
                if ln.startswith("\\"):
                    i += 1
                    continue
                if ln.startswith(" "):
                    old.append(ln[1:])
                    new.append(ln[1:])
                elif ln.startswith("-"):
                    old.append(ln[1:])
                elif ln.startswith("+"):
                    new.append(ln[1:])
                else:
                    break
                i += 1
            hunks.append((old_start, old, new))
        if not hunks:
            continue
        path = root / target
        text = path.read_text(encoding="utf-8", errors="surrogateescape") if path.exists() else ""
        doc = _split_lines(text)
        applied = 0
        for old_start, old, new in hunks:
            # Idempotence: a previously applied hunk already contains its added block.
            joined = "".join(doc)
            if old and "".join(old) not in joined:
                if "".join(new) in joined:
                    applied += 1
                    continue
                raise RuntimeError(f"patch {patch.name} does not apply to {target}")
            doc, ok = _apply_hunk(doc, old, new, old_start)
            if not ok:
                raise RuntimeError(f"hunk failed in {patch.name}: {target} @ {old_start + 1}")
            applied += 1
        if applied:
            path.write_text("".join(doc), encoding="utf-8", errors="surrogateescape")
            changed.append(target)
    return changed


def apply_ghidra_patches() -> list[str]:
    files = sorted(p for p in PATCH_DIR.glob("*.patch") if p.suffix == ".patch" and "disabled" not in p.name)
    changed = []
    for p in files:
        native_patch=Path('C:/msys64/usr/bin/patch.exe')
        if native_patch.is_file():
            cmd=[str(native_patch),'--batch','--forward','--no-backup-if-mismatch','-p1','-i',str(p)]
            result=subprocess.run(cmd,cwd=GHIDRA_SRC,capture_output=True,text=True)
            if result.returncode:
                already=subprocess.run([str(native_patch),'--dry-run','--batch','--reverse','-p1','-i',str(p)],cwd=GHIDRA_SRC,capture_output=True,text=True)
                if already.returncode:raise RuntimeError(f'upstream patch failed: {p.name}\n{result.stdout}\n{result.stderr}')
            log(f'patch {p.name}: '+result.stdout.strip().replace('\n','; '))
        else:changed.extend(apply_patch_file(GHIDRA_SRC, p))
    log(f"applied {len(files)} pinned ghidra-native patches ({len(changed)} file edits)")
    return changed


CROSS_TEMPLATE = """[binaries]
c = '{ndk}/toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe'
cpp = '{ndk}/toolchains/llvm/prebuilt/windows-x86_64/bin/clang++.exe'
ar = '{ndk}/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-ar.exe'
strip = '{ndk}/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strip.exe'
ranlib = '{ndk}/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-ranlib.exe'
pkg-config = 'C:/msys64/mingw64/bin/pkg-config.exe'

[host_machine]
system = 'android'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'

[properties]
needs_exe_wrapper = true

[built-in options]
c_args = ['--target=aarch64-linux-android26', '-O2', '-fPIC', '-DANDROID']
cpp_args = ['--target=aarch64-linux-android26', '-O2', '-fPIC', '-DANDROID', '-D__STDC_FORMAT_MACROS', '-D__STDC_CONSTANT_MACROS']
c_link_args = ['--target=aarch64-linux-android26', '-Wl,-z,max-page-size=16384', '-Wl,-z,common-page-size=16384']
cpp_link_args = ['--target=aarch64-linux-android26', '-Wl,-z,max-page-size=16384', '-Wl,-z,common-page-size=16384']
"""


def meson_cmd() -> list[str]:
    py = sys.executable
    return [py, "-m", "mesonbuild.mesonmain"]


def run(cmd, cwd=None, env=None, shell=False, logfile=None) -> subprocess.CompletedProcess:
    if logfile:
        logfile.parent.mkdir(parents=True, exist_ok=True)
        with logfile.open("wb") as fh:
            return subprocess.run(cmd, cwd=cwd, env=env, shell=shell, stdout=fh, stderr=subprocess.STDOUT)
    return subprocess.run(cmd, cwd=cwd, env=env, shell=shell)


def configure(ndk: Path, sdk: Path, force: bool) -> None:
    cross = BUILD_ROOT / "android-arm64.ini"
    cross.write_text(CROSS_TEMPLATE.format(ndk=ndk.as_posix()), encoding="utf-8")
    env = os.environ.copy()
    pkg = BUILD_ROOT / "pc"
    pkg.mkdir(exist_ok=True)
    for source_pc in (sdk/'lib/pkgconfig').glob('*.pc'):
        text=source_pc.read_text(encoding='utf8').replace('/data/data/com.termux/files/usr',sdk.as_posix())
        (pkg/source_pc.name).write_text(text,encoding='utf8')
    env["PKG_CONFIG_PATH"] = str(pkg)
    env["PKG_CONFIG_LIBDIR"] = str(pkg)
    env["PYTHONPATH"] = str(BUILD_ROOT / "pymodules") + (os.pathsep + env["PYTHONPATH"] if env.get("PYTHONPATH") else "")
    if force:
        safe_remove(BUILD)
    if not (BUILD / "build.ninja").exists():
        log("meson setup")
        r = subprocess.run(meson_cmd() + ["setup", str(BUILD), str(SRC), f"--cross-file={cross}",
                                          "--buildtype=release", "-Dprefix=/stage", "--wrap-mode=nodownload"],
                           env=env, text=True)
        if r.returncode:
            raise SystemExit("meson setup failed")


def ninja_tool() -> str:
    for cand in (r"C:/msys64/mingw64/bin/ninja.exe", "ninja"):
        if cand == "ninja" or Path(cand).exists():
            return cand
    raise SystemExit("ninja not found")


def ninja_commands(target: str) -> list[str]:
    r = subprocess.run([ninja_tool(), "-C", str(BUILD), "-t", "commands", target],
                       capture_output=True, text=True)
    if r.returncode:
        raise SystemExit(f"ninja -t commands {target} failed: {r.stderr}")
    return [ln for ln in r.stdout.splitlines() if ln.strip()]


def compile_objects(jobs: int, incremental: bool) -> None:
    entries = json.loads((BUILD / "compile_commands.json").read_text(encoding="utf-8"))
    logdir = BUILD / "manual-logs"
    logdir.mkdir(exist_ok=True)
    pending = []
    for e in entries:
        out = BUILD / e.get("output", "")
        if incremental and out.exists() and out.stat().st_mtime >= (BUILD / "build.ninja").stat().st_mtime:
            dep = Path(str(out) + ".d")
            # Header changes are part of every C++ translation unit in this small optional build.
            headers=list(GHIDRA_SRC.rglob('*.hh'))+list((SRC/'src').glob('*.h'))
            newest = max([Path(e["file"]).stat().st_mtime] + [p.stat().st_mtime for p in [dep]+headers if p.exists()])
            if out.stat().st_mtime >= newest:
                continue
        pending.append(e)
    log(f"compiling {len(pending)}/{len(entries)} objects with {jobs} jobs")

    def one(e):
        name = re.sub(r"[^A-Za-z0-9_.-]+", "_", Path(e["file"]).name)
        t0 = time.time()
        r = subprocess.run(e["command"], cwd=e["directory"], shell=True,
                           stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        (logdir / f"{name}.log").write_bytes(r.stdout or b"")
        if r.returncode:
            raise RuntimeError(f"compile failed: {e['file']} (see {logdir / (name + '.log')})")
        return e["file"], time.time() - t0

    with concurrent.futures.ThreadPoolExecutor(max_workers=jobs) as pool:
        for i, (f, dt) in enumerate(pool.map(one, pending), 1):
            if i % 10 == 0 or i == len(pending):
                log(f"  [{i}/{len(pending)}] {Path(f).name} {dt:.1f}s")


def build_archives(ndk: Path) -> None:
    ar = ndk / "toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-ar.exe"
    ninja = (BUILD / "build.ninja").read_text(encoding="utf-8")
    for lib in ("libbase.a", "libghidra.a", "libslgh.a", "liblibdecomp.a"):
        m = re.search(rf"(?m)^build subprojects/ghidra-native/{re.escape(lib)}: STATIC_LINKER(?:_RSP)? (.+)$", ninja)
        if not m:
            raise SystemExit(f"missing archive edge for {lib}")
        objs = [o for o in m.group(1).split() if o.endswith(".o")]
        out = BUILD / "subprojects" / "ghidra-native" / lib
        r = subprocess.run([str(ar), "csrDT", str(out)] + objs, cwd=BUILD,
                           capture_output=True, text=True)
        if r.returncode:
            raise SystemExit(f"archive {lib} failed: {r.stderr}")
        log(f"archived {lib} ({len(objs)} objects)")


def link_plugin() -> Path:
    cmds = ninja_commands("libcore_r2ghidra.so")
    link = cmds[-1]
    log("linking libcore_r2ghidra.so")
    r = subprocess.run(link, cwd=BUILD, shell=True, capture_output=True, text=True)
    if r.returncode:
        raise SystemExit(f"link failed:\n{r.stdout}\n{r.stderr}")
    so = BUILD / "libcore_r2ghidra.so"
    if not so.exists():
        raise SystemExit("link reported success but libcore_r2ghidra.so is missing")
    return so


def verify_elf(so: Path, ndk: Path) -> dict:
    data = so.read_bytes()
    if data[:4] != b"\x7fELF":
        raise SystemExit("not an ELF file")
    ei_class, = struct.unpack_from("<B", data, 4)
    e_machine, = struct.unpack_from("<H", data, 18)
    e_phoff, = struct.unpack_from("<Q", data, 32)
    e_phentsize, e_phnum = struct.unpack_from("<HH", data, 54)
    aligns = []
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        p_type, = struct.unpack_from("<I", data, off)
        p_align, = struct.unpack_from("<Q", data, off + 48)
        if p_type == 1:
            aligns.append(p_align)
    nm = ndk / "toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-nm.exe"
    symbols = subprocess.run([str(nm), "-D", str(so)], capture_output=True, text=True).stdout
    result = {
        "elf_class": "ELF64" if ei_class == 2 else str(ei_class),
        "machine": "AArch64" if e_machine == 183 else hex(e_machine),
        "load_aligns": [hex(a) for a in aligns],
        "radare_plugin_exported": "radare_plugin" in symbols,
        "plugin_name_exported": "r_core_plugin_r2ghidra" in symbols,
        "needed_libcxx_shared": b"libc++_shared.so" in data,
    }
    if ei_class != 2 or e_machine != 183:
        raise SystemExit(f"unexpected ELF: {result}")
    if any(a < 0x4000 for a in aligns):
        raise SystemExit(f"LOAD alignment below 16 KiB: {result['load_aligns']}")
    if not result["radare_plugin_exported"]:
        raise SystemExit("radare_plugin loader symbol missing")
    return result


def stage(so: Path, ndk: Path) -> dict:
    (DIST / "lib").mkdir(parents=True, exist_ok=True)
    data_out = DIST / "share" / "r2ghidra"
    safe_remove(data_out)
    data_out.mkdir(parents=True)
    if so.resolve() != (DIST / "lib" / "core_ghidra.so").resolve():
        shutil.copy2(so, DIST / "lib" / "core_ghidra.so")
    for p in SLEIGH_DIR.iterdir():
        if p.is_file():
            shutil.copy2(p, data_out / p.name)
    files = [p for p in data_out.iterdir() if p.is_file()]
    elf = verify_elf(DIST / "lib" / "core_ghidra.so", ndk)
    manifest = {
        "r2ghidra": R2GHIDRA_VERSION,
        "ghidra_native": GHIDRA_NATIVE_REV,
        "radare2": "6.2.4",
        "r2_abi_version": 152,
        "android_abi": "arm64-v8a",
        "android_api": 26,
        "ndk": str(ndk),
        "page_size": 16384,
        "plugin": {
            "path": "lib/core_ghidra.so",
            "sha256": sha256(DIST / "lib" / "core_ghidra.so"),
            "size": (DIST / "lib" / "core_ghidra.so").stat().st_size,
        },
        "sleigh": {
            "path": "share/r2ghidra",
            "files": len(files),
            "bytes": sum(p.stat().st_size for p in files),
            "sla": len(list(data_out.glob("*.sla"))),
            "ldefs": len(list(data_out.glob("*.ldefs"))),
        },
        "verification": elf,
        "runtime_dependency": "libc++_shared.so (already shipped by host APK), r2 6.2.4 libr_*.so",
        "standalone_sleigh_so_required": False,
    }
    (DIST / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    return manifest


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--ndk", type=Path, default=Path("C:/Android/ndk-r26d"))
    ap.add_argument("--r2-sdk", type=Path,
                    default=HERE.parent.parent / ".artifacts" / "reverse-suite-20261103" / "sdk" / "radare2")
    ap.add_argument("--jobs", type=int, default=2)
    ap.add_argument("--force-configure", action="store_true")
    ap.add_argument("--incremental", action="store_true")
    ap.add_argument("--fetch", action="store_true", help="download missing pinned archives")
    ap.add_argument("--driver", choices=("manual", "ninja"), default="manual")
    ap.add_argument("--verify-only", action="store_true")
    ap.add_argument("--reset-source",action="store_true",help="archive the partial source before applying all pinned upstream patches")
    args = ap.parse_args()

    so = DIST / "lib" / "core_ghidra.so"
    if args.verify_only:
        if not so.exists():
            raise SystemExit(f"missing {so}")
        log(json.dumps(stage(so, args.ndk), indent=2))
        return 0

    ndk = args.ndk.resolve()
    sdk = args.r2_sdk.resolve()
    compiler = ndk / "toolchains/llvm/prebuilt/windows-x86_64/bin/clang++.exe"
    if not compiler.exists():
        raise SystemExit(f"NDK compiler missing: {compiler}")
    if not (sdk / "lib/pkgconfig").exists():
        raise SystemExit(f"radare2 SDK pkgconfig missing: {sdk / 'lib/pkgconfig'}")

    verify_inputs(args.fetch)
    if args.reset_source and GHIDRA_SRC.exists():
        saved=GHIDRA_SRC.with_name('ghidra-native-partial-'+uuid.uuid4().hex[:8])
        assert GHIDRA_SRC.resolve().is_relative_to(BUILD_ROOT.resolve()) and saved.resolve().is_relative_to(BUILD_ROOT.resolve())
        GHIDRA_SRC.rename(saved)
    extract_inputs()
    apply_ghidra_patches()
    configure(ndk, sdk, args.force_configure)

    if args.driver == "ninja":
        env = os.environ.copy()
        env["PKG_CONFIG_PATH"] = str(sdk / "lib" / "pkgconfig")
        log("ninja -C build -j%d" % args.jobs)
        r = subprocess.run([ninja_tool(), "-C", str(BUILD), f"-j{args.jobs}"], env=env)
        if r.returncode:
            raise SystemExit("ninja build failed")
        so_built = BUILD / "libcore_r2ghidra.so"
    else:
        compile_objects(args.jobs, args.incremental)
        build_archives(ndk)
        so_built = link_plugin()

    manifest = stage(so_built, ndk)
    log(f"OK {DIST / 'lib' / 'core_ghidra.so'} {manifest['plugin']['sha256']}")
    log(f"   {manifest['sleigh']['files']} sleigh files, {manifest['sleigh']['bytes']} bytes")
    return 0


if __name__ == "__main__":
    sys.exit(main())
