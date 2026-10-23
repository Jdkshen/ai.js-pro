"nodejs";
// Both production backends, on the same ART process. No APK/dex supplied by third parties.
const context=$autojs.androidContext;
const Generator=$java.findClass('org.autojs.source.engine.android.Node24ClassGenerator');
const Direct=$java.findClass('org.autojs.source.engine.android.Node24DirectDexGenerator');
const Loader=$java.findClass('com.stardust.autojs.rhino.AndroidClassLoader');
const methods=new java.util.HashSet();methods.add('toString');
const state={ok:false,direct:[],legacy:[]},stamp=Date.now();
function one(direct,i){
 const name='perf.dex.G'+stamp+'_'+(direct?'D':'L')+i;
 const file=new java.io.File(context.getCodeCacheDir(),'dex-perf-'+stamp+'-'+(direct?'D':'L')+i);
 const loader=new Loader(context.getClassLoader(),file);
 const start=Date.now();
 const bytes=direct?Direct.generateDex(name,java.lang.Object,null,methods):Generator.generate(name,java.lang.Object,null,methods);
 const result=direct?loader.defineDex(name,bytes):loader.defineClass(name,bytes,true);
 if(result.getName()!==name)throw Error('wrong generated class');
 return Date.now()-start;
}
state.firstDirect=one(true,0);state.firstLegacy=one(false,0);
for(let i=1;i<=5;i++){
 if(i%2){state.direct.push(one(true,i));state.legacy.push(one(false,i));}
 else {state.legacy.push(one(false,i));state.direct.push(one(true,i));}
}
state.ok=true;console.log('DEX_PERF_ALL_PASS',JSON.stringify(state));
