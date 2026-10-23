"ui"; "nodejs";
// Device regression for AIjsPro-Node24-UI-Bugs.md; writes evidence before any failure.
const ui = require('ui'), fs = require('fs');
const evidence = '/sdcard/脚本/node24-ui-dynamic-result.json';
const state = {ok:false, checks:[], rounds:0, generations:0, started:Date.now()};
function save(){fs.writeFileSync(evidence,JSON.stringify(state));}
function check(name,value){if(!value)throw Error('UI_ASSERT '+name);state.checks.push(name);save();console.log('UI_CHECK',name);}
function fail(error){state.error=String(error.stack||error);save();console.error('UI_PROBE_FAILED',state.error);process.exitCode=1;ui.close();}
process.on('uncaughtException',fail);
process.on('unhandledRejection',fail);
save();
const detached=ui.inflate('<column id="detached"><text id="detachedText" text="offscreen"/></column>');
check('offscreen XML inflate',detached.binding.detachedText.getText().toString()==='offscreen');
check('Android attr nested class',android.R.attr.state_checked>0);
check('Android color nested class',android.R.color.system_accent1_500>0);
check('host Class.forName',java.lang.Class.forName('com.google.android.material.button.MaterialButton').getName().toString().includes('MaterialButton'));
class Card extends ui.Widget {render(){return '<frame padding="8"><column id="slot"/></frame>';}}
ui.Widget.register('m3-card',Card);
let count=0, startedTests=false, watcherHits=0, progressHits=0;
class Page extends ui.Activity {
 get layoutXml(){return '<column id="root" w="*" h="*"><text id="generation" text="generation '+count+'"/>'
  +'<switch id="sw" checked="true"/><m3-card id="card"><text id="child" text="child"/></m3-card>'
  +'<button id="rebuild" text="recreate"/><frame id="stress" w="*" h="*"/></column>';}
 onContentViewSet(view){
  try {
   check('lifecycle UI origin '+count,ui.isUiThread());
   check('fresh XML generation '+count,view.binding.generation.getText().toString()==='generation '+count);
   state.generations++;save();
   view.binding.rebuild.on('click',()=>{count++;this.recreate();});
   if(count<3){setTimeout(()=>view.binding.rebuild.performClick(),150);return;}
   if(startedTests)return;startedTests=true;
   check('switch alias',view.binding.sw.getClass().getName().toString().includes('Switch'));
   check('Widget slot children',view.binding.card.binding.slot.getChildCount()===1);
   check('enumerable binding',Object.keys(view.binding).includes('child'));
   check('missing binding undefined',view.binding.notARealId===undefined);
   check('missing binding not in keys','notARealId' in view.binding===false);
   const inflated=ui.inflate('<column id="dynamic"><text id="dynamicText" text="dynamic"/></column>',view,false);
   check('dynamic XML binding',inflated.binding.dynamicText.getText().toString()==='dynamic');
   check('parent LayoutParams',inflated.getLayoutParams()!=null);
   const attached=ui.inflate('<text id="attached" text="attach"/>',inflated,true);
   check('attachToParent',attached.getParent().equals(inflated));
   const ctx=view.getContext(), input=new android.widget.EditText(ctx);
   const watcher={count:0,beforeTextChanged(){},onTextChanged(){watcherHits++;this.count++;},afterTextChanged(){}};
   input.addTextChangedListener(watcher);
   input.setText('listen');check('TextWatcher object',watcherHits>0);
   check('listener this state',watcher.count>0);
   const seek=new android.widget.SeekBar(ctx);
   seek.setOnSeekBarChangeListener({onProgressChanged(){progressHits++;},onStartTrackingTouch(){},onStopTrackingTouch(){}});
   seek.setProgress(40);check('SeekBar object',progressHits>0);
   const button=new android.widget.Button(ctx);let clicked=false;
   button.on('click',()=>{clicked=true;});button.performClick();check('raw View.on',clicked);
   view.post(new java.lang.Runnable({run(){check('View.post UI origin',ui.isUiThread());}}));
   ui.runOnUiThread(()=>{check('runOnUiThread UI origin',ui.isUiThread());return 42;})
    .then(value=>check('runOnUiThread result',value===42));
   console.error('UI_ERROR_VISIBLE_MARKER');console.warn('UI_WARN_VISIBLE_MARKER');
   setTimeout(()=>stress(view.binding.stress),100);
  }catch(error){fail(error);}
 }
}
function stress(container){
 try{
  const ctx=container.getContext(), root=new android.widget.LinearLayout(ctx);root.setOrientation(1);
  const begin=Date.now();
  for(let i=0;i<40;i++){
   const card=new android.widget.FrameLayout(ctx),g=new android.graphics.drawable.GradientDrawable();
   g.setColor(0xfff3edf7);g.setCornerRadius(32);card.setBackground(g);
   const col=new android.widget.LinearLayout(ctx);col.setOrientation(1);
   for(let j=0;j<4;j++){const tv=new android.widget.TextView(ctx);tv.setText('round '+state.rounds+' card '+i+' line '+j);
    tv.setTextSize(14);col.addView(tv,new android.widget.LinearLayout.LayoutParams(-1,-2));}
   card.addView(col,new android.widget.FrameLayout.LayoutParams(-1,-2));
   root.addView(card,new android.widget.LinearLayout.LayoutParams(-1,-2));
   console.log('UI_BUILD',state.rounds,i);
  }
  container.removeAllViews();container.addView(root,new android.widget.FrameLayout.LayoutParams(-1,-1));
  state.rounds++;state.lastBuildMillis=Date.now()-begin;save();
  if(state.rounds<20){setTimeout(()=>stress(container),40);return;}
  check('20 nested card builds with logs',state.rounds===20);
  // No manual keepRunning: Activity owns the event loop through all rounds.
  const remaining=Math.max(0,18000-(Date.now()-state.started));
  setTimeout(()=>{state.ok=true;state.elapsed=Date.now()-state.started;save();console.log('UI_DYNAMIC_ALL_PASS',JSON.stringify(state));ui.close();},remaining);
 }catch(error){fail(error);}
}
ui.setMainActivity(Page);
