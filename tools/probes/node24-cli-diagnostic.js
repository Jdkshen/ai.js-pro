"nodejs";
const shell=require('shell');
const q=s=>"'"+s.replace(/'/g,"'\\''")+"'";
(async()=>{
 const cwd='/sdcard/脚本/_node_pro_gaps_/react-dev';
 for(const [name,code] of [
 ['execPath','console.log(process.execPath)'],
 ['spawn','const p=require("child_process").spawnSync(process.execPath,["-e","console.log(42)"],{encoding:"utf8"}); console.log({status:p.status,signal:p.signal,error:String(p.error),stdout:p.stdout,stderr:p.stderr})']]) {
  console.log('CLI_DIAG '+name+' '+JSON.stringify(await shell.exec('node -e '+q(code),{cwd,timeout:5000})));
 }
 const sh=shell.createShell({cwd,timeout:20000});sh.on('line',l=>console.log('DIRECT_REACT '+l));
 console.log('DIRECT_REACT_RESULT '+JSON.stringify(await sh.exec('node node_modules/react-scripts/scripts/start.js')));
})().catch(e=>{console.error(e.stack||e);process.exitCode=1;});
