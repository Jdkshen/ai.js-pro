"ui nodejs";
const ui=require('ui'),shell=require('shell');
class A extends ui.Activity {get layoutXml(){return '<text text="Shell UI callback check"/>';}}
ui.setMainActivity(A);
let done=false;
const watchdog=setTimeout(()=>{if(!done){console.error('NODE_SHELL_UI_RESULT false timeout');ui.close();process.exit(1);}},5000);
shell.exec('node --version && npm --version').then(r=>{done=true;clearTimeout(watchdog);console.log('NODE_SHELL_UI_RESULT '+JSON.stringify(r));ui.close();}).catch(e=>{done=true;console.error(e.stack||e);ui.close();});
