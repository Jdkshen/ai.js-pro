"nodejs";
const assert=require('assert');
const plugins=require('plugins');
const fen='rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1';
(async()=>{
    assert(Number(host.context.getPackageManager().getPackageInfo('com.jdkshen.xiangqi.plugin',0).versionCode)>=2,'requires plugin 1.0.1+');
    const chess=await plugins.load('com.jdkshen.xiangqi.plugin');
    try {
        assert(chess.engine.start({threads:1,hashMb:16}).ok);
        const invalid=chess.engine.analyze('9/9/9/9/9/9/9/9/9/9 w - - 0 1',100,7000,1,1);
        assert(invalid.ok===false&&invalid.error.includes('invalid-position'));
        const invalidHistory=chess.engine.analyzePosition('position fen '+fen+' moves a0a0',100,7000,1,1);
        assert(invalidHistory.ok===false&&invalidHistory.error.includes('Illegal move'));
        const valid=chess.engine.analyze(fen,100,7000,1,1);
        assert(valid.ok&&/^[a-i][0-9][a-i][0-9]$/.test(valid.move));
        chess.engine.quit();
        assert(!chess.engine.isRunning());
        console.log('PIKAFISH_PLUGIN_SAFE_RESULT='+JSON.stringify({total:5,passed:5,move:valid.move}));
    }finally{chess.engine.quit();await plugins.unloadAll();}
})().catch(e=>{console.error(e.stack||e);process.exitCode=1;});
