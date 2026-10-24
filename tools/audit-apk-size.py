#!/usr/bin/env python3
"""Audit APK ZIP entry sizes by group, compare with a baseline, and enforce budgets.

This tool never modifies the APK. It reports both the *raw* (uncompressed) size and
the *compressed contribution* of every ZIP entry. The APK's signing block, local
headers, central directory and EOF records are not ZIP entries; they are reported
separately as container overhead so "compressed" is not mistaken for the final file
size.

It also counts ZIP entry names that are valid UTF-8. Android APKs may contain
non-ASCII (for example Chinese) asset names, and some tooling omits the general
purpose UTF-8 flag. Reading the raw central-directory name bytes lets us report
valid UTF-8 names independently from that flag.

Usage:
    python tools/audit-apk-size.py app.apk
    python tools/audit-apk-size.py app.apk --baseline old.apk --max-growth-mib 2
    python tools/audit-apk-size.py app.apk --max-total-mib 150 --json audit.json
"""

from __future__ import annotations

import argparse
import json
import os
import re
import struct
import sys
import zipfile
from collections import OrderedDict

MIB = 1024 * 1024
EOCD_SIGNATURE = b"PK\x05\x06"
ZIP64_EOCD_SIGNATURE = b"PK\x06\x06"
ZIP64_LOCATOR_SIGNATURE = b"PK\x06\x07"
CENTRAL_SIGNATURE = b"PK\x01\x02"
UTF8_FLAG = 0x800


def classify_entry(name: str) -> str:
    """Return a stable, human-sized group name for an entry."""
    if name in ("AndroidManifest.xml", "resources.arsc"):
        return "manifest/resources"
    if re.fullmatch(r"classes(?:\d+)?\.dex", name):
        return "dex"
    if name.startswith("lib/"):
        parts = name.split("/")
        return "lib/" + (parts[1] if len(parts) > 1 else "?")
    if name.startswith("assets/"):
        parts = name.split("/")
        if len(parts) < 2 or not parts[1]:
            return "assets"
        # A file directly under assets/ is its own small group; a directory aggregates.
        return "assets/" + parts[1]
    if name.startswith("res/"):
        return "res"
    if name.startswith("META-INF/"):
        return "META-INF"
    if name.startswith("kotlin/"):
        return "kotlin metadata"
    if name.startswith("okhttp3/"):
        return "okhttp3 metadata"
    return "other/" + (name.split("/", 1)[0] if name else "?")


def _find_eocd(data: bytes) -> tuple[int, int, int, int | None]:
    """Return (entries, cd_size, cd_offset, zip64_eocd_offset)."""
    pos = data.rfind(EOCD_SIGNATURE)
    while pos >= 0:
        if pos + 22 <= len(data):
            comment_len = struct.unpack_from("<H", data, pos + 20)[0]
            if pos + 22 + comment_len <= len(data):
                break
        pos = data.rfind(EOCD_SIGNATURE, 0, pos)
    if pos < 0:
        raise ValueError("EOCD not found")

    _disk, _cd_disk, _entries_disk, entries_total, cd_size, cd_offset, _comment = struct.unpack_from(
        "<HHHHIIH", data, pos + 4)
    if entries_total != 0xFFFF and cd_size != 0xFFFFFFFF and cd_offset != 0xFFFFFFFF:
        return entries_total, cd_size, cd_offset, None

    # ZIP64: the locator sits before the EOCD (not necessarily adjacent because of
    # the archive comment, so search backwards). The ZIP64 EOCD itself may be
    # outside this tail buffer; return its absolute offset for the caller to read.
    locator = data.rfind(ZIP64_LOCATOR_SIGNATURE, max(0, pos - 65557), pos)
    if locator < 0 or locator + 20 > len(data):
        raise ValueError("ZIP64 locator not found")
    zip64_offset = struct.unpack_from("<Q", data, locator + 8)[0]
    return entries_total, cd_size, cd_offset, zip64_offset


