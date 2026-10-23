"nodejs";
const state={ok:false,checks:[],fastAvailable:typeof globalThis.__ajsJavaFastCall==='function'};
function check(name,value){if(!value)throw Error(name);state.checks.push(name);}
const nativeFast=globalThis.__ajsJavaFastCall;
let hits=0;
if(nativeFast)globalThis.__ajsJavaFastCall=function(){hits++;return nativeFast.apply(this,arguments);};
(async()=>{
 try{
  check('numeric overload int',java.lang.Math.max(3,7)===7);
  check('numeric overload fraction',java.lang.Math.max(1.5,2.75)===2.75);
  check('numeric overload long',java.lang.Math.max(5000000000,4000000000)===5000000000);
  const fastFunction=globalThis.__ajsJavaFastCall;
  globalThis.__ajsJavaFastCall=undefined;
  const legacyLarge=java.lang.String.valueOf(1000000000000000100);
  globalThis.__ajsJavaFastCall=fastFunction;
  check('unsafe integer keeps JSON conversion',java.lang.String.valueOf(1000000000000000100)===legacyLarge);
  const huge=new java.math.BigInteger('1'+'0'.repeat(400));
  let largeFailed=false;try{huge.add(new java.math.BigInteger('0'));}catch(error){largeFailed=true;}
  check('non-finite BigInteger result rejected like JSON',largeFailed);
  check('boolean null args',java.util.Objects.equals(null,null)===true);
  const a=new java.lang.StringBuilder(),b=new java.lang.StringBuilder();
  a.append('中文🙂');b.append('other');
  check('receiver isolation',a.toString()==='中文🙂'&&b.toString()==='other');
  check('fluent reference identity',a.append('!').equals(a));
  const text=new java.lang.String('ab');
  check('char result',text.charAt(1)==='b');
  const bytes=text.getBytes('UTF-8');
  check('array result reference',bytes.length===2&&bytes[0]===97);
  check('Class result',a.getClass().getName()==='java.lang.StringBuilder');
  check('Class.forName host loader',java.lang.Class.forName('com.google.android.material.button.MaterialButton').getName().includes('MaterialButton'));
  let threw=false;try{text.charAt(9);}catch(error){threw=String(error).includes('charAt');}
  check('Java exception propagates',threw);
  check('recovery after error',text.charAt(0)==='a');
  class FastNested extends java.lang.Object {toString(){return 'nested:'+java.lang.Math.max(2,7);}}
  const start=Date.now();await $java.defineClass(FastNested);state.defineMs=Date.now()-start;
  state.directDex=new FastNested().getClass().getClassLoader().getClass().getName().includes('InMemoryDexClassLoader');
  if(nativeFast)check('direct dex in-memory loader',state.directDex);
  check('generated override and nested Java call',new java.lang.StringBuilder().append(new FastNested()).toString()==='nested:7');
  let wideCalled=false;
  class WideCanvas extends android.graphics.Canvas {
   drawVertices(mode,count,vertices,offset,texture,textureOffset,colors,colorOffset,indices,indexOffset,indexCount,paint){wideCalled=true;}
  }
  await $java.defineClass(WideCanvas);
  const canvas=new WideCanvas(),methods=canvas.getClass().getDeclaredMethods();
  for(let i=0;i<methods.length;i++)if(methods[i].getName()==='drawVertices'){
   methods[i].invoke(canvas,[null,0,null,0,null,0,null,0,null,0,0,null]);break;
  }
  check('large generated method verified and invoked by ART',wideCalled);
  if(nativeFast)check('typed JNI used',hits>10);
  const method=java.lang.Math.abs;
  function bench(){let sum=0;const start=Date.now();for(let i=0;i<1500;i++)sum+=method(-i);check('benchmark values',sum===1124250);return Date.now()-start;}
  method(-1);state.fastMs=[];state.jsonMs=[];
  for(let i=0;i<3;i++){
   globalThis.__ajsJavaFastCall=nativeFast;state.fastMs.push(bench());
   globalThis.__ajsJavaFastCall=undefined;state.jsonMs.push(bench());
  }
  state.fastCalls=hits;state.ok=true;
  console.log('JAVA_FAST_ALL_PASS',JSON.stringify(state));
 }finally{globalThis.__ajsJavaFastCall=nativeFast;}
})().catch(error=>{console.error('JAVA_FAST_FAILED',String(error.stack||error));process.exitCode=1;});
