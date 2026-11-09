package com.stardust.autojs.core.content;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;

import java.util.HashMap;
import java.util.Map;

/**
 * 脚本侧广播接收的 Android 胶水层（task-54，自研；不复制任何闭源实现）。
 *
 * 把纯逻辑（{@link BroadcastFilter} 归一化/flags、{@link BroadcastReceiverRegistry} 生命周期与异常隔离）
 * 接到真实 Context.registerReceiver 上；脚本回调是 org.mozilla.javascript.Function（跟 Sqlite.java 同一套写法）。
 */
public final class ScriptBroadcastReceivers {
    private static final BroadcastReceiverRegistry REGISTRY = new BroadcastReceiverRegistry();

    private ScriptBroadcastReceivers() {
    }

    public static BroadcastReceiverRegistry registry() {
        return REGISTRY;
    }

    /** 每个 Context 一个 host（真机上通常是应用 Context，全局一份）。 */
    private static final Map<Context, AndroidHost> HOSTS = new HashMap<Context, AndroidHost>();

    private static synchronized AndroidHost hostFor(Context context) {
        AndroidHost host = HOSTS.get(context);
        if (host == null) {
            host = new AndroidHost(context);
            HOSTS.put(context, host);
        }
        return host;
    }

    /** 平台侧实现：按 API 级别决定是否带导出标志，注销幂等。 */
    private static final class AndroidHost implements BroadcastReceiverRegistry.Host {
        private final Context context;
        private final Map<String, Receiver> receivers = new HashMap<String, Receiver>();

        AndroidHost(Context context) {
            this.context = context;
        }

        @Override
        public void register(BroadcastReceiverRegistry.Handle handle) {
            BroadcastFilter filter = handle.filter;
            IntentFilter intentFilter = new IntentFilter();
            for (String action : filter.actions) intentFilter.addAction(action);
            for (String category : filter.categories) intentFilter.addCategory(category);
            for (String scheme : filter.schemes) intentFilter.addDataScheme(scheme);
            Receiver receiver = new Receiver(handle.id);
            int flags = filter.receiverFlags(Build.VERSION.SDK_INT);
            if (flags != 0) {
                if (filter.permission != null) {
                    context.registerReceiver(receiver, intentFilter, filter.permission, null, flags);
                } else {
                    context.registerReceiver(receiver, intentFilter, flags);
                }
            } else if (filter.permission != null) {
                context.registerReceiver(receiver, intentFilter, filter.permission, null);
            } else {
                context.registerReceiver(receiver, intentFilter);
            }
            receivers.put(handle.id, receiver);
        }

        @Override
        public void unregister(BroadcastReceiverRegistry.Handle handle) {
            Receiver receiver = receivers.remove(handle.id);
            if (receiver == null) return;
            try {
                context.unregisterReceiver(receiver);
            } catch (IllegalArgumentException alreadyGone) {
                // 已经注销/进程重建过：幂等，不抛。
            }
        }
    }

    /** 真正递交给 Android 的接收器：只把 intent 转给登记表（异常在登记表里隔离）。 */
    public static final class Receiver extends BroadcastReceiver {
        private final String handleId;

        Receiver(String handleId) {
            this.handleId = handleId;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            REGISTRY.dispatch(handleId, intent);
        }
    }

    /** 脚本回调包装：onIntent 抛错由登记表转成 onError，绝不向上抛。 */
    private static final class JsListener implements BroadcastReceiverRegistry.Listener {
        private final Object onIntent;
        private final Object onError;
        private final Scriptable scope;

        JsListener(Object onIntent, Object onError, Scriptable scope) {
            this.onIntent = onIntent;
            this.onError = onError;
            this.scope = scope;
        }

        @Override
        public void onIntent(Object intent) {
            call(onIntent, new Object[]{intent});
        }

        @Override
        public void onError(Throwable error) {
            call(onError, new Object[]{error});
        }

        private void call(Object functionLike, Object[] args) {
            if (!(functionLike instanceof Function)) return;
            org.mozilla.javascript.Context cx = org.mozilla.javascript.Context.enter();
            try {
                // scope 允许为 null（例如从 AppUtils 直接调用时没有脚本作用域）：退化成用函数自身当作用域/thisObj。
                Scriptable effective = scope != null ? scope
                        : (functionLike instanceof Scriptable ? (Scriptable) functionLike : null);
                ((Function) functionLike).call(cx, effective, effective, args);
            } finally {
                org.mozilla.javascript.Context.exit();
            }
        }
    }

    /** 脚本入口：登记并返回可读句柄的 JSON（id/actions/categories/schemes/exported/registeredAt）。 */
    public static String register(Context context, String[] actions, String[] categories, String[] schemes,
                                  String permission, boolean exported, Object onIntent, Object onError,
                                  Scriptable scope) {
        if (context == null) throw new IllegalArgumentException("registerBroadcastReceiver: 需要 Android Context");
        BroadcastFilter filter = BroadcastFilter.of(actions, categories, schemes, permission, exported);
        BroadcastReceiverRegistry.Handle handle = REGISTRY.register(filter, exported, Build.VERSION.SDK_INT,
                new JsListener(onIntent, onError, scope), hostFor(context));
        return handleJson(handle);
    }

    public static boolean unregister(Object idOrHandle) {
        boolean removed = REGISTRY.unregister(idOrHandle == null ? null : String.valueOf(idOrHandle));
        REGISTRY.sweep();
        return removed;
    }

    public static int unregisterAll() {
        return REGISTRY.unregisterAll();
    }

    /** 清理 owner 线程已结束的接收器（脚本异常退出后的兜底）。 */
    public static int sweep() {
        return REGISTRY.sweep();
    }

    /** 可观测：计数 + 当前句柄（JSON 字符串，脚本 JSON.parse 后即用）。 */
    public static String describe() {
        REGISTRY.sweep();
        StringBuilder builder = new StringBuilder();
        builder.append("{\"counters\":{");
        boolean first = true;
        for (Map.Entry<String, Long> item : REGISTRY.counters().entrySet()) {
            if (!first) builder.append(',');
            builder.append('"').append(item.getKey()).append("\":").append(item.getValue());
            first = false;
        }
        builder.append("},\"active\":[");
        boolean firstHandle = true;
        for (BroadcastReceiverRegistry.Handle handle : REGISTRY.activeHandles()) {
            if (!firstHandle) builder.append(',');
            builder.append(handleJson(handle));
            firstHandle = false;
        }
        builder.append("]}");
        return builder.toString();
    }

    private static String handleJson(BroadcastReceiverRegistry.Handle handle) {
        StringBuilder builder = new StringBuilder();
        builder.append("{\"id\":\"").append(handle.id).append("\"");
        builder.append(",\"actions\":").append(jsonArray(handle.filter.actions));
        builder.append(",\"categories\":").append(jsonArray(handle.filter.categories));
        builder.append(",\"schemes\":").append(jsonArray(handle.filter.schemes));
        builder.append(",\"permission\":").append(handle.filter.permission == null ? "null" : "\"" + handle.filter.permission + "\"");
        builder.append(",\"exported\":").append(handle.exported);
        builder.append(",\"registeredAt\":").append(handle.registeredAt);
        builder.append(",\"exportFlag\":").append(handle.filter.receiverFlags(Build.VERSION.SDK_INT));
        builder.append('}');
        return builder.toString();
    }

    private static String jsonArray(java.util.List<String> values) {
        StringBuilder builder = new StringBuilder("[");
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) builder.append(',');
            builder.append('"').append(values.get(index)).append('"');
        }
        return builder.append(']').toString();
    }
}
