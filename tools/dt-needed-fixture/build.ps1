#requires -version 5.1
# Build a minimal signed arm64 APK containing libdtprobe.so (dev-1415 P1-4 device test).
param([string]$SoPath = 'native\libdtprobe.so')
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$ndk = 'C:\Android\ndk-r26d\toolchains\llvm\prebuilt\windows-x86_64\bin'
$bt  = 'C:\Android\build-tools\34.0.0'
$plat = 'C:\Android\platforms\android-35\android.jar'
$out = Join-Path $root 'out'
New-Item -ItemType Directory -Force -Path $out | Out-Null

'[1/7] clang 编译 arm64 .so'
$so = Join-Path $root $SoPath
if (-not (Test-Path $so)) {
  & (Join-Path $ndk 'aarch64-linux-android21-clang.cmd') -shared -fPIC -O2 -o $so (Join-Path $root 'native\dtprobe.c')
  if ($LASTEXITCODE -ne 0) { throw 'clang failed' }
}
'  so=' + $so + ' size=' + (Get-Item $so).Length

'[2/7] javac'
$classes = Join-Path $out 'classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
& javac -source 8 -target 8 -bootclasspath $plat -d $classes (Join-Path $root 'src\com\aijspro\dtprobe\MainActivity.java') 2>&1 | Select-Object -Last 3
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }

'[3/7] d8'
$dexDir = Join-Path $out 'dex'
New-Item -ItemType Directory -Force -Path $dexDir | Out-Null
& (Join-Path $bt 'd8.bat') --min-api 21 --lib $plat --output $dexDir (Get-ChildItem $classes -Recurse -Filter *.class | ForEach-Object { $_.FullName }) 2>&1 | Select-Object -Last 6
if ($LASTEXITCODE -ne 0) { throw 'd8 failed' }

'[4/7] aapt2 link'
$apkUnsigned = Join-Path $out 'dtprobe-unsigned.apk'
if (Test-Path $apkUnsigned) { Remove-Item $apkUnsigned -Force }
& (Join-Path $bt 'aapt2.exe') link -o $apkUnsigned -I $plat --manifest (Join-Path $root 'AndroidManifest.xml') --min-sdk-version 21 --target-sdk-version 28 2>&1 | Select-Object -Last 3
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link failed' }

'[5/7] 塞入 classes.dex 与 lib/arm64-v8a/libdtprobe.so'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open($apkUnsigned, 'Update')
$entryName = 'lib/arm64-v8a/' + (Split-Path $so -Leaf)
$old = $zip.GetEntry($entryName)
if ($old) { $old.Delete() }
$e = $zip.CreateEntry($entryName, [System.IO.Compression.CompressionLevel]::NoCompression)
$s = [System.IO.File]::OpenRead($so)
$d = $e.Open()
$s.CopyTo($d)
$d.Dispose(); $s.Dispose()
$dexOld = $zip.GetEntry('classes.dex')
if ($dexOld) { $dexOld.Delete() }
$dexEntry = $zip.CreateEntry('classes.dex', [System.IO.Compression.CompressionLevel]::Optimal)
$ds = [System.IO.File]::OpenRead((Join-Path $dexDir 'classes.dex'))
$dd = $dexEntry.Open()
$ds.CopyTo($dd)
$dd.Dispose(); $ds.Dispose()
$zip.Dispose()
'  entries: ' + $entryName + ' + classes.dex'

'[6/7] zipalign'
$aligned = Join-Path $out 'dtprobe-aligned.apk'
if (Test-Path $aligned) { Remove-Item $aligned -Force }
& (Join-Path $bt 'zipalign.exe') -f -p 4 $apkUnsigned $aligned
if ($LASTEXITCODE -ne 0) { throw 'zipalign failed' }

'[7/7] apksigner (debug key)'
$signed = Join-Path $out 'dtprobe-signed.apk'
if (Test-Path $signed) { Remove-Item $signed -Force }
$ks = Join-Path $env:USERPROFILE '.android\debug.keystore'
& (Join-Path $bt 'apksigner.bat') sign --ks $ks --ks-pass pass:android --key-pass pass:android --ks-key-alias androiddebugkey --out $signed $aligned
if ($LASTEXITCODE -ne 0) { throw 'apksigner failed' }
& (Join-Path $bt 'apksigner.bat') verify --print-certs $signed | Select-Object -First 4
'DONE ' + $signed + ' size=' + (Get-Item $signed).Length + ' sha256=' + (Get-FileHash $signed -Algorithm SHA256).Hash