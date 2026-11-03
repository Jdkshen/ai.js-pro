# Android-compatible Apktool nine-patch decoder

Drop-in replacement for Apktool **3.0.3**:

```
brut.androlib.res.decoder.ResNinePatchStreamDecoder
```

The upstream class uses `javax.imageio.ImageIO`, `java.awt.image.BufferedImage`,
`Raster`, and AWT. Those classes do not exist on Android, which causes:

```
java.lang.NoClassDefFoundError: javax.imageio.ImageIO
```

This replacement has the same FQCN, implements `ResStreamDecoder`, compiles
against the locally supplied Apktool JAR plus Android API 35, and uses Android's
native PNG decoder (`BitmapFactory` / `Bitmap`) instead of AWT. It has no host
GUI or AWT dependency. The original class must be removed from the Apktool JAR
and this class merged before D8.

## Deliverables

| Path | Size | SHA-256 |
|---|---:|---|
| `src/brut/androlib/res/decoder/ResNinePatchStreamDecoder.java` | 18,294 B | `79ED006D3369868EAA7626C360B58F04DEFB1A3164E4DF3C4F67523332A9A905` |
| `build/classes/brut/androlib/res/decoder/ResNinePatchStreamDecoder.class` | 9,731 B | `2D419B2B0AD6E8FF3B29F8071431F797A4F175B755CC0FC1FA3CA4A3676782A5` |
| `dist/apktool-ninepatch-android.jar` | 5,756 B | `CC9134250F5234B5CFDB822E0D85270C9D542BFED82E52B7BB5EAF197EC6D687` |

The JAR contains exactly the replacement class (plus `META-INF/MANIFEST.MF`).
The class file is Java 8 bytecode (major version 52). It contains no
`javax/imageio` or `java/awt` references.

`LICENSE` is the full Apache License 2.0 text. `NOTICE` records the upstream
Apktool attribution. The replacement is distributed under Apache-2.0.

## Source provenance and API inspection

Official upstream tag:

