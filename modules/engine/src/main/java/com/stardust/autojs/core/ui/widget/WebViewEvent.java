package com.stardust.autojs.core.ui.widget;

/**
 * 传给脚本的 WebView 事件对象（Pro {@code $ui.web.events} / {@code jsBridge.on} 语义）。
 *
 * <p>脚本回调形如 {@code (event, ...args)}：event 是本发明对象（{@code event.name} 在 Pro 里是
 * 属性；本引擎补了 {@link #getName()}，同时保留字段便于阅读；本项目示例只用第二个参数）。
 */
public class WebViewEvent {

    private final String mName;
    private final Object[] mArguments;
    private boolean mConsumed;

    public WebViewEvent(String name, Object[] arguments) {
        mName = name == null ? "" : name;
        mArguments = arguments == null ? new Object[0] : arguments;
    }

    public String getName() {
        return mName;
    }

    public Object[] getArguments() {
        return mArguments;
    }

    public boolean isConsumed() {
        return mConsumed;
    }

    public void setConsumed(boolean consumed) {
        mConsumed = consumed;
    }
}
