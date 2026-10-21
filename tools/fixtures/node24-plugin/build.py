"""Build a real SDK registry + Java + resources + JNI + Binder plugin test APK."""
from pathlib import Path
import subprocess, zipfile, os
ROOT=Path(__file__).resolve().parents[3]
FIX=Path(__file__).resolve().parent
OUT=ROOT/'output/node-pro-gaps-20261021/plugin-v2'
OUT.mkdir(parents=True,exist_ok=True)
BT=Path('C:/Android/build-tools/34.0.0')
JDK=Path('C:/Program Files/Microsoft/jdk-17.0.19.10-hotspot')
ANDROID='C:/Android/platforms/android-35/android.jar'
def run(*args): subprocess.run([str(x) for x in args],check=True)
run(BT/'aapt2.exe','compile','--dir',FIX/'res','-o',OUT/'resources.zip')
run(BT/'aapt2.exe','link','-o',OUT/'unsigned.apk','-I',ANDROID,'--manifest',FIX/'AndroidManifest.xml','--auto-add-overlay','-R',OUT/'resources.zip')
(OUT/'classes').mkdir(exist_ok=True);(OUT/'dex').mkdir(exist_ok=True)
run(JDK/'bin/javac.exe','-source','8','-target','8','-classpath',ANDROID,'-d',OUT/'classes',*FIX.rglob('*.java'))
run(JDK/'bin/jar.exe','cf',OUT/'classes.jar','-C',OUT/'classes','.')
run(JDK/'bin/java.exe','-cp',BT/'lib/d8.jar','com.android.tools.r8.D8','--min-api','26','--lib',ANDROID,'--output',OUT/'dex',OUT/'classes.jar')
run('C:/Android/ndk-r26d/toolchains/llvm/prebuilt/windows-x86_64/bin/aarch64-linux-android26-clang.cmd','-shared','-fPIC',FIX/'native.c','-Wl,-z,max-page-size=16384','-o',OUT/'libnodeprobe.so')
with zipfile.ZipFile(OUT/'unsigned.apk','a') as z:
 z.write(OUT/'dex/classes.dex','classes.dex');z.write(OUT/'libnodeprobe.so','lib/arm64-v8a/libnodeprobe.so')
 for p in (FIX/'assets').rglob('*'):
  if p.is_file():z.write(p,'assets/'+p.relative_to(FIX/'assets').as_posix())
run(BT/'zipalign.exe','-f','-p','4',OUT/'unsigned.apk',OUT/'aligned.apk')
run(JDK/'bin/java.exe','-jar',BT/'lib/apksigner.jar','sign','--ks',Path.home()/'.android/debug.keystore','--ks-pass','pass:android','--out',OUT/'fixture-plugin.apk',OUT/'aligned.apk')
run(JDK/'bin/java.exe','-jar',BT/'lib/apksigner.jar','verify',OUT/'fixture-plugin.apk')
print(OUT/'fixture-plugin.apk')
