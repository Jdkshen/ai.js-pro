

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

    scope.newInjectableWebClient = newInjectableWebClient;
    scope.newInjectableWebView = newInjectableWebView;

    // 与 QuickJS 版对齐：web.newInjectableWebView() / web.newInjectableWebClient() 同样可用
    return {
        newInjectableWebClient: newInjectableWebClient,
        newInjectableWebView: newInjectableWebView
    };
}



