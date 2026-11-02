#!/usr/bin/env python3
"""Reject packaged Android/XML Pull API definitions, not ordinary references to them."""
from __future__ import annotations
import argparse
import json
import struct
import sys
import zipfile
from pathlib import Path

FORBIDDEN = ("Landroid/content/res/", "Landroid/util/AttributeSet;", "Lorg/xmlpull/v1/")
JAR_FORBIDDEN = ("android/content/res/", "android/util/AttributeSet.class", "org/xmlpull/v1/")

def dex_classes(data: bytes):
    if len(data) < 112 or not data.startswith(b"dex\n") or data[7] != 0:
        raise ValueError("Unsupported or truncated DEX header")
    def u32(offset):
        if offset < 0 or offset + 4 > len(data): raise ValueError("DEX integer outside file")
        return struct.unpack_from("<I", data, offset)[0]
    if u32(40) != 0x12345678: raise ValueError("Unsupported DEX byte order")
    strings, strings_offset = u32(56), u32(60)
    types, types_offset = u32(64), u32(68)
    classes, classes_offset = u32(96), u32(100)
    for count, offset, size in [(strings, strings_offset, 4), (types, types_offset, 4), (classes, classes_offset, 32)]:
        if offset > len(data) or count > (len(data) - offset) // size: raise ValueError("DEX table outside file")
    cache = {}
    def descriptor(type_index):
        if type_index >= types: raise ValueError("Invalid DEX type index")
        string_index = u32(types_offset + type_index * 4)
        if string_index >= strings: raise ValueError("Invalid DEX string index")
        if string_index not in cache:
            offset = u32(strings_offset + string_index * 4)
            for _ in range(5):
                if offset >= len(data): raise ValueError("Truncated DEX string length")
                value = data[offset]; offset += 1
                if value < 128: break
            else: raise ValueError("Invalid DEX string length")
            end = data.find(b"\0", offset)
            if end < 0: raise ValueError("Unterminated DEX descriptor")
            cache[string_index] = data[offset:end].decode("utf-8", errors="replace")
        return cache[string_index]
    for index in range(classes):
        yield descriptor(u32(classes_offset + index * 32))

def inspect(path: Path):
    definitions = 0; dex_count = 0; collisions = []
    with zipfile.ZipFile(path) as archive:
        for item in archive.infolist():
            if item.filename.endswith(".class"):
                definitions += 1
                if item.filename.startswith(JAR_FORBIDDEN): collisions.append({"entry": item.filename, "class": item.filename[:-6].replace("/", ".")})
            elif item.filename.startswith("classes") and item.filename.endswith(".dex") and "/" not in item.filename:
                if item.file_size > 256 * 1024 * 1024: raise ValueError("DEX entry exceeds 256 MiB")
                dex_count += 1
                for name in dex_classes(archive.read(item)):
                    definitions += 1
                    if name.startswith(FORBIDDEN): collisions.append({"entry": item.filename, "class": name})
    if definitions == 0: raise ValueError("Archive has no program class definitions")
    return {"path": str(path.resolve()), "passed": not collisions, "dexCount": dex_count, "classDefinitions": definitions, "platformDefinitions": collisions}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("path", type=Path); parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    try: report = inspect(args.path)
    except (ValueError, OSError, zipfile.BadZipFile, RuntimeError) as error:
        print("PLATFORM_API_CHECK_ERROR:", error, file=sys.stderr); return 1
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False))
    return 0 if report["passed"] else 1

if __name__ == "__main__": raise SystemExit(main())
