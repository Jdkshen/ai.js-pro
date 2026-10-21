#!/usr/bin/env python3
"""Compile bundled sample projects' Android resources into standalone project-res.apk files.

This is the cross-platform companion of tools/build-project-res.ps1. It scans a bundled
samples tree for project.json files with an ``androidResources`` object, builds each one
with aapt2, and writes:

    <output>/sample/<relative-project>/build/project-res.apk
    <output>/sample/<relative-project>/build/project-res.json

The source samples are never modified: only a temporary copy of the manifest is rewritten
to the host package name. aapt2 always runs in an ASCII temporary directory so Windows
paths containing Chinese characters do not get mangled when passed as native arguments.

The sidecar records the SHA-256 of the uncompressed ``resources.arsc`` entry of --host-res
(AGP's resources-variant.ap_ / linked resources APK) so CompiledProjectResources can decide
whether the resource table still matches the installed host APK, without hashing the whole
host APK.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shlex
import shutil
import subprocess
import sys
import tempfile
import uuid
import zipfile
from datetime import datetime
from pathlib import Path
from typing import Iterable, List, Optional, Tuple

PACKAGE_NAME = "com.jdkshen.aijspro"
PACKAGE_ID = "0x7e"
MIN_SDK = "21"
TARGET_SDK = "28"
SIDECAR_NAME = "project-res.json"
RESOURCES_ENTRY = "resources.arsc"

_MANIFEST_TAG_RE = re.compile(r"<manifest\b[^>]*>", re.DOTALL)
_PACKAGE_ATTR_RE = re.compile(r"(\bpackage\s*=\s*)([\"'])(.*?)\2", re.DOTALL)
_AAPT2_PACKAGE_RE = re.compile(r"Package name=(\S+)\s+id=")


class BuildError(RuntimeError):
    pass


def _log(message: str) -> None:
    print(message, flush=True)


def _is_ascii_path(path: Path) -> bool:
    return all(ord(ch) < 128 for ch in str(path))


def _can_write_dir(path: Path) -> bool:
    try:
        path.mkdir(parents=True, exist_ok=True)
        probe = path / (".aijspro-write-" + str(os.getpid()))
        probe.write_bytes(b"")
        probe.unlink()
        return True
    except OSError:
        return False


def _windows_short_path(path: Path) -> Optional[Path]:
    """Return the 8.3 path for *path* when Windows has one (may be non-ASCII)."""
    if os.name != "nt":
        return None
    try:
        import ctypes

        buffer = ctypes.create_unicode_buffer(32768)
        length = ctypes.windll.kernel32.GetShortPathNameW(str(path), buffer, len(buffer))
        if length:
            return Path(buffer.value)
    except Exception:
        pass
    return None


def _ascii_temp_root() -> Path:
    """Find a writable directory whose path is pure ASCII.

    aapt2 on Windows is known to fail when a native argument contains Chinese characters.
    %TEMP% is usually ASCII, but it is not guaranteed on machines with a Chinese username.
    """
    candidates: List[Path] = []
    for name in ("TEMP", "TMP"):
        value = os.environ.get(name)
        if value:
            candidates.append(Path(value))
    candidates.append(Path(tempfile.gettempdir()))
    if os.name == "nt":
        candidates.append(Path(os.environ.get("SystemRoot", r"C:\Windows")) / "Temp")

    seen = set()
    for candidate in candidates:
        try:
            candidate = candidate.resolve(strict=False)
        except OSError:
            continue
        key = str(candidate).lower() if os.name == "nt" else str(candidate)
        if key in seen:
            continue
        seen.add(key)
        if candidate.is_dir() and _is_ascii_path(candidate) and _can_write_dir(candidate):
            return candidate

    if os.name == "nt":
        short = _windows_short_path(Path(tempfile.gettempdir()))
        if short is not None and _is_ascii_path(short) and _can_write_dir(short):
            return short

    raise BuildError(
        "找不到可写的 ASCII 临时目录；aapt2 在 Windows 中文路径下会乱码。"
        "请把 TEMP/TMP 指向纯 ASCII 路径后重试。"
    )


def _format_command(command: Iterable[object]) -> str:
    parts = [str(part) for part in command]
    if os.name == "nt":
        return subprocess.list2cmdline(parts)
    return shlex.join(parts)


def _run(command: List[object], cwd: Optional[Path] = None) -> str:
    process = subprocess.run(
        [str(part) for part in command],
        cwd=str(cwd) if cwd is not None else None,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    if process.returncode != 0:
        details = (process.stdout or "") + (process.stderr or "")
        raise BuildError(
            "命令失败（exit {}）：{}\n{}".format(
                process.returncode, _format_command(command), details.strip()
            )
        )
    return process.stdout


def _validate_file(path: Path, label: str) -> Path:
    resolved = path.expanduser().resolve()
    if not resolved.is_file():
        raise BuildError("{} 不存在或不是文件：{}".format(label, resolved))
    return resolved


def _resources_arsc_sha256(apk_path: Path) -> str:
    """SHA-256 of the uncompressed resources.arsc ZIP entry (never the whole APK)."""
    try:
        with zipfile.ZipFile(apk_path) as archive:
            try:
                entry = archive.getinfo(RESOURCES_ENTRY)
            except KeyError:
                raise BuildError("APK 里没有 {}：{}".format(RESOURCES_ENTRY, apk_path))
            digest = hashlib.sha256()
            with archive.open(entry) as stream:
                while True:
                    chunk = stream.read(1024 * 1024)
                    if not chunk:
                        break
                    digest.update(chunk)
            return digest.hexdigest()
    except zipfile.BadZipFile as error:
        raise BuildError("不是有效的 ZIP/APK：{}（{}）".format(apk_path, error))


def _aapt2_input_path(path: Path, temp_root: Path, label: str) -> Path:
    """Keep aapt2 arguments ASCII on Windows, using 8.3 names or a temp copy."""
    if os.name != "nt" or _is_ascii_path(path):
        return path
    short = _windows_short_path(path)
    if short is not None and _is_ascii_path(short):
        return short
    target = temp_root / "inputs" / (label + path.suffix)
    target.parent.mkdir(parents=True, exist_ok=True)
    if not target.exists():
        _log("==> {} 路径含中文，复制到 ASCII 临时目录：{}".format(label, target))
        shutil.copyfile(path, target)
    return target


def _read_json(path: Path) -> dict:
    try:
        return json.loads(path.read_text(encoding="utf-8-sig"))
    except (OSError, ValueError) as error:
        raise BuildError("读不了 {}：{}".format(path, error))


def _rewrite_manifest_package(manifest_text: str, package_name: str) -> str:
    """Rewrite only the package attribute of the root <manifest> element."""
    match = _MANIFEST_TAG_RE.search(manifest_text)
    if match is None:
        raise BuildError("AndroidManifest.xml 里找不到 <manifest> 根节点")
    tag = match.group(0)
    if tag.rstrip().endswith("/>"):
        raise BuildError("AndroidManifest.xml 的 <manifest> 不能是自闭合标签")
    if _PACKAGE_ATTR_RE.search(tag):
        new_tag = _PACKAGE_ATTR_RE.sub(
            lambda found: found.group(1) + '"' + package_name + '"', tag, count=1
        )
    else:
        close = tag.rfind(">")
        new_tag = tag[:close].rstrip() + ' package="' + package_name + '">'
    return manifest_text[: match.start()] + new_tag + manifest_text[match.end() :]


def _discover_projects(samples_root: Path) -> List[Tuple[Path, dict]]:
    """Find project.json with androidResources, skipping node_modules entirely."""
    projects: List[Tuple[Path, dict]] = []
    for root, dirnames, filenames in os.walk(samples_root):
        dirnames[:] = sorted(
            name for name in dirnames if name.lower() not in ("node_modules", ".git")
        )
        if "project.json" not in filenames:
            continue
        project_dir = Path(root)
        config = _read_json(project_dir / "project.json")
        android_resources = config.get("androidResources")
        if android_resources is None:
            continue
        if not isinstance(android_resources, dict):
            raise BuildError(
                "{} 的 androidResources 不是对象：{}".format(
                    project_dir / "project.json", android_resources
                )
            )
        projects.append((project_dir, android_resources))
    projects.sort(key=lambda item: item[0].as_posix())
    return projects


def _detect_resource_package(aapt2: Path, apk_path: Path) -> str:
    dump = _run([aapt2, "dump", "resources", apk_path])
    match = _AAPT2_PACKAGE_RE.search(dump)
    if match is None:
        raise BuildError("aapt2 dump 里找不到资源包名：{}".format(apk_path))
    return match.group(1)


def _copy_atomic(source: Path, target: Path) -> None:
    temporary = target.with_name(target.name + ".tmp")
    shutil.copyfile(source, temporary)
    os.replace(temporary, target)


def _write_text_atomic(target: Path, text: str) -> None:
    temporary = target.with_name(target.name + ".tmp")
    with temporary.open("w", encoding="utf-8", newline="\n") as stream:
        stream.write(text)
    os.replace(temporary, target)


def _build_project(
    project_dir: Path,
    android_resources: dict,
    samples_root: Path,
    output_root: Path,
    work_root: Path,
    case_index: int,
    aapt2: Path,
    android_jar: Path,
    host_res: Path,
    host_res_label: Path,
    host_resources_sha256: str,
) -> Path:
    relative = project_dir.relative_to(samples_root)
    res_dir_name = android_resources.get("resDir") or "res"
    manifest_name = android_resources.get("manifest") or "AndroidManifest.xml"
    res_dir = (project_dir / res_dir_name).resolve()
    manifest_file = (project_dir / manifest_name).resolve()
    if not res_dir.is_dir():
        raise BuildError("找不到资源目录：{}".format(res_dir))
    if not manifest_file.is_file():
        raise BuildError("找不到清单文件：{}".format(manifest_file))

    project_name = relative.as_posix()
    _log("==> [{}] {}".format(case_index, project_name))

    work = work_root / ("project-" + str(case_index))
    work.mkdir(parents=True, exist_ok=True)
    shutil.copytree(res_dir, work / "res")
    manifest_text = manifest_file.read_text(encoding="utf-8-sig")
    with (work / "AndroidManifest.xml").open("w", encoding="utf-8", newline="\n") as stream:
        stream.write(_rewrite_manifest_package(manifest_text, PACKAGE_NAME))

    compiled_zip = work / "compiled.zip"
    linked_apk = work / "project-res.apk"
    _run([aapt2, "compile", "--dir", work / "res", "-o", compiled_zip])
    _run(
        [
            aapt2,
            "link",
            "-o",
            linked_apk,
            "-I",
            android_jar,
            "-I",
            host_res,
            "--manifest",
            work / "AndroidManifest.xml",
            "--package-id",
            PACKAGE_ID,
            "--allow-reserved-package-id",
            "--min-sdk-version",
            MIN_SDK,
            "--target-sdk-version",
            TARGET_SDK,
            compiled_zip,
        ]
    )
    if not linked_apk.is_file():
        raise BuildError("aapt2 link 没有生成 APK：{}".format(linked_apk))

    resource_package_name = _detect_resource_package(aapt2, linked_apk)
    output_dir = output_root / "sample" / relative / "build"
    output_dir.mkdir(parents=True, exist_ok=True)
    output_apk = output_dir / "project-res.apk"
    _copy_atomic(linked_apk, output_apk)

    sidecar = {
        "packageName": PACKAGE_NAME,
        "resourcePackageName": resource_package_name,
        "hostRes": str(host_res_label),
        "appResourcesSha256": host_resources_sha256,
        "generatedAt": datetime.now().astimezone().isoformat(timespec="seconds"),
    }
    _write_text_atomic(
        output_dir / SIDECAR_NAME,
        json.dumps(sidecar, ensure_ascii=False, indent=2) + "\n",
    )
    _log("    包名 {} / 资源包名 {}".format(PACKAGE_NAME, resource_package_name))
    _log("    {}".format(output_apk))
    return output_apk


def _parse_args(argv: Optional[List[str]]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "把 --samples 下带 androidResources 的内置示例工程编译成 "
            "output/sample/<相对工程>/build/project-res.apk（含 sidecar）。"
        )
    )
    parser.add_argument("--aapt2", required=True, type=Path, help="aapt2 可执行文件")
    parser.add_argument("--android-jar", required=True, type=Path, help="platform android.jar（aapt2 -I）")
    parser.add_argument(
        "--host-res",
        required=True,
        type=Path,
        help="AGP 的 resources-variant.ap_（或 linked-resources-binary-format-*.ap_，aapt2 -I 并提供 resources.arsc 摘要）",
    )
    parser.add_argument("--samples", required=True, type=Path, help="内置示例根目录")
    parser.add_argument("--output", required=True, type=Path, help="输出根目录（生成 output/sample/...）")
    return parser.parse_args(argv)


def main(argv: Optional[List[str]] = None) -> int:
    args = _parse_args(argv)
    try:
        aapt2 = _validate_file(args.aapt2, "--aapt2")
        android_jar = _validate_file(args.android_jar, "--android-jar")
        host_res = _validate_file(args.host_res, "--host-res")
        samples_root = args.samples.expanduser().resolve()
        if not samples_root.is_dir():
            raise BuildError("--samples 不存在或不是目录：{}".format(samples_root))
        # Do not resolve the output path: it may be a junction/symlink into the build tree.
        output_root = args.output.expanduser().absolute()
        try:
            output_root.mkdir(parents=True, exist_ok=True)
        except OSError as error:
            raise BuildError("创建输出目录失败：{}（{}）".format(output_root, error))

        projects = _discover_projects(samples_root)
        _log("aapt2      : {}".format(aapt2))
        _log("android.jar: {}".format(android_jar))
        _log("host-res   : {}".format(host_res))
        _log("samples    : {}".format(samples_root))
        _log("output     : {}".format(output_root))
        _log("待编译工程 : {} 个".format(len(projects)))
        if not projects:
            _log("没有带 androidResources 的示例工程，跳过")
            return 0

        host_resources_sha256 = _resources_arsc_sha256(host_res)
        _log("宿主 resources.arsc SHA256：{}".format(host_resources_sha256))

        # Do not use tempfile.mkdtemp here: it creates the directory with mode 0700, which
        # makes nested creation fail under some Windows sandboxes. A default-mode unique
        # directory is enough for transient aapt2 outputs.
        temp_root = _ascii_temp_root() / ("aijspro-bundled-" + uuid.uuid4().hex[:8])
        temp_root.mkdir()
        try:
            safe_android_jar = _aapt2_input_path(android_jar, temp_root, "android")
            safe_host_res = _aapt2_input_path(host_res, temp_root, "host-res")
            outputs = []
            for index, (project_dir, android_resources) in enumerate(projects, start=1):
                outputs.append(
                    _build_project(
                        project_dir=project_dir,
                        android_resources=android_resources,
                        samples_root=samples_root,
                        output_root=output_root,
                        work_root=temp_root,
                        case_index=index,
                        aapt2=aapt2,
                        android_jar=safe_android_jar,
                        host_res=safe_host_res,
                        host_res_label=host_res,
                        host_resources_sha256=host_resources_sha256,
                    )
                )
        finally:
            # Resolve and check the entire deletion target before recursive cleanup.
            cleanup = temp_root.resolve()
            parent = _ascii_temp_root().resolve()
            if cleanup.parent != parent or not cleanup.name.startswith("aijspro-bundled-"):
                raise BuildError("Refusing to clean an unexpected temporary path: " + str(cleanup))
            shutil.rmtree(cleanup)

        _log("完成：{} 个资源包".format(len(outputs)))
        return 0
    except BuildError as error:
        print("error: {}".format(error), file=sys.stderr)
        return 1
    except OSError as error:
        print("error: {}".format(error), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
