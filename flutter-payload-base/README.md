# Flutter Android release base

Current runtime: **Flutter 3.47.7 / Dart 3.13.5**, stable. Exact framework and
engine revisions and SHA-256 pins are in `runtime.json`.

`libflutter.so` and `flutter_embedding_release.jar` come from the **same SDK's**
`bin/cache/artifacts/engine/android-arm64-release/flutter.jar`. The library is
stripped; the embedding JAR contains classes only. To refresh both together:

```powershell
python tools/flutter-poc/refresh-base.py --sdk C:/Android/flutter-3.47.7 --strip C:/Android/ndk-r26d/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strip.exe
```

`tools/flutter-poc/build-aot.ps1` checks the SDK engine revision and base hashes,
builds Dart AOT and **fresh flutter_assets**, then records the AOT hash. Gradle's
`verifyFlutterPayload` rejects a mismatched engine/embedding/AOT payload.

The old `assets/` tree and `flutter_embedding_3.3.4.jar` remain as historical
baseline files; the upgraded pipeline consumes neither. Application assets and
compiled shaders now come from `flutter-ui/` through the pinned Flutter build.

See `docs/reports/2026-10-22_Flutter升级与16KB兼容修复.md` for verification and
the remaining 16KB-kernel device test. Static ELF checks do not replace it.
