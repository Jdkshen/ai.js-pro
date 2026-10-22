"""Inventory the API surface declared by the Pro API contract on real engines.

This tool is deliberately a *shape* audit, not a compatibility or behavior test.
It records what the engine exposes through MCP ``list_engine_api`` and through a
bounded, non-invoking dotted-property lookup. Presence of a name is not evidence
that the API works, that its arguments are accepted, or that a returned object
has the documented shape.

Scope:
  * v2: query each of the 39 non-``$autojs`` Pro modules exactly once per engine
    with ``list_engine_api`` and use only the returned export key set. Nested
    class/interface members are not claimed as present. ``$autojs`` records are
    accounted for but excluded from module probing.
  * v1: the 737 declared CSV entries are sent in approximately 100-entry
    ``run_script`` chunks. Ordinary global members get a type-only property
    lookup. Class methods are always recorded as ``instance_unverified`` because
    no instance fixture is created. CSV section labels are excluded. Invalid
    identifier paths are recorded as ``invalid_mapping`` and are not executed.
  * Engine globals are queried once per engine. No script is retried.

No percentage or compatibility rate is emitted. The exact MCP request/response
pairs are written by ``DeviceAudit`` to ``mcp-responses.json`` in the output
directory.

Normal use (requires a reachable MCP service and a real device)::

    python tools/audit-pro-api-surface.py --serial <serial> --output <dir> \\
        [--engines rhino quickjs nodejs]

Offline self-test (no device, no ADB, no network)::

    python tools/audit-pro-api-surface.py --self-test --output <dir>
    python tools/audit-pro-api-surface.py --self-test

The self-test uses the checked-in contract plus synthetic parser/coverage
fixtures. It does not instantiate ``DeviceAudit``.
"""

from __future__ import annotations

import argparse
import collections
import datetime as dt
import hashlib
import importlib.util
import json
import re
import sys
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CONTRACT = ROOT / "docs" / "architecture" / "pro-api-contract.json"
DEFAULT_AUDIT_UTILITY = ROOT / "tools" / "audit-engine-details.py"
ENGINES = ("rhino", "quickjs", "nodejs")
CHUNK_SIZE = 100
MARKER_PREFIX = "PRO_API_SURFACE_V1_RESULT_"
ACTIVE_STATES = frozenset({"STARTING", "RUNNING", "QUEUED"})
IDENTIFIER_PATH = re.compile(r"^[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)*$")
ANSI_ESCAPE = re.compile(r"\x1b\[[0-9;]*[A-Za-z]")
MISSING_MODULE_HINTS = re.compile(
    r"missing[_ ]?module|cannot find module|module .*not found|no such module|"
    r"MODULE_NOT_FOUND|require is not defined|require is undefined|"
    r"未知模块|模块不存在|找不到模块",
    re.IGNORECASE,
)
DECLARATION_KINDS = frozenset({"interface", "type", "global-interface"})
TOP_KIND_PRIORITY = {
    "class": 0,
    "enum": 1,
    "function": 2,
    "variable": 3,
    "interface": 4,
    "type": 5,
    "global-interface": 6,
}
SCHEMA_VERSION = 1

LIMITATIONS = [
    "Declarations and runtime presence only: no compatibility percentage or behavior claim is made.",
    "v2 module coverage is the export-key shape returned by list_engine_api; nested class/interface members are not enumerated or invoked.",
    "v2 interface/type declarations are erased TypeScript constructs and are recorded as declaration_unverified, never as runtime export coverage.",
    "v1 class methods require an instance fixture, so they are always instance_unverified; a missing instance is not labeled as a missing API.",
    "v1 ordinary global members are inspected with a guarded dotted-property lookup that returns typeof/arity only and never invokes a method.",
    "Getter/accessor properties are reported as presence only; the getter is not invoked.",
    "CSV section labels are contract metadata, not APIs, and are recorded as excluded_label.",
    "The $autojs v2 global surface is excluded from module queries and accounted for as excluded_global_surface.",
    "No script is retried; each module is queried once and each v1 chunk is executed once per engine.",
    "No tools/list or device warmup call is made; the MCP service must already be reachable and ready.",
    "Device permissions, argument validation, return-object shapes, side effects, and functional correctness are not verified.",
]

CLAIMS = {
    "compatibilityClaimed": False,
    "behaviorVerified": False,
    "presenceIsNotBehavior": True,
    "note": "This report inventories declared surface and runtime name-shape only. It is not a compatibility conclusion.",
}


# ---------------------------------------------------------------------------
# Contract loading and coverage planning
# ---------------------------------------------------------------------------


def utc_now() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat()


def sha256_file(path: Path) -> str | None:
    if not path.is_file():
        return None
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def load_contract(path: Path = DEFAULT_CONTRACT) -> dict[str, Any]:
    data = json.loads(path.read_text(encoding="utf8"))
    if not isinstance(data, dict):
        raise ValueError("contract root must be a JSON object")
    records = data.get("records")
    if not isinstance(records, list) or not records:
        raise ValueError("contract.records must be a non-empty array")
    return data


def relative_v2_path(record: dict[str, Any]) -> str:
    """Return the part after the module name; an empty string for the module itself."""
    module = str(record.get("module", ""))
    api = str(record.get("api", ""))
    if api == module:
        return ""
    prefix = module + "."
    if api.startswith(prefix):
        return api[len(prefix):]
    return api


def valid_identifier_path(path: str) -> bool:
    return bool(IDENTIFIER_PATH.fullmatch(path))


