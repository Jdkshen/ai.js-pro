# AI.js Pro optional reverse helpers

Build downloadable payloads from the repository root with `C:/Python314/python.exe -X utf8 tools/prepare-reverse-components.py <component>`.
Components: `radare2`, `r2ghidra`, `yara`, `unicorn`, `frida`, `apktool`. The main APK only contains adapters and documentation.

| Helper | CLI | Bounds |
|---|---|---|
| aijs-unicorn | `<arch> <code_file> <data_file_or_dash> <timeout_ms> <instruction_limit> [REG=HEX...]` | arm64/arm/thumb/x86/x86_64; code≤64KiB, data≤4096B, ≤1000ms, ≤100000 instructions |
| aijs-yara | `<rules_file> <target_file> <timeout_seconds> <include_offsets_0_or_1>` | rules≤1MiB, target≤64MiB, ≤30s; includes disabled, 200 rules / 100 offsets each |
| aijs-frida | `<request.json>` | status/processes/eval; remote Server/Gadget required; bounded messages and cleanup |

On 2026-11-04, root integration verified these helpers through the real MCP on MI8 / Android15: five emulator architectures and instruction limits, YARA rule offsets, Frida Gadget connection/enumeration/native sum=42/runtime errors/message truncation/cancel-and-reattach. Root fixed private-GLib initialization, the cancellable callback signature, and the Thumb entry low bit. Other devices still require their own checks.

## aijs-frida

`aijs-frida` is the bounded Android native Frida client used by the AI.js MCP
reverse-engineering tools.  It is intentionally small: one C source file, one
JSON request in, one JSON document out.  It does **not** provide a shell, a
server launcher, spawning, persistent hooks, or arbitrary root helpers.

The implementation is `frida_runner.c`; the expected built artifact is
`.artifacts/reverse-suite-20261103/frida-build/aijs-frida` (arm64, Android API
26+).

## Upstream, SDK, and licensing

- Frida core **17.25.2**, upstream <https://github.com/frida/frida>.
- The provided SDK directory is
  `.artifacts/reverse-suite-20261103/sdk/frida-core/` and contains:
  - `frida-core.h` — official amalgamated header (includes the GLib and
    json-glib declarations used by this helper);
  - `libfrida-core.a` — arm64 static Frida core;
  - `frida-core-example.c` and `frida-core.gir` — reference/API metadata.
- Frida core is licensed **LGPL-2.1-or-later**.  The amalgamated header also
  carries GLib and json-glib, both **LGPL-2.1-or-later**.  See upstream
  `COPYING` and the Frida/GLib/json-glib source distributions for the full
  texts and notices.
- This helper is distributed under the same LGPL-2.1-or-later terms.  Static
  linking with `libfrida-core.a` is used here because Android deployment needs
  one executable.  Anyone redistributing the resulting binary must comply with
  the LGPL relinking/source-offer obligations for Frida core, GLib, json-glib,
  and this file.  The AI.js repository keeps this source and the SDK provenance
  available to satisfy that requirement.

## CLI contract

```
aijs-frida <request.json>
```

Exactly one positional argument: the request file.  The program writes exactly
one JSON object to stdout and exits `0` only when `ok` is `true`.  Diagnostics
go to stderr.  There is no interactive mode.

### Request

| field | type | required | default / range |
|---|---|---|---|
| `operation` | string | yes | `status`, `processes`, or `eval` |
| `endpoint` | string | no | `127.0.0.1:27042`; 1..512 chars |
| `pid` | integer | for `eval` | positive Android PID |
| `script` | string | for `eval` | non-empty JavaScript source |
| `durationMs` | integer | no | `1000`; 1..10000 |
| `timeoutMs` | integer | no | `10000`; 1..15000 |

Unknown fields are ignored.  Wrong JSON types and out-of-range values fail the
whole request with `ok:false`; they are never silently coerced.

Example:

```json
{
  "operation": "eval",
  "endpoint": "127.0.0.1:27042",
  "pid": 1234,
  "script": "setImmediate(() => send({hello: Process.arch}));",
  "durationMs": 1000,
  "timeoutMs": 5000
}
```

### Operations

- **status** — reports the linked Frida version and proves that the endpoint
  actually answers by creating a remote device manager, adding the remote
  device, and querying its system parameters.  `status.connected` is only
  `true` after that query succeeds.
- **processes** — same connection check, then enumerates processes through the
  remote `FridaDevice`.  At most 500 records are emitted; `truncated` is `true`
  when the device returned more and `processTotal` gives the server-reported
  total.  `processCount` is the number of emitted records.
- **eval** — connects, attaches to exactly one supplied PID, creates a QJS
  script named `aijs-frida`, loads it, collects messages for `durationMs`, then
  unloads the script, detaches the session, and closes the device manager.  It
  never calls `frida_device_spawn*`, `frida_script_eternalize*`, or a server
  launcher.

### Response

Every invocation emits all of these top-level members:

