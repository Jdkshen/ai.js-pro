(function () {
    var path = '/sdcard/脚本/__aijs_observer_contract__' + Date.now() + '.txt';
    files.write(path, 'before');
    var watcher = files.observe(path);
    var events = [];
    watcher.on('any', function (event, relativePath) { events.push({event: String(event), path: relativePath}); });
    setTimeout(function () { files.write(path, 'after'); }, 150);
    setTimeout(function () {
        watcher.stopWatching();
        var stopped = !watcher.isWatching();
        watcher.startWatching();
        var restarted = watcher.isWatching();
        watcher.stopWatching();
        files.remove(path);
        var modified = events.some(function (event) { return event.event === 'modify' || event.event === 'close_write'; });
        console.log('COMMON_OBSERVER_RESULT=' + JSON.stringify({events: events, modified: modified, stopped: stopped, restarted: restarted}));
    }, 900);
})();
