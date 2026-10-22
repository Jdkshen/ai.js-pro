"nodejs";
(function () {
    var seen = [];
    var canceledInterval = setInterval(function () { seen.push('unexpected-interval'); }, 60000);
    var canceledTimeout = setTimeout(function () { seen.push('unexpected-timeout'); }, 60000);
    clearInterval(canceledInterval);
    clearTimeout(canceledTimeout);
    var selfStopping;
    selfStopping = setInterval(function () {
        clearInterval(selfStopping);
        seen.push('interval');
        setTimeout(function () {
            seen.push('timeout');
            var checks = [{name: 'timers.cancellation_and_natural_exit', ok: JSON.stringify(seen) === '["interval","timeout"]'}];
            console.log('COMMON_TIMERS_RESULT=' + JSON.stringify({checks: checks, passed: checks[0].ok ? 1 : 0,
                failed: checks[0].ok ? [] : checks}));
        }, 20);
    }, 20);
})();
