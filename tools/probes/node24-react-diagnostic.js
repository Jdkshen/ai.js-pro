"nodejs";
const shell=require('shell');
const q=s=>"'"+s.replace(/'/g,"'\\''")+"'";
(async()=>{
 const code='const stop=process.exit;process.exit=function(c){require("fs").writeSync(2,"EXIT_STACK "+c+" "+new Error().stack+"\\n");stop(c)};const old=Promise.prototype.catch;Promise.prototype.catch=function(fn){return old.call(this,e=>{require("fs").writeSync(2,"REJECT_TRACE "+String(e)+" "+String(e&&e.stack)+"\\n");return fn(e)})};require("./node_modules/react-scripts/scripts/start.js")';
 const sh=shell.createShell({cwd:'/sdcard/脚本/_node_pro_gaps_/react-dev',timeout:25000});
 sh.on('line',l=>console.log('REACT_TRACE '+l));
 console.log('REACT_TRACE_RESULT '+JSON.stringify(await sh.exec('node -e '+q(code))));
})().catch(e=>{console.error(e.stack||e);process.exitCode=1;});