def contract_facts(contract: dict[str, Any]) -> dict[str, Any]:
    records = contract["records"]
    ids = [str(record.get("id", "")) for record in records]
    if any(not record_id for record_id in ids):
        raise ValueError("every contract record must have an id")
    if len(ids) != len(set(ids)):
        raise ValueError("contract record ids must be unique")

    v1 = [record for record in records if record.get("layer") == "v1-csv"]
    v2 = [record for record in records if record.get("layer") == "pro-v2-dts"]
    if len(v1) + len(v2) != len(records):
        unknown = sorted({str(record.get("layer")) for record in records if record.get("layer") not in {"v1-csv", "pro-v2-dts"}})
        raise ValueError("unexpected contract layer(s): " + ", ".join(unknown))

    v1_labels = [record for record in v1 if not record.get("declared", False)]
    v1_declared = [record for record in v1 if record.get("declared", False)]
    profiles = collections.Counter(str(record.get("profile", "")) for record in v1_declared)
    if set(profiles) - {"classmethod", "member"}:
        raise ValueError("unexpected v1 declared profile(s): " + ", ".join(sorted(set(profiles) - {"classmethod", "member"})))
    if sum(profiles.values()) != len(v1_declared):
        raise ValueError("v1 declared profile counts do not cover all declared records")

    autojs_records = [record for record in v2 if record.get("module") == "$autojs"]
    module_record_set = {str(record.get("module")) for record in v2 if record.get("module") != "$autojs"}
    counts = contract.get("counts") or {}
    v2_counts = counts.get("v2") or {}
    declared_module_list = v2_counts.get("module_list")
    if isinstance(declared_module_list, list) and declared_module_list:
        modules = [str(name) for name in declared_module_list if str(name) != "$autojs"]
    else:
        modules = sorted(module_record_set)
    if len(modules) != len(set(modules)):
        raise ValueError("module list contains duplicates")
    if set(modules) != module_record_set:
        missing = sorted(module_record_set - set(modules))
        extra = sorted(set(modules) - module_record_set)
        raise ValueError(f"module list mismatch: missing={missing} extra={extra}")

    # Top-level export kind is used only to keep interfaces/types clearly
    # separate from runtime classes/objects.
    top_kind: dict[tuple[str, str], str] = {}
    for record in v2:
        module = str(record.get("module", ""))
        if module == "$autojs" or record.get("kind") == "module":
            continue
        rel = relative_v2_path(record)
        if not rel or "." in rel:
            continue
        kind = str(record.get("kind", ""))
        key = (module, rel)
        current = top_kind.get(key)
        if current is None or TOP_KIND_PRIORITY.get(kind, 99) < TOP_KIND_PRIORITY.get(current, 99):
            top_kind[key] = kind

    v1_entries: list[dict[str, Any]] = []
    v1_invalid: list[dict[str, Any]] = []
    for record in v1_declared:
        api = str(record.get("api", ""))
        if valid_identifier_path(api):
            v1_entries.append(record)
        else:
            v1_invalid.append(record)

    chunks = [v1_entries[index:index + CHUNK_SIZE] for index in range(0, len(v1_entries), CHUNK_SIZE)]
    chunk_for_id: dict[str, int] = {}
    for index, chunk in enumerate(chunks):
        for record in chunk:
            chunk_for_id[str(record["id"])] = index

    return {
        "contract": contract,
        "records": records,
        "by_id": {str(record["id"]): record for record in records},
        "ids": ids,
        "v1": v1,
        "v1_labels": v1_labels,
        "v1_declared": v1_declared,
        "v1_profiles": profiles,
        "v1_entries": v1_entries,
        "v1_invalid": v1_invalid,
        "v1_chunks": chunks,
        "v1_chunk_for_id": chunk_for_id,
        "v2": v2,
        "v2_autojs": autojs_records,
        "modules": modules,
        "top_kind": top_kind,
        "module_records": {str(record.get("module")): record for record in v2 if record.get("kind") == "module" and record.get("module") != "$autojs"},
    }


# ---------------------------------------------------------------------------
# v1 JavaScript probe
# ---------------------------------------------------------------------------


def make_v1_chunk_source(records: list[dict[str, Any]], marker: str, chunk_index: int) -> str:
    payload = [
        {
            "id": str(record["id"]),
            "api": str(record["api"]),
            "mode": "classmethod" if record.get("profile") == "classmethod" else "member",
        }
        for record in records
    ]
    body = json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
    marker_literal = json.dumps(marker)
    return """(function(){
  var root = (typeof globalThis !== 'undefined' && globalThis) ? globalThis : (function(){ return this; })();
  var payload = __PAYLOAD__;
  function descriptorOf(obj, key) {
    var p = obj, depth = 0;
    while (p !== null && typeof p !== 'undefined' && depth < 8) {
      try {
        var d = Object.getOwnPropertyDescriptor(Object(p), key);
        if (d) return d;
      } catch (e) {}
      try { p = Object.getPrototypeOf(Object(p)); } catch (e) { p = null; }
      if (p === Object.prototype) break;
      depth++;
    }
    return null;
  }
  function lookup(path) {
    var parts = path.split('.');
    var value = root;
    var via = 'descriptor';
    for (var i = 0; i < parts.length; i++) {
      if (value === null || typeof value === 'undefined') {
        return {state: 'missing', missingAt: i};
      }
      var d = descriptorOf(value, parts[i]);
      if (d) {
        if (d.get || d.set) {
          return {state: 'accessor', type: 'accessor', accessor: true, missingAt: -1};
        }
        value = d.value;
      } else {
        try {
          value = value[parts[i]];
          via = 'guarded_read';
        } catch (e) {
          return {state: 'error', error: String(e && e.message || e).slice(0, 300)};
        }
        if (typeof value === 'undefined') {
          return {state: 'missing', missingAt: i, via: via};
        }
      }
    }
    var result = {state: 'present', type: typeof value, missingAt: -1, via: via};
    if (typeof value === 'function') result.arity = value.length;
    return result;
  }
  var output = [];
  for (var n = 0; n < payload.length; n++) {
    var entry = payload[n];
    var result;
    if (entry.mode === 'classmethod') {
      result = lookup(entry.api.split('.')[0]);
      result.mode = 'classmethod';
      result.root = entry.api.split('.')[0];
    } else {
      result = lookup(entry.api);
      result.mode = 'member';
    }
    result.id = entry.id;
    output.push(result);
  }
  console.log(__MARKER__ + JSON.stringify({chunk: __CHUNK__, records: output}));
}).call(this);
""".replace("__PAYLOAD__", body).replace("__MARKER__", marker_literal).replace("__CHUNK__", str(int(chunk_index)))


def extract_marker(logs: Any, marker: str) -> tuple[dict[str, Any] | None, str | None]:
    """Return the last parseable marker payload, ignoring terminal control noise."""
    texts: list[str] = []
    if isinstance(logs, str):
        texts = [logs]
    elif isinstance(logs, list):
        for item in logs:
            if isinstance(item, dict):
                texts.append(str(item.get("content", "")))
            else:
                texts.append(str(item))
    candidates: list[str] = []
    for text in texts:
        cleaned = ANSI_ESCAPE.sub("", text.replace("\x1e", "").replace("\r", ""))
        start = 0
        while True:
            position = cleaned.find(marker, start)
            if position < 0:
                break
            candidates.append(cleaned[position + len(marker):])
            start = position + len(marker)
    decoder = json.JSONDecoder()
    last_error: str | None = None
    for tail in reversed(candidates):
        try:
            value, _ = decoder.raw_decode(tail.lstrip())
        except json.JSONDecodeError as error:
            last_error = str(error)
            continue
        if isinstance(value, dict):
            return value, None
        last_error = "marker payload is not an object"
    if last_error:
        return None, "marker result not parseable: " + last_error
    return None, "completion result not observed"


# ---------------------------------------------------------------------------
# Device execution wrapper
# ---------------------------------------------------------------------------


def load_device_audit_class() -> type:
    if not DEFAULT_AUDIT_UTILITY.is_file():
        raise RuntimeError("missing audit utility: " + str(DEFAULT_AUDIT_UTILITY))
    spec = importlib.util.spec_from_file_location("pro_surface_device_audit", DEFAULT_AUDIT_UTILITY)
    if spec is None or spec.loader is None:
        raise RuntimeError("cannot load DeviceAudit from " + str(DEFAULT_AUDIT_UTILITY))
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module.DeviceAudit


class SurfaceAudit:
    """Small wrapper around the existing DeviceAudit class.

    DeviceAudit persists every exact request/response pair in
    ``mcp-responses.json``. This wrapper adds no retries.
    """

    def __init__(self, serial: str, output: Path):
        self.output = Path(output)
        self.output.mkdir(parents=True, exist_ok=True)
        self.device = load_device_audit_class()(serial, self.output)
        self._closed = False

    def call(self, tool_name: str, **arguments: Any) -> Any:
        return self.device.call(tool_name, **arguments)

    def close(self) -> None:
        if self._closed:
            return
        self._closed = True
        self.device.close()


