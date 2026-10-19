package com.stardust.autojs.core.ui.widget;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.stardust.autojs.runtime.ScriptRuntime;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 脚本侧 {@code $ui.web.events} 的 EventEmitter 语义实现（Pro 兼容层）。
 *
 * <p>Pro 里 {@code $ui.web.events} 是 {@code $events.emitter()}：支持 {@code on/once/off/emit}，
 * 由 WebView 的原生事件（{@code console_message} 等）驱动，回调形如
 * {@code (event, ...args)}。这里用 Java 实现同一语义，挂在 {@link JsWebView#getEvents()} 上，
 * 脚本写 {@code $ui.web.events.on('console_message', (e, msg) => ...)} 即可。
 *
 * <p>线程模型：原生 WebView 回调在 main 线程；事件监听在脚本线程注册时记下该线程的 Handler，
 * 派发时投递回脚本线程执行（ui 模式下脚本就在 main，直接同步），避免跨线程调用 JS。
 */
public class JsWebViewEvents {

    private static final String TAG = "JsWebViewEvents";

    private static final class Listener {
        final Object callback;
        final boolean once;

        Listener(Object callback, boolean once) {
            this.callback = callback;
            this.once = once;
        }
    }

    private final ScriptRuntime mRuntime;
    private final Map<String, CopyOnWriteArrayList<Listener>> mListeners = new ConcurrentHashMap<>();
    private volatile Handler mScriptHandler;

    JsWebViewEvents(ScriptRuntime runtime) {
        mRuntime = runtime;
    }

    /** 在脚本线程调用 on/once 时记住脚本线程 Looper（WebView 事件从 main 派发）。 */
    private void captureScriptHandler() {
        if (mScriptHandler != null) {
            return;
        }
        synchronized (this) {
            if (mScriptHandler == null) {
                Looper looper = Looper.myLooper();
                if (looper != null) {
                    mScriptHandler = new Handler(looper);
                }
            }
        }
    }

    public JsWebViewEvents on(String eventName, Object listener) {
        add(eventName, listener, false);
        return this;
    }

    public JsWebViewEvents addListener(String eventName, Object listener) {
        return on(eventName, listener);
    }

    public JsWebViewEvents once(String eventName, Object listener) {
        add(eventName, listener, true);
        return this;
    }

    public JsWebViewEvents off(String eventName, Object listener) {
        return removeListener(eventName, listener);
    }

    public JsWebViewEvents removeListener(String eventName, Object listener) {
        CopyOnWriteArrayList<Listener> list = mListeners.get(eventName);
        if (list != null) {
            for (Listener item : list) {
                if (item.callback == listener) {
                    list.remove(item);
                    break;
                }
            }
        }
        return this;
    }

    public JsWebViewEvents removeAllListeners(String eventName) {
        if (eventName == null) {
            mListeners.clear();
        } else {
            mListeners.remove(eventName);
        }
        return this;
    }

    public int listenerCount(String eventName) {
        CopyOnWriteArrayList<Listener> list = mListeners.get(eventName);
        return list == null ? 0 : list.size();
    }

    public String[] eventNames() {
        return mListeners.keySet().toArray(new String[0]);
    }

    private void add(String eventName, Object listener, boolean once) {
        if (eventName == null || listener == null) {
            return;
        }
        captureScriptHandler();
        mListeners.computeIfAbsent(eventName, key -> new CopyOnWriteArrayList<>())
                .addIfAbsent(new Listener(listener, once));
    }

    /**
     * 派发事件：回调参数为 {@code (event, ...args)}，event 是 {@link WebViewJs#newEvent} 构造的
     * {@code {name, arguments, consumed}} 对象。返回 false 表示没有监听者。
     */
    public boolean emit(String eventName, Object... args) {
        CopyOnWriteArrayList<Listener> list = mListeners.get(eventName);
        if (list == null || list.isEmpty()) {
            return false;
        }
        Object event = WebViewJs.newEvent(eventName, args);
        Object[] callbackArgs = new Object[1 + (args == null ? 0 : args.length)];
        callbackArgs[0] = event;
        if (args != null) {
            System.arraycopy(args, 0, callbackArgs, 1, args.length);
        }
        for (Listener item : list) {
            if (item.once) {
                list.remove(item);
            }
            invoke(item.callback, callbackArgs);
        }
        return true;
    }

    private void invoke(final Object callback, final Object[] args) {
        Handler handler = mScriptHandler;
        Runnable action = () -> {
            if (mRuntime == null) {
                Log.w(TAG, "events listener ignored: no script runtime");
                return;
            }
            try {
                mRuntime.bridges.callFunction(callback, null, args);
            } catch (Throwable error) {
                Log.w(TAG, "webview event listener error", error);
            }
        };
        if (handler == null || Looper.myLooper() == handler.getLooper()) {
            action.run();
        } else {
            handler.post(action);
        }
    }
}
