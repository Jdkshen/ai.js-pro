// SPDX-License-Identifier: MIT
package org.autojs.source.engine.android;

import android.content.*;
import android.os.*;
import java.io.IOException;
import java.util.concurrent.*;
import org.json.JSONObject;

/** Same-UID Binder/Messenger client; execute only on a worker thread. */
public final class Node24Client implements AutoCloseable {
    /**
     * task-168：evaluate 阻塞等待 RESULT 期间，逐行接收 :node24 捕获的 stdout/stderr。
     * index 是该行在最终 {@code logs} 数组中的下标；实现方应只显示一次。
     */
    public interface LogListener { void onLog(String line,int index); }
    private final Context context;
    private final Class<?> serviceClass;
    private final ConcurrentHashMap<String,Pending> active=new ConcurrentHashMap<>();
    // forceStop may arrive before evaluate registers its Pending or binds the service.
    private final ConcurrentHashMap<String,Boolean> cancelledIds=new ConcurrentHashMap<>();
    private final CountDownLatch connected=new CountDownLatch(1);
    private volatile Messenger remote;private volatile String failure;
    private boolean bound;private volatile boolean closed;
    private static final class Pending {
        final CountDownLatch done=new CountDownLatch(1);volatile JSONObject result;
        boolean sent;volatile boolean cancelled;
        final LiveLogState logs;
        Pending(LogListener listener){logs=new LiveLogState(listener);}
    }
    /**
     * 单次 job 的实时日志窗口：按 seq 去重/保序，RESULT 之前先 close。
     * close() 返回后不会再触发 onLog，因此 evaluate 返回时引擎的去重状态是稳定的。
     */
    static final class LiveLogState {
        private final LogListener listener;
        private boolean closed;
        private int lastIndex=-1;
        private int delivered;
        LiveLogState(LogListener listener){this.listener=listener;}
        boolean offer(String line,int index) {
            LogListener callback;
            int acceptedIndex;
            synchronized(this) {
                if(closed)return false;
                if(index<0)index=lastIndex+1;
                else if(index<=lastIndex)return false;
                lastIndex=index;delivered++;
                callback=listener;acceptedIndex=index;
                if(callback==null)return true;
                // 回调放在锁内：close() 会等正在执行的回调结束，保证 RESULT 之后不再有实时行。
                try {callback.onLog(line==null?"":line,acceptedIndex);}
                catch(Throwable ignored) { }
                return true;
            }
        }
        synchronized void close(){closed=true;}
        synchronized int delivered(){return delivered;}
    }
    private final Messenger replies=new Messenger(new Handler(Looper.getMainLooper()) {
        @Override public void handleMessage(Message message) {
            if(message.what==Node24Service.LOG) {
                try {
                    Bundle data=message.getData();
                    String id=data.getString("id");
                    if(id==null)return;
                    Pending pending=active.get(id);
                    if(pending!=null) pending.logs.offer(data.getString("line"),data.getInt("seq",-1));
                } catch(Throwable error) { android.util.Log.w("Node24Client","Invalid log",error); }
                return;
            }
            if(message.what!=Node24Service.RESULT)return;
            try {
                JSONObject result=new JSONObject(message.getData().getString("payload"));
                Pending pending=active.get(result.getString("id"));
                if(pending!=null){
                    // 先关实时窗口再发布结果：之后到达/迟到的 LOG 一律丢弃，由 result.logs 回填。
                    pending.logs.close();
                    pending.result=result;
                    pending.done.countDown();
                }
            }catch(Exception error){android.util.Log.w("Node24Client","Invalid result",error);}
        }
    });
    private final ServiceConnection connection=new ServiceConnection() {
        public void onServiceConnected(ComponentName name,IBinder binder){remote=new Messenger(binder);connected.countDown();}
        public void onServiceDisconnected(ComponentName name){fail("Node24 service disconnected");}
        public void onBindingDied(ComponentName name){fail("Node24 service binding died");}
        public void onNullBinding(ComponentName name){fail("Node24 service returned no binder");}
    };
    public Node24Client(Context context){this(context,Node24Service.class);}
    /** task-175：绑定到指定 Node 宿主进程池槽（每个槽一个 :node24_N 进程）。 */
    public Node24Client(Context context,Class<?> serviceClass){
        this.context=context.getApplicationContext();
        this.serviceClass=serviceClass==null?Node24Service.class:serviceClass;
    }
    private void fail(String reason){
        failure=reason;connected.countDown();
        for(Pending pending:active.values()){pending.logs.close();pending.done.countDown();}
    }
    private synchronized void bind() throws IOException {
        if(closed)throw new IOException("Node client closed");
        if(!bound){
            Intent intent=new Intent(context,serviceClass);
            if(!context.bindService(intent,connection,Context.BIND_AUTO_CREATE))throw new IOException("Cannot bind Node24 service");
            bound=true;
        }
    }
    /** 兼容旧调用方：不订阅实时日志，仍从返回结果的 logs 读取全部输出。 */
    public JSONObject evaluate(JSONObject request) throws Exception {
        return evaluate(request,null);
    }
    /**
     * task-168：等待期间通过 listener 实时收到 stdout/stderr 行；返回结果里的 logs 仍是完整
     * 兜底数组。listener 只在当前 job 的 Pending 上生效，不跨 job/全局共享。
     */
    public JSONObject evaluate(JSONObject request,LogListener listener) throws Exception {
        if(Looper.myLooper()==Looper.getMainLooper())throw new IllegalStateException("Evaluate Node on a worker thread");
        String id=request.getString("id");Pending pending=new Pending(listener);
        if(active.putIfAbsent(id,pending)!=null)throw new IOException("Duplicate Node execution id");
        java.io.File sourceFile=null;
        try {
            JSONObject payload=request;
            if(request.getString("source").length()>96*1024 || request.toString().length()>196608) {
                sourceFile=Node24SourceTransport.write(context.getCacheDir(),request.getString("source"));
                payload=new JSONObject(request.toString());
                payload.remove("source");
                payload.put("sourceFile",sourceFile.getAbsolutePath());
            }
            if(payload.toString().length()>196608)throw new IOException("Node request metadata exceeds Binder payload limit");
            if(isCancelled(id,pending))return cancelledResult(id);
            bind();
            long connectDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
            while(!connected.await(100,TimeUnit.MILLISECONDS)) {
                if(isCancelled(id,pending))return cancelledResult(id);
                if(System.nanoTime()>=connectDeadline)throw new IOException("Node service connection timeout");
            }
            if(isCancelled(id,pending))return cancelledResult(id);
            if(failure!=null||remote==null)throw new IOException(failure==null?"Node service unavailable":failure);
            Message message=Message.obtain(null,Node24Service.EXECUTE);Bundle data=new Bundle();
            data.putString("payload",payload.toString());message.setData(data);message.replyTo=replies;
            // Serialize EXECUTE/CANCEL for this id so a cancel cannot get delivered first
            // and then be lost when EXECUTE is sent after connection establishment.
            synchronized(pending) {
                if(isCancelled(id,pending))return cancelledResult(id);
                remote.send(message);pending.sent=true;
            }
            // A visible UI is governed by its lifecycle and explicit stop, not elapsed time.
            long holdMillis=request.optLong("holdMillis",0L);
            long waitMillis=request.getLong("timeoutMillis")+holdMillis+15000L;
            if(request.optBoolean("uiScript",false)) pending.done.await();
            else if(!pending.done.await(waitMillis,TimeUnit.MILLISECONDS)) {
                cancel(id);throw new IOException("Node service response timeout");
            }
            if(isCancelled(id,pending))return cancelledResult(id);
            if(pending.result==null)throw new IOException(failure==null?"Node service returned no result":failure);
            return pending.result;
        }catch(InterruptedException interrupted) {
            // Do this before active.remove: an interrupted outer :script execution must
            // still cancel its nested Node task instead of leaving it running alone.
            cancel(id);Thread.currentThread().interrupt();throw interrupted;
        }finally{
            if(sourceFile!=null)sourceFile.delete();
            // 超时/中断/断连路径也要关窗口：返回前完成正在执行的回调，之后到达的 LOG 不再显示。
            pending.logs.close();
            active.remove(id,pending);cancelledIds.remove(id);
        }
    }
    private boolean isCancelled(String id,Pending pending) {
        return pending.cancelled||cancelledIds.containsKey(id);
    }
    private static JSONObject cancelledResult(String id) throws Exception {
        return new JSONObject().put("id",id).put("status","CANCELLED")
                .put("success",false).put("cancelled",true).put("message","Node execution cancelled");
    }
    public boolean cancel(String id) {
        if(id==null||closed)return false;
        cancelledIds.put(id,Boolean.TRUE);
        Pending pending=active.get(id);
        if(pending==null)return true;
        synchronized(pending) {
            pending.cancelled=true;
            if(!pending.sent)return true;
            Messenger target=remote;if(target==null)return true;
            try {Message message=Message.obtain(null,Node24Service.CANCEL);Bundle data=new Bundle();data.putString("id",id);message.setData(data);target.send(message);return true;}
            catch(RemoteException error){fail(error.toString());return false;}
        }
    }
    /**
     * task-58/59：把事件投递到当前 Node 执行任务的轮询队列（:node24 侧 Node24HostBridge.poll 取走）。
     * 执行结束后投递会被忽略（active 里没有该 id）。
     */
    public boolean postEvent(String id,String type,String payloadJson) {
        if(id==null || type==null || type.isEmpty()) return false;
        Messenger target=remote;
        if(target==null || !active.containsKey(id)) return false;
        try {
            Message message=Message.obtain(null,Node24Service.EVENT);
            Bundle data=new Bundle();
            data.putString("id",id);data.putString("type",type);
            data.putString("payload",payloadJson==null?"":payloadJson);
            message.setData(data);target.send(message);return true;
        } catch(RemoteException error) {fail(error.toString());return false;}
    }
    /** task-175：池健康检查；Binder 失败/已关闭时返回 false。 */
    public boolean isAlive() { return !closed && failure == null && remote != null; }
    @Override public synchronized void close() {
        if(closed)return;for(String id:active.keySet())cancel(id);closed=true;
        if(bound){context.unbindService(connection);bound=false;}fail("Node client closed");
    }
}
