# Optional Android APK components

Payloads are separate downloads; the app APK does not bundle JADX or Android AAPT2.

From the repository root, with Python 3.14 and JDK 17:

```powershell
C:/Python314/python.exe -X utf8 tools/prepare-apk-components.py resources
C:/Python314/python.exe -X utf8 tools/prepare-apk-components.py jadx --d8-jar <R8-8.5.35-or-newer.jar>
```

The Android SDK 34 D8 version can fail on newer dependency annotations. Use a current R8/D8 jar explicitly. SDK 35 android.jar is the default platform input. Outputs are `.artifacts/updates/components/<id>-arm64-v1.{zip,json}` with SHA-256 and upstream pins. The resource payload includes Android aarch64 AAPT2 and required shared libraries. JADX is pre-DEXed and runs in an owned child process.

Serve this directory through the existing LAN update server. Install via `apk_component_install`, wait with `apk_job_status`, and read `apk_job_result`; installation alone does not prove execution works. Rebuild changes under a new component version/filename before distributing future revisions, preserving old immutable manifests.

Java reading does not provide Java-to-APK rebuilding. Resource compilation only links project Android resources against the actual installed host; it does not restore the cancelled full Gradle/Java toolchain.
