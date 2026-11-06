"""Package an OpenCV 5 Android arm64 build with matching Java bindings.

Configure/build OpenCV with BUILD_LIST=core,imgproc,dnn,imgcodecs,features,java
and ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON first. This preserves the base
AAR's resources and other ABIs; features support is added for arm64 only.
"""
import argparse
import io
from pathlib import Path
import subprocess
import tempfile
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--build-dir', required=True, type=Path)
    parser.add_argument('--base-aar', required=True, type=Path)
    parser.add_argument('--output-aar', required=True, type=Path)
    parser.add_argument('--android-jar', required=True, type=Path)
    parser.add_argument('--jdk-bin', required=True, type=Path)
    args = parser.parse_args()
    build = args.build_dir.resolve(strict=True)
    base = args.base_aar.resolve(strict=True)
    output = args.output_aar.resolve()
    android = args.android_jar.resolve(strict=True)
    javac = (args.jdk_bin / 'javac.exe').resolve(strict=True)
    generated = build / 'modules/java_bindings_generator/gen'
    sources = sorted((generated / 'java').rglob('*.java'))
    # The existing AAR includes these helpers, not the CameraBridge UI widgets
    # (those require a separate Android resource/BuildConfig Gradle build).
    sources += [generated / 'android/java/org/opencv/android' / (name + '.java')
                for name in ('FpsMeter', 'OpenCVLoader', 'StaticHelper', 'Utils')]
    if not (generated / 'java/org/opencv/features/SIFT.java').is_file():
        raise RuntimeError('features Java bindings missing: build opencv_java first')
    libraries = {}
    for name in ('java5', 'core', 'imgproc', 'imgcodecs', 'dnn', 'flann', 'geometry', 'features'):
        filename = 'libopencv_' + name + '.so'
        candidates = [build / root / 'arm64-v8a' / filename for root in ('jni', 'lib')]
        libraries['jni/arm64-v8a/' + filename] = next((p for p in candidates if p.is_file()), None)
    libraries['jni/arm64-v8a/libtbb.so'] = build / 'lib/arm64-v8a/libtbb.so'
    for entry, path in libraries.items():
        if path is None or not path.is_file():
            raise RuntimeError('Missing native library: ' + entry)
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='opencv-features-', dir=output.parent) as temporary:
        staging = Path(temporary)
        classes = staging / 'classes'
        classes.mkdir()
        response = staging / 'sources.txt'
        response.write_text('\n'.join('"' + p.as_posix() + '"' for p in sources), encoding='utf-8')
        command = [str(javac), '-encoding', 'UTF-8', '-source', '8', '-target', '8',
                   '-classpath', str(android), '-d', str(classes), '@' + str(response)]
        subprocess.run(command, check=True)
        jar = io.BytesIO()
        with zipfile.ZipFile(jar, 'w', zipfile.ZIP_DEFLATED) as archive:
            for path in sorted(classes.rglob('*.class')):
                archive.write(path, path.relative_to(classes).as_posix())
        assembled = staging / 'opencv.aar'
        with zipfile.ZipFile(base) as original, zipfile.ZipFile(assembled, 'w', zipfile.ZIP_DEFLATED) as archive:
            for info in original.infolist():
                if info.filename != 'classes.jar' and info.filename not in libraries:
                    archive.writestr(info, original.read(info.filename))
            archive.writestr('classes.jar', jar.getvalue())
            for entry, path in libraries.items():
                archive.write(path, entry)
        assembled.replace(output)
    print(f'Packaged {len(sources)} Java sources and {len(libraries)} arm64 libraries: {output}')


if __name__ == '__main__':
    main()