def logs_text(data: dict[str, Any]) -> str:
    rows = data.get("logs")
    if not isinstance(rows, list):
        return ""
    return "\n".join(str(row.get("content", "")) for row in rows if isinstance(row, dict))


def error_text(value: Any) -> str | None:
    if value is None or value == "":
        return None
    if isinstance(value, (dict, list)):
        return json.dumps(value, ensure_ascii=False)
    return str(value)


def execute_v1_chunk(surface: SurfaceAudit, engine: str, chunk_index: int, records: list[dict[str, Any]]) -> dict[str, Any]:
    marker = f"{MARKER_PREFIX}{engine}_{chunk_index:02d}="
    source = make_v1_chunk_source(records, marker, chunk_index)
    label = f"v1-{engine}-chunk-{chunk_index:02d}"
    execution: dict[str, Any] = {
        "label": label,
        "engine": engine,
        "chunk": chunk_index,
        "marker": marker,
        "entryCount": len(records),
        "sourceSha256": hashlib.sha256(source.encode("utf8")).hexdigest(),
        "status": "CALL_ERROR",
        "error": None,
        "parseError": None,
        "executionId": None,
        "stopRequested": False,
        "logFile": label + ".log",
    }
    try:
        data = surface.call("run_script", engine=engine, source=source, wait=True, timeoutSeconds=90)
    except Exception as error:  # DeviceAudit has already persisted the raw failure.
        execution["error"] = str(error)
        (surface.output / (label + ".execution.json")).write_text(
            json.dumps(execution, ensure_ascii=False, indent=2), encoding="utf8"
        )
        return {"execution": execution, "outcomes": {}, "error": str(error)}

    if not isinstance(data, dict):
        execution["error"] = "run_script did not return an object"
        (surface.output / (label + ".execution.json")).write_text(
            json.dumps(execution, ensure_ascii=False, indent=2), encoding="utf8"
        )
        return {"execution": execution, "outcomes": {}, "error": execution["error"]}

    execution["executionId"] = data.get("executionId")
    status = data.get("status")
    if status in ACTIVE_STATES:
        try:
            data = surface.call(
                "wait_execution",
                executionId=data.get("executionId"),
                timeoutSeconds=90,
            )
            if not isinstance(data, dict):
                execution["status"] = str(status)
                execution["error"] = "wait_execution did not return an object"
                (surface.output / (label + ".execution.json")).write_text(
                    json.dumps(execution, ensure_ascii=False, indent=2), encoding="utf8"
                )
                return {"execution": execution, "outcomes": {}, "error": execution["error"]}
            execution["executionId"] = data.get("executionId", execution["executionId"])
            status = data.get("status")
        except Exception as error:
            execution["status"] = str(status)
            execution["error"] = "wait_execution failed: " + str(error)
            (surface.output / (label + ".execution.json")).write_text(
                json.dumps(execution, ensure_ascii=False, indent=2), encoding="utf8"
            )
            return {"execution": execution, "outcomes": {}, "error": execution["error"]}

    execution["status"] = status or "UNKNOWN"
    execution["error"] = error_text(data.get("error"))
    text = logs_text(data)
    (surface.output / (label + ".log")).write_text(text, encoding="utf8")

    if status in ACTIVE_STATES and data.get("executionId"):
        execution["stopRequested"] = True
        try:
            surface.call("stop_script", executionId=data.get("executionId"))
        except Exception as error:
            execution["error"] = (execution["error"] + "; " if execution["error"] else "") + "stop_script failed: " + str(error)

    payload, parse_error = extract_marker(text, marker)
    execution["parseError"] = parse_error
    (surface.output / (label + ".execution.json")).write_text(
        json.dumps(execution, ensure_ascii=False, indent=2), encoding="utf8"
    )

    if status != "SUCCEEDED":
        return {
            "execution": execution,
            "outcomes": {},
            "error": execution["error"] or ("execution status " + str(status)),
        }
    if parse_error or payload is None:
        return {"execution": execution, "outcomes": {}, "error": parse_error or "marker payload absent"}
    if payload.get("chunk") != chunk_index:
        return {"execution": execution, "outcomes": {}, "error": "chunk marker mismatch"}
    rows = payload.get("records")
    if not isinstance(rows, list):
        return {"execution": execution, "outcomes": {}, "error": "chunk payload has no records"}
    outcomes = {
        str(row.get("id")): row
        for row in rows
        if isinstance(row, dict) and row.get("id")
    }
    return {"execution": execution, "outcomes": outcomes, "error": None}


# ---------------------------------------------------------------------------
# MCP list_engine_api queries
# ---------------------------------------------------------------------------


def classify_module_error(message: str) -> str:
    return "missing_module" if MISSING_MODULE_HINTS.search(message) else "query_error"


def query_engine_globals(surface: SurfaceAudit, engine: str) -> dict[str, Any]:
    try:
        data = surface.call("list_engine_api", engine=engine)
    except Exception as error:
        return {
            "queryState": "error",
            "count": 0,
            "keys": [],
            "modules": [],
            "error": str(error),
            "moduleCatalogAvailable": False,
        }
    if not isinstance(data, dict):
        return {
            "queryState": "error",
            "count": 0,
            "keys": [],
            "modules": [],
            "error": "list_engine_api did not return an object",
            "moduleCatalogAvailable": False,
        }
    items = data.get("items")
    if not isinstance(items, list):
        return {
            "queryState": "error",
            "count": 0,
            "keys": [],
            "modules": [],
            "error": "list_engine_api did not return an items array",
            "moduleCatalogAvailable": False,
        }
    keys = [str(item) for item in items]
    modules = data.get("modules") if isinstance(data.get("modules"), list) else []
    return {
        "queryState": "present",
        "count": len(keys),
        "keys": keys,
        "modules": [str(item) for item in modules],
        "error": None,
        "moduleCatalogAvailable": True,
    }


def query_module_exports(surface: SurfaceAudit, engine: str, module: str) -> dict[str, Any]:
    try:
        data = surface.call("list_engine_api", engine=engine, module=module)
    except Exception as error:
        message = str(error)
        return {
            "queryState": classify_module_error(message),
            "count": 0,
            "exports": [],
            "error": message,
        }
    if not isinstance(data, dict):
        return {
            "queryState": "query_error",
            "count": 0,
            "exports": [],
            "error": "list_engine_api did not return an object",
        }
    items = data.get("items")
    if not isinstance(items, list):
        return {
            "queryState": "query_error",
            "count": 0,
            "exports": [],
            "error": "list_engine_api did not return an items array",
        }
    exports = [str(item) for item in items]
    return {"queryState": "present", "count": len(exports), "exports": exports, "error": None}


# ---------------------------------------------------------------------------
# Record classification
# ---------------------------------------------------------------------------