def read_central_directory_entries(apk_path: os.PathLike[str] | str) -> list[dict]:
    """Read entries directly from the central directory.

    Returns a list in archive order with raw name bytes, flags and sizes:
    {"raw_name": bytes, "flags": int, "raw_bytes": int, "compressed_bytes": int}.

    This avoids zipfile's name decoding, which raises UnicodeDecodeError when a
    name has the UTF-8 flag but invalid bytes. Raises ValueError/struct.error if the
    archive cannot be parsed; callers fall back to zipfile in that case.
    """
    file_size = os.path.getsize(apk_path)
    tail_size = min(file_size, 65557 + 56)
    with open(apk_path, "rb") as stream:
        stream.seek(file_size - tail_size)
        tail = stream.read()
        entries_total, cd_size, cd_offset, zip64_offset = _find_eocd(tail)
        if zip64_offset is not None:
            if zip64_offset + 56 > file_size:
                raise ValueError("ZIP64 EOCD outside archive")
            stream.seek(zip64_offset)
            zip64 = stream.read(56)
            if zip64[:4] != ZIP64_EOCD_SIGNATURE:
                raise ValueError("ZIP64 EOCD not found")
            entries_total = struct.unpack_from("<Q", zip64, 32)[0]
            cd_size = struct.unpack_from("<Q", zip64, 40)[0]
            cd_offset = struct.unpack_from("<Q", zip64, 48)[0]
        if cd_offset + cd_size > file_size:
            raise ValueError("central directory outside archive")
        stream.seek(cd_offset)
        central = stream.read(cd_size)

    result = []
    offset = 0
    for _ in range(entries_total):
        if offset + 46 > len(central) or central[offset:offset + 4] != CENTRAL_SIGNATURE:
            raise ValueError("invalid central directory record")
        fields = struct.unpack_from("<4s6H3I5H2I", central, offset)
        flags = fields[3]
        compressed_bytes = fields[8]
        raw_bytes = fields[9]
        name_len = fields[10]
        extra_len = fields[11]
        comment_len = fields[12]
        name_start = offset + 46
        name_end = name_start + name_len
        if name_end > len(central):
            raise ValueError("truncated central directory name")
        if compressed_bytes == 0xFFFFFFFF or raw_bytes == 0xFFFFFFFF:
            # ZIP64 entry sizes live in the extra field; APKs are below 4 GiB,
            # so fall back to zipfile rather than half-parsing that case.
            raise ValueError("ZIP64 entry size not supported by raw scanner")
        result.append({
            "raw_name": central[name_start:name_end],
            "flags": flags,
            "raw_bytes": raw_bytes,
            "compressed_bytes": compressed_bytes,
        })
        offset = name_end + extra_len + comment_len
    return result


def _fallback_raw_name(info: zipfile.ZipInfo) -> bytes:
    """Best-effort raw name when central-directory parsing is unavailable."""
    if info.flag_bits & UTF8_FLAG:
        return info.filename.encode("utf-8", "surrogateescape")
    try:
        return info.filename.encode("cp437")
    except UnicodeEncodeError:
        return info.filename.encode("utf-8", "surrogateescape")


def _display_name(raw: bytes) -> str:
    try:
        return raw.decode("utf-8")
    except UnicodeDecodeError:
        return raw.decode("utf-8", "backslashreplace")


