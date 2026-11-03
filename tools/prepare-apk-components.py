"""Prepare optional Android aapt2/JADX payloads; not embedded in the main APK.
Downloads only upstream packages/dependencies, records pins, and emits the verified store manifest.
Run after implementation, before the unified build/device acceptance phase.
"""
from pathlib import Path
import argparse, gzip, hashlib, io, json, os, re, shutil, subprocess, tarfile, urllib.request, zipfile

def fetch(url, expected=None, limit=128*1024*1024):
    with urllib.request.urlopen(url, timeout=45) as f:
        data=f.read(limit+1)
    if len(data)>limit: raise ValueError('upstream object too large')
    if expected and hashlib.sha256(data).hexdigest()!=expected: raise ValueError('upstream sha256 mismatch: '+url)
    return data

def deb_data(data):
    if not data.startswith(b'!<arch>\n'): raise ValueError('not a deb archive')
    pos=8
    while pos+60<=len(data):
        h=data[pos:pos+60];name=h[:16].decode().strip().rstrip('/');size=int(h[48:58]);pos+=60
        value=data[pos:pos+size];pos+=size+(size%2)
        if name.startswith('data.tar'):
            if name.endswith('.zst'):
                from compression import zstd
                value=zstd.decompress(value)
            return tarfile.open(fileobj=io.BytesIO(value),mode='r:*')
    raise ValueError('deb has no payload')

def package(out, component, files, source, executables):
    out.mkdir(parents=True,exist_ok=True);archive=out/(component+'-arm64-v1.zip')
    with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED,compresslevel=9) as z:
        for name,data in sorted(files.items()):
            if name.startswith('/') or '..' in name.split('/'): raise ValueError('unsafe component path')
            z.writestr(name,data)
    manifest={'schemaVersion':1,'id':component,'version':'v1','abi':'arm64-v8a','minSdk':26,
        'url':archive.name,'size':archive.stat().st_size,'sha256':hashlib.sha256(archive.read_bytes()).hexdigest(),
        'unpackedSize':sum(map(len,files.values())),'executables':executables,
        'license':'Apache-2.0 and upstream dependency licenses (included)','source':source}
    (out/(component+'-arm64-v1.json')).write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(manifest,ensure_ascii=False))

def resources(args):
    base='https://packages.termux.dev/apt/termux-main/'
    data=gzip.decompress(fetch(base+'dists/stable/main/binary-aarch64/Packages.gz'))
    packages={}
    for block in data.decode().split('\n\n'):
        fields={};last=None
        for line in block.splitlines():
            if line.startswith(' ') and last: fields[last]+=' '+line.strip()
            elif ': ' in line:last,value=line.split(': ',1);fields[last]=value
        if fields.get('Package'):packages[fields['Package']]=fields
    queue=['aapt2'];selected={};files={};links={}
    while queue:
        name=queue.pop(0)
        if name in selected:continue
        if name not in packages:raise ValueError('missing upstream runtime dependency '+name)
        meta=packages[name];selected[name]=meta
        for item in meta.get('Depends','').split(','):
            dep=re.split(r'[ (|]',item.strip())[0]
            if dep:queue.append(dep)
        raw=fetch(base+meta['Filename'],meta['SHA256'])
        with deb_data(raw) as tar:
            for m in tar.getmembers():
                n=m.name.removeprefix('./');prefix='data/data/com.termux/files/usr/'
                if not n.startswith(prefix):continue
                n=n[len(prefix):]
                if n!='bin/aapt2' and not n.startswith(('lib/','share/doc/','share/licenses/')):continue
                if m.isfile():
                    if m.size>128*1024*1024:raise ValueError('large component file')
                    files[n]=tar.extractfile(m).read()
                elif m.issym():
                    target=m.linkname
                    if target.startswith('/'+prefix):target=target[len(prefix)+1:]
                    elif not target.startswith('/'):target=os.path.normpath(str(Path(n).parent/target)).replace('\\','/')
                    if target.startswith('/') or '..' in target.split('/'):raise ValueError('escaping upstream symlink')
                    links[n]=target
    for name,target in links.items():
        seen={name}
        while target in links:
            if target in seen:raise ValueError('upstream symlink cycle')
            seen.add(target);target=links[target]
        if target in files:files[name]=files[target]
    if 'bin/aapt2' not in files:raise ValueError('upstream aapt2 package did not contain aapt2')
    files={n:b for n,b in files.items() if n=='bin/aapt2' or n.startswith(('share/doc/','share/licenses/')) or (n.startswith('lib/') and ('.so' in Path(n).name))}
    files['android.jar']=Path(args.android_jar).read_bytes()
    files['upstream-lock.json']=json.dumps(selected,ensure_ascii=False,indent=2).encode()
    package(args.out,'apk-resources',files,'https://github.com/termux/android-build-tools + Android SDK platform '+str(args.android_jar),['bin/aapt2'])

def jadx(args):
    repo=Path(__file__).absolute().parent.parent;work=repo/'.artifacts/apk-components';jars=work/'jadx-jars';dex=work/'jadx-dex';dex.mkdir(parents=True,exist_ok=True)
    env=os.environ.copy();env['JAVA_HOME']=args.jdk;env['PATH']=str(Path(args.jdk)/'bin')+os.pathsep+env['PATH']
    subprocess.run([str(repo/'gradlew.bat'),'-p',str(repo/'tools/apk-components'),'copyJadxPayload','-PpayloadOutput='+str(jars),'--console=plain','--max-workers=1'],env=env,check=True)
    inputs=sorted(jars.glob('*.jar'));assert inputs
    subprocess.run([str(Path(args.jdk)/'bin/java.exe'),'-Xmx2048m','-cp',args.d8_jar,'com.android.tools.r8.D8','--min-api','26','--lib',args.android_jar,'--output',str(dex),*map(str,inputs)],env=env,check=True)
    payload=work/'jadx.dex.jar'
    with zipfile.ZipFile(payload,'w',zipfile.ZIP_DEFLATED,compresslevel=9) as z:
        seen=set()
        for p in sorted(dex.glob('classes*.dex')):z.write(p,p.name);seen.add(p.name)
        for p in inputs:
            with zipfile.ZipFile(p) as source:
                for n in source.namelist():
                    if n in seen or n.endswith(('/', '.class')) or n.startswith('META-INF/versions/') or n.upper().endswith(('.SF','.RSA','.DSA')):continue
                    if n.startswith('/') or '..' in n.split('/'):raise ValueError('unsafe jar resource')
                    seen.add(n);z.writestr(n,source.read(n))
    files={'jadx.dex.jar':payload.read_bytes(),'upstream-lock.json':json.dumps({p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in inputs},indent=2).encode()}
    files['licenses/JADX-LICENSE']=fetch('https://raw.githubusercontent.com/skylot/jadx/v1.5.6/LICENSE')
    package(args.out,'jadx',files,'https://github.com/skylot/jadx/tree/v1.5.6',[])

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('component',choices=['resources','jadx'])
    parser.add_argument('--out',type=Path,default=Path('.artifacts/updates/components'))
    parser.add_argument('--android-jar',default='C:/Android/platforms/android-35/android.jar')
    parser.add_argument('--jdk',default='C:/Program Files/Microsoft/jdk-17.0.19.10-hotspot')
    parser.add_argument('--d8-jar',default='C:/Android/build-tools/34.0.0/lib/d8.jar')
    args=parser.parse_args();(resources if args.component=='resources' else jadx)(args)