def classify_v1_record(
    record: dict[str, Any],
    outcome: dict[str, Any] | None,
    chunk_index: int | None,
    invalid_mapping: bool,
    chunk_error: str | None,
) -> dict[str, Any]:
    result = {
        "id": str(record["id"]),
        "layer": "v1-csv",
        "api": str(record.get("api", "")),
        "module": str(record.get("module", "")),
        "profile": str(record.get("profile", "")),
        "kind": str(record.get("kind", "")),
        "declared": bool(record.get("declared", False)),
        "surfaceState": None,
        "type": None,
        "error": None,
        "exports": None,
        "evidence": {"kind": "v1_script", "chunk": chunk_index, "label": None if chunk_index is None else f"v1-chunk-{chunk_index:02d}"},
    }
    if not result["declared"]:
        result["surfaceState"] = "excluded_label"
        result["evidence"] = {"kind": "contract_metadata"}
        return result
    if invalid_mapping:
        result["surfaceState"] = "invalid_mapping"
        result["error"] = "invalid API identifier path"
        result["evidence"] = {"kind": "preflight"}
        return result
    if result["profile"] == "classmethod":
        result["surfaceState"] = "instance_unverified"
        result["instanceFixtureProvided"] = False
        result["rootState"] = "unprobed"
        result["rootType"] = None
        if outcome:
            result["rootState"] = outcome.get("state")
            result["rootType"] = outcome.get("type")
            result["root"] = outcome.get("root")
            if outcome.get("state") == "error":
                result["error"] = outcome.get("error")
            elif outcome.get("state") == "missing":
                result["rootState"] = "missing"
        if chunk_error:
            result["error"] = chunk_error
            result["probeError"] = chunk_error
        return result
    if outcome is None:
        result["surfaceState"] = "probe_failed"
        result["error"] = chunk_error or "no result returned for declared v1 entry"
        return result
    state = outcome.get("state")
    if state == "missing":
        result["surfaceState"] = "missing"
        result["missingAt"] = outcome.get("missingAt")
    elif state == "accessor":
        result["surfaceState"] = "present_type_only"
        result["type"] = "accessor"
        result["accessorNotInvoked"] = True
    elif state == "error":
        result["surfaceState"] = "probe_error"
        result["error"] = str(outcome.get("error", "property lookup failed"))
    elif state == "present":
        value_type = outcome.get("type")
        result["type"] = value_type
        result["surfaceState"] = "undefined_value" if value_type == "undefined" else "present_type_only"
        if outcome.get("arity") is not None:
            result["arity"] = outcome.get("arity")
    else:
        result["surfaceState"] = "probe_error"
        result["error"] = "unexpected lookup state: " + str(state)
    if outcome.get("missingAt") is not None and result["surfaceState"] != "missing":
        result["missingAt"] = outcome.get("missingAt")
    if outcome.get("via"):
        result["lookupVia"] = outcome.get("via")
    return result


def classify_v2_record(
    record: dict[str, Any],
    module_state: dict[str, Any],
    top_kind: str | None,
    export_set: set[str],
) -> dict[str, Any]:
    module = str(record.get("module", ""))
    result = {
        "id": str(record["id"]),
        "layer": "pro-v2-dts",
        "api": str(record.get("api", "")),
        "module": module,
        "profile": str(record.get("profile", "")),
        "kind": str(record.get("kind", "")),
        "declared": bool(record.get("declared", False)),
        "surfaceState": None,
        "type": None,
        "error": None,
        "exports": None,
        "moduleExport": None,
        "moduleExportPresent": None,
        "moduleExportKind": top_kind,
        "evidence": {"kind": "module_query", "module": module},
    }
    if module == "$autojs":
        result["surfaceState"] = "excluded_global_surface"
        result["evidence"] = {"kind": "excluded_global_surface"}
        return result

    query_state = module_state.get("queryState")
    if query_state == "missing_module":
        result["surfaceState"] = "missing_module"
        result["error"] = module_state.get("error")
        return result
    if query_state != "present":
        result["surfaceState"] = "query_error"
        result["error"] = module_state.get("error") or "module query did not succeed"
        return result

    rel = relative_v2_path(record)
    if record.get("kind") == "module":
        result["surfaceState"] = "module_present_shape_only"
        result["type"] = "module"
        result["exports"] = list(module_state.get("exports") or [])
        return result
    if not rel or not record.get("api", "").startswith(module + "."):
        result["surfaceState"] = "invalid_mapping"
        result["error"] = "v2 API path is outside its module prefix"
        return result

    parts = rel.split(".")
    top = parts[0]
    result["moduleExport"] = top
    present = top in export_set
    result["moduleExportPresent"] = present

    if record.get("kind") in DECLARATION_KINDS or top_kind in DECLARATION_KINDS:
        # Interfaces and type aliases are erased at runtime. A name in the
        # export-key set is not evidence that an interface exists at runtime.
        result["surfaceState"] = "declaration_unverified"
        result["typeSource"] = "declaration_only"
    elif len(parts) > 1:
        if present:
            result["surfaceState"] = "instance_unverified"
            result["typeSource"] = "instance_not_created"
            result["instanceFixtureProvided"] = False
        else:
            result["surfaceState"] = "export_not_in_shape"
            result["typeSource"] = "module_export_key_absent"
    else:
        if present:
            result["surfaceState"] = "export_present_shape_only"
            result["typeSource"] = "list_engine_api_keys_only"
        else:
            result["surfaceState"] = "export_not_in_shape"
            result["typeSource"] = "module_export_key_absent"
    return result


def classify_v1_records(
    facts: dict[str, Any],
    outcomes: dict[str, dict[str, Any]],
    chunk_errors: dict[int, str],
    invalid_ids: set[str] | None = None,
) -> dict[str, dict[str, Any]]:
    invalid_ids = invalid_ids or set()
    results: dict[str, dict[str, Any]] = {}
    for record in facts["v1"]:
        record_id = str(record["id"])
        if record.get("declared", False) and record.get("profile") in {"member", "classmethod"}:
            chunk_index = facts["v1_chunk_for_id"].get(record_id)
        else:
            chunk_index = None
        results[record_id] = classify_v1_record(
            record,
            outcomes.get(record_id),
            chunk_index,
            record_id in invalid_ids,
            chunk_errors.get(chunk_index) if chunk_index is not None else None,
        )
    return results


def classify_v2_records(
    facts: dict[str, Any],
    module_states: dict[str, dict[str, Any]],
) -> dict[str, dict[str, Any]]:
    results: dict[str, dict[str, Any]] = {}
    for record in facts["v2"]:
        module = str(record.get("module", ""))
        state = module_states.get(module, {
            "queryState": "query_error",
            "exports": [],
            "error": "module was not queried",
        })
        export_set = set(state.get("exports") or [])
        top = relative_v2_path(record).split(".", 1)[0] if relative_v2_path(record) else None
        top_kind = facts["top_kind"].get((module, top)) if top else None
        results[str(record["id"])] = classify_v2_record(record, state, top_kind, export_set)
    return results


# ---------------------------------------------------------------------------
# Per-engine document assembly
# ---------------------------------------------------------------------------