def analyze_apk(apk_path: os.PathLike[str] | str, top: int = 15) -> dict:
    """Analyze one APK without modifying it."""
    path = os.fspath(apk_path)
    try:
        raw_entries = read_central_directory_entries(path)
    except (OSError, ValueError, struct.error):
        raw_entries = None
    if raw_entries is None:
        with zipfile.ZipFile(path, "r") as archive:
            infos = archive.infolist()
        raw_entries = [{
            "raw_name": _fallback_raw_name(info),
            "flags": info.flag_bits,
            "raw_bytes": info.file_size,
            "compressed_bytes": info.compress_size,
        } for info in infos]

    groups: "OrderedDict[str, dict]" = OrderedDict()
    entry_rows = []
    ascii_names = 0
    utf8_non_ascii = 0
    invalid_utf8 = 0
    utf8_flag = 0
    utf8_flag_non_ascii = 0
    utf8_flag_invalid = 0
    non_ascii_without_flag = 0
    invalid_samples = []

    for raw_entry in raw_entries:
        raw_name = raw_entry["raw_name"]
        flags = raw_entry["flags"]
        try:
            decoded = raw_name.decode("utf-8")
            valid_utf8 = True
        except UnicodeDecodeError:
            decoded = _display_name(raw_name)
            valid_utf8 = False
            invalid_samples.append(_display_name(raw_name))

        has_non_ascii = any(byte >= 0x80 for byte in raw_name)
        if valid_utf8 and not has_non_ascii:
            ascii_names += 1
        elif valid_utf8:
            utf8_non_ascii += 1
        else:
            invalid_utf8 += 1
        if flags & UTF8_FLAG:
            utf8_flag += 1
            if has_non_ascii:
                utf8_flag_non_ascii += 1
            if not valid_utf8:
                utf8_flag_invalid += 1
        elif has_non_ascii:
            non_ascii_without_flag += 1

        group = classify_entry(decoded)
        bucket = groups.setdefault(group, {"entries": 0, "raw_bytes": 0, "compressed_bytes": 0})
        bucket["entries"] += 1
        bucket["raw_bytes"] += raw_entry["raw_bytes"]
        bucket["compressed_bytes"] += raw_entry["compressed_bytes"]
        entry_rows.append({
            "name": decoded,
            "group": group,
            "raw_bytes": raw_entry["raw_bytes"],
            "compressed_bytes": raw_entry["compressed_bytes"],
        })

    total_raw = sum(entry["raw_bytes"] for entry in entry_rows)
    total_compressed = sum(entry["compressed_bytes"] for entry in entry_rows)
    file_size = os.path.getsize(path)
    top_entries = sorted(entry_rows, key=lambda item: (-item["compressed_bytes"], item["name"]))[:max(0, top)]

    group_list = []
    for name, bucket in groups.items():
        group_list.append({
            "group": name,
            "entries": bucket["entries"],
            "raw_bytes": bucket["raw_bytes"],
            "compressed_bytes": bucket["compressed_bytes"],
            "compressed_share": (bucket["compressed_bytes"] / total_compressed) if total_compressed else 0.0,
        })
    group_list.sort(key=lambda item: (-item["compressed_bytes"], item["group"]))

    return {
        "apk": os.path.abspath(path),
        "file_size": file_size,
        "zip": {
            "entries": len(entry_rows),
            "raw_bytes": total_raw,
            "compressed_bytes": total_compressed,
            "container_overhead_bytes": file_size - total_compressed,
            "compressed_ratio": (total_compressed / total_raw) if total_raw else 0.0,
        },
        "utf8_names": {
            "ascii": ascii_names,
            "non_ascii_valid": utf8_non_ascii,
            "invalid": invalid_utf8,
            "utf8_flag_entries": utf8_flag,
            "utf8_flag_non_ascii": utf8_flag_non_ascii,
            "utf8_flag_invalid": utf8_flag_invalid,
            "non_ascii_without_flag": non_ascii_without_flag,
            "invalid_samples": sorted(set(invalid_samples))[:10],
        },
        "groups": group_list,
        "top_entries": top_entries,
    }


def compare_with_baseline(current: dict, baseline: dict) -> dict:
    """Return total and per-group byte/share deltas (current - baseline)."""
    base_groups = {item["group"]: item for item in baseline["groups"]}
    current_groups = {item["group"]: item for item in current["groups"]}
    groups = []
    for name in sorted(set(base_groups) | set(current_groups)):
        before = base_groups.get(name, {"entries": 0, "raw_bytes": 0, "compressed_bytes": 0})
        after = current_groups.get(name, {"entries": 0, "raw_bytes": 0, "compressed_bytes": 0})
        groups.append({
            "group": name,
            "entries_before": before["entries"],
            "entries_after": after["entries"],
            "raw_bytes_delta": after["raw_bytes"] - before["raw_bytes"],
            "compressed_bytes_delta": after["compressed_bytes"] - before["compressed_bytes"],
        })
    total_before = baseline["zip"]["compressed_bytes"]
    total_after = current["zip"]["compressed_bytes"]
    return {
        "baseline_apk": baseline["apk"],
        "current_apk": current["apk"],
        "file_size_delta": current["file_size"] - baseline["file_size"],
        "raw_bytes_delta": current["zip"]["raw_bytes"] - baseline["zip"]["raw_bytes"],
        "compressed_bytes_delta": total_after - total_before,
        "compressed_percent_delta": ((total_after - total_before) / total_before * 100.0)
        if total_before else None,
        "groups": groups,
    }