- [`ResNinePatchStreamDecoder.java` @ v3.0.3](https://github.com/iBotPeaches/Apktool/blob/v3.0.3/brut.apktool/apktool-lib/src/main/java/brut/androlib/res/decoder/ResNinePatchStreamDecoder.java)
- [`ResStreamDecoder.java` @ v3.0.3](https://github.com/iBotPeaches/Apktool/blob/v3.0.3/brut.apktool/apktool-lib/src/main/java/brut/androlib/res/decoder/ResStreamDecoder.java)
- [`NinePatchData.java` @ v3.0.3](https://github.com/iBotPeaches/Apktool/blob/v3.0.3/brut.apktool/apktool-lib/src/main/java/brut/androlib/res/data/NinePatchData.java)
- [`LayoutBounds.java` @ v3.0.3](https://github.com/iBotPeaches/Apktool/blob/v3.0.3/brut.apktool/apktool-lib/src/main/java/brut/androlib/res/data/LayoutBounds.java)
- [`NinePatchNotFoundException.java` @ v3.0.3](https://github.com/iBotPeaches/Apktool/blob/v3.0.3/brut.apktool/apktool-lib/src/main/java/brut/androlib/exceptions/NinePatchNotFoundException.java)

The supplied local JAR is an R8 `full` build (`3.0.3-dirty`) of that code. R8
stripped checked-exception attributes and made the former instance helpers
`public static`:

```
public final class ...ResNinePatchStreamDecoder implements ...ResStreamDecoder {
  public ...ResNinePatchStreamDecoder();
  public final void decode(InputStream, OutputStream);
  public static ...NinePatchData findNinePatchData(byte[]);
  public static ...LayoutBounds findLayoutBounds(byte[]);
}
```

The replacement matches that public shape. Because the JAR's
`ResStreamDecoder.decode` has no checked-exception attribute, the replacement's
`decode` method also cannot declare `throws AndrolibException` when compiled
against the supplied JAR. It throws the exact `AndrolibException` at runtime
through a private generic "sneaky throw". Existing Apktool `catch` sites still
receive the checked exception object normally.

`findNinePatchData` and `findLayoutBounds` are retained for JAR API parity. They
are not the main decode path. The public helper exceptions are likewise thrown
at runtime.

## Exact build

The build was performed with JDK 17 and `--release 8`, against the supplied
Apktool JAR and `android-35/android.jar`. Run from the repository root:

```powershell
$JDK     = 'C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot'
$APKTOOL = (Resolve-Path '.artifacts/reverse-suite-20261103/sdk/apktool/apktool.jar').Path
$ANDROID = (Resolve-Path 'C:\Android\platforms\android-35\android.jar').Path
$ROOT    = (Resolve-Path 'tools/apktool-android').Path

Remove-Item -Recurse -Force "$ROOT\build\classes" -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force "$ROOT\build\classes" | Out-Null

& "$JDK\bin\javac.exe" --release 8 -Xlint:all `
    -d "$ROOT\build\classes" `
    -classpath "$APKTOOL;$ANDROID" `
    "$ROOT\src\brut\androlib\res\decoder\ResNinePatchStreamDecoder.java"

if ($LASTEXITCODE -ne 0) { throw "javac failed" }
```

Package the single replacement class:

```powershell
New-Item -ItemType Directory -Force "$ROOT\dist" | Out-Null
& "$JDK\bin\jar.exe" --create `
    --file "$ROOT\dist\apktool-ninepatch-android.jar" `
    -C "$ROOT\build\classes" `
    brut/androlib/res/decoder/ResNinePatchStreamDecoder.class
```

On POSIX, replace `;` in the classpath with `:`.

## Payload patch before D8

Do this on a **copy**; never modify the supplied Apktool JAR in place. The root
preparer's remove-original/merge-replacement flow should produce the same
result. The version below uses JDK 17's `jar --update` plus .NET's ZIP API
(JDK 17's `jar` has no `--delete` option):

```powershell
$JDK     = 'C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot'
$APKTOOL = (Resolve-Path '.artifacts/reverse-suite-20261103/sdk/apktool/apktool.jar').Path
$ROOT    = (Resolve-Path 'tools/apktool-android').Path
$CLASS   = 'brut/androlib/res/decoder/ResNinePatchStreamDecoder.class'
$PATCHED = (Join-Path $ROOT 'dist\apktool-ninepatch-patched.jar')

Copy-Item $APKTOOL $PATCHED -Force
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open(
    $PATCHED, [System.IO.Compression.ZipArchiveMode]::Update)
try {
    $entry = $zip.GetEntry($CLASS)
    if ($null -ne $entry) { $entry.Delete() }
} finally {
    $zip.Dispose()
}

& "$JDK\bin\jar.exe" --update `
    --file $PATCHED `
    -C "$ROOT\build\classes" `
    brut/androlib/res/decoder/ResNinePatchStreamDecoder.class

if ($LASTEXITCODE -ne 0) { throw "jar update failed" }
```

Verify there is exactly one replacement entry and that the class API is the
replacement, not the upstream AWT class:

```powershell
$entries = & "$JDK\bin\jar.exe" tf $PATCHED
$hits = @($entries | Where-Object { $_ -eq $CLASS })
if ($hits.Count -ne 1) { throw "expected one decoder class, got $($hits.Count)" }

& "$JDK\bin\javap.exe" -p -s -classpath $PATCHED `
    brut.androlib.res.decoder.ResNinePatchStreamDecoder
```

`ResNinePatchStreamDecoder` must appear once. `javap` must show:
`decode(java.io.InputStream, java.io.OutputStream)`,
`findNinePatchData(byte[])`, and `findLayoutBounds(byte[])`.
The class must not reference `java.awt` or `javax.imageio`.

After this, the root preparer can run D8 with the patched JAR. Do not run D8 on
the unpatched JAR: both classes have the same FQCN, and the original AWT class
will still be selected depending on classpath order.

## Decode behavior

- Reads the complete caller-owned `InputStream` without closing it, capped at
  **64 MiB**. An empty stream returns without writing output, matching upstream.
  The caller-owned `OutputStream` is also left open.
- Performs an `inJustDecodeBounds` pass first. Rejects non-positive dimensions,
  any dimension over **8192**, or more than **16,000,000** pixels before full
  decoding.
- Fully decodes as `ARGB_8888`, `inScaled=false`, `inPremultiplied=false`.
- Requires a non-empty `Bitmap.getNinePatchChunk()`. A PNG without an npTc
  chunk throws `NinePatchNotFoundException`; it is **not** rewritten as an
  uncut/fake nine-patch.
- Parses the serialized chunk in native byte order:
  `wasDeserialized`, `numXDivs`, `numYDivs`, `numColors`, two ignored offsets,
  four paddings, an ignored colors offset, then the x/y/color int arrays.
- Validates even div counts, declared array byte length, non-negative padding,
  padding sums against the content dimensions, non-negative div ranges, div
  ends against content dimensions, and sorted/non-overlapping div ranges.
  Invalid metadata becomes an `AndrolibException` with a specific message.
- Creates `(width + 2) x (height + 2)` ARGB_8888 output, clears it to fully
  transparent, copies the decoded interior at `(1,1)` row by row, then draws
  **solid black** top/left stretch markers and bottom/right padding markers.
  If a div array is empty, the corresponding full border is drawn, matching
  upstream. The actual RGBA interior is preserved from BitmapFactory.
- Writes the result with `Bitmap.compress(PNG, 100, out)`. Both source and
  destination Bitmaps are recycled in `finally`.
- No `Canvas`, `Context`, `Resources`, host UI, file system, or cache is used.

## Known fidelity limits

1. **Optical layout bounds are not reconstructed.** Android's
   `Bitmap.getNinePatchChunk()` exposes npTc content/div/padding data, not the
   optional npLb/npOl metadata. Upstream draws red optical ticks when npLb is
   present. Those ticks are omitted here. A public `findLayoutBounds(byte[])`
   helper can parse npLb from raw PNG bytes, but the bitmap decode path does not
   use it. The generated aapt2 fixture had npOl and standard black markers;
   npOl is not used by upstream's decoder either.
2. **Nine-patch color optimization data is ignored.** The `colors[]` table is
   parsed for length validation only. The output is the literal RGBA image, not
   an optimized single-color nine-patch. File size can differ.
3. **PNG metadata and color profiles are not preserved.** The output is a new
   PNG from Android's encoder; ICC profiles, gamma, ancillary chunks, original
   compression, filters, and text chunks are lost. Pixel values and the
   reconstructed border are the compatibility target.
4. **Android's PNG codec is not ImageIO.** PNGs that ImageIO accepts but
   `BitmapFactory` rejects (or vice versa), unusual bit depths, color types, or
   malformed ancillary data may behave differently.
5. **Alpha handling is Android's.** `inPremultiplied=false` requests
   unpremultiplied pixels, but very low-alpha or fully transparent pixels may
   still lose hidden RGB during decode/encode. Output is not guaranteed to be
   byte-identical to upstream; it is intended to be pixel-equivalent for normal
   opaque/translucent resources.
6. **Stricter limits and validation.** Upstream accepts arbitrary image sizes
   and some malformed metadata. This replacement intentionally rejects
   >64 MiB input, dimensions >8192, >16M pixels, negative/overlapping padding,
   odd div counts, out-of-range divs, and truncated chunks with a clear
   `AndrolibException`. It does not fall back to a fake uncut nine-patch.
7. **Compiled resources only.** A raw, uncompiled `.9.png` has no npTc chunk;
   like upstream, it raises `NinePatchNotFoundException`. It is not interpreted
   by scanning the bitmap border.
8. **Requires modern Android APIs.** The code uses the API 19+
   `BitmapFactory.Options.inPremultiplied` field and API 1+ Bitmap APIs. It
   targets the Android API level of the host tool, not a desktop JVM.
9. **No end-to-end Android device test was run in this workspace.** The class
   was cross-compiled and its native-endian chunk parser/validation was tested
   offline. The root preparer owns the actual Android decode test after patching.

## Build/test evidence from this task

- `javac` JDK 17.0.19 with `--release 8` exited 0; class major version is 52.
- Byte scan of the final `.class`: `ImageIO=False`, `AWT=False`,
  `android/graphics/Bitmap=True`.
- Public API `javap` matches the supplied JAR's methods (constructor,
  `decode`, `findNinePatchData`, `findLayoutBounds`).
- A one-off reflective test parsed a synthetic native-endian 32-byte-header
  npTc chunk: `x=[1,3]`, `y=[1,3]`, paddings `(0,2,0,1)`. Invalid odd count,
  padding sum over width, and div end over width were each rejected with
  `AndrolibException`.
- A 6x6 aapt2-compiled fixture with a standard 8x8 raw `.9.png` border produced
  an 84-byte npTc payload. The raw helper returned `x=[1,3]`, `y=[1,3]`,
  paddings `(0,2,0,1)`. Upstream Apktool 3.0.3 decoded the same APK to an 8x8
  PNG with matching black markers.
- No phone, Gradle, git, network deployment, or D8 run was performed.