def build_engine_document(
    facts: dict[str, Any],
    engine: str,
    contract_path: Path,
    contract_sha: str | None,
    globals_state: dict[str, Any],
    module_states: dict[str, dict[str, Any]],
    v1_outcomes: dict[str, dict[str, Any]],
    chunk_errors: dict[int, str],
    chunk_executions: list[dict[str, Any]],
    invalid_ids: set[str] | None = None,
    engine_error: str | None = None,
) -> dict[str, Any]:
    v1_results = classify_v1_records(facts, v1_outcomes, chunk_errors, invalid_ids)
    v2_results = classify_v2_records(facts, module_states)
    records = {**v1_results, **v2_results}
    missing_ids = [record_id for record_id in facts["ids"] if record_id not in records]
    unexpected_ids = [record_id for record_id in records if record_id not in facts["by_id"]]
    states = collections.Counter(str(item["surfaceState"]) for item in records.values())
    v1_states = collections.Counter(item["surfaceState"] for item in v1_results.values())
    v2_states = collections.Counter(item["surfaceState"] for item in v2_results.values())
    module_query_states = collections.Counter(state.get("queryState") for state in module_states.values())
    classmethods = [item for item in v1_results.values() if item["profile"] == "classmethod"]
    classmethod_unverified = sum(item["surfaceState"] == "instance_unverified" for item in classmethods)
    v1_labels = [item for item in v1_results.values() if not item["declared"]]
    v2_autojs = [item for item in v2_results.values() if item["module"] == "$autojs"]
    declaration_states = {"declaration_unverified", "missing_module", "query_error", "invalid_mapping", "excluded_global_surface"}
    declaration_records = [
        item for item in v2_results.values()
        if item["kind"] in DECLARATION_KINDS
    ]
    fake_interface_coverage = [
        item["id"] for item in declaration_records
        if item["surfaceState"] not in declaration_states
    ]

    chunk_failures = [
        {
            "chunk": execution.get("chunk"),
            "label": execution.get("label"),
            "status": execution.get("status"),
            "error": execution.get("error") or execution.get("parseError"),
        }
        for execution in chunk_executions
        if execution.get("status") != "SUCCEEDED" or execution.get("parseError")
    ]
    module_errors = [name for name, state in module_states.items() if state.get("queryState") == "query_error"]
    missing_modules = [name for name, state in module_states.items() if state.get("queryState") == "missing_module"]
    coverage_complete = (
        not missing_ids
        and not unexpected_ids
        and len(records) == len(facts["records"])
    )

    document = {
        "schemaVersion": SCHEMA_VERSION,
        "generatedAt": utc_now(),
        "engine": engine,
        "contract": {
            "path": str(contract_path.relative_to(ROOT)) if contract_path.is_relative_to(ROOT) else str(contract_path),
            "sha256": contract_sha,
            "counts": facts["contract"].get("counts") or {},
        },
        "globals": globals_state,
        "moduleExports": {
            module: {
                "queryState": module_states.get(module, {}).get("queryState"),
                "count": module_states.get(module, {}).get("count", 0),
                "exports": list(module_states.get(module, {}).get("exports") or []),
                "error": module_states.get(module, {}).get("error"),
            }
            for module in facts["modules"]
        },
        "chunks": chunk_executions,
        "records": records,
        "summary": {
            "accountedRecords": len(records),
            "contractRecords": len(facts["records"]),
            "states": dict(sorted(states.items())),
            "v1": {
                "records": len(v1_results),
                "declared": len(facts["v1_declared"]),
                "labelsExcluded": len(v1_labels),
                "byProfile": dict(sorted(facts["v1_profiles"].items())),
                "classmethodsInstanceUnverified": classmethod_unverified,
                "states": dict(sorted(v1_states.items())),
            },
            "v2": {
                "records": len(v2_results),
                "globalSurfaceRecordsExcluded": len(v2_autojs),
                "moduleQueryStates": dict(sorted(module_query_states.items())),
                "states": dict(sorted(v2_states.items())),
                "declarationRecords": len(declaration_records),
                "declarationFakeCoverage": len(fake_interface_coverage),
            },
            "coverage": {
                "complete": coverage_complete,
                "missingRecordIds": missing_ids,
                "unexpectedRecordIds": unexpected_ids,
                "v1Declared": len(facts["v1_declared"]),
                "v1DeclaredInChunks": sum(len(chunk) for chunk in facts["v1_chunks"]),
                "v1ChunkCount": len(facts["v1_chunks"]),
                "v1ChunkSizeLimit": CHUNK_SIZE,
                "v1InvalidMappings": len(facts["v1_invalid"]),
                "v1LabelsExcluded": len(facts["v1_labels"]),
                "v2ModulesQueried": len(facts["modules"]),
                "v2GlobalRecordsExcluded": len(facts["v2_autojs"]),
                "globalQueries": 1,
            },
            "operational": {
                "engineError": engine_error,
                "globalQueryState": globals_state.get("queryState"),
                "moduleQueryErrors": sorted(module_errors),
                "missingModules": sorted(missing_modules),
                "chunkFailures": chunk_failures,
            },
            "claims": CLAIMS,
        },
        "limitations": LIMITATIONS,
    }
    return document


def fallback_engine_document(
    facts: dict[str, Any],
    engine: str,
    contract_path: Path,
    contract_sha: str | None,
    error: str,
) -> dict[str, Any]:
    modules = {
        module: {
            "queryState": "query_error",
            "count": 0,
            "exports": [],
            "error": error,
        }
        for module in facts["modules"]
    }
    return build_engine_document(
        facts,
        engine,
        contract_path,
        contract_sha,
        {"queryState": "error", "count": 0, "keys": [], "modules": [], "error": error},
        modules,
        {},
        {},
        [],
        engine_error=error,
    )


# ---------------------------------------------------------------------------
# Engine run
# ---------------------------------------------------------------------------


def audit_engine(
    surface: SurfaceAudit,
    facts: dict[str, Any],
    engine: str,
    contract_path: Path,
    contract_sha: str | None,
) -> dict[str, Any]:
    globals_state = query_engine_globals(surface, engine)
    module_states = {
        module: query_module_exports(surface, engine, module)
        for module in facts["modules"]
    }
    invalid_ids = {str(record["id"]) for record in facts["v1_invalid"]}
    v1_outcomes: dict[str, dict[str, Any]] = {}
    chunk_errors: dict[int, str] = {}
    chunk_executions: list[dict[str, Any]] = []
    for chunk_index, records in enumerate(facts["v1_chunks"]):
        result = execute_v1_chunk(surface, engine, chunk_index, records)
        chunk_executions.append(result["execution"])
        if result["error"]:
            chunk_errors[chunk_index] = result["error"]
        v1_outcomes.update(result["outcomes"])
    for record in facts["v1_declared"]:
        record_id = str(record["id"])
        if record_id not in facts["v1_chunk_for_id"]:
            continue
        if record_id not in v1_outcomes and record_id not in invalid_ids:
            chunk_index = facts["v1_chunk_for_id"][record_id]
            # Only members can be "failed"; class methods remain intentionally
            # instance_unverified even when the root lookup chunk failed.
            if record.get("profile") == "member":
                chunk_errors.setdefault(chunk_index, "chunk result missing for declared v1 entry")
    return build_engine_document(
        facts,
        engine,
        contract_path,
        contract_sha,
        globals_state,
        module_states,
        v1_outcomes,
        chunk_errors,
        chunk_executions,
        invalid_ids,
    )


# ---------------------------------------------------------------------------
# Output
# ---------------------------------------------------------------------------


def write_json(path: Path, value: Any) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf8")


