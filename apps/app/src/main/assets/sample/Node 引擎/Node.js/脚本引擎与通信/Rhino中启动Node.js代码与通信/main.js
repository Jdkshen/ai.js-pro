"rhino";
// Rhino 启动 Node 并等待真实回复；收到回复或超时后结束子引擎。
var execution = $engines.execScriptFile('./http请求.node.js', {
    arguments: { serverEngineId: $engines.myEngine().id }
});
var timeout = setTimeout(function () {
    console.error('等待 Node HTTP 回复超时（15 秒）');
    execution.engine.forceStop();
}, 15000);
$events.on('reply', function (result) {
    console.log(result);
    console.log(result.error ? 'RHINO_NODE_REPLY_ERROR ' + result.error : 'RHINO_NODE_REPLY_OK');
    clearTimeout(timeout);
    execution.engine.forceStop();
});
sleep(1000);
execution.engine.emit('command', {name:'httpGet', args:{url:'https://httpbin.org/json'}});
