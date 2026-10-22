#!/usr/bin/env python3
"""Build a deterministic declaration inventory for the Pro API surface.

Inputs (read only):
  * docs/architecture/pro-api-compatibility.csv - one v1 row per documented API
  * output/auma-base.apk - public .d.ts assets, read with zipfile

The generator never extracts or copies JavaScript implementation files.  It
parses public TypeScript declarations with the TypeScript compiler API when a
local `typescript` package is available (no install is attempted).  `declared`
is evidence of a declaration only; every record is `status: unverified` and no
runtime support is inferred.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import os
import posixpath
import re
import shutil
import subprocess
import sys
import zipfile
from collections import Counter
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_APK = ROOT / "output" / "auma-base.apk"
DEFAULT_CSV = ROOT / "docs" / "architecture" / "pro-api-compatibility.csv"
DEFAULT_OUT = ROOT / "docs" / "architecture" / "pro-api-contract.json"

BUNDLE_ASSET = (
    "assets/flutter_assets/assets/project_templates/nodejs/node_modules/"
    "@autojs/types-pro9/libautojs9.d.ts"
)
MODULE_PREFIX = "assets/v8/built_in_modules/node_modules/"
NODE_TYPES_PREFIX = MODULE_PREFIX + "@types/node/"
TS_LIB_PREFIX = "assets/editor/lsp/lib/typescript/"
OPENCV_PREFIX = MODULE_PREFIX + "@autojs/opencv/"
MAX_SIGNATURE_CHARS = 4000
SCHEMA_VERSION = 1
GENERATOR_VERSION = "1.0.0"
VALID_PROFILES = {"module", "member", "classmethod"}

NODE_EXTRACTOR = r'''
"use strict";
const fs = require("fs");
const tsPath = process.env.PRO_API_TS_PATH;
if (!tsPath) {
  throw new Error("PRO_API_TS_PATH is not set");
}
const ts = require(tsPath);
const payload = JSON.parse(fs.readFileSync(0, "utf8"));
const printer = ts.createPrinter({ removeComments: true, newLine: ts.NewLineKind.LineFeed });

function norm(value) {
  let s = String(value || "").replace(/\r/g, " ").replace(/\s+/g, " ").trim();
  for (let i = 0; i < 6; i += 1) {
    s = s.replace(/^(export|declare|default|public)\s+/, "");
  }
  s = s.replace(/\s*;\s*$/, "").trim();
  return s;
}

function cap(value, meta) {
  let s = norm(value);
  if (s.length > 4000) {
    s = s.slice(0, 3999).trimEnd() + " ... [truncated]";
    if (meta) meta.truncated = true;
  }
  return s;
}

function printed(node, sf) {
  return norm(printer.printNode(ts.EmitHint.Unspecified, node, sf));
}

function headerOf(node, sf) {
  const start = node.getStart(sf);
  const end = node.members ? node.members.pos : node.getEnd();
  let raw = sf.text.slice(start, end);
  raw = raw.replace(/\{\s*$/, "");
  return norm(raw);
}

function nameOf(node, sf) {
  if (!node || !node.name) return null;
  if (ts.isIdentifier(node.name) || ts.isStringLiteral(node.name) || ts.isNumericLiteral(node.name)) {
    return node.name.text;
  }
  return norm(node.name.getText(sf));
}

function modifiersOf(node) {
  if (typeof ts.getModifiers === "function") return ts.getModifiers(node) || [];
  return node.modifiers || [];
}

function isPrivate(node) {
  if (node.name && ts.isPrivateIdentifier(node.name)) return true;
  return modifiersOf(node).some((m) =>
    m.kind === ts.SyntaxKind.PrivateKeyword || m.kind === ts.SyntaxKind.ProtectedKeyword
  );
}

function isExported(node) {
  return modifiersOf(node).some((m) =>
    m.kind === ts.SyntaxKind.ExportKeyword || m.kind === ts.SyntaxKind.DefaultKeyword
  );
}

function add(ctx, module, name, profile, kind, signature, owner, meta) {
  if (!name || !signature) return;
  const flags = meta || {};
  const text = cap(signature, flags);
  if (!text) return;
  const record = {
    module,
    name,
    profile,
    kind,
    signature: text,
    owner: owner || null
  };
  if (flags.truncated) record.truncated = true;
  ctx.declarations.push(record);
}

function collectMembers(node, module, owner, ctx, flat) {
  for (const member of node.members) {
    if (isPrivate(member)) continue;
    if (ts.isConstructorDeclaration(member) || ts.isConstructSignatureDeclaration(member)) {
      add(ctx, module, (flat ? "" : owner + ".") + "constructor", "classmethod", "constructor", printed(member, ctx.sf), owner);
      continue;
    }
    const memberName = nameOf(member, ctx.sf);
    if (!memberName) continue;
    const fullName = flat ? memberName : owner + "." + memberName;
    if (ts.isMethodDeclaration(member) || ts.isMethodSignature(member)) {
      add(ctx, module, fullName, "classmethod", "method", printed(member, ctx.sf), owner);
    } else if (ts.isGetAccessorDeclaration(member)) {
      add(ctx, module, fullName, "classmethod", "getter", printed(member, ctx.sf), owner);
    } else if (ts.isSetAccessorDeclaration(member)) {
      add(ctx, module, fullName, "classmethod", "setter", printed(member, ctx.sf), owner);
    } else if (ts.isPropertyDeclaration(member) || ts.isPropertySignature(member)) {
      add(ctx, module, fullName, "member", "property", printed(member, ctx.sf), owner);
    } else if (ts.isCallSignatureDeclaration(member)) {
      add(ctx, module, owner + ".call", "classmethod", "call-signature", printed(member, ctx.sf), owner);
    }
  }
}

function addClassLike(node, module, ctx, ambient) {
  if (!ambient && !isExported(node)) return;
  let name = nameOf(node, ctx.sf);
  if (!name) name = "default";
  const isInterface = ts.isInterfaceDeclaration(node);
  add(ctx, module, name, "member", isInterface ? "interface" : "class",
      headerOf(node, ctx.sf), null);
  collectMembers(node, module, name, ctx);
}

function addEnum(node, module, ctx) {
  const name = nameOf(node, ctx.sf);
  if (!name) return;
  add(ctx, module, name, "member", "enum", headerOf(node, ctx.sf), null);
  for (const member of node.members) {
    const memberName = nameOf(member, ctx.sf);
    if (!memberName) continue;
    add(ctx, module, name + "." + memberName, "member", "enum-member",
        printed(member, ctx.sf), name);
  }
}

function addReexport(ctx, module, target, kind, names) {
  if (!target) return;
  ctx.reexports.push({ module, target, kind, names: names || [] });
}

function collectStatement(node, module, ctx, ambient) {
  if (ts.isFunctionDeclaration(node)) {
    if (!ambient && !isExported(node)) return;
    const name = nameOf(node, ctx.sf) || "default";
    add(ctx, module, name, "member", "function", printed(node, ctx.sf), null);
    return;
  }
  if (ts.isClassDeclaration(node) || ts.isInterfaceDeclaration(node)) {
    if (!ambient && !isExported(node)) return;
    addClassLike(node, module, ctx, ambient);
    return;
  }
  if (ts.isTypeAliasDeclaration(node)) {
    if (!ambient && !isExported(node)) return;
    const name = nameOf(node, ctx.sf);
    if (name) add(ctx, module, name, "member", "type", printed(node, ctx.sf), null);
    return;
  }
  if (ts.isEnumDeclaration(node)) {
    if (!ambient && !isExported(node)) return;
    addEnum(node, module, ctx);
    return;
  }
  if (ts.isVariableStatement(node)) {
    if (!ambient && !isExported(node)) return;
    const declarations = node.declarationList.declarations;
    for (const decl of declarations) {
      const name = nameOf(decl, ctx.sf);
      if (!name) continue;
      let sig;
      if (declarations.length === 1) {
        sig = printed(node, ctx.sf);
      } else {
        const flags = node.declarationList.flags;
        const keyword = (flags & ts.NodeFlags.Const) ? "const" :
          (flags & ts.NodeFlags.Let) ? "let" : "var";
        sig = norm(keyword + " " + printed(decl, ctx.sf));
      }
      add(ctx, module, name, "member", "variable", sig, null);
    }
    return;
  }
  if (ts.isExportDeclaration(node)) {
    const target = node.moduleSpecifier && ts.isStringLiteral(node.moduleSpecifier)
      ? node.moduleSpecifier.text : module;
    if (node.exportClause && ts.isNamedExports(node.exportClause)) {
      const names = [];
      for (const el of node.exportClause.elements) {
        const exported = el.name.text;
        const imported = el.propertyName ? el.propertyName.text : exported;
        names.push({ exported, imported, typeOnly: !!el.isTypeOnly });
      }
      addReexport(ctx, module, target, "named", names);
    } else if (node.exportClause && ts.isNamespaceExport(node.exportClause)) {
      addReexport(ctx, module, target, "namespace", [
        { exported: node.exportClause.name.text, imported: "*", typeOnly: !!node.isTypeOnly }
      ]);
    } else {
      addReexport(ctx, module, target, "star", []);
    }
    return;
  }
  if (ts.isExportAssignment(node)) {
    add(ctx, module, "export=", "member", "export-assignment", printed(node, ctx.sf), null);
    return;
  }
  if (ts.isModuleDeclaration(node) && ts.isIdentifier(node.name)) {
    add(ctx, module, node.name.text, "member", "namespace", printed(node, ctx.sf), null);
    return;
  }
}

function collectModule(name, synthetic, ctx) {
  let mod = ctx.modules.get(name);
  if (!mod) {
    mod = { name, synthetic: !!synthetic, kind: synthetic ? "global-interface" : "module",
            declarations: [], reexports: [] };
    ctx.modules.set(name, mod);
  }
  return mod;
}

function processFile(file) {
  const sf = ts.createSourceFile(file.fileName, file.text, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS);
  const ctx = { sf, modules: new Map(), declarations: [], reexports: [] };
  const defaultModule = file.module || null;
  const moduleCtx = defaultModule ? collectModule(defaultModule, false, ctx) : null;
  if (moduleCtx) {
    ctx.declarations = moduleCtx.declarations;
    ctx.reexports = moduleCtx.reexports;
  }

  for (const stmt of sf.statements) {
    if (ts.isModuleDeclaration(stmt) && ts.isStringLiteral(stmt.name)) {
      const moduleName = stmt.name.text;
      const mod = collectModule(moduleName, false, ctx);
      const inner = { sf, declarations: mod.declarations, reexports: mod.reexports };
      if (stmt.body && ts.isModuleBlock(stmt.body)) {
        for (const child of stmt.body.statements) {
          collectStatement(child, moduleName, inner, true);
        }
      }
      continue;
    }
    if (defaultModule) {
      collectStatement(stmt, defaultModule, ctx, false);
      continue;
    }
    if (ts.isInterfaceDeclaration(stmt) && nameOf(stmt, sf) === "AutoJs") {
      const mod = collectModule("$autojs", true, ctx);
      mod.kind = "global-interface";
      const inner = { sf, declarations: mod.declarations, reexports: mod.reexports };
      collectMembers(stmt, "$autojs", "AutoJs", inner, true);
    }
  }

  const diagnostics = (sf.parseDiagnostics || []).map((d) => ({
    message: ts.flattenDiagnosticMessageText(d.messageText, " "),
    start: d.start == null ? null : d.start
  }));
  return {
    fileName: file.fileName,
    asset: file.asset,
    modules: Array.from(ctx.modules.values()),
    diagnostics
  };
}

const results = payload.files.map(processFile);
process.stdout.write(JSON.stringify({
  typescriptVersion: ts.version,
  results
}));
'''


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def relpath(path: Path) -> str:
    try:
        return path.resolve().relative_to(ROOT).as_posix()
    except ValueError:
        return path.as_posix()


def find_typescript(explicit: str | None) -> tuple[Path, str]:
    candidates: list[Path] = []
    if explicit:
        candidates.append(Path(explicit))
    if os.environ.get("TYPESCRIPT_PATH"):
        candidates.append(Path(os.environ["TYPESCRIPT_PATH"]))
    candidates.extend([
        ROOT / "vscode-extension" / "node_modules" / "typescript",
        ROOT / "node_modules" / "typescript",
        ROOT / "tools" / "node_modules" / "typescript",
    ])
    seen: set[Path] = set()
    for candidate in candidates:
        try:
            resolved = candidate.resolve()
        except OSError:
            continue
        if resolved in seen:
            continue
        seen.add(resolved)
        package = resolved / "package.json"
        entry = resolved / "lib" / "typescript.js"
        if package.is_file() and entry.is_file():
            try:
                version = json.loads(package.read_text(encoding="utf-8"))["version"]
            except (OSError, KeyError, json.JSONDecodeError):
                continue
            return resolved, version
    raise SystemExit(
        "typescript package not found. Put it in vscode-extension/node_modules/typescript, "
        "set TYPESCRIPT_PATH, or pass --typescript <dir>. No dependency is installed by this script."
    )


def find_node() -> str:
    node = shutil.which("node")
    if not node:
        raise SystemExit("node executable not found; the TypeScript AST parser requires node")
    return node


def read_zip(apk: Path) -> tuple[zipfile.ZipFile, bytes]:
    data = apk.read_bytes()
    try:
        zf = zipfile.ZipFile(apk)
    except zipfile.BadZipFile as exc:
        raise SystemExit(f"not a zip/apk: {apk}: {exc}") from exc
    return zf, data


def module_name_for(asset: str) -> str:
    rel = asset[len(MODULE_PREFIX):]
    if rel.endswith(".d.ts"):
        rel = rel[:-5]
    if rel.endswith("/index"):
        rel = rel[: -len("/index")]
    return rel


def is_pro_module_asset(asset: str) -> bool:
    if not asset.startswith(MODULE_PREFIX) or not asset.endswith(".d.ts"):
        return False
    rel = asset[len(MODULE_PREFIX):]
    if rel.startswith(("@types/", "@autojs/", "axios/")):
        return False
    if rel == "opencv.d.ts":
        return False
    return True


def parse_pro_files(apk_path: Path, ts_dir: Path, ts_version: str) -> dict[str, Any]:
    zf, apk_bytes = read_zip(apk_path)
    with zf:
        names = set(zf.namelist())
        if BUNDLE_ASSET not in names:
            raise SystemExit(f"missing public Pro bundle in APK: {BUNDLE_ASSET}")
        bundle_bytes = zf.read(BUNDLE_ASSET)
        module_assets = sorted(a for a in names if is_pro_module_asset(a))
        data: dict[str, bytes] = {BUNDLE_ASSET: bundle_bytes}
        data.update({asset: zf.read(asset) for asset in module_assets})

        node_assets = sorted(
            a for a in names
            if a.startswith(NODE_TYPES_PREFIX) and a.endswith(".d.ts")
        )
        ts_lib_assets = sorted(a for a in names if a.startswith(TS_LIB_PREFIX) and a.endswith(".d.ts"))
        opencv_assets = sorted(a for a in names if a.startswith(OPENCV_PREFIX) and a.endswith(".d.ts"))
        axios_assets = sorted(a for a in names if a == MODULE_PREFIX + "axios/index.d.ts")

    files = []
    for asset in [BUNDLE_ASSET] + module_assets:
        text = data[asset].decode("utf-8-sig")
        files.append({
            "asset": asset,
            "fileName": asset,
            "module": None if asset == BUNDLE_ASSET else module_name_for(asset),
            "text": text,
        })

    payload = json.dumps({"files": files}, ensure_ascii=False)
    env = dict(os.environ)
    env["PRO_API_TS_PATH"] = str(ts_dir)
    proc = subprocess.run(
        [find_node(), "-e", NODE_EXTRACTOR],
        input=payload,
        text=True,
        encoding="utf-8",
        capture_output=True,
        cwd=str(ROOT),
        env=env,
        timeout=180,
    )
    if proc.returncode != 0:
        raise SystemExit(f"TypeScript extractor failed ({proc.returncode}): {proc.stderr.strip()}")
    try:
        parsed = json.loads(proc.stdout)
    except json.JSONDecodeError as exc:
        raise SystemExit(f"TypeScript extractor returned invalid JSON: {exc}\n{proc.stdout[:1000]}") from exc
    if parsed.get("typescriptVersion") != ts_version:
        raise SystemExit(
            f"TypeScript version mismatch: package.json={ts_version} parser={parsed.get('typescriptVersion')}"
        )

    parse_errors = []
    for result in parsed["results"]:
        for diag in result.get("diagnostics", []):
            parse_errors.append(
                f"{result['asset']}:{diag.get('start')}: {diag.get('message')}"
            )
    if parse_errors:
        raise SystemExit("TypeScript parse diagnostics:\n" + "\n".join(parse_errors[:20]))

    meta = {
        "apk_path": apk_path,
        "apk_bytes": apk_bytes,
        "bundle_bytes": bundle_bytes,
        "module_assets": module_assets,
        "node_assets": node_assets,
        "ts_lib_assets": ts_lib_assets,
        "opencv_assets": opencv_assets,
        "axios_assets": axios_assets,
        "files": data,
        "parsed": parsed,
    }
    return meta


def csv_rows(path: Path) -> tuple[list[dict[str, str]], bytes]:
    raw = path.read_bytes()
    text = raw.decode("utf-8-sig")
    rows = list(csv.DictReader(text.splitlines()))
    if not rows:
        raise SystemExit(f"CSV has no rows: {path}")
    return rows, raw


def build_v1(csv_path: Path, rows: list[dict[str, str]], raw: bytes) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    source = {
        "asset": relpath(csv_path),
        "sha256": sha256_bytes(raw),
        "bytes": len(raw),
        "family": "v1-compat-csv",
    }
    class_names = {r["api"].strip() for r in rows if r["kind"] == "class"}
    class_names.update({"UiSelector", "UiObject", "Rect", "RootAutomator", "RootAutomator2"})
    records: list[dict[str, Any]] = []
    for index, row in enumerate(rows, start=1):
        kind = row["kind"].strip()
        api = row["api"].strip()
        if kind == "label":
            profile = "module"
        elif kind == "constructor":
            profile = "classmethod"
        elif kind == "class":
            profile = "member"
        elif kind == "member":
            profile = "member"
        elif "." in api and api.split(".", 1)[0] in class_names:
            profile = "classmethod"
        else:
            profile = "member"
        declared = kind != "label"
        record: dict[str, Any] = {
            "id": f"v1-{index:04d}",
            "layer": "v1-csv",
            "api": api,
            "module": row["module"].strip(),
            "profile": profile,
            "kind": kind,
            "signature": row["signature"].strip(),
            "source": dict(source, row=index + 1),
            "declared": declared,
            "status": "unverified",
        }
        legacy = {
            "module_num": row.get("module_num", "").strip(),
            "pdf_page": row.get("pdf_page", "").strip(),
            "version": row.get("version", "").strip(),
            "section": row.get("section", "").strip(),
        }
        if row.get("note", "").strip():
            legacy["note"] = row["note"].strip()
        record["v1_metadata"] = legacy
        records.append(record)

    kind_counts = Counter(r["kind"] for r in rows)
    declared = [r for r in records if r["declared"]]
    counts = {
        "entries": len(records),
        "declared_api_entries": len(declared),
        "labels": len(records) - len(declared),
        "by_kind": dict(sorted(kind_counts.items())),
        "by_profile_all": dict(sorted(Counter(r["profile"] for r in records).items())),
        "by_profile_declared": dict(sorted(Counter(r["profile"] for r in declared).items())),
    }
    return records, counts


def primary_source(sources: list[dict[str, Any]]) -> dict[str, Any]:
    rank = {
        "pro-v2-bundle": 0,
        "pro-v2-module": 1,
        "v1-compat-csv": 2,
    }
    ordered = sorted(sources, key=lambda s: (rank.get(s.get("family"), 9), s["asset"]))
    result = dict(ordered[0])
    return result


def normalize_target(current: str, target: str, known: set[str]) -> str:
    value = target.strip()
    if value.startswith("."):
        value = posixpath.normpath(posixpath.join(current, value))
    if value.endswith(".d"):
        value = value[:-2]
    return value


def build_v2(meta: dict[str, Any], ts_version: str) -> tuple[list[dict[str, Any]], dict[str, Any], list[dict[str, Any]], list[dict[str, Any]]]:
    asset_meta: dict[str, dict[str, Any]] = {}
    for asset, blob in meta["files"].items():
        family = "pro-v2-bundle" if asset == BUNDLE_ASSET else "pro-v2-module"
        asset_meta[asset] = {
            "asset": asset,
            "sha256": sha256_bytes(blob),
            "bytes": len(blob),
            "family": family,
        }

    module_meta: dict[str, dict[str, Any]] = {}
    module_entries: dict[str, dict[tuple[Any, ...], dict[str, Any]]] = {}
    reexports: list[dict[str, Any]] = []

    def ensure_module(name: str, synthetic: bool = False) -> dict[str, Any]:
        mm = module_meta.get(name)
        if mm is None:
            mm = {"name": name, "synthetic": synthetic, "sources": set()}
            module_meta[name] = mm
            module_entries[name] = {}
        if synthetic:
            mm["synthetic"] = True
        return mm

    for result in meta["parsed"]["results"]:
        asset = result["asset"]
        source = asset_meta[asset]
        for mod in result["modules"]:
            name = mod["name"]
            mm = ensure_module(name, bool(mod.get("synthetic")))
            mm["sources"].add(asset)
            if mod.get("kind") == "global-interface":
                mm["global"] = True
            for decl in mod.get("declarations", []):
                entry = dict(decl)
                entry["_sources"] = {asset}
                entry.setdefault("declared_in", name)
                key = (
                    name,
                    entry["name"],
                    entry["profile"],
                    entry["kind"],
                    entry["signature"],
                )
                current = module_entries[name].get(key)
                if current is None:
                    module_entries[name][key] = entry
                else:
                    current["_sources"].update(entry["_sources"])
            for reexport in mod.get("reexports", []):
                item = dict(reexport)
                item["_source"] = asset
                reexports.append(item)

    known = set(module_meta)
    unresolved: dict[tuple[str, str, str], set[str]] = {}

    def add_copy(current: str, original: dict[str, Any], new_name: str, target: str) -> bool:
        copy = dict(original)
        copy["module"] = current
        copy["name"] = new_name
        copy["declared_in"] = original.get("declared_in", target)
        copy["_sources"] = set(original["_sources"])
        copy["reexported"] = True
        copy["exported_from"] = target
        key = (
            current,
            copy["name"],
            copy["profile"],
            copy["kind"],
            copy["signature"],
        )
        existing = module_entries[current].get(key)
        if existing is None:
            module_entries[current][key] = copy
            return True
        existing["_sources"].update(copy["_sources"])
        return False

    # Resolve star/named re-exports inside the parsed declaration graph.  A
    # small fixed-point loop handles chains such as ui -> ui/activity.
    changed = True
    passes = 0
    while changed and passes < 8:
        changed = False
        passes += 1
        for reexport in sorted(
            reexports,
            key=lambda r: (
                r["module"],
                r["target"],
                r["kind"],
                json.dumps(r.get("names", []), sort_keys=True),
            ),
        ):
            current = reexport["module"]
            target = normalize_target(current, reexport["target"], known)
            if target not in known or target == current:
                if target not in known:
                    unresolved.setdefault((current, target, reexport["kind"]), set())
                    for item in reexport.get("names", []):
                        unresolved[(current, target, reexport["kind"])].add(item["exported"])
                continue
            target_entries = list(module_entries[target].values())
            if reexport["kind"] == "star":
                top_names = sorted({
                    e["name"] for e in target_entries
                    if "." not in e["name"] and not e["name"].startswith("$")
                })
                for top in top_names:
                    for entry in list(target_entries):
                        if entry["name"] == top or entry["name"].startswith(top + "."):
                            if add_copy(current, entry, entry["name"], target):
                                changed = True
            elif reexport["kind"] == "named":
                for item in reexport.get("names", []):
                    imported = item.get("imported")
                    exported = item.get("exported")
                    if not imported or not exported:
                        continue
                    if imported == exported and current == target:
                        continue
                    for entry in list(target_entries):
                        if entry["name"] == imported or entry["name"].startswith(imported + "."):
                            suffix = entry["name"][len(imported):]
                            if add_copy(current, entry, exported + suffix, target):
                                changed = True
            else:
                unresolved.setdefault((current, target, reexport["kind"]), set())
                for item in reexport.get("names", []):
                    unresolved[(current, target, reexport["kind"])].add(item.get("exported", "*"))

    # Add module records and materialize records.
    all_entries: list[dict[str, Any]] = []
    for module, entries in module_entries.items():
        mm = module_meta[module]
        synthetic = bool(mm.get("synthetic"))
        module_sig = "interface AutoJs" if synthetic else f'declare module "{module}"'
        module_kind = "global-interface" if synthetic else "module"
        module_record = {
            "module": module,
            "name": "",
            "profile": "module",
            "kind": module_kind,
            "signature": module_sig,
            "owner": None,
            "declared_in": module,
            "_sources": set(mm["sources"]),
            "synthetic": synthetic,
        }
        all_entries.append(module_record)
        all_entries.extend(entries.values())

    materialized: list[dict[str, Any]] = []
    for entry in all_entries:
        module = entry["module"]
        name = entry["name"]
        api = module if entry["profile"] == "module" else f"{module}.{name}"
        sources = [
            asset_meta[a] for a in sorted(entry.get("_sources", set()))
            if a in asset_meta
        ]
        if not sources:
            raise SystemExit(f"record without source: {api}")
        source = primary_source(sources)
        record: dict[str, Any] = {
            "id": "",
            "layer": "pro-v2-dts",
            "api": api,
            "module": module,
            "profile": entry["profile"],
            "kind": entry["kind"],
            "signature": entry["signature"],
            "source": source,
            "sources": sources,
            "declared": True,
            "status": "unverified",
            "scope": "global" if module_meta[module].get("synthetic") else "module",
            "declared_in": entry.get("declared_in", module),
        }
        if entry.get("owner"):
            record["owner"] = entry["owner"]
        if entry.get("truncated"):
            record["signature_truncated"] = True
        if entry.get("reexported"):
            record["reexported"] = True
            record["exported_from"] = entry.get("exported_from", entry.get("declared_in", module))
        if module_meta[module].get("synthetic"):
            record["synthetic_module"] = True
        materialized.append(record)

    materialized.sort(key=lambda r: (
        r["module"], r["api"], r["profile"], r["kind"], r["signature"],
        r.get("declared_in", ""), r["source"]["asset"],
    ))
    for index, record in enumerate(materialized, start=1):
        record["id"] = f"v2-{index:04d}"

    unresolved_list = [
        {"module": key[0], "target": key[1], "kind": key[2], "names": sorted(names)}
        for key, names in sorted(unresolved.items())
    ]
    modules = sorted(module_meta)
    by_module = Counter(r["module"] for r in materialized)
    counts = {
        "records": len(materialized),
        "modules": sum(1 for m in modules if not module_meta[m].get("synthetic")),
        "global_surfaces": sum(1 for m in modules if module_meta[m].get("synthetic")),
        "module_list": modules,
        "by_profile": dict(sorted(Counter(r["profile"] for r in materialized).items())),
        "by_kind": dict(sorted(Counter(r["kind"] for r in materialized).items())),
        "by_module": dict(sorted(by_module.items())),
        "classes": sum(1 for r in materialized if r["kind"] == "class"),
        "interfaces": sum(1 for r in materialized if r["kind"] == "interface"),
        "class_methods": sum(1 for r in materialized if r["profile"] == "classmethod"),
        "signature_truncated": sum(1 for r in materialized if r.get("signature_truncated")),
        "unresolved_reexports": len(unresolved_list),
        "reused_sources": sum(1 for r in materialized if len(r["sources"]) > 1),
    }
    return materialized, counts, unresolved_list, [asset_meta[a] for a in sorted(asset_meta)]


def validate(v1_records: list[dict[str, Any]], v2_records: list[dict[str, Any]],
             unresolved: list[dict[str, Any]], apk_sha: str, csv_sha: str, ts_version: str) -> None:
    records = v1_records + v2_records
    if not v1_records:
        raise SystemExit("v1 inventory is empty")
    if not v2_records:
        raise SystemExit("v2 inventory is empty")
    ids = [r["id"] for r in records]
    if len(ids) != len(set(ids)):
        raise SystemExit("duplicate record ids")
    for record in records:
        if record["profile"] not in VALID_PROFILES:
            raise SystemExit(f"invalid profile: {record['profile']}")
        if record["status"] != "unverified":
            raise SystemExit(f"record is not unverified: {record['id']}")
        if not record["signature"]:
            raise SystemExit(f"empty signature: {record['id']}")
        if not re.fullmatch(r"[0-9a-f]{64}", record["source"]["sha256"]):
            raise SystemExit(f"invalid source sha256: {record['id']}")
        if record["layer"] == "v1-csv":
            if record["source"]["asset"] != "docs/architecture/pro-api-compatibility.csv":
                raise SystemExit(f"v1 source mismatch: {record['id']}")
            if record["source"]["sha256"] != csv_sha:
                raise SystemExit(f"v1 csv hash mismatch: {record['id']}")
        else:
            if not record["declared"]:
                raise SystemExit(f"v2 record is not declared: {record['id']}")
            if record["source"]["family"] not in {"pro-v2-bundle", "pro-v2-module"}:
                raise SystemExit(f"v2 source family mismatch: {record['id']}")
    if not re.fullmatch(r"[0-9a-f]{64}", apk_sha):
        raise SystemExit("invalid apk sha256")
    if not ts_version:
        raise SystemExit("missing typescript version")
    for item in unresolved:
        if not item["target"]:
            raise SystemExit("unresolved re-export without target")


def build_contract(apk_path: Path, csv_path: Path, ts_dir: Path, ts_version: str) -> tuple[dict[str, Any], str]:
    rows, csv_raw = csv_rows(csv_path)
    v1_records, v1_counts = build_v1(csv_path, rows, csv_raw)
    meta = parse_pro_files(apk_path, ts_dir, ts_version)
    v2_records, v2_counts, unresolved, module_sources = build_v2(meta, ts_version)

    apk_sha = sha256_bytes(meta["apk_bytes"])
    csv_sha = sha256_bytes(csv_raw)
    validate(v1_records, v2_records, unresolved, apk_sha, csv_sha, ts_version)

    bundle_meta = next(s for s in module_sources if s["asset"] == BUNDLE_ASSET)
    source_assets = []
    csv_source = {
        "asset": relpath(csv_path),
        "sha256": csv_sha,
        "bytes": len(csv_raw),
        "family": "v1-compat-csv",
    }
    source_assets.append(csv_source)
    source_assets.extend(module_sources)

    used_by: Counter[str] = Counter()
    for record in v1_records:
        used_by[record["source"]["asset"]] += 1
    for record in v2_records:
        for src in record["sources"]:
            used_by[src["asset"]] += 1
    for item in source_assets:
        item["used_by_records"] = used_by.get(item["asset"], 0)

    node_assets = []
    with zipfile.ZipFile(apk_path) as zf:
        for asset in meta["node_assets"]:
            blob = zf.read(asset)
            node_assets.append({
                "asset": asset,
                "sha256": sha256_bytes(blob),
                "bytes": len(blob),
                "module": asset[len(NODE_TYPES_PREFIX):-5] if asset.endswith(".d.ts") else asset,
            })
    node_builtins = {
        "included_in_api_records": False,
        "inventory_depth": "asset-identity-only",
        "claim": "Node-only declarations; this is not evidence that Rhino or QuickJS supports these modules, npm packages, or Node builtins.",
        "dts_files": len(node_assets),
        "assets": node_assets,
    }
    excluded = {
        "typescript_stdlib": {
            "asset_prefix": TS_LIB_PREFIX,
            "dts_files": len(meta["ts_lib_assets"]),
            "reason": "TypeScript compiler/lib declarations, not AutoJS Pro API.",
        },
        "node_builtins": {
            "asset_prefix": NODE_TYPES_PREFIX,
            "dts_files": len(meta["node_assets"]),
            "reason": "Kept separate; no runtime or npm support claim.",
        },
        "opencv_individual_typings": {
            "asset_prefix": OPENCV_PREFIX,
            "dts_files": len(meta["opencv_assets"]),
            "reason": "The Pro bundle's @autojs/opencv module is inventoried; individual typings are duplicate implementation detail.",
        },
        "axios": {
            "assets": meta["axios_assets"],
            "reason": "Third-party npm declaration; not an AutoJS Pro module.",
        },
    }

    signature_truncated = v2_counts["signature_truncated"]
    limitations = [
        "Declarations only: declared=true and source SHA256 identify a signature, not an implementation or a callable runtime API. Every record has status=unverified.",
        "No device, build, publish, runtime probe, or functional inference was performed; CSV runtime evidence columns are intentionally not copied.",
        "v1 entries are CSV rows from the compatibility document. Rows with kind=label are non-API section markers, use profile=module, and have declared=false.",
        "v2 records are public .d.ts declarations from the Pro9.3.11-0 APK bundle and v8 built_in_modules files. Class/interface methods and public properties are expanded; inherited members are not flattened.",
        "export * / named re-exports are resolved only when the target module is present in the parsed declarations. External and unresolved targets are listed in unresolved_reexports.",
        "Node builtins (@types/node) are kept outside the API records. No npm, Node builtin, Rhino, or QuickJS runtime support is claimed.",
        "TypeScript standard-library declarations, axios, and individual @autojs/opencv typings are excluded; the Pro bundle's @autojs/opencv module is included.",
        "Signatures are printed by the TypeScript parser/printer, with comments and non-semantic whitespace removed. Source order and formatting are not preserved.",
        "Only public .d.ts files are read; no proprietary JavaScript implementation files are extracted or copied.",
    ]
    if signature_truncated:
        limitations.append(
            f"{signature_truncated} signature(s) exceeded {MAX_SIGNATURE_CHARS} characters and were truncated; signature_truncated marks them."
        )
    if unresolved:
        limitations.append(
            f"{len(unresolved)} re-export target(s) could not be resolved and are not expanded."
        )

    contract = {
        "schema_version": SCHEMA_VERSION,
        "generator": {
            "name": "tools/build-pro-api-contract.py",
            "version": GENERATOR_VERSION,
            "parser": "typescript-ast",
            "typescript_version": ts_version,
        },
        "inputs": {
            "apk": {
                "path": relpath(apk_path),
                "sha256": apk_sha,
                "bytes": len(meta["apk_bytes"]),
                "build_label": "Pro9.3.11-0 advanced",
            },
            "csv": {
                "path": relpath(csv_path),
                "sha256": csv_sha,
                "bytes": len(csv_raw),
                "rows": len(rows),
            },
            "pro_v2_bundle": {
                "asset": BUNDLE_ASSET,
                "sha256": bundle_meta["sha256"],
                "bytes": bundle_meta["bytes"],
            },
        },
        "contract": {
            "declared_means": "The signature exists in the cited public declaration asset. It does not mean the API is implemented or callable.",
            "status": "unverified",
            "runtime_support_claimed": False,
            "profiles": {
                "module": "Ambient module or synthetic global interface surface.",
                "member": "Module-level export, class/interface declaration, property, or enum member.",
                "classmethod": "Class/interface method, constructor, getter, setter, or call signature.",
            },
        },
        "source_assets": source_assets,
        "counts": {
            "total_records": len(v1_records) + len(v2_records),
            "v1": v1_counts,
            "v2": v2_counts,
            "node_builtins": {
                "dts_files": node_builtins["dts_files"],
                "included_in_api_records": False,
            },
            "unresolved_reexports": len(unresolved),
        },
        "node_builtins": node_builtins,
        "excluded_assets": excluded,
        "unresolved_reexports": unresolved,
        "limitations": limitations,
        "records": v1_records + v2_records,
    }
    rendered = json.dumps(contract, ensure_ascii=False, indent=2) + "\n"
    return contract, rendered


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, default=DEFAULT_APK)
    parser.add_argument("--csv", type=Path, default=DEFAULT_CSV)
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    parser.add_argument("--typescript", help="path to a local typescript package directory")
    parser.add_argument("--check", action="store_true", help="verify --out is byte-identical; do not write")
    args = parser.parse_args(argv)

    apk = args.apk.resolve()
    csv_path = args.csv.resolve()
    out = args.out.resolve()
    if not apk.is_file():
        raise SystemExit(f"APK not found: {apk}")
    if not csv_path.is_file():
        raise SystemExit(f"CSV not found: {csv_path}")
    ts_dir, package_version = find_typescript(args.typescript)
    contract, rendered = build_contract(apk, csv_path, ts_dir, package_version)
    digest = sha256_bytes(rendered.encode("utf-8"))

    if args.check:
        if not out.is_file():
            raise SystemExit(f"contract not found for --check: {out}")
        existing = out.read_bytes()
        if existing != rendered.encode("utf-8"):
            raise SystemExit(
                f"contract is stale: {out} (existing sha256={sha256_bytes(existing)}, generated sha256={digest})"
            )
        action = "verified"
    else:
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_bytes(rendered.encode("utf-8"))
        action = "wrote"

    c = contract["counts"]
    print(f"{action} {relpath(out)}")
    print(f"sha256 {digest}")
    print(
        f"records {c['total_records']} | v1 entries {c['v1']['entries']} "
        f"(declared {c['v1']['declared_api_entries']}, labels {c['v1']['labels']}) | "
        f"v2 records {c['v2']['records']} (modules {c['v2']['modules']}, "
        f"global {c['v2']['global_surfaces']}) | node d.ts {c['node_builtins']['dts_files']}"
    )
    print(
        f"v2 profiles {c['v2']['by_profile']} | unresolved reexports {c['v2']['unresolved_reexports']} | "
        f"truncated {c['v2']['signature_truncated']}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
