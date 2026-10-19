package com.stardust.autojs.core.ui.inflater.inflaters;

import androidx.annotation.Nullable;

import com.stardust.autojs.core.ui.inflater.ResourceParser;
import com.stardust.autojs.core.ui.inflater.ViewCreator;
import com.stardust.autojs.core.ui.widget.JsWebView;
import com.stardust.autojs.runtime.ScriptRuntime;

import java.util.Map;

/**
 * {@code <webview>} 的 inflater：创建带脚本运行时的 {@link JsWebView}，
 * 这样 {@code $ui.web.events} / {@code $ui.web.jsBridge} 才有 EventEmitter/桥实现。
 */
public class JsWebViewInflater<V extends JsWebView> extends BaseViewInflater<V> {

    private final ScriptRuntime mRuntime;

    public JsWebViewInflater(ResourceParser resourceParser, ScriptRuntime runtime) {
        super(resourceParser);
        mRuntime = runtime;
    }

    public ScriptRuntime getRuntime() {
        return mRuntime;
    }

    @Nullable
    @Override
    public ViewCreator<? super JsWebView> getCreator() {
        return new ViewCreator<JsWebView>() {
            @Override
            public JsWebView create(android.content.Context context, Map<String, String> attrs) {
                return new JsWebView(context, mRuntime);
            }
        };
    }
}
