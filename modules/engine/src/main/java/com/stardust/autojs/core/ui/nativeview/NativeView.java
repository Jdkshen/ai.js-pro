package com.stardust.autojs.core.ui.nativeview;

import android.graphics.PorterDuff;
import android.view.View;
import android.webkit.WebView;
import android.widget.Button;

import com.stardust.autojs.core.ui.JsViewHelper;
import com.stardust.autojs.core.ui.ViewExtras;
import com.stardust.autojs.core.ui.attribute.ViewAttributes;
import com.stardust.autojs.core.web.InjectableWebClient;
import com.stardust.autojs.rhino.NativeJavaObjectWithPrototype;

import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.NativeJavaObject;
import org.mozilla.javascript.NativeObject;
import org.mozilla.javascript.ScriptRuntime;
import org.mozilla.javascript.Scriptable;

public class NativeView extends NativeJavaObjectWithPrototype {

    private static final String LOG_TAG = "NativeView";

    private static final String SET_WEB_VIEW_CLIENT = "setWebViewClient";

    public static class ScrollEvent {
        public int scrollX;
        public int scrollY;
        public int oldScrollX;
        public int oldScrollY;

        public ScrollEvent(int scrollX, int scrollY, int oldScrollX, int oldScrollY) {
            this.scrollX = scrollX;
            this.scrollY = scrollY;
            this.oldScrollX = oldScrollX;
            this.oldScrollY = oldScrollY;
        }
    }

    public static class LongClickEvent {
        public final View view;

        public LongClickEvent(View view) {
            this.view = view;
        }
    }


    private final ViewAttributes mViewAttributes;
    private final View mView;
    private final ViewPrototype mViewPrototype;

    public NativeView(Scriptable scope, View view, Class<?> staticType, com.stardust.autojs.runtime.ScriptRuntime runtime) {
        super(scope, view, staticType);
        mViewAttributes = ViewExtras.getViewAttributes(view, runtime.ui.getResourceParser());
        mView = view;
        mViewPrototype = new ViewPrototype(mView, mViewAttributes, scope, runtime);
        prototype = new NativeJavaObjectWithPrototype(scope, mViewPrototype, mViewPrototype.getClass());
        prototype.setPrototype(new NativeObject());
    }

    @Override
    public boolean has(String name, Scriptable start) {
        if (mViewAttributes.contains(name)) {
            return true;
        }
        return super.has(name, start);
    }

    @Override
    public Object get(String name, Scriptable start) {
        if (mView instanceof WebView && SET_WEB_VIEW_CLIENT.equals(name)) {
            Object member = super.has(name, start) ? super.get(name, start) : Scriptable.NOT_FOUND;
            if (member instanceof Function) {
                return new SetWebViewClientFunction((Function) member, (WebView) mView);
            }
            return member;
        }
        if (super.has(name, start)) {
            return super.get(name, start);
        } else {
            View view = JsViewHelper.findViewByStringId(mView, name);
            if (view != null) {
                return view;
            }
        }
        return Scriptable.NOT_FOUND;
    }

    /**
     * `webView.setWebViewClient(client)` 的包装：如果 client 是本引擎的 {@link InjectableWebClient}，
     * 则趁 loadUrl / loadData 之前把页面桥（页面里的 `rhino`）与 WebView 设置提前应用。
     * <p>
     * 现代 Chromium WebView 只在「文档开始」时注入 addJavascriptInterface 注册的对象，
     * 而历史上这个桥是在 onPageFinished 才注册的，于是页面里 `window.rhino` 一直是 undefined、
     * 页面回调脚本的能力失效（Auto.js 经典写法就是「先 setWebViewClient 再 loadUrl」）。
     */
    public static class SetWebViewClientFunction extends BaseFunction {

        private static final long serialVersionUID = 1L;

        private final Function mOriginal;
        private final WebView mWebView;

        SetWebViewClientFunction(Function original, WebView webView) {
            mOriginal = original;
            mWebView = webView;
        }

        @Override
        public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
            Object client = args.length > 0 ? args[0] : null;
            // 脚本传进来的是 NativeJavaObject 包装，得先解包才能 instanceof 判断
            if (client instanceof NativeJavaObject) {
                client = ((NativeJavaObject) client).unwrap();
            }
            if (client instanceof InjectableWebClient) {
                ((InjectableWebClient) client).attach(mWebView);
            }
            return mOriginal.call(cx, scope, thisObj, args);
        }

        @Override
        public String getFunctionName() {
            return SET_WEB_VIEW_CLIENT;
        }
    }

    public ViewPrototype getViewPrototype() {
        return mViewPrototype;
    }

    @Override
    public View unwrap() {
        return mView;
    }

}
