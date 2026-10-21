"ui nodejs";
const assert=require('assert'),ui=require('ui');
const settings=require('settings');
const original=settings.foregroundService.value;
const open=ui.setMainActivity;
let instance;
ui.setMainActivity=function(Ctor,opts){const result=open(Ctor,opts);instance=result.instance;return result;};
require('./main.node.js');
const delay=ms=>new Promise(r=>setTimeout(r,ms));
(async()=>{
 try {
  const deadline=Date.now()+45000;
  while(Date.now()<deadline) {
   if(instance&&instance.webview&&await instance.webview.jsBridge.eval('document.body && document.body.innerText.includes("基于Vue的界面")'))break;
   await delay(250);
  }
  const bridge=instance.webview.jsBridge;
  assert(await bridge.eval('document.body && document.body.innerText.includes("基于Vue的界面")'));
  // Wait for the original get-foreground request to initialize Vue's v-model.
  await delay(500);
  await bridge.eval('document.querySelector(".van-switch").click()');
  for(let i=0;i<30&&settings.foregroundService.value===original;i++)await delay(100);
  assert.strictEqual(settings.foregroundService.value,!original);
  await bridge.eval('document.querySelector(".van-switch").click()');
  for(let i=0;i<30&&settings.foregroundService.value!==original;i++)await delay(100);
  assert.strictEqual(settings.foregroundService.value,original);
  const color=ui.getWindow().getStatusBarColor();
  assert.strictEqual(color,-1);
  console.log('NODE_VUE2_INTERACTION_RESULT '+JSON.stringify({ok:true,realSettingsRoundTrip:true,restored:true,initialStatusBar:true}));
 } catch(e) {console.error('NODE_VUE2_INTERACTION_FAIL '+(e.stack||e));process.exitCode=1;} finally {settings.foregroundService.value=original;ui.close();}
})().catch(e=>{console.error(e.stack||e);process.exitCode=1;});
