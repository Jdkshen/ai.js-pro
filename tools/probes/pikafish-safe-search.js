"quickjs";
if(app.versionCode<1193)throw Error('Requires native position-validation patch');
var result={checks:[]};
var fen='rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1';
function check(name,fn){try{if(!fn())throw Error('assertion');result.checks.push({name:name,ok:true});}catch(e){result.checks.push({name:name,ok:false,error:String(e)});}}
var started=$pikafish.start({threads:1,hashMb:16});
if(!started.ok)throw Error(JSON.stringify(started));
try{
 check('quickjs_invalid_position_returns_error',function(){var r=$pikafish.analyze('9/9/9/9/9/9/9/9/9/9 w - - 0 1',100,7000,1,1);return r.ok===false&&String(r.error).indexOf('invalid-position')>=0;});
 check('valid_search_after_error',function(){var r=$pikafish.analyze(fen,100,7000,1,1);result.validMove=r.move;return r.ok===true&&/^[a-i][0-9][a-i][0-9]$/.test(r.move);});
 check('quickjs_invalid_history_returns_error',function(){var r=$pikafish.analyzePosition('position fen '+fen+' moves a0a0',100,7000,1,1);return r.ok===false&&String(r.error).indexOf('Illegal move')>=0;});
 check('valid_history_after_error',function(){var r=$pikafish.analyzePosition('position fen '+fen+' moves b0c2',100,7000,1,1);result.historyMove=r.move;return r.ok===true&&/^[a-i][0-9][a-i][0-9]$/.test(r.move);});
 check('repeat_rejections_do_not_kill_engine',function(){for(var i=0;i<25;i++){var r=$pikafish.analyzePosition('position fen '+fen+' moves a0a0',100,7000,1,1);if(r.ok!==false)return false;}return $pikafish.isRunning();});
}finally{if(typeof $pikafish.quit==='function')$pikafish.quit();else $pikafish.stop();}
result.total=result.checks.length;result.passed=result.checks.filter(function(c){return c.ok;}).length;
console.log('PIKAFISH_SAFE_SEARCH_RESULT='+JSON.stringify(result));
exit();
