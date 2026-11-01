// @engine rhino
// 需要运行中的 Shizuku/Sui；首次运行会请求当前应用的授权。
(function () {
    if (!shizuku.isAvailable()) { console.error('请先启动 Shizuku，再运行本示例'); return; }
    function show() {
        console.log('Shizuku 状态：' + JSON.stringify(shizuku.getState()));
        var result = shizuku.shell('id', {timeout:5000,maxOutput:4096});
        if (result && typeof result.then === 'function') result.then(print, function(error){console.error(error);});
        else print(result);
    }
    function print(result) { console.log('退出码：'+result.code+'\n'+result.result); if(result.error)console.error(result.error); }
    if (shizuku.hasPermission()) { show(); return; }
    var granted=shizuku.requestPermission(60000);
    if(granted && typeof granted.then === 'function') granted.then(function(ok){if(ok)show();else console.error('未获得Shizuku授权');});
    else if(granted) show(); else console.error('未获得Shizuku授权');
})();