def evaluate_budget(current: dict, baseline: dict | None,
                    max_total_mib: float | None = None,
                    max_growth_mib: float | None = None,
                    max_growth_percent: float | None = None) -> list[str]:
    """Return a list of human-readable budget failures (empty means pass)."""
    failures = []
    if max_total_mib is not None:
        limit = int(max_total_mib * MIB)
        if current["zip"]["compressed_bytes"] > limit:
            failures.append(
                "total compressed size %d bytes exceeds budget %d bytes (%.3f MiB > %.3f MiB)"
                % (current["zip"]["compressed_bytes"], limit,
                   current["zip"]["compressed_bytes"] / MIB, max_total_mib))
    growth_checks = max_growth_mib is not None or max_growth_percent is not None
    if growth_checks and baseline is None:
        failures.append("growth budget needs --baseline")
        return failures
    if baseline is not None:
        delta = current["zip"]["compressed_bytes"] - baseline["zip"]["compressed_bytes"]
        if max_growth_mib is not None and delta > int(max_growth_mib * MIB):
            failures.append(
                "compressed size grew %d bytes over baseline (limit %.3f MiB)"
                % (delta, max_growth_mib))
        if max_growth_percent is not None and baseline["zip"]["compressed_bytes"]:
            percent = delta / baseline["zip"]["compressed_bytes"] * 100.0
            if percent > max_growth_percent:
                failures.append(
                    "compressed size grew %.3f%% over baseline (limit %.3f%%)"
                    % (percent, max_growth_percent))
    return failures


def _mib(value: int) -> str:
    return "%.2f MiB" % (value / MIB)


def format_report(current: dict, baseline: dict | None = None,
                  comparison: dict | None = None,
                  failures: list[str] | None = None) -> str:
    """Format the audit as text suitable for a build log."""
    lines = [
        "APK: %s" % current["apk"],
        "File size: %d bytes (%s)" % (current["file_size"], _mib(current["file_size"])),
        "ZIP entries: %d" % current["zip"]["entries"],
        "Raw entry bytes: %d (%s)" % (current["zip"]["raw_bytes"], _mib(current["zip"]["raw_bytes"])),
        "Compressed contribution: %d (%s, %.2f%% of raw)"
        % (current["zip"]["compressed_bytes"], _mib(current["zip"]["compressed_bytes"]),
           current["zip"]["compressed_ratio"] * 100.0),
        "Container overhead (headers/central directory/signing): %d (%s)"
        % (current["zip"]["container_overhead_bytes"], _mib(current["zip"]["container_overhead_bytes"])),
        "",
        "UTF-8 entry names:",
        "  ASCII names: %d" % current["utf8_names"]["ascii"],
        "  Non-ASCII names valid UTF-8: %d" % current["utf8_names"]["non_ascii_valid"],
        "  Invalid UTF-8 names: %d" % current["utf8_names"]["invalid"],
        "  ZIP UTF-8 flag set: %d (non-ASCII: %d, invalid: %d)"
        % (current["utf8_names"]["utf8_flag_entries"],
           current["utf8_names"]["utf8_flag_non_ascii"],
           current["utf8_names"]["utf8_flag_invalid"]),
        "  Non-ASCII without UTF-8 flag: %d" % current["utf8_names"]["non_ascii_without_flag"],
    ]
    if current["utf8_names"]["invalid_samples"]:
        lines.append("  Invalid name samples: %s" % ", ".join(current["utf8_names"]["invalid_samples"]))
    lines.extend([
        "",
        "%-30s %8s %13s %13s %9s" % ("Group", "Entries", "Raw", "Compressed", "Share"),
    ])
    for group in current["groups"]:
        lines.append("%-30s %8d %13s %13s %8.2f%%" % (
            group["group"][:30], group["entries"], _mib(group["raw_bytes"]),
            _mib(group["compressed_bytes"]), group["compressed_share"] * 100.0))
    lines.append("")
    lines.append("Top %d entries by compressed size:" % len(current["top_entries"]))
    for entry in current["top_entries"]:
        lines.append("  %10s  %s" % (_mib(entry["compressed_bytes"]), entry["name"]))

    if baseline is not None and comparison is not None:
        lines.extend([
            "",
            "Baseline: %s" % baseline["apk"],
            "Total compressed delta: %+d bytes (%+.2f%%)"
            % (comparison["compressed_bytes_delta"],
               comparison["compressed_percent_delta"] or 0.0),
            "File size delta: %+d bytes" % comparison["file_size_delta"],
            "Groups with changes (compressed delta):",
        ])
        changed = [item for item in comparison["groups"]
                   if item["compressed_bytes_delta"] or item["entries_before"] != item["entries_after"]]
        if not changed:
            lines.append("  (none)")
        for item in changed:
            lines.append("  %-30s %+12d bytes  entries %d -> %d" % (
                item["group"][:30], item["compressed_bytes_delta"],
                item["entries_before"], item["entries_after"]))

    lines.append("")
    if failures:
        lines.append("BUDGET: FAIL")
        lines.extend("  - " + failure for failure in failures)
    else:
        lines.append("BUDGET: PASS (or not configured)")
    return "\n".join(lines)


