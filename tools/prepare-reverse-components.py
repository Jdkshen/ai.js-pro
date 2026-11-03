"""Build separately downloadable Android reverse-engineering payloads from pinned upstream files."""
from pathlib import Path
import argparse,gzip,hashlib,json,posixpath,runpy,subprocess,tarfile,zipfile,os,shutil,lzma,contextlib,io
BASE=Path.cwd();WORK=BASE/'.artifacts/reverse-suite-20261103';CACHE=WORK/'upstream';CACHE.mkdir(parents=True,exist_ok=True)
shared=runpy.run_path(str(BASE/'tools/prepare-apk-components.py'));fetch=shared['fetch'];deb_data=shared['deb_data'];package=shared['package']

def emit(args,component,payload,source,executables,license_name):
    license_urls={
        'radare2':[('LGPL-3.0.txt','https://raw.githubusercontent.com/spdx/license-list-data/main/text/LGPL-3.0-only.txt'),('GPL-3.0.txt','https://raw.githubusercontent.com/spdx/license-list-data/main/text/GPL-3.0-only.txt')],
        'unicorn':[('GPL-2.0.txt','https://raw.githubusercontent.com/spdx/license-list-data/main/text/GPL-2.0-only.txt')],
        'yara':[('YARA-BSD-3-Clause.txt','https://raw.githubusercontent.com/VirusTotal/yara/v4.6.0/COPYING')],
        'apktool':[('Apktool-LICENSE.txt','https://raw.githubusercontent.com/iBotPeaches/Apktool/v3.0.3/LICENSE.md')],
    }
    for name,url in license_urls.get(component,[]):payload['licenses/'+name]=cached(url)
    payload['SOURCE-NOTICE.txt']=(source+'\nOptional component; not embedded in AI.js Pro.\nLicense: '+license_name+'\nVersions and upstream SHA256 are recorded in upstream-lock.json.\n').encode()
    with contextlib.redirect_stdout(io.StringIO()):package(args.out,component,payload,source,executables)
    manifest=args.out/(component+'-arm64-v1.json');meta=json.loads(manifest.read_text(encoding='utf8'));meta['license']=license_name
    manifest.write_text(json.dumps(meta,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
    print(json.dumps(meta,ensure_ascii=False),flush=True)
def cached(url,sha=None):
    dest=CACHE/(sha or hashlib.sha256(url.encode()).hexdigest())
    if dest.is_file() and (not sha or hashlib.sha256(dest.read_bytes()).hexdigest()==sha):return dest.read_bytes()
    failure=None
    for _ in range(3):
        try:data=fetch(url,sha);dest.write_bytes(data);return data
        except Exception as error:failure=error
    raise failure
def termux(names,dependencies=True):
    base='https://packages.termux.dev/apt/termux-main/'
    index=gzip.decompress(cached(base+'dists/stable/main/binary-aarch64/Packages.gz')).decode();pkgs={}
    for block in index.split('\n\n'):
        fields={};last=None
        for line in block.splitlines():
            if line.startswith(' ') and last:fields[last]+=' '+line.strip()
            elif ': ' in line:last,value=line.split(': ',1);fields[last]=value
        if fields.get('Package'):pkgs[fields['Package']]=fields
    selected={};files={};links={};queue=list(names)
    while queue:
        name=queue.pop(0)
        if name in selected:continue
        info=pkgs[name];selected[name]=info;print('fetch',name,info['Version'],flush=True)
        if dependencies:
            for item in info.get('Depends','').split(','):
                dep=item.strip().split(' ')[0].split('|')[0]
                if dep:queue.append(dep)
        with deb_data(cached(base+info['Filename'],info['SHA256'])) as tar:
            for item in tar.getmembers():
                path=item.name.removeprefix('./');prefix='data/data/com.termux/files/usr/'
                if not path.startswith(prefix):continue
                path=path[len(prefix):]
                if path.startswith('/') or '..' in path.split('/'):raise ValueError('unsafe upstream path')
                if item.isfile():
                    if item.size>256*1024*1024:raise ValueError('oversized upstream file')
                    files[path]=tar.extractfile(item).read()
                elif item.issym() or item.islnk():
                    target=item.linkname.removeprefix('./')
                    if target.startswith('/'+prefix):target=target[len(prefix)+1:]
                    elif target.startswith(prefix):target=target[len(prefix):]
                    elif item.issym():target=posixpath.normpath(posixpath.join(posixpath.dirname(path),target))
                    if target.startswith('/') or '..' in target.split('/'):raise ValueError('escaping upstream link')
                    links[path]=target
    for path,target in links.items():
        seen={path}
        while target in links:
            if target in seen:raise ValueError('cyclic upstream link')
            seen.add(target);target=links[target]
        if target in files:files[path]=files[target]
    return files,selected
def save_sdk(component,files):
    sdk=WORK/'sdk'/component
    for name,data in files.items():
        if name.startswith(('include/','lib/')):
            p=sdk/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(data)
    return sdk
def native(args):
    files,pins=termux([args.component])
    spawn_sources={}
    if args.component=='radare2':
        # This one upstream dependency still uses 4K PT_LOAD segments; relink from pinned source.
        pinned={'posix_spawn.cpp':'5267405b1b14fcbe885bd815d9bd5dde94144dc90b319d6f4be17e47f43bcad3',
            'posix_spawn.h':'5cc27528237cf17727709eaab624f20878b71013b2eb265e3a6e6dae1e4294a0','LICENSE':'511ce23b36ca7dd3858570d8ee3054ceed18e12d284f96661d030a82590a5954'}
        folder=WORK/'sdk/spawn16k';folder.mkdir(parents=True,exist_ok=True)
        for name,sha in pinned.items():
            data=cached('https://raw.githubusercontent.com/termux/termux-packages/master/packages/libandroid-spawn/'+name,sha)
            (folder/name).write_bytes(data);spawn_sources['source/libandroid-spawn/'+name]=data
        cc=Path(args.ndk)/'toolchains/llvm/prebuilt/windows-x86_64/bin/aarch64-linux-android26-clang++.cmd'
        output=folder/'libandroid-spawn.so'
        subprocess.run([str(cc),'-O2','-fPIC','-shared','-static-libstdc++','-I'+str(folder),str(folder/'posix_spawn.cpp'),'-Wl,-soname,libandroid-spawn.so','-Wl,-z,max-page-size=16384','-Wl,-z,common-page-size=16384','-o',str(output)],check=True)
        files['lib/libandroid-spawn.so']=output.read_bytes();pins['aijsSpawn16kb']={'sourceSha256':pinned,'ndk':args.ndk,'pageSize':16384}
    save_sdk(args.component,files)
    bins={'radare2':{'radare2','r2','rabin2','rasm2','radiff2','rax2'},'unicorn':set(),'yara':{'yara','yarac'}}[args.component]
    payload={n:b for n,b in files.items() if (n.startswith('bin/') and Path(n).name in bins) or n.startswith(('share/radare2/','share/doc/','share/licenses/')) or (n.startswith('lib/') and ('.so' in Path(n).name))}
    payload['upstream-lock.json']=json.dumps(pins,ensure_ascii=False,indent=2).encode()
    payload.update(spawn_sources)
    if args.component in ['unicorn','yara']:
        runner=BASE/'tools/reverse-native'/('unicorn_runner.c' if args.component=='unicorn' else 'yara_runner.c')
        if not runner.is_file():print('SDK prepared; runner source pending',flush=True);return
        sdk=WORK/'sdk'/args.component;binary=WORK/('aijs-'+args.component)
        cc=Path(args.ndk)/'toolchains/llvm/prebuilt/windows-x86_64/bin/aarch64-linux-android26-clang.cmd'
        subprocess.run([str(cc),str(runner),'-O2','-I'+str(sdk/'include'),'-L'+str(sdk/'lib'),'-l'+args.component,'-Wl,--allow-shlib-undefined','-Wl,-z,max-page-size=16384','-Wl,-z,common-page-size=16384','-o',str(binary)],check=True)
        payload['bin/aijs-'+args.component]=binary.read_bytes();bins.add('aijs-'+args.component)
    licenses={'radare2':'LGPL-3.0 / GPL plugins and dependency licenses','unicorn':'GPL-2.0 and dependency licenses','yara':'BSD-3-Clause and dependency licenses'}
    if args.component in ['unicorn','yara']:payload['source/'+runner.name]=runner.read_bytes()
    emit(args,args.component,payload,'Termux official packages; dependency versions/hashes in upstream-lock.json',['bin/'+b for b in sorted(bins) if 'bin/'+b in payload],licenses[args.component])
def apktool(args):
    files,pins=termux(['apktool'],False);jars=[(n,b) for n,b in files.items() if n.endswith('.jar')];assert len(jars)==1,jars
    work=WORK/'sdk/apktool';work.mkdir(parents=True,exist_ok=True);jar=work/'apktool.jar';jar.write_bytes(jars[0][1]);print('Apktool jar',jars[0][0],jar,flush=True)
    port=BASE/'tools/apktool-android';source=port/'src/brut/androlib/res/decoder/ResNinePatchStreamDecoder.java'
    assert source.is_file(),'Android nine-patch source is required; desktop ImageIO is not available on ART'
    classes=work/'android-classes';classes.mkdir(exist_ok=True)
    subprocess.run([str(Path(args.jdk)/'bin/javac.exe'),'--release','8','-classpath',str(jar)+os.pathsep+'C:/Android/platforms/android-35/android.jar','-d',str(classes),str(source)],check=True)
    class_name='brut/androlib/res/decoder/ResNinePatchStreamDecoder.class';patched=work/'apktool-android.jar'
    with zipfile.ZipFile(jar) as original,zipfile.ZipFile(patched,'w',zipfile.ZIP_DEFLATED) as target:
        for name in original.namelist():
            if name==class_name or name.upper().endswith(('.SF','.RSA','.DSA')):continue
            target.writestr(name,original.read(name))
        target.writestr(class_name,(classes/class_name).read_bytes())
    dex=work/'dex';dex.mkdir(exist_ok=True)
    subprocess.run([str(Path(args.jdk)/'bin/java.exe'),'-Xmx2048m','-cp',args.d8,'com.android.tools.r8.D8','--min-api','26','--lib','C:/Android/platforms/android-35/android.jar','--output',str(dex),str(patched)],check=True)
    compiled=work/'apktool.dex.jar'
    with zipfile.ZipFile(compiled,'w',zipfile.ZIP_DEFLATED) as out,zipfile.ZipFile(patched) as source_jar:
        for p in sorted(dex.glob('classes*.dex')):out.write(p,p.name)
        for n in source_jar.namelist():
            if n.endswith(('/','.class')) or n.startswith(('META-INF/versions/','prebuilt/linux/','prebuilt/windows/','prebuilt/macos/')) or n.upper().endswith(('.SF','.DSA','.RSA')):continue
            out.writestr(n,source_jar.read(n))
    pins['aijsAndroidNinePatch']={'sha256':hashlib.sha256(source.read_bytes()).hexdigest(),'source':source.relative_to(BASE).as_posix()}
    payload={'apktool.dex.jar':compiled.read_bytes(),'upstream-lock.json':json.dumps(pins,indent=2).encode(),'source/ResNinePatchStreamDecoder.java':source.read_bytes(),
        'source/Android-ninepatch-README.md':(port/'README.md').read_bytes(),'licenses/Android-ninepatch-NOTICE':(port/'NOTICE').read_bytes()}
    for n,b in files.items():
        if n.startswith(('share/doc/','share/licenses/')):payload[n]=b
    emit(args,'apktool',payload,'https://github.com/iBotPeaches/Apktool (Termux Android packaging)',[],'Apache-2.0 and dependency licenses')

def frida(args):
    sdk=WORK/'sdk/frida-core';runner=BASE/'tools/reverse-native/frida_runner.c';binary=WORK/'aijs-frida';obj=WORK/'frida_runner.o'
    cc=Path(args.ndk)/'toolchains/llvm/prebuilt/windows-x86_64/bin/aarch64-linux-android26-clang.cmd'
    subprocess.run([str(cc),'-DANDROID','-O2','-Wall','-Wextra','-ffunction-sections','-fdata-sections','-I'+str(sdk),'-c',str(runner),'-o',str(obj)],check=True)
    subprocess.run([str(cc),str(obj),str(sdk/'libfrida-core.a'),'-llog','-ldl','-lm','-latomic','-pthread','-Wl,--export-dynamic','-Wl,--gc-sections','-Wl,-z,max-page-size=16384','-Wl,-z,common-page-size=16384','-o',str(binary)],check=True)
    subprocess.run([str(Path(args.ndk)/'toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strip.exe'),'--strip-unneeded',str(binary)],check=True)
    url='https://github.com/frida/frida/releases/download/17.25.2/frida-core-devkit-17.25.2-android-arm64.tar.xz'
    archive=WORK/'prototypes/frida-core.tar.xz';assert archive.is_file()
    payload={'bin/aijs-frida':binary.read_bytes(),'source/frida_runner.c':runner.read_bytes(),'source/frida_runner.o':obj.read_bytes(),'source/BUILD.md':(BASE/'tools/reverse-native/README.md').read_bytes(),
        'LICENSE-frida.txt':cached('https://raw.githubusercontent.com/frida/frida/17.25.2/COPYING'),
        'upstream-lock.json':json.dumps({'version':'17.25.2','sdkUrl':url,'sdkSha256':hashlib.sha256(archive.read_bytes()).hexdigest(),'runnerSha256':hashlib.sha256(runner.read_bytes()).hexdigest()},indent=2).encode()}
    emit(args,'frida',payload,url,['bin/aijs-frida'],'LGPL-2.1-or-later and bundled GLib/json-glib licenses; relink object/source included')

def r2ghidra(args):
    dist=BASE/'tools/reverse-native/r2ghidra-build/dist';meta=json.loads((dist/'manifest.json').read_text(encoding='utf8'))
    plugin=dist/'lib/core_ghidra.so';assert hashlib.sha256(plugin.read_bytes()).hexdigest()==meta['plugin']['sha256']
    files,pins=termux(['libc++'])
    payload={n:b for n,b in files.items() if n.startswith(('share/licenses/','share/doc/')) or (n.startswith('lib/') and '.so' in Path(n).name)}
    for p in dist.rglob('*'):
        if p.is_file():payload[p.relative_to(dist).as_posix()]=p.read_bytes()
    payload['upstream-lock.json']=json.dumps({'plugin':meta,'dependencies':pins},ensure_ascii=False,indent=2).encode()
    payload['source/build-r2ghidra.py']=(BASE/'tools/reverse-native/build-r2ghidra.py').read_bytes()
    payload['licenses/LGPL-3.0.txt']=cached('https://raw.githubusercontent.com/spdx/license-list-data/main/text/LGPL-3.0-only.txt')
    payload['licenses/GPL-3.0.txt']=cached('https://raw.githubusercontent.com/spdx/license-list-data/main/text/GPL-3.0-only.txt')
    payload['licenses/Ghidra-Apache-2.0.txt']=cached('https://raw.githubusercontent.com/iBotPeaches/Apktool/v3.0.3/LICENSE.md')
    emit(args,'r2ghidra',payload,'https://github.com/radareorg/r2ghidra/releases/tag/6.2.4 (ABI 152); upstream-lock.json records pinned sources and build',[],'LGPL-3.0, Apache-2.0 Ghidra, LLVM libc++ and dependency licenses')
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('component',choices=['radare2','unicorn','yara','apktool','frida','r2ghidra'])
    p.add_argument('--out',type=Path,default=BASE/'.artifacts/updates/components')
    p.add_argument('--ndk',default='C:/Android/ndk-r26d');p.add_argument('--jdk',default='C:/Program Files/Microsoft/jdk-17.0.19.10-hotspot')
    p.add_argument('--d8',default='C:/Users/18101/.gradle/caches/modules-2/files-2.1/com.android.tools/r8/8.5.35/6b41e4ac5e238c953246d07c006585aecc16c546/r8-8.5.35.jar')
    a=p.parse_args();({'apktool':apktool,'frida':frida,'r2ghidra':r2ghidra}.get(a.component,native))(a)
