"rhino";
if(app.versionCode<1193)throw Error('This regression requires the native position-validation patch (dev-1193+)');
// Deliberately invalid positions must return errors, without terminating Android.
var P=com.stardust.autojs.engine.PikafishNativeBridge;
var result={checks:[]};
var startFen='rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1';
function check(name,fn){try{if(!fn())throw Error('assertion');result.checks.push({name:name,ok:true});}catch(e){result.checks.push({name:name,ok:false,error:String(e)});}}
if(!P.isAvailable())throw Error(P.loadError());
var started=P.nativeStart('',1,16,0);
if(!started)throw Error('native start failed');
try{
 check('missing_kings_rejected',function(){return String(P.nativeAnalyze('9/9/9/9/9/9/9/9/9/9 w - - 0 1',50,2000,1,1)).indexOf('error: invalid-position:')===0;});
 check('impossible_history_rejected',function(){return String(P.nativeAnalyzePosition('position fen '+startFen+' moves a0a0',50,2000,1,1)).indexOf('Illegal move:')>=0;});
 check('illegal_piece_count_rejected',function(){return String(P.nativeAnalyze('rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/R8/RNBAKABNR w - - 0 1',50,2000,1,1)).indexOf('error: invalid-position:')===0;});
 check('invalid_rule60_rejected',function(){return String(P.nativeAnalyze(startFen.replace('0 1','120 1'),50,2000,1,1)).indexOf('error: invalid-position:')===0;});
 check('engine_stays_alive',function(){return P.nativeIsRunning();});
}finally{P.nativeQuit();}
check('quit_cleanly',function(){return !P.nativeIsRunning();});
result.total=result.checks.length;result.passed=result.checks.filter(function(c){return c.ok;}).length;
console.log('PIKAFISH_INVALID_POSITION_RESULT='+JSON.stringify(result));
exit();