def _write_json(path: str, payload: dict) -> None:
    with open(path, "w", encoding="utf-8", newline="\n") as stream:
        json.dump(payload, stream, ensure_ascii=False, indent=2)
        stream.write("\n")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("apk", help="APK to audit")
    parser.add_argument("--baseline", help="older APK to compare against")
    parser.add_argument("--max-total-mib", type=float, default=None,
                        help="fail if total compressed contribution exceeds this many MiB")
    parser.add_argument("--max-growth-mib", type=float, default=None,
                        help="fail if compressed contribution grows more than this over baseline")
    parser.add_argument("--max-growth-percent", type=float, default=None,
                        help="fail if compressed contribution grows more than this percent over baseline")
    parser.add_argument("--top", type=int, default=15, help="number of largest entries to print")
    parser.add_argument("--json", dest="json_path", default=None, help="write machine-readable report")
    parser.add_argument("--fail-on-invalid-utf8", action="store_true",
                        help="exit non-zero when any entry name is not valid UTF-8")
    args = parser.parse_args(argv)

    if not os.path.isfile(args.apk):
        print("error: APK not found: %s" % args.apk, file=sys.stderr)
        return 2
    if args.baseline and not os.path.isfile(args.baseline):
        print("error: baseline APK not found: %s" % args.baseline, file=sys.stderr)
        return 2

    try:
        current = analyze_apk(args.apk, top=args.top)
        baseline = analyze_apk(args.baseline, top=0) if args.baseline else None
    except (OSError, ValueError, zipfile.BadZipFile, struct.error) as error:
        print("error: cannot read APK: %s" % error, file=sys.stderr)
        return 2

    comparison = compare_with_baseline(current, baseline) if baseline else None
    failures = evaluate_budget(current, baseline, args.max_total_mib,
                               args.max_growth_mib, args.max_growth_percent)
    if args.fail_on_invalid_utf8 and current["utf8_names"]["invalid"]:
        failures.append("%d ZIP entry name(s) are not valid UTF-8" % current["utf8_names"]["invalid"])

    print(format_report(current, baseline, comparison, failures))
    if args.json_path:
        payload = {
            "current": current,
            "baseline": baseline,
            "comparison": comparison,
            "budget_failures": failures,
        }
        _write_json(args.json_path, payload)

    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
