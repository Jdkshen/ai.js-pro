package com.stardust.autojs.engine.node24;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

import org.json.JSONObject;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Node-side Binder clients: :nodeui for rendering, main process for work/settings.
 *
 * <p>B 只需：show() 成功则Activity 模式；在 Listener.onEvent 里把事件入队到自己的 pollEvents；
 * onClosed 里置 closeRequested（让 A 的 keep-alive 退出）。show 失败时回退 overlay。
 */
public final class NodeUiActivityClient {

    public interface Listener {
        void onEvent(String type, JSONObject payload);
        void onClosed();
    }

    private static final String TAG = "NodeUiActivityClient";
    private static final NodeUiActivityClient INSTANCE = new NodeUiActivityClient();
    private static final NodeUiActivityClient WORK_INSTANCE = new NodeUiActivityClient();
    private volatile CountDownLatch mConnected = new CountDownLatch(1);
    private final AtomicBoolean mBinding = new AtomicBoolean(false);
    private volatile Messenger mService;
    private volatile boolean mBound;
    private volatile Listener mListener;
    private Context mContext;
    private String mSessionId;
    private CountDownLatch mShowLatch;
    private volatile String mShowError;
    private volatile boolean mShowStarted;
    private volatile boolean mClosed;
    private CountDownLatch mUpdateLatch;
    private volatile String mUpdateResult;
    private volatile String mUpdateError;

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            mService = new Messenger(binder);
            mBound = true;
            mConnected.countDown();
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            mBound = false;
            mService = null;
            if (mBinding.getAndSet(false) && mContext != null) {
                try { mContext.unbindService(this); } catch (Throwable ignored) { }
            }
            mShowError = "Node UI 服务进程已断开";
            mUpdateError = mShowError;
            mUpdateResult = "{\"ok\":false,\"error\":\"Node UI 服务进程已断开\"}";
            if (mShowLatch != null) mShowLatch.countDown();
            if (mUpdateLatch != null) mUpdateLatch.countDown();
            notifyClosed();
        }
        @Override public void onBindingDied(ComponentName name) { onServiceDisconnected(name); }
    };

    private final Messenger mReply = new Messenger(new Handler(Looper.getMainLooper()) {
        @Override public void handleMessage(Message msg) {
            Bundle data = msg.getData();
            if (data == null) return;
            String session = data.getString(NodeUiProtocol.K_SESSION);
            if (mSessionId != null && session != null && !mSessionId.equals(session)) return;
            if (msg.what == NodeUiProtocol.MSG_WORK) {
                mUpdateResult = data.getString(NodeUiProtocol.K_RESULT, "{}");
                CountDownLatch workLatch = mUpdateLatch;
                if (workLatch != null) workLatch.countDown();
                return;
            }
            if (msg.what == NodeUiProtocol.MSG_OVERLAY) {
                mUpdateResult = data.getString(NodeUiProtocol.K_RESULT, "{}");
                CountDownLatch overlayLatch = mUpdateLatch;
                if (overlayLatch != null) overlayLatch.countDown();
                return;
            }
            if (msg.what == NodeUiProtocol.MSG_DIALOG) {
                mUpdateResult = data.getString(NodeUiProtocol.K_RESULT, "{}");
                CountDownLatch dialogLatch = mUpdateLatch;
                if (dialogLatch != null) dialogLatch.countDown();
                return;
            }
            if (msg.what == NodeUiProtocol.MSG_UPDATE) {
                mUpdateResult = data.getString(NodeUiProtocol.K_RESULT, "{}");
                CountDownLatch updateLatch = mUpdateLatch;
                if (updateLatch != null) updateLatch.countDown();
                return;
            }
            if (msg.what == NodeUiProtocol.MSG_RESULT) {
                if (data.getBoolean(NodeUiProtocol.K_SUCCESS, false)) {
                    mShowStarted = true;
                } else {
                    mShowError = data.getString(NodeUiProtocol.K_ERROR, "Activity 启动失败");
                }
                CountDownLatch latch = mShowLatch;
                if (latch != null) latch.countDown();
            } else if (msg.what == NodeUiProtocol.MSG_EVENT) {
                String type = data.getString(NodeUiProtocol.K_EVENT, "");
                JSONObject payload = null;
                try { payload = new JSONObject(data.getString(NodeUiProtocol.K_PAYLOAD, "{}")); }
                catch (Throwable ignored) { }
                if (NodeUiProtocol.EVENT_DESTROY.equals(type)) notifyClosed();
                Listener listener = mListener;
                if (listener != null) {
                    try { listener.onEvent(type, payload); } catch (Throwable error) { Log.w(TAG, "event failed", error); }
                }
            }
        }
    });

    public static NodeUiActivityClient get() { return INSTANCE; }

    public void setListener(Listener listener) { mListener = listener; }

    private synchronized void bind(Context context) throws Exception {
        if (mBound) return;
        if (context == null) {
            throw new IllegalStateException("Node UI 宿主 context 为空（调用方未提供 Android Context）");
        }
        mContext = context.getApplicationContext();
        if (mBinding.compareAndSet(false, true)) {
            mConnected = new CountDownLatch(1);
            String serviceClass = this == WORK_INSTANCE ? NodeUiProtocol.WORK_SERVICE_CLASS : NodeUiProtocol.SERVICE_CLASS;
            Intent intent = new Intent().setComponent(new ComponentName(mContext.getPackageName(), serviceClass));
            if (!mContext.bindService(intent, mConnection, Context.BIND_AUTO_CREATE)) {
                mBinding.set(false);
                throw new IllegalStateException(serviceClass + " 不可用（bindService=false）");
            }
        }
        if (!mConnected.await(8, TimeUnit.SECONDS) || mService == null) {
            release(context);
            throw new IllegalStateException("Node 服务绑定超时或已断开");
        }
    }

    /** 同步打开主进程 Activity；返回 null 表示失败（B 回退 overlay），成功返回 {mode:"activity",sessionId}。 */
    public JSONObject show(Context context, String projectDir, String layoutName, int layoutId,
                           String className, String title) throws Exception {
        bind(context);
        mSessionId = UUID.randomUUID().toString();
        mShowStarted = false;
        mShowError = null;
        mClosed = false;
        mShowLatch = new CountDownLatch(1);
        Message message = Message.obtain(null, NodeUiProtocol.MSG_SHOW);
        Bundle data = new Bundle();
        data.putString(NodeUiProtocol.K_SESSION, mSessionId);
        data.putString(NodeUiProtocol.K_PROJECT, projectDir);
        data.putString(NodeUiProtocol.K_LAYOUT_NAME, layoutName == null ? "" : layoutName);
        data.putInt(NodeUiProtocol.K_LAYOUT_ID, layoutId);
        data.putString(NodeUiProtocol.K_CLASS, className == null ? "" : className);
        data.putString(NodeUiProtocol.K_TITLE, title == null ? "" : title);
        message.setData(data);
        message.replyTo = mReply;
        mService.send(message);
        if (!mShowLatch.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("主进程 NodeUiActivity 启动超时");
        }
        if (!mShowStarted) {
            throw new IllegalStateException(mShowError == null ? "主进程 NodeUiActivity 启动失败" : mShowError);
        }
        return new JSONObject().put(NodeUiProtocol.K_MODE, "activity")
                .put(NodeUiProtocol.K_SESSION, mSessionId);
    }

    /** task-76：Activity 模式下的 setAdapter/setPager 等更新（B 的 NodeUiActivity.handleUpdate 处理）。 */
    public synchronized JSONObject update(JSONObject request) throws Exception {
        bind(mContext);
        String session = mSessionId;
        if (session == null) throw new IllegalStateException("Node UI Activity 尚未打开");
        mUpdateResult = null;
        mUpdateError = null;
        mUpdateLatch = new CountDownLatch(1);
        Message message = Message.obtain(null, NodeUiProtocol.MSG_UPDATE);
        Bundle data = new Bundle();
        data.putString(NodeUiProtocol.K_SESSION, session);
        data.putString(NodeUiProtocol.K_REQUEST, request == null ? "{}" : request.toString());
        message.setData(data);
        message.replyTo = mReply;
        mService.send(message);
        if (!mUpdateLatch.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Node UI Activity update 超时");
        }
        String text = mUpdateResult == null ? "{}" : mUpdateResult;
        return new JSONObject(text);
    }

    public static JSONObject updateForCurrent(JSONObject request) throws Exception {
        return INSTANCE.update(request);
    }

    /** task-135 Phase2：向主进程 NodeUiActivity 发送 View 句柄命令，同步返回 {ok,handleId,...}。 */
    public synchronized JSONObject view(JSONObject request) throws Exception {
        bind(mContext);
        String session = mSessionId;
        if (session == null) throw new IllegalStateException("Node UI Activity 尚未打开");
        mUpdateResult = null;
        mUpdateLatch = new CountDownLatch(1);
        Message message = Message.obtain(null, NodeUiProtocol.MSG_VIEW);
        Bundle data = new Bundle();
        data.putString(NodeUiProtocol.K_SESSION, session);
        data.putString(NodeUiProtocol.K_REQUEST, request == null ? "{}" : request.toString());
        message.setData(data);
        message.replyTo = mReply;
        mService.send(message);
        if (!mUpdateLatch.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Node UI View 命令超时");
        }
        return new JSONObject(mUpdateResult == null ? "{}" : mUpdateResult);
    }

    /** task-141：无 Node UI Activity 时的对话框入口（用 :node24 侧 Context 绑定主进程服务）。 */
    public synchronized JSONObject dialog(Context context, JSONObject request) throws Exception {
        if (mContext == null || !mBound) {
            bind(context);
        }
        return dialog(request);
    }

    /** task-141：主进程对话框，同步等待用户操作；最长 120s。 */
    public synchronized JSONObject dialog(JSONObject request) throws Exception {
        bind(mContext);
        // 对话框不要求 Node UI Activity 已打开：无 session 时用临时 token，服务端仅用于回执路由。
        String session = mSessionId;
        if (session == null) {
            session = java.util.UUID.randomUUID().toString();
        }
        mUpdateResult = null;
        mUpdateError = null;
        mUpdateLatch = new CountDownLatch(1);
        Message message = Message.obtain(null, NodeUiProtocol.MSG_DIALOG);
        Bundle data = new Bundle();
        data.putString(NodeUiProtocol.K_SESSION, session);
        data.putString(NodeUiProtocol.K_REQUEST, request == null ? "{}" : request.toString());
        message.setData(data);
        message.replyTo = mReply;
        mService.send(message);
        if (!mUpdateLatch.await(120, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Node UI 对话框等待超时");
        }
        return new JSONObject(mUpdateResult == null ? "{}" : mUpdateResult);
    }

    public synchronized JSONObject overlay(Context context, JSONObject request) throws Exception {
        if (mContext == null || !mBound) { bind(context); }
        return overlay(request);
    }

    /** task-142：主进程 floating_window overlay 同步命令。 */
    public synchronized JSONObject overlay(JSONObject request) throws Exception {
        if (mService == null) throw new IllegalStateException("主进程 NodeUiBridgeService 未绑定");
        mUpdateResult = null;
        mUpdateLatch = new CountDownLatch(1);
        Message message = Message.obtain(null, NodeUiProtocol.MSG_OVERLAY);
        Bundle data = new Bundle();
        data.putString(NodeUiProtocol.K_REQUEST, request == null ? "{}" : request.toString());
        message.setData(data);
        message.replyTo = mReply;
        mService.send(message);
        if (!mUpdateLatch.await(15, TimeUnit.SECONDS)) {
            throw new IllegalStateException("floating_window 命令超时");
        }
        return new JSONObject(mUpdateResult == null ? "{}" : mUpdateResult);
    }

    /** 脚本结束/取消时清理所有 overlay（未绑定则忽略）。 */
    public void closeOverlaysIfBound() {
        closeOverlaysIfBound(null);
    }

    /**
     * task-188：清理本 owner 的 overlay；owner 为空保持旧语义（全部清理，向后兼容）。
     * 并行 Node job 各自一个 owner，停一个不能清掉另一个的浮窗。
     */
    public void closeOverlaysIfBound(String owner) {
        Messenger service = mService;
        if (service == null) return;
        try {
            Message message = Message.obtain(null, NodeUiProtocol.MSG_OVERLAY);
            Bundle data = new Bundle();
            org.json.JSONObject request = new org.json.JSONObject().put("action", "closeAll");
            if (owner != null && !owner.isEmpty()) request.put("owner", owner);
            data.putString(NodeUiProtocol.K_REQUEST, request.toString());
            message.setData(data);
            service.send(message);
        } catch (Throwable ignored) { }
    }

    public void cleanupOwnerIfBound(String owner) {
        Messenger service = mService;
        if (service == null || owner == null) return;
        try {
            Message message = Message.obtain(null, NodeUiProtocol.MSG_CLEANUP_OWNER);
            Bundle data = new Bundle();
            data.putString("owner", owner);
            message.setData(data);
            service.send(message);
        } catch (RemoteException ignored) { }
    }

    public synchronized JSONObject work(Context context, JSONObject request) throws Exception {
        if (this != WORK_INSTANCE) return WORK_INSTANCE.work(context, request);
        if (mContext == null || !mBound) { bind(context); }
        if (mService == null) throw new IllegalStateException("主进程 NodeUiBridgeService 未绑定");
        mUpdateResult = null;
        mUpdateLatch = new CountDownLatch(1);
        Message message = Message.obtain(null, NodeUiProtocol.MSG_WORK);
        Bundle data = new Bundle();
        data.putString(NodeUiProtocol.K_REQUEST, request == null ? "{}" : request.toString());
        message.setData(data);
        message.replyTo = mReply;
        mService.send(message);
        if (!mUpdateLatch.await(15, TimeUnit.SECONDS)) {
            throw new IllegalStateException("work_manager 命令超时");
        }
        return new JSONObject(mUpdateResult == null ? "{}" : mUpdateResult);
    }

    public boolean isClosed() { return mClosed; }

    /** task-94：当前会话是否已打开过主进程 Activity（脚本结束时据此 close）。 */
    public boolean hasActivity() { return mShowStarted && mSessionId != null; }

    public void close() {
        Messenger service = mService;
        String session = mSessionId;
        if (service == null || session == null) return;
        try {
            Message message = Message.obtain(null, NodeUiProtocol.MSG_CLOSE);
            Bundle data = new Bundle();
            data.putString(NodeUiProtocol.K_SESSION, session);
            message.setData(data);
            service.send(message);
        } catch (Throwable error) { Log.w(TAG, "close failed", error); }
    }

    public synchronized void release(Context context) {
        if (mBinding.getAndSet(false) && context != null) {
            try { context.getApplicationContext().unbindService(mConnection); } catch (Throwable ignored) { }
        }
        mBound = false;
        mService = null;
        notifyClosed();
        if (this == INSTANCE) WORK_INSTANCE.release(context);
    }

    private void notifyClosed() {
        if (mClosed) return;
        mClosed = true;
        Listener listener = mListener;
        if (listener != null) {
            try { listener.onClosed(); } catch (Throwable ignored) { }
        }
    }
}
