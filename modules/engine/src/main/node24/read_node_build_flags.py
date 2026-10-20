"""Read public V8 ABI defines from the matching generated Release target.

config.gypi omits variables whose defaults live in common.gypi. The generated
node_base.target.mk contains GYP's evaluated defines, including those defaults.
Require that build evidence and cross-check every ABI switch present in config.
"""
import ast,pathlib,re,shlex,sys
KEYS={
 'v8_enable_v8_checks':'V8_ENABLE_CHECKS',
 'v8_enable_webassembly':'V8_ENABLE_WEBASSEMBLY',
 'v8_enable_pointer_compression':'V8_COMPRESS_POINTERS',
 'v8_enable_zone_compression':'V8_COMPRESS_ZONES',
 'v8_enable_sandbox':'V8_ENABLE_SANDBOX',
 'v8_enable_external_code_space':'V8_EXTERNAL_CODE_SPACE',
 'v8_deprecation_warnings':'V8_DEPRECATION_WARNINGS',
 'v8_imminent_deprecation_warnings':'V8_IMMINENT_DEPRECATION_WARNINGS',
 'v8_use_perfetto':'V8_USE_PERFETTO',
 'v8_enable_map_packing':'V8_MAP_PACKING',
 'tsan':'V8_IS_TSAN',
 'v8_enable_conservative_stack_scanning':'V8_ENABLE_CONSERVATIVE_STACK_SCANNING',
 'v8_enable_direct_local':'V8_ENABLE_DIRECT_LOCAL',
}
def flags(path):
    path=pathlib.Path(path)
    variables=ast.literal_eval(path.read_text(encoding='utf-8'))['variables']
    if variables.get('target_arch')!='arm64' or variables.get('node_shared') not in [True,1,'true','1']:
        raise ValueError('Need the actual Android arm64 shared Node build config')
    required={'v8_enable_pointer_compression','v8_enable_sandbox','v8_enable_external_code_space',
              'v8_enable_pointer_compression_shared_cage','v8_enable_31bit_smis_on_64bit_arch'}
    missing=required-variables.keys()
    if missing: raise ValueError('V8 ABI variables absent: '+', '.join(sorted(missing)))
    yes=lambda key:variables[key] in [True,1,'true','1']
    candidates=[path.parent/'node_base.target.mk',path.parent/'out/node_base.target.mk']
    makefile=next((p for p in candidates if p.is_file()),None)
    if makefile is None:
        raise ValueError('Need matching generated node_base.target.mk beside config.gypi or in its out directory')
    content=makefile.read_text(encoding='utf-8')
    if not re.search(r'^TOOLSET := target$',content,re.M) or not re.search(r'^TARGET := node_base$',content,re.M):
        raise ValueError('Expected the generated Node target makefile')
    match=re.search(r'^DEFS_Release := ([^\n]+)$',content.replace('\\\n',' '),re.M)
    if not match: raise ValueError('Release target defines missing')
    tokens=shlex.split(match.group(1))
    if not tokens or any(not token.startswith('-D') for token in tokens):
        raise ValueError('Unexpected Release target defines')
    macros=dict(token[2:].split('=',1) if '=' in token else (token[2:],'1') for token in tokens)
    defines=set(macros)
    if macros.get('NODE_ARCH')!='"arm64"' or macros.get('NODE_PLATFORM')!='"android"':
        raise ValueError('Generated target is not Android arm64')
    expected={define:yes(key) for key,define in KEYS.items() if key in variables}
    expected['V8_COMPRESS_POINTERS_IN_SHARED_CAGE']=yes('v8_enable_pointer_compression_shared_cage')
    expected['V8_COMPRESS_POINTERS_IN_MULTIPLE_CAGES']=yes('v8_enable_pointer_compression') and not yes('v8_enable_pointer_compression_shared_cage')
    expected['V8_31BIT_SMIS_ON_64BIT_ARCH']=yes('v8_enable_pointer_compression') or yes('v8_enable_31bit_smis_on_64bit_arch')
    if any((define in defines)!=enabled for define,enabled in expected.items()):
        raise ValueError('Generated target ABI defines do not match config.gypi')
    allowed=set(KEYS.values())|{'V8_COMPRESS_POINTERS_IN_SHARED_CAGE',
        'V8_COMPRESS_POINTERS_IN_MULTIPLE_CAGES','V8_31BIT_SMIS_ON_64BIT_ARCH'}
    out=['USING_V8_SHARED=1','USING_UV_SHARED=1']+sorted(defines & allowed)
    return out
if __name__=='__main__':
    try: print(';'.join(flags(sys.argv[1])))
    except Exception as error: raise SystemExit(str(error))
