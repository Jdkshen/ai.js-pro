"ui"; "nodejs";
// task-236: binding cache + batch view attributes on real Activity views.
const ui=require('ui');
const state={ok:false,checks:[],fast:{}};
function check(name,value){if(!value)throw Error(name);state.checks.push(name);console.log('BIND_CHECK',name);}
const fastOrig=globalThis.__ajsJavaFastCall;
let fastCalls=0;
if(fastOrig){globalThis.__ajsJavaFastCall=function(){fastCalls++;return fastOrig.apply(this,arguments);};}
class Batch extends ui.Activity{
 get layoutXml(){return '<column id="root" w="*" h="*"><text id="title" text="a"/><text id="body" text="b"/></column>';}
 onContentViewSet(view){
  try{
   const binding=view.binding;
   check('binding proxy identity',binding===view.binding);
   const before=fastCalls;
   const title=binding.title;
   const first=fastCalls;
   for(let i=0;i<50;i++){if(binding.title!==title)throw Error('binding child identity');}
   const repeat=fastCalls;
   check('binding child cached',repeat===first);
   check('binding child identity',title===binding.title);
   check('binding has own key','title' in binding);
   check('binding keys include ids',Object.keys(binding).includes('body'));
   const attrBefore=fastCalls;
   title.attr({text:'batched',alpha:'0.5',visibility:'visible'});
   const attrAfter=fastCalls;
   check('batch attr applied',String(title.getText())==='batched'&&Math.abs(Number(title.getAlpha())-0.5)<0.01&&Number(title.getVisibility())===0);
   check('batch attr one bridge call',attrAfter-attrBefore<=2);
   state.fast={bindingFirst:first-before,bindingRepeat:repeat-first,attrCalls:attrAfter-attrBefore};
   state.ok=true;
   console.log('BINDING_BATCH_ALL_PASS',JSON.stringify(state));
  }catch(e){state.error=String(e.stack||e);console.error('BINDING_BATCH_FAILED',state.error);process.exitCode=1;}
  process.exit(0);
 }
}
ui.setMainActivity(Batch);
