package com.stardust.autojs.engine.node24;

import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * :node24 进程内的同步客户端：在真实 Node 父引擎所在进程（主进程或 {@code :script}）绑定
 * {@link NodeEngineBridgeService}，把 {@code {kind:"engines", action:...}} 转成一次 JSON Binder transact。
 *
 * <p>Flutter 主界面的外部 Run 把 Node 父脚本放在 {@code :script}，旧入口仍可能在主进程；因此
 * 不能固定绑定某一个进程。客户端先并发绑定两个私有服务，对每个服务查询
 * {@link NodeEngineBridgeProtocol#OP_STATUS}，只选<b>本进程有活动 Node 引擎</b>的那个，并把该
 * Binder 固定为本次 Node 任务的 owner。之后的 my/list/exec/execution/emit/stop/stopAll 全部走
 * 同一个 owner 的真实注册表：不会把主进程的 engine id 拿到 {@code :script} 去查，也不会伪造 id。
 *
 * <p>没有活动 owner 时明确抛「当前没有正在运行的 Node 引擎」，不默认回退到主进程。owner 选定后
 * 中途断开也只报错固定 owner 已断开，不会静默切到另一个进程。
 */
public final class NodeEngineBridgeClient implements AutoCloseable {

    private static final String TAG = "NodeEngineBridge";
    private static final long CONNECT_TIMEOUT_MS = 5_000L;

    private final Context mContext;
    private final List<Candidate> mCandidates = new ArrayList<>(2);
    private volatile Candidate mOwner;
    private volatile boolean mClosed;
    private final int mOwnerEngineId;

    public NodeEngineBridgeClient(Context context) {
        this(context, -1);
    }

    public NodeEngineBridgeClient(Context context, int ownerEngineId) {
        mContext = context.getApplicationContext();
        mOwnerEngineId = ownerEngineId;
        // 只枚举真实存在的两个 app-private 服务类；不是虚拟进程 id，也不是硬编码 :script。
        mCandidates.add(new Candidate("主进程", NodeEngineBridgeService.class));
        mCandidates.add(new Candidate(":script", NodeEngineBridgeScriptService.class));
    }

    /** 同步调用；第一次调用先发现并固定真实 owner，后续调用不再切换进程。 */
    public synchronized JSONObject call(JSONObject request) throws Exception {
        if (mClosed) {
            throw new IllegalStateException("Node 引擎桥已关闭");
        }
        if (request == null) {
            throw new IllegalArgumentException("engine bridge request == null");
        }
        ensureOwner();
        Candidate owner = mOwner;
        IBinder binder = owner == null ? null : owner.binder;
        if (binder == null) {
            throw new IllegalStateException(owner == null
                    ? "Node 引擎桥 owner 不可用"
                    : owner.label + " 的引擎桥已断开；本次 Node 任务的 owner 已固定，不会切换进程");
        }
        request.put("ownerEngineId", mOwnerEngineId);
        return transact(binder, request, owner.label);
    }

    private void ensureOwner() throws Exception {
        Candidate pinned = mOwner;
        if (pinned != null) {
            if (pinned.binder == null) {
                throw new IllegalStateException(pinned.label
                        + " 的引擎桥已断开；本次 Node 任务的 owner 已固定，不会切换进程");
            }
            return;
        }
        bindCandidates();
        JSONArray statuses = new JSONArray();
        String[] failures = new String[mCandidates.size()];
        for (int i = 0; i < mCandidates.size(); i++) {
            Candidate candidate = mCandidates.get(i);
            IBinder binder = candidate.binder;
            if (binder == null) {
                statuses.put(JSONObject.NULL);
                failures[i] = candidate.label + "=" + (candidate.failure == null
                        ? "不可用" : candidate.failure);
                continue;
            }
            try {
                JSONObject response = transact(binder, statusRequest(), candidate.label);
                JSONObject value = response.optJSONObject("value");
                if (!response.optBoolean("ok", false) || value == null) {
                    statuses.put(JSONObject.NULL);
                    failures[i] = candidate.label + "="
                            + response.optString("error", "status 无 value");
                    continue;
                }
                statuses.put(new JSONObject()
                        .put("hasNode", value.optBoolean("hasNode", false))
                        .put("startedAt", value.optLong("startedAt", 0L))
                        .put("engineId", value.optInt("engineId", 0))
                        .put("process", value.optString("process", candidate.label))
                        .put("pid", value.optInt("pid", 0)));
            } catch (Throwable error) {
                statuses.put(JSONObject.NULL);
                failures[i] = candidate.label + "=" + errorMessage(error);
            }
        }
        int ownerIndex = NodeEngineBridgeProtocol.selectOwner(statuses);
        if (ownerIndex < 0) {
            throw new IllegalStateException(noOwnerMessage(statuses, failures));
        }
        Candidate selected = mCandidates.get(ownerIndex);
        if (selected.binder == null) {
            throw new IllegalStateException(selected.label + " 的引擎桥在发现后立即断开");
        }
        mOwner = selected;
        JSONObject status = statuses.optJSONObject(ownerIndex);
        Log.i(TAG, "pinned Node engine bridge owner=" + selected.label
                + " pid=" + (status == null ? 0 : status.optInt("pid", 0))
                + " engineId=" + (status == null ? 0 : status.optInt("engineId", 0)));
        // 已经确定 owner：解绑另一个进程，避免本次 Node 任务无谓地占用它的 ServiceConnection。
        for (int i = 0; i < mCandidates.size(); i++) {
            if (i != ownerIndex) {
                mCandidates.get(i).unbind();
            }
        }
    }

    private void bindCandidates() {
        for (Candidate candidate : mCandidates) {
            candidate.bind();
        }
        long deadline = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(CONNECT_TIMEOUT_MS);
        for (Candidate candidate : mCandidates) {
            candidate.await(deadline);
        }
    }

    private JSONObject statusRequest() throws Exception {
        return new JSONObject()
                .put("ownerEngineId", mOwnerEngineId)
                .put("action", NodeEngineBridgeProtocol.OP_STATUS)
                .put("args", new JSONObject());
    }

    private String noOwnerMessage(JSONArray statuses, String[] failures) {
        StringBuilder checked = new StringBuilder();
        StringBuilder detail = new StringBuilder();
        for (int i = 0; i < mCandidates.size(); i++) {
            Candidate candidate = mCandidates.get(i);
            if (checked.length() > 0) {
                checked.append('、');
            }
            JSONObject status = statuses.optJSONObject(i);
            checked.append(candidate.label);
            boolean hasNode = status != null && status.optBoolean("hasNode", false);
            if (hasNode) {
                checked.append("（有活动 Node 引擎但无法选中）");
            } else if (status != null) {
                checked.append("（无活动 Node 引擎）");
            }
            if (failures[i] != null) {
                if (detail.length() > 0) {
                    detail.append("；");
                }
                detail.append(failures[i]);
            }
        }
        return "当前没有正在运行的 Node 引擎（已检查 " + checked + " 的真实引擎注册表）"
                + (detail.length() == 0 ? "" : "；连接失败：" + detail)
                + "；无法确定 engines 的父引擎，也不会改用其它进程的 id";
    }

    private static JSONObject transact(IBinder binder, JSONObject request, String ownerLabel)
            throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(NodeEngineBridgeProtocol.SERVICE_ACTION);
            data.writeString(request.toString());
            // 同步 transact：服务端在当前 Binder 线程直接返回，不用 Messenger/Handler 排队，
            // 避免 Node 轮询线程等待宿主主线程 Looper。
            if (!binder.transact(NodeEngineBridgeProtocol.TRANSACT_JSON, data, reply, 0)) {
                throw new IllegalStateException(ownerLabel + " 的引擎桥拒绝请求");
            }
            reply.readException();
            String payload = reply.readString();
            if (payload == null || payload.isEmpty()) {
                throw new IllegalStateException(ownerLabel + " 的引擎桥返回空响应");
            }
            return new JSONObject(payload);
        } catch (android.os.DeadObjectException error) {
            throw new IllegalStateException(ownerLabel + " 的引擎桥已断开", error);
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static String errorMessage(Throwable error) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.isEmpty()
                ? (error == null ? "未知错误" : error.getClass().getSimpleName()) : message;
    }

    @Override
    public void close() {
        synchronized (this) {
            if (mClosed) {
                return;
            }
            mClosed = true;
            for (Candidate candidate : mCandidates) {
                candidate.unbind();
            }
            mOwner = null;
        }
    }

    /** 一个候选服务进程的连接；状态只在对应的 ServiceConnection 里更新。 */
    private final class Candidate {
        final String label;
        final Class<? extends Service> serviceClass;
        final CountDownLatch connected = new CountDownLatch(1);
        volatile IBinder binder;
        volatile String failure;
        volatile boolean bound;

        final ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                binder = service;
                failure = null;
                connected.countDown();
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                binder = null;
                failure = "引擎桥已断开";
                connected.countDown();
            }

            @Override
            public void onBindingDied(ComponentName name) {
                binder = null;
                failure = "引擎桥 binding died";
                connected.countDown();
            }

            @Override
            public void onNullBinding(ComponentName name) {
                binder = null;
                failure = "引擎桥返回空 Binder";
                connected.countDown();
            }
        };

        Candidate(String label, Class<? extends Service> serviceClass) {
            this.label = label;
            this.serviceClass = serviceClass;
        }

        void bind() {
            if (mClosed || bound || binder != null) {
                return;
            }
            try {
                Intent intent = new Intent(mContext, serviceClass);
                intent.setAction(NodeEngineBridgeProtocol.SERVICE_ACTION);
                if (!mContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
                    failure = "无法绑定（服务未注册或进程不可达）";
                    connected.countDown();
                    return;
                }
                bound = true;
            } catch (Throwable error) {
                failure = "绑定失败：" + errorMessage(error);
                connected.countDown();
            }
        }

        void await(long deadlineNanos) {
            long remaining = deadlineNanos - System.nanoTime();
            try {
                if (remaining > 0L) {
                    connected.await(remaining, TimeUnit.NANOSECONDS);
                } else {
                    connected.await(0L, TimeUnit.NANOSECONDS);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                failure = "等待绑定被中断";
            }
            if (binder == null && failure == null) {
                failure = "绑定超时";
            }
        }

        void unbind() {
            if (!bound) {
                return;
            }
            bound = false;
            binder = null;
            try {
                mContext.unbindService(connection);
            } catch (Throwable error) {
                Log.w(TAG, "unbind engine bridge failed: " + label, error);
            }
        }
    }
}
