# Node 24 Android full ICU core

Build on Linux / WSL with Linux NDK r26d, GCC/G++ for host tools, Python 3,
and clang 18+ for the one V8 JSON translation unit affected by an NDK clang 17 crash.
The exact upstream Node 24.22.0 source archive SHA-256 is checked before extraction.
`prepare_android_source.py` verifies every pinned source file and applies three
reviewed Android arm64 / Linux host patches to a separate build tree.

Example environment (use absolute Linux paths):

```sh
export ANDROID_NDK_ROOT=/opt/android-ndk-r26d
export NODE24_ARCHIVE=/downloads/node-v24.22.0.tar.xz
export NODE24_SOURCE_DIR=/build/node24-full-icu
export NODE24_OUTPUT_DIR=/artifacts/node24-full-icu
export NODE24_JSON_CLANGXX=/usr/bin/clang++
bash tools/node24-runtime/build-linux.sh
```

Default parallelism is four; `JOBS` overrides it. `NODE24_SKIP_CONFIGURE=1`
permits an incremental rebuild only when the existing configuration is already
Android arm64 shared with ICU enabled. Source pins are still checked.

The script produces the stripped core, matching configuration/makefile, NDK
libc++ runtime, Node license (including ICU notices), and ELF evidence. It does
not install an APK or overwrite bundled runtime assets.

Integration order:

1. Preserve the previous runtime, review ELF dependencies and 16 KB LOAD alignment.
2. Copy the core and libc++ into the existing `source-engine-upgrade/node24/arm64-v8a`
   asset folder and update their SHA-256 pins in runtime.json.
3. Rebuild JNI using `tools/build-node24-bridge.ps1 -NodeSourceDir ... -NodeConfigFile ... -UpdateAssets`.
   Headers must be Node 24.22.0; config.gypi and node_base.target.mk must come from this core.
4. Run `python tools/build-node24-tools.py` against the pinned installed npm 11.13.0;
   preserve `assets/node24/tools/NODE-LICENSE.txt` from the core's LICENSE.
5. Build the app, verify `tools/probes/node24-icu.js` in embedded Node and CLI,
   then run the plugin, Web UI, native UI and same-process Dex probes.

The verified core uses full ICU (78.3 / Unicode 17.0), shared Android arm64/API24,
no Node startup snapshot/code cache, no OpenSSL assembly, strict undefined-symbol
link checking, and 16 KB max page size. The JSON unit retains clang 17 C++ ABI
compatibility and the same NDK sysroot/libc++.

The repository script was run against the completed independent source tree;
its stripped output matched the integrated core byte for byte. A clean source
build takes substantially longer than an incremental check.
