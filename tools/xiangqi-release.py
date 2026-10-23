"""Prepare and verify the exact Xiangqi source snapshot bundled in an APK.

project.json is the version source of truth. No timestamps or Git cleanliness
assumptions: an uncommitted file is part of the release just like a tracked file.
"""
import argparse
import hashlib
import json
from pathlib import Path
import sys
import zipfile

REPO = Path(__file__).resolve().parent.parent
# 象棋工程源码现在放在仓库根的 samples/ 下：它不再随宿主 APK 打包
# （宿主带的是「象棋引擎插件」plugins/xiangqi-plugin，插件构建时从这里拷贝）。
RELATIVE = Path("samples/象棋自动走棋")
PROJECT = REPO / RELATIVE
# 插件把工程整棵拷到 assets/scripts/ 下（宿主加载插件时会再复制到 cache/plugin-scripts）。
# 发布校验因此打的是插件 APK，不再是宿主 APK。
APK_PREFIX = "assets/scripts/"
GENERATED = {"release-manifest.json", "src/app/version.js"}
# 插件 APK 在工程文件之外还有自己的一层入口脚本（宿主加载插件时先跑 index.js）。
PLUGIN_SCRIPTS = {"index.js", "engine.js", "library.js", "uci-client.js", "uci-parser.js"}
IGNORED_PARTS = {".artifacts", ".floaty-panel", "__pycache__", "node_modules"}


def digest(data):
    return hashlib.sha256(data).hexdigest()


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")


def source_files(project):
    result = {}
    for path in sorted(project.rglob("*")):
        relative = path.relative_to(project)
        # Android's default asset packaging ignores dot paths and editor backups.
        if (not path.is_file() or set(relative.parts) & IGNORED_PARTS or
                any(part.startswith(".") for part in relative.parts) or
                path.name.endswith(("~", ".scc")) or path.name.lower() in {"thumbs.db", "picasa.ini"}):
            continue
        name = relative.as_posix()
        if name not in GENERATED:
            result[name] = digest(path.read_bytes())
    return result


def snapshot(project):
    config = json.loads((project / "project.json").read_text(encoding="utf-8-sig"))
    files = source_files(project)
    return {"schema": 1, "version": config["versionName"], "versionCode": config["versionCode"],
            "sourceSha256": digest(canonical(files)), "files": files}


def version_source(manifest):
    return ('// @engine quickjs\n"use strict";\n'
            '// Generated from project.json and release-manifest.json; do not edit.\n'
            'module.exports = { VERSION: ' + json.dumps(manifest["version"]) +
            ', SOURCE_SHA256: ' + json.dumps(manifest["sourceSha256"]) + ' };\n').encode("utf-8")


def write_changed(path, data):
    if not path.is_file() or path.read_bytes() != data:
        path.parent.mkdir(parents=True, exist_ok=True)
        temporary = path.with_name("." + path.name + ".tmp")
        temporary.write_bytes(data)
        temporary.replace(path)


def prepare(project=PROJECT):
    value = snapshot(project)
    write_changed(project / "release-manifest.json", canonical(value) + b"\n")
    write_changed(project / "src/app/version.js", version_source(value))
    return value


def verify_source(project=PROJECT):
    actual = snapshot(project)
    recorded = json.loads((project / "release-manifest.json").read_bytes())
    if actual != recorded:
        raise ValueError("Xiangqi source changed after manifest generation; rebuild before publishing")
    if (project / "src/app/version.js").read_bytes() != version_source(recorded):
        raise ValueError("Xiangqi version.js differs from project.json / source fingerprint")
    return actual


def verify_apk(apk, project=PROJECT, plugin=False):
    source = verify_source(project)
    # Android APK writers can store UTF-8 path bytes without ZIP's UTF-8 flag.
    with zipfile.ZipFile(apk, metadata_encoding="utf-8") as package:
        members = [i.filename for i in package.infolist() if i.filename.startswith(APK_PREFIX) and not i.is_dir()]
        if len(members) != len(set(members)):
            raise ValueError("APK contains duplicate Xiangqi entries")
        names = {name[len(APK_PREFIX):] for name in members}
        expected = set(source["files"]) | GENERATED | (PLUGIN_SCRIPTS if plugin else set())
        if names != expected:
            raise ValueError("APK source file list differs: missing=" + str(sorted(expected - names)) +
                             " extra=" + str(sorted(names - expected)))
        bundled = json.loads(package.read(APK_PREFIX + "release-manifest.json"))
        if bundled != source:
            raise ValueError("APK contains an old Xiangqi manifest; rebuild instead of republishing")
        wrong = [name for name, sha in source["files"].items()
                 if digest(package.read(APK_PREFIX + name)) != sha]
        if wrong:
            raise ValueError("APK contains modified/stale Xiangqi files: " + str(wrong))
        if package.read(APK_PREFIX + "src/app/version.js") != version_source(source):
            raise ValueError("APK version.js does not match its manifest")
    return source


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["prepare", "verify-source", "verify-apk"])
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--plugin", action="store_true",
                       help="verify the chess plugin APK, which also ships the entry scripts")
    args = parser.parse_args()
    if args.command == "prepare":
        value = prepare()
    elif args.command == "verify-source":
        value = verify_source()
    else:
        if not args.apk:
            parser.error("verify-apk requires --apk")
        value = verify_apk(args.apk, plugin=args.plugin)
    print("XIANGQI RELEASE OK version={} files={} source={}".format(
        value["version"], len(value["files"]), value["sourceSha256"]))


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        print("XIANGQI RELEASE FAILED: " + str(error), file=sys.stderr)
        sys.exit(1)