```json
{
  "ok": true,
  "error": null,
  "status": {
    "version": "17.25.2",
    "endpoint": "127.0.0.1:27042",
    "connected": true,
    "deviceId": "...",
    "deviceName": "..."
  },
  "messages": [],
  "truncated": false,
  "timedOut": false,
  "operation": "eval",
  "cleanupError": null,
  "messageBytes": 0,
  "pid": 1234,
  "durationMs": 1000
}
```

- `status` is always an object; fields are `null`/`false` when the operation
  failed before they could be established.
- `messages` is an array of the parsed Frida message objects.  Frida message
  types such as `send`, `log`, and `error` keep their original `type`,
  `payload`, `description`, `stack`, etc.  If a Frida message carries a binary
  `GBytes`, the record gets:
  ```json
  "binary": { "bytes": 4096, "included": false }
  ```
  Binary bytes are **not** copied, encoded, or returned.  A malformed message
  becomes `{"type":"invalid","rawBytes":N,"parseError":"..."}`.
- Collection stops at **100 messages or 1,048,576 bytes of serialized message
  records**, whichever comes first; `truncated` becomes `true`.  The binary
  metadata is included in that byte budget.
- `error` is the operation/connection error, or a joined cleanup error.  A
  non-null `cleanupError` also makes the overall `ok` false, because unload,
  detach, and manager close are part of the contract.
- A script-level `error` message does not by itself make `ok` false: `ok`
  describes whether attach/load/duration/unload/detach completed.  Inspect
  `messages` for script errors.
- `timedOut` is set when the operation deadline fired.  No success is
  synthesized from a cancelled or failed SDK call.

## Build

Use the NDK r26d compiler wrapper and the official static SDK:

```powershell
$CC  = 'C:\Android\ndk-r26d\toolchains\llvm\prebuilt\windows-x86_64\bin\aarch64-linux-android26-clang.cmd'
$SDK = 'C:\Users\18101\Documents\ai-js-pro\.artifacts\reverse-suite-20261103\sdk\frida-core'
$OUT = 'C:\Users\18101\Documents\ai-js-pro\.artifacts\reverse-suite-20261103\frida-build\aijs-frida'

New-Item -ItemType Directory -Force (Split-Path -Parent $OUT) | Out-Null
& $CC -DANDROID -O2 -fPIC -ffunction-sections -fdata-sections `
  -I $SDK -o $OUT `
  'C:\Users\18101\Documents\ai-js-pro\tools\reverse-native\frida_runner.c' `
  "$SDK\libfrida-core.a" `
  -llog -ldl -lm -latomic -pthread `
  '-Wl,--export-dynamic' '-Wl,--gc-sections' `
  '-Wl,-z,max-page-size=16384' '-Wl,-z,common-page-size=16384'
```

The two page-size flags are required for Android 16 KB page-size devices.  A
successful build is ELF64 AArch64, is PIE (`Type: DYN`), has no
`libfrida-core.so` `NEEDED` entry, and every `PT_LOAD` segment is aligned to
`0x4000`.  Optional size reduction:

```powershell
& 'C:\Android\ndk-r26d\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-strip.exe' --strip-unneeded $OUT
& 'C:\Android\ndk-r26d\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-readelf.exe' -l $OUT
```

The static link is large (roughly 150 MB unstripped / 90 MB stripped with the
provided archive); that is expected because Frida core carries its GLib,
json-glib, and Gum dependencies.

## Bounds, cleanup, and remaining limitations

- The timer is a real `GThread` with `GCancellable`.  It cancels blocking Frida
  SDK calls at `timeoutMs` and wakes the message loop through a cancellable
  `GSource`.  The timer holds refs to its cancellable and loop; the main thread
  joins it before releasing them.
- The operation deadline stops before eval cleanup.  Cleanup (unload, detach,
  close) uses fresh cancellables with a **1500 ms** bound per step, so total
  wall time can exceed `timeoutMs` by up to about 4.5 s.  This trade-off is
  deliberate: a timed-out operation still attempts an orderly detach.  If a
  cleanup step times out or errors, `cleanupError` records it and `ok` is
  false.
- Process enumeration receives the full list from frida-server; only the output
  is capped at 500.  A device with an enormous process table can still consume
  memory inside Frida/GLib.  Frida's current public process-query API has no
  server-side limit to pass here.
- The helper supports only plain remote endpoints.  It does not expose TLS
  certificate, origin, token, keepalive, USB, or barebone options.
- It does not start or restart frida-server, does not use root, does not spawn,
  does not inject a library, and does not eternalize or retain hooks after the
  process exits.  If the helper is killed, frida-server still owns the session
  until the transport notices the disconnect; the helper cannot guarantee its
  own cleanup after `SIGKILL`.
- On-device deployment must already be executable (`chmod 755`) and a
  compatible frida-server must already be listening at `endpoint`.  The helper
  does not install, copy, or launch anything.
- The Frida client and frida-server must be protocol-compatible.  A version or
  endpoint mismatch is reported as a connection error, not retried.
- stdout is a single JSON document by construction.  Do not add debug prints to
  this file; put future diagnostics on stderr.
- The initial delegated change was compile-only. Root subsequently ran the
  MI8 Gadget/MCP checks described above; this is sample evidence rather than
  a guarantee for every server, target process or Android release.
