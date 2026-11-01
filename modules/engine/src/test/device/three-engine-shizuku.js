// Run on a test device with Shizuku already running and this package authorized.
// Node awaits the asynchronous bridge; Rhino/QuickJS use their script worker.
(function () {
    function check(name, actual, expected) {
        if (JSON.stringify(actual) !== JSON.stringify(expected)) throw new Error(name + ': '+JSON.stringify(actual));
        console.log('SHIZUKU_CHECK '+name);
    }
    function state() {
        check('binder available', shizuku.isAvailable(), true);
        check('permission', shizuku.hasPermission(), true);
        var value=shizuku.getState();
        check('real backend identity', value.backendUid === 2000 || value.backendUid === 0, true);
        console.log('SHIZUKU_STATE '+JSON.stringify(value));
    }
    function verify(id, nonzero, limited, timeout, took) {
        check('id success',id.code,0);check('privileged uid',/uid=(2000|0)\b/.test(id.result),true);
        check('command exit code',nonzero.code,7);check('failed command stdout',nonzero.result,'stdout');check('stderr',nonzero.error,'stderr');
        check('output capped',limited.result.length,32);check('output still drained',limited.code,0);
        check('timeout code',timeout.code,-1);check('timeout diagnostic',/timeout/.test(timeout.error),true);
        check('bounded timeout',took<4000,true);
        console.log('SHIZUKU_PASS');
    }
    state();
    if(typeof process!=='undefined' && process.versions && process.versions.node) {
        var data={};
        shizuku.shell('id').then(function(value){data.id=value;return shizuku.shell('printf stdout; printf stderr >&2; exit 7');})
        .then(function(value){data.nonzero=value;return shizuku.shell('head -c 131072 /dev/zero | tr "\\000" x',{maxOutput:32});})
        .then(function(value){data.limited=value;data.started=Date.now();return shizuku.shell('sleep 30',{timeout:250});})
        .then(function(value){
            verify(data.id,data.nonzero,data.limited,value,Date.now()-data.started);
            check('Node module identity',require('shizuku')===shizuku,true);
            return require('shell').exec('id',{shizuku:true});
        }).then(function(value){check('shell option backend',value.code,0);})
        .catch(function(error){console.error(error.stack||error);process.exitCode=1;});
    } else {
        var id=shizuku.shell('id'),nonzero=shizuku.shell('printf stdout; printf stderr >&2; exit 7');
        var limited=shizuku.shell('head -c 131072 /dev/zero | tr "\\000" x',{maxOutput:32});
        var started=Date.now(),timeout=shizuku.shell('sleep 30',{timeout:250});
        verify(id,nonzero,limited,timeout,Date.now()-started);
        check('shell option backend',shell('id',{shizuku:true}).code,0);
    }
})();