def build_overall_summary(
    facts: dict[str, Any],
    documents: dict[str, dict[str, Any]],
    serial: str | None,
    contract_path: Path,
    contract_sha: str | None,
    raw_sha: str | None,
    close_error: str | None,
    started_at: str,
) -> dict[str, Any]:
    per_engine: dict[str, Any] = {}
    all_complete = True
    operational_failures: list[str] = []
    for engine, document in documents.items():
        summary = document["summary"]
        per_engine[engine] = {
            "path": f"surface-{engine}.json",
            "contractRecords": summary["contractRecords"],
            "accountedRecords": summary["accountedRecords"],
            "coverageComplete": summary["coverage"]["complete"],
            "states": summary["states"],
            "v1": summary["v1"],
            "v2": summary["v2"],
            "operational": summary["operational"],
        }
        all_complete = all_complete and summary["coverage"]["complete"]
        operational = summary["operational"]
        if operational.get("engineError"):
            operational_failures.append(f"{engine}: engine error: {operational['engineError']}")
        if operational.get("globalQueryState") != "present":
            operational_failures.append(f"{engine}: global query state is {operational.get('globalQueryState')}")
        for item in operational.get("chunkFailures") or []:
            operational_failures.append(f"{engine}: chunk {item.get('label')} not SUCCEEDED ({item.get('status')}): {item.get('error')}")
        for module in operational.get("moduleQueryErrors") or []:
            operational_failures.append(f"{engine}: module query error: {module}")
    return {
        "schemaVersion": SCHEMA_VERSION,
        "generatedAt": utc_now(),
        "startedAt": started_at,
        "serial": serial,
        "contract": {
            "path": str(contract_path.relative_to(ROOT)) if contract_path.is_relative_to(ROOT) else str(contract_path),
            "sha256": contract_sha,
            "counts": facts["contract"].get("counts") or {},
        },
        "engines": list(documents),
        "rawEvidence": {
            "path": "mcp-responses.json",
            "sha256": raw_sha,
            "description": "Exact MCP request/response pairs persisted by DeviceAudit, including failed calls.",
        },
        "perEngine": per_engine,
        "files": {
            "perEngine": {engine: f"surface-{engine}.json" for engine in documents},
            "summary": "surface-summary.json",
        },
        "coverage": {
            "complete": all_complete and bool(documents),
            "contractRecords": len(facts["records"]),
            "v1": {
                "records": len(facts["v1"]),
                "declared": len(facts["v1_declared"]),
                "labelsExcluded": len(facts["v1_labels"]),
                "chunks": len(facts["v1_chunks"]),
                "chunkSizeLimit": CHUNK_SIZE,
                "declaredInChunks": sum(len(chunk) for chunk in facts["v1_chunks"]),
            },
            "v2": {
                "records": len(facts["v2"]),
                "modulesQueried": len(facts["modules"]),
                "globalSurfaceRecordsExcluded": len(facts["v2_autojs"]),
            },
        },
        "operationalFailures": operational_failures,
        "closeError": close_error,
        "claims": CLAIMS,
        "limitations": LIMITATIONS,
    }


# ---------------------------------------------------------------------------
# Offline self-test
# ---------------------------------------------------------------------------


