package com.stardust.autojs.core.database;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code db.transaction(fn)} 返回的事件对象：支持链式
 * {@code .on("begin"|"commit"|"rollback"|"end"|"error", listener)}。
 *
 * <p>事务在本对象返回前已同步执行完成，因此注册监听时对"已发生的事件"做补放（replay），
 * 回调按注册顺序补放，回调异常向脚本调用方传播。
 */
public class TransactionEvents {

    private final Map<String, List<Function>> mListeners = new HashMap<>();
    private final List<String> mOccurred = new ArrayList<>();
    private final Map<String, Object> mPayloads = new HashMap<>();

    public TransactionEvents on(String event, Function listener) {
        if (event == null || listener == null) {
            return this;
        }
        String key = event.toLowerCase();
        if (mOccurred.contains(key)) {
            fire(listener, mPayloads.get(key));
        } else {
            List<Function> list = mListeners.get(key);
            if (list == null) {
                list = new ArrayList<>();
                mListeners.put(key, list);
            }
            list.add(listener);
        }
        return this;
    }

    void occur(String event, Object payload) {
        String key = event.toLowerCase();
        mOccurred.add(key);
        mPayloads.put(key, payload);
        List<Function> list = mListeners.remove(key);
        if (list != null) {
            for (Function listener : list) {
                fire(listener, payload);
            }
        }
    }

    private static void fire(Function listener, Object payload) {
        Context cx = Context.getCurrentContext();
        if (cx == null) {
            throw new IllegalStateException("No Rhino context for transaction listener");
        }
        Scriptable scope = listener.getParentScope();
        Scriptable callbackScope = scope != null ? scope : listener;
        listener.call(cx, callbackScope, callbackScope, new Object[]{Context.javaToJS(payload, callbackScope)});
    }
}
