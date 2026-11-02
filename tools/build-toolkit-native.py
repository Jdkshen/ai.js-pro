"""Build the small Capstone JNI on an authorized arm64 Android device using optional clang."""
from pathlib import Path
import argparse,hashlib,json,urllib.request,subprocess,sys,time,shlex,tarfile

REPO=Path(__file__).resolve().parent.parent
PIN='240ebc834c51aae41ca9215d3190cc372fd132b9c5c8aa2d5f19ca0c325e28f9'
SOURCE=REPO/'apps/app/src/main/toolkit-native/binary_tools.cpp'
LIB=REPO/'apps/app/src/main/jniLibs/arm64-v8a/libaijs_binarytools.so'
LOCK=SOURCE.parent/'binary-tools.lock.json'
OUT=REPO/'.artifacts/mt-mcp-20261102'
def sha(path):
 with path.open('rb') as stream:return hashlib.file_digest(stream,'sha256').hexdigest()
def check():
 lock=json.loads(LOCK.read_text(encoding='utf-8'))
 assert lock['sourceSha256']==sha(SOURCE),'Toolkit native source changed: run tools/build-toolkit-native.py'
 assert lock['librarySha256']==sha(LIB),'Toolkit native library differs from lock'
 assert lock['capstoneSha256']==PIN,'Toolkit Capstone source pin changed'
 assert lock['recipeSha256']==sha(Path(__file__)),'Toolkit native build recipe changed'
 print('TOOLKIT_NATIVE_FRESH',lock['librarySha256']);return lock
def main():
 parser=argparse.ArgumentParser();parser.add_argument('--check',action='store_true');parser.add_argument('--serial',default='cccc62c7');parser.add_argument('--url',default='http://192.168.10.5:8765/mcp');args=parser.parse_args()
 if args.check:check();return
 OUT.mkdir(parents=True,exist_ok=True)
 archive=OUT/'capstone-5.0.6.tar.gz'
 if not archive.exists():urllib.request.urlretrieve('https://codeload.github.com/capstone-engine/capstone/tar.gz/refs/tags/5.0.6',archive)
 assert sha(archive)==PIN,'Capstone archive digest mismatch'
 adb=['C:/Android/platform-tools/adb.exe','-s',args.serial]
 public='/storage/emulated/0/Download/__codex_toolkit_native_20261102'
 subprocess.run(adb+['shell','mkdir','-p',public],check=True)
 for file in [archive,SOURCE]:subprocess.run(adb+['push',str(file),public+'/'+file.name],check=True)
 def rpc(name,values):
  body=json.dumps({'jsonrpc':'2.0','id':1,'method':'tools/call','params':{'name':name,'arguments':values}}).encode()
  req=urllib.request.Request(args.url,data=body,headers={'Content-Type':'application/json','Accept':'application/json, text/event-stream'})
  with urllib.request.urlopen(req,timeout=90) as stream:response=json.load(stream)
  r=response['result'];data=r.get('structuredContent')
  if data is None:data=json.loads(next(x['text'] for x in r['content'] if x['type']=='text'))
  if r.get('isError'):raise RuntimeError(data)
  return data
 command='''set -e
toolkit_build_dir="$HOME/codex-toolkit-native-5.0.6"
rm -rf "$toolkit_build_dir/obj"
mkdir -p "$toolkit_build_dir/obj"
cd "$toolkit_build_dir"
test -f capstone-5.0.6/cs.c || tar -xzf PUBLIC/capstone-5.0.6.tar.gz
toolkit_flags="-O2 -fPIC -fvisibility=hidden -ffunction-sections -fdata-sections -DCAPSTONE_HAS_ARM -DCAPSTONE_ARM_SUPPORT -DCAPSTONE_HAS_ARM64 -DCAPSTONE_ARM64_SUPPORT -DCAPSTONE_HAS_X86 -DCAPSTONE_X86_SUPPORT -DCAPSTONE_USE_SYS_DYN_MEM -Icapstone-5.0.6/include -Icapstone-5.0.6"
for toolkit_source in cs.c Mapping.c MCInst.c MCInstrDesc.c MCRegisterInfo.c SStream.c utils.c arch/ARM/ARMDisassembler.c arch/ARM/ARMInstPrinter.c arch/ARM/ARMMapping.c arch/ARM/ARMModule.c arch/AArch64/AArch64BaseInfo.c arch/AArch64/AArch64Disassembler.c arch/AArch64/AArch64InstPrinter.c arch/AArch64/AArch64Mapping.c arch/AArch64/AArch64Module.c arch/X86/X86Disassembler.c arch/X86/X86DisassemblerDecoder.c arch/X86/X86ATTInstPrinter.c arch/X86/X86IntelInstPrinter.c arch/X86/X86InstPrinterCommon.c arch/X86/X86Mapping.c arch/X86/X86Module.c; do
toolkit_object=$(echo "$toolkit_source" | tr '/.' '__')
echo "compile $toolkit_source"
clang $toolkit_flags -c "capstone-5.0.6/$toolkit_source" -o "obj/$toolkit_object.o"
done
clang++ $toolkit_flags -std=c++17 -c PUBLIC/binary_tools.cpp -o obj/jni.o
clang++ -shared -Wl,--no-undefined -Wl,--gc-sections -Wl,-s -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 -static-libstdc++ obj/*.o -o libaijs_binarytools.so
cp libaijs_binarytools.so PUBLIC/libaijs_binarytools.so
sha256sum libaijs_binarytools.so
clang --version
'''.replace('PUBLIC',shlex.quote(public))
 start=rpc('terminal_exec',{'command':command,'backend':'app','timeoutSeconds':900});(OUT/'native-build-job.json').write_text(json.dumps(start),encoding='utf-8')
 while True:
  state=rpc('terminal_read',{'jobId':start['jobId'],'waitMillis':1000,'maxChars':65536})
  if not state['running'] and not state['processActive']:break
 (OUT/'native-build-result.json').write_text(json.dumps(state,ensure_ascii=False,indent=2),encoding='utf-8')
 print(state.get('output',''))
 assert state.get('exitCode')==0 and state['status']=='SUCCEEDED',state
 LIB.parent.mkdir(parents=True,exist_ok=True);subprocess.run(adb+['pull',public+'/libaijs_binarytools.so',str(LIB)],check=True)
 LOCK.write_text(json.dumps({'schemaVersion':1,'capstone':'5.0.6','capstoneSha256':PIN,'sourceSha256':sha(SOURCE),'recipeSha256':sha(Path(__file__)),'librarySha256':sha(LIB),'abi':'arm64-v8a','pageSize':16384},indent=2)+'\n',encoding='utf-8')
 check()
if __name__=='__main__':main()