def run_self_test(output: Path | None) -> int:
    """Exercise parser/coverage/classification fixtures without DeviceAudit."""
    output = Path(output) if output is not None else None
    if output is not None:
        output.mkdir(parents=True, exist_ok=True)
    checks: list[dict[str, Any]] = []
    failures: list[str] = []

    class NullWriter:
        def write_text(self, text: str, encoding: str | None = None) -> int:
            return 0

    class NullOutput:
        def __truediv__(self, name: str) -> NullWriter:
            return NullWriter()

    fixture_output = output if output is not None else NullOutput()

    def check(name: str, condition: Any, detail: Any = None) -> None:
        passed = bool(condition)
        checks.append({"name": name, "passed": passed, "detail": detail})
        if not passed:
            failures.append(name)

    contract = load_contract(DEFAULT_CONTRACT)
    facts = contract_facts(contract)
    contract_sha = sha256_file(DEFAULT_CONTRACT)

    check("contract records == 3980", len(facts["records"]) == 3980, len(facts["records"]))
    check("v1 records == 938", len(facts["v1"]) == 938, len(facts["v1"]))
    check("v1 declared == 737", len(facts["v1_declared"]) == 737, len(facts["v1_declared"]))
    check("v1 labels == 201", len(facts["v1_labels"]) == 201, len(facts["v1_labels"]))
    check("v1 classmethods == 149", facts["v1_profiles"].get("classmethod") == 149, facts["v1_profiles"].get("classmethod"))
    check("v1 members == 588", facts["v1_profiles"].get("member") == 588, facts["v1_profiles"].get("member"))
    check("v2 records == 3042", len(facts["v2"]) == 3042, len(facts["v2"]))
    check("v2 modules == 39", len(facts["modules"]) == 39, len(facts["modules"]))
    check("v2 module records == 39", len(facts["module_records"]) == 39, len(facts["module_records"]))
    check("v2 $autojs records == 9", len(facts["v2_autojs"]) == 9, len(facts["v2_autojs"]))
    check("all v1 declared chunks <= 100", all(len(chunk) <= CHUNK_SIZE for chunk in facts["v1_chunks"]))
    check("v1 chunks == 8", len(facts["v1_chunks"]) == 8, len(facts["v1_chunks"]))
    chunk_ids = [str(record["id"]) for chunk in facts["v1_chunks"] for record in chunk]
    check("all valid v1 declared entries chunked once", len(chunk_ids) == len(set(chunk_ids)) == len(facts["v1_entries"]))
    check("v1 labels excluded from chunks", not (set(str(record["id"]) for record in facts["v1_labels"]) & set(chunk_ids)))

    bad_record = {"id": "fixture-invalid", "api": "device.bad path", "profile": "member", "declared": True}
    check("invalid path rejected before probe", not valid_identifier_path(bad_record["api"]))
    check("dotted $ path accepted", valid_identifier_path("$ui.root.child"))
    check("module-not-found classified", classify_module_error("Cannot find module 'convert'") == "missing_module")
    check("missing require classified", classify_module_error("ReferenceError: require is not defined") == "missing_module")
    check("unrelated module exception not mislabeled", classify_module_error("MCP timeout") == "query_error")

    marker = "PRO_API_SURFACE_SELFTEST="
    marker_value = {"chunk": 0, "records": [{"id": "v1-0001", "state": "present", "type": "function"}]}
    parser_logs = [{"content": "noise\r\n\x1e" + marker + json.dumps(marker_value) + "\r\n"}]
    parsed, parse_error = extract_marker(parser_logs, marker)
    check("marker parser ignores control noise", parse_error is None and parsed == marker_value, parse_error)
    parsed_bad, parse_bad_error = extract_marker([{"content": marker + "not-json"}], marker)
    check("marker parser reports invalid JSON", parsed_bad is None and bool(parse_bad_error), parse_bad_error)
    parsed_last, _ = extract_marker([
        {"content": marker + json.dumps({"chunk": 1, "records": []})},
        {"content": marker + json.dumps(marker_value)},
    ], marker)
    check("marker parser uses last parseable payload", parsed_last == marker_value)

    # v1 classification fixture: every declared record gets a state, class
    # methods never become missing, labels never enter lookup outcomes.
    v1_outcomes: dict[str, dict[str, Any]] = {}
    member_index = 0
    for record in facts["v1_declared"]:
        if record.get("profile") == "classmethod":
            v1_outcomes[str(record["id"])] = {"state": "present", "type": "function", "root": str(record["api"]).split(".")[0]}
        else:
            member_index += 1
            if member_index % 3 == 0:
                v1_outcomes[str(record["id"])] = {"state": "missing", "missingAt": 1}
            else:
                v1_outcomes[str(record["id"])] = {"state": "present", "type": "object" if member_index % 2 else "function", "arity": 0}
    v1_results = classify_v1_records(facts, v1_outcomes, {}, set())
    check("v1 fixture accounts all records", len(v1_results) == len(facts["v1"]) == 938, len(v1_results))
    check("v1 labels are excluded_label", all(item["surfaceState"] == "excluded_label" for item in v1_results.values() if not item["declared"]))
    cm_results = [item for item in v1_results.values() if item["profile"] == "classmethod"]
    check("classmethods are instance_unverified", all(item["surfaceState"] == "instance_unverified" for item in cm_results))
    check("classmethods are never labeled missing", not any(item["surfaceState"] == "missing" for item in cm_results))
    member_results = [item for item in v1_results.values() if item["profile"] == "member"]
    check("member states account for 588 records", sum(1 for item in member_results if item["surfaceState"] in {"present_type_only", "missing", "undefined_value", "probe_error", "probe_failed"}) == 588)

    bad_v1 = classify_v1_record(
        {"id": "fixture-invalid", "api": "device.bad path", "profile": "member", "kind": "function", "declared": True},
        {"state": "present", "type": "object"},
        0,
        True,
        None,
    )
    check("invalid v1 mapping is explicit", bad_v1["surfaceState"] == "invalid_mapping" and bool(bad_v1["error"]))

    # v2 fixture: all module keys are present except convert (missing). This
    # checks shape-only coverage, nested member unverified states and type-only
    # declaration handling without claiming interface runtime coverage.
    module_states: dict[str, dict[str, Any]] = {}
    for module in facts["modules"]:
        exports = sorted({
            relative_v2_path(record).split(".", 1)[0]
            for record in facts["v2"]
            if record.get("module") == module and relative_v2_path(record)
        })
        module_states[module] = {"queryState": "present", "count": len(exports), "exports": exports, "error": None}
    module_states["convert"] = {"queryState": "missing_module", "count": 0, "exports": [], "error": "Cannot find module 'convert'"}
    globals_state = {"queryState": "present", "count": 3, "keys": ["$autojs", "device", "ui"], "modules": [], "error": None}
    document = build_engine_document(
        facts,
        "quickjs",
        DEFAULT_CONTRACT,
        contract_sha,
        globals_state,
        module_states,
        v1_outcomes,
        {},
        [],
        set(),
    )
    check("fixture document accounts all 3980 records", len(document["records"]) == 3980, len(document["records"]))
    check("fixture document has no missing ids", not document["summary"]["coverage"]["missingRecordIds"])
    check("fixture document state counts sum to 3980", sum(document["summary"]["states"].values()) == 3980)
    check("fixture document pins contract sha", contract_sha is not None and document["contract"]["sha256"] == contract_sha)
    check("fixture v2 module count is 39", document["summary"]["coverage"]["v2ModulesQueried"] == 39)
    check("fixture $autojs records excluded", all(
        item["surfaceState"] == "excluded_global_surface"
        for item in document["records"].values()
        if item.get("module") == "$autojs"
    ))
    check("fixture convert module is missing_module", document["moduleExports"]["convert"]["queryState"] == "missing_module")
    check("fixture convert records are missing_module", all(
        item["surfaceState"] == "missing_module"
        for item in document["records"].values()
        if item.get("module") == "convert"
    ))
    check("fixture classmethods remain instance_unverified", document["summary"]["v1"]["classmethodsInstanceUnverified"] == 149)
    check("fixture no fake interface runtime coverage", document["summary"]["v2"]["declarationFakeCoverage"] == 0)
    check("fixture v2 has nested instance_unverified", any(
        item["surfaceState"] == "instance_unverified" and item["layer"] == "pro-v2-dts"
        for item in document["records"].values()
    ))
    check("fixture v2 has shape-only exports", any(
        item["surfaceState"] == "export_present_shape_only"
        for item in document["records"].values()
    ))

    interface_results = [
        item for item in document["records"].values()
        if item["kind"] in DECLARATION_KINDS
    ]
    check("fixture interface/type declarations are unverified", all(
        item["surfaceState"] in {"declaration_unverified", "missing_module", "query_error", "invalid_mapping", "excluded_global_surface"}
        for item in interface_results
    ))
    check("fixture coverage remains complete with missing module", document["summary"]["coverage"]["complete"] is True)

    # End-to-end orchestration fixture with a fake surface: verifies one global
    # query, exactly 39 module queries, eight one-shot v1 script chunks, no
    # retries, and complete record accounting without ADB or MCP.
    fake_exports = {
        module: sorted({
            relative_v2_path(record).split(".", 1)[0]
            for record in facts["v2"]
            if record.get("module") == module and relative_v2_path(record)
        })
        for module in facts["modules"]
    }

    class FakeSurface:
        def __init__(self, output_dir: Path):
            self.output = output_dir
            self.global_calls = 0
            self.module_calls: list[str] = []
            self.script_calls: list[tuple[str, list[dict[str, Any]]]] = []
            self.wait_calls = 0

        def call(self, tool_name: str, **arguments: Any) -> Any:
            if tool_name == "list_engine_api":
                if "module" in arguments:
                    module = str(arguments["module"])
                    self.module_calls.append(module)
                    if module == "convert":
                        raise RuntimeError("Cannot find module 'convert'")
                    return {
                        "engine": "quickjs",
                        "module": module,
                        "items": fake_exports[module],
                        "count": len(fake_exports[module]),
                        "modules": [],
                    }
                self.global_calls += 1
                return {
                    "engine": "quickjs",
                    "items": ["$autojs", "device", "ui"],
                    "count": 3,
                    "modules": ["fake_module_catalog"],
                }
            if tool_name == "run_script":
                source = str(arguments.get("source", ""))
                payload_text = source.split("var payload = ", 1)[1].split(";\n  function descriptorOf", 1)[0]
                payload = json.loads(payload_text)
                marker_match = re.search(r"PRO_API_SURFACE_V1_RESULT_[a-z]+_\d+=", source)
                chunk_match = re.search(r"\{chunk: (\d+), records:", source)
                if marker_match is None or chunk_match is None:
                    raise AssertionError("fake surface could not parse chunk source")
                marker = marker_match.group(0)
                chunk_index = int(chunk_match.group(1))
                rows = []
                for entry in payload:
                    row = {
                        "id": entry["id"],
                        "state": "present",
                        "type": "function" if entry["mode"] == "classmethod" else "object",
                    }
                    if entry["mode"] == "classmethod":
                        row["root"] = entry["api"].split(".")[0]
                    rows.append(row)
                self.script_calls.append((source, payload))
                return {
                    "executionId": f"fake-{chunk_index}",
                    "status": "SUCCEEDED",
                    "logs": [{"content": marker + json.dumps({"chunk": chunk_index, "records": rows})}],
                }
            if tool_name == "wait_execution":
                self.wait_calls += 1
                raise AssertionError("self-test must not wait/retry an execution")
            raise AssertionError("unexpected fake MCP tool: " + tool_name)

    fake = FakeSurface(fixture_output)
    orchestrated = audit_engine(fake, facts, "quickjs", DEFAULT_CONTRACT, contract_sha)
    check("orchestration queries globals exactly once", fake.global_calls == 1, fake.global_calls)
    check("orchestration queries 39 modules", len(fake.module_calls) == 39, len(fake.module_calls))
    check("orchestration excludes $autojs module query", "$autojs" not in fake.module_calls)
    check("orchestration runs exactly eight v1 chunks", len(fake.script_calls) == 8, len(fake.script_calls))
    check("orchestration makes no wait/retry calls", fake.wait_calls == 0, fake.wait_calls)
    check("orchestration sends all 737 declared entries", sum(len(payload) for _, payload in fake.script_calls) == 737)
    check("orchestration chunks are <= 100 entries", all(len(payload) <= CHUNK_SIZE for _, payload in fake.script_calls))
    check("orchestration accounts all 3980 records", len(orchestrated["records"]) == 3980, len(orchestrated["records"]))
    check("orchestration marks missing module", orchestrated["moduleExports"]["convert"]["queryState"] == "missing_module")
    check("orchestration keeps classmethods unverified", orchestrated["summary"]["v1"]["classmethodsInstanceUnverified"] == 149)
    check("orchestration coverage complete", orchestrated["summary"]["coverage"]["complete"] is True)
    check("orchestration no fake interface coverage", orchestrated["summary"]["v2"]["declarationFakeCoverage"] == 0)
    try:
        json.dumps(orchestrated, ensure_ascii=False)
        orchestrated_serializable = True
    except (TypeError, ValueError):
        orchestrated_serializable = False
    check("orchestration document is JSON serializable", orchestrated_serializable)
    subset_overall = build_overall_summary(
        facts,
        {"quickjs": orchestrated},
        "fake-serial",
        DEFAULT_CONTRACT,
        contract_sha,
        None,
        None,
        utc_now(),
    )
    check("selected-engine summary coverage complete", subset_overall["coverage"]["complete"] is True)

    # A failed chunk must not turn a member into a supported/present API.
    failed_member = classify_v1_record(
        {"id": "v1-fixture", "api": "device.width", "profile": "member", "kind": "function", "declared": True},
        None,
        0,
        False,
        "execution status FAILED",
    )
    check("failed v1 chunk is probe_failed", failed_member["surfaceState"] == "probe_failed")
    failed_classmethod = classify_v1_record(
        {"id": "v1-fixture-cm", "api": "UiSelector.text", "profile": "classmethod", "kind": "function", "declared": True},
        None,
        0,
        False,
        "execution status FAILED",
    )
    check("failed classmethod still instance_unverified", failed_classmethod["surfaceState"] == "instance_unverified")

    parser = build_parser()
    parser_args = ["--self-test"] + (["--output", str(output)] if output is not None else [])
    parsed_args = parser.parse_args(parser_args)
    check("self-test parser does not require serial", parsed_args.serial is None)
    check("self-test parser accepts output", parsed_args.output == output)

    result = {
        "ok": not failures,
        "generatedAt": utc_now(),
        "contract": {
            "path": str(DEFAULT_CONTRACT.relative_to(ROOT)),
            "sha256": contract_sha,
            "records": len(facts["records"]),
            "v1Declared": len(facts["v1_declared"]),
            "v2Modules": len(facts["modules"]),
        },
        "checks": checks,
        "failures": failures,
        "fixtureSummary": document["summary"],
        "note": "Offline fixture only. No device, ADB, network, script execution, or compatibility claim.",
    }
    result_path = None
    if output is not None:
        write_json(output / "self-test.json", result)
        result_path = str(output / "self-test.json")
    print(json.dumps({
        "selfTest": "PASS" if not failures else "FAIL",
        "checks": len(checks),
        "failures": failures,
        "output": result_path,
        "note": "No device, ADB, network, or script execution was used.",
    }, ensure_ascii=False, indent=2))
    return 0 if not failures else 1


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("--serial", help="device serial (required unless --self-test)")
    parser.add_argument("--output", type=Path, help="output directory (required for inventory; optional for --self-test)")
    parser.add_argument(
        "--engines",
        nargs="+",
        choices=list(ENGINES),
        default=list(ENGINES),
        help="engines to inventory; default: all three",
    )
    parser.add_argument(
        "--self-test",
        action="store_true",
        help="run offline parser/coverage fixtures; does not contact ADB or MCP",
    )
    return parser


