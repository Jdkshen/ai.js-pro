

module.exports = function(__runtime__, scope){
    function newInjectableWebClient(){
        return new com.stardust.autojs.core.web.InjectableWebClient(org.mozilla.javascript.Context.getCurrentContext(), scope);
    }

    function newInjectableWebView(activity){
        var context = activity || __runtime__.app.getCurrentActivity();
        if (!context) {
            throw new Error('newInjectableWebView() 需要当前 Activity，请先启动界面或手动传入 activity');
        }
        return new com.stardust.autojs.core.web.InjectableWebView(context, org.mozilla.javascript.Context.getCurrentContext(), scope);
    }

    /**
     * Auto.js Pro 的 web.newWebSocket(url[, options])。
     * 返回对象可直接 .on('open'|'text'|'binary'|'closing'|'closed'|'failure', fn)
     * （回调参数末尾已附带 ws 自身，与 Pro 一致）。
     * options.eventThread 兼容接受；自研统一把回调投递到脚本线程。
     */
    function newWebSocket(url, options){
        return new com.stardust.autojs.core.web.WebSocket(__runtime__, String(url));
    }

    scope.newInjectableWebClient = newInjectableWebClient;
    scope.newInjectableWebView = newInjectableWebView;
    scope.newWebSocket = newWebSocket;

    // 与 QuickJS 版对齐：web.newInjectableWebView() / web.newInjectableWebClient() 同样可用
    return {
        newInjectableWebClient: newInjectableWebClient,
        newInjectableWebView: newInjectableWebView,
        newWebSocket: newWebSocket,
        ByteString: Packages.okio.ByteString
    };
}



