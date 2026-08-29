# Auto.js ImGui hybrid workspace

This directory contains the first hybrid UI migration slice:

- `autojs_imgui.cpp` owns the EGL/OpenGL ES 2 renderer and the ImGui workspace;
- Java owns Android lifecycle, system screens and existing Auto.js services;
- the script workspace reads the configured Auto.js directory and reuses the existing editor and
  script engine for edit, run and stop actions;
- `third_party/imgui` is Dear ImGui 1.92.4 under its MIT license;
- the bundled Noto Sans CJK font remains under the SIL Open Font License.

The old Android Gradle Plugin cannot discover modern NDK layouts. Native libraries are therefore
built independently and checked into `app/src/main/jniLibs` as prebuilt inputs for the legacy APK
build. Rebuild all three supported ABIs from PowerShell with:

```powershell
& 'app/src/main/cpp/build-native.ps1'
```

Pass `-NdkPath`, `-CMakePath`, or `-NinjaPath` when the tools live elsewhere. The default is NDK
r27c with Android API 21 and flexible page-size support enabled. This produces `armeabi-v7a`,
`arm64-v8a`, and `x86` libraries; the arm64 output is compatible with Android's 16 KB page size.