def normalize_engines(values: list[str]) -> list[str]:
    selected = set(values)
    return [engine for engine in ENGINES if engine in selected]


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)

    if args.self_test:
        return run_self_test(args.output)

    if not args.serial:
        parser.error("--serial is required unless --self-test is used")
    if args.output is None:
        parser.error("--output is required unless --self-test is used")
    engines = normalize_engines(args.engines)
    if not engines:
        parser.error("at least one engine is required")

    contract_path = DEFAULT_CONTRACT
    contract = load_contract(contract_path)
    facts = contract_facts(contract)
    contract_sha = sha256_file(contract_path)
    started_at = utc_now()
    documents: dict[str, dict[str, Any]] = {}
    close_error: str | None = None
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=True)

    try:
        surface = SurfaceAudit(args.serial, output)
    except Exception as error:
        print("Cannot start DeviceAudit: " + str(error), file=sys.stderr)
        return 2

    try:
        for engine in engines:
            print(f"Inventorying {engine} ...", flush=True)
            try:
                document = audit_engine(surface, facts, engine, contract_path, contract_sha)
            except Exception as error:
                document = fallback_engine_document(
                    facts, engine, contract_path, contract_sha, str(error)
                )
            documents[engine] = document
            summary = document["summary"]
            print(
                f"  {engine}: accounted={summary['accountedRecords']}/{summary['contractRecords']} "
                f"states={summary['states']}",
                flush=True,
            )
    finally:
        try:
            surface.close()
        except Exception as error:
            close_error = str(error)

    raw_path = output / "mcp-responses.json"
    raw_sha = sha256_file(raw_path)
    for engine, document in documents.items():
        document["evidence"] = {
            "rawMcpResponses": "mcp-responses.json",
            "rawMcpResponsesSha256": raw_sha,
            "perEngineJson": f"surface-{engine}.json",
            "deviceAudit": "tools/audit-engine-details.py DeviceAudit",
        }
        write_json(output / f"surface-{engine}.json", document)
        write_json(output / f"queries-{engine}.json", {
            "engine": engine,
            "globals": document["globals"],
            "moduleExports": document["moduleExports"],
        })

    overall = build_overall_summary(
        facts,
        documents,
        args.serial,
        contract_path,
        contract_sha,
        raw_sha,
        close_error,
        started_at,
    )
    if close_error:
        overall["operationalFailures"].append("DeviceAudit close failed: " + close_error)
    write_json(output / "surface-summary.json", overall)

    print(json.dumps({
        "output": str(output),
        "engines": list(documents),
        "coverageComplete": overall["coverage"]["complete"],
        "operationalFailures": len(overall["operationalFailures"]),
        "perEngine": {
            engine: {
                "accounted": item["accountedRecords"],
                "contractRecords": item["contractRecords"],
                "states": item["states"],
                "missingModules": item["operational"].get("missingModules", []),
                "chunkFailures": len(item["operational"].get("chunkFailures") or []),
            }
            for engine, item in overall["perEngine"].items()
        },
        "claims": CLAIMS,
    }, ensure_ascii=False, indent=2))
    print("Presence/shape evidence only; no compatibility percentage is computed.")
    return 0 if overall["coverage"]["complete"] and not overall["operationalFailures"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
