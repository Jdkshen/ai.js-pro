"nodejs";
const fs=require('fs'), state={ok:false,checks:[],ticks:0};
const output='/sdcard/脚本/node24-define-class-result.json';
function save(){fs.writeFileSync(output,JSON.stringify(state));}
function check(name,value){if(!value)throw Error(name);state.checks.push(name);save();}
const timer=setInterval(()=>state.ticks++,5);
(async()=>{
 try{
  class AsyncA extends java.lang.Object {toString(){return 'async-a';}}
  class AsyncB extends java.lang.Object {toString(){return 'async-b';}}
  const start=Date.now(),a=$java.defineClass(AsyncA),b=$java.defineClass(AsyncB);
  check('submit returns before compile',Date.now()-start<150);
  let duplicate=false;try{await $java.defineClass(AsyncA);}catch(e){duplicate=String(e).includes('已定义');}
  check('duplicate in-flight rejected',duplicate);
  await Promise.all([a,b]);
  check('event loop progresses during compile',state.ticks>2);
  check('parallel A callback',new java.lang.StringBuilder().append(new AsyncA()).toString().toString()==='async-a');
  check('parallel B callback',new java.lang.StringBuilder().append(new AsyncB()).toString().toString()==='async-b');
  check('generated loader chain',new AsyncB().getClass().getClassLoader().loadClass('AsyncA').getName().toString()==='AsyncA');
  class RetryCompile extends java.lang.String {}
  let failures=0;
  for(let i=0;i<2;i++){try{await $java.defineClass(RetryCompile);}catch(e){if(String(e).includes('final'))failures++;}}
  check('compile error and retry reach host',failures===2);
  state.elapsed=Date.now()-start;state.ok=true;save();console.log('DEFINE_CLASS_ALL_PASS',JSON.stringify(state));
 }catch(error){state.error=String(error.stack||error);save();throw error;}
 finally{clearInterval(timer);}
})().catch(error=>{console.error(error);process.exitCode=1;});
