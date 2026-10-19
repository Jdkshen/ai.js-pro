// SPDX-License-Identifier: MIT
package org.autojs.source.engine.android;

import android.content.*;
import android.os.*;
import java.io.IOException;
import java.util.concurrent.*;
import org.json.JSONObject;

/** Same-UID Binder/Messenger client; execute only on a worker thread. */
public final class Node24Client implements AutoCloseable {
    private final Context context;
    private final ConcurrentHashMap<String,Pending> active=new ConcurrentHashMap<>();
    // forceStop may arrive before evaluate registers its Pending or binds the service.
    private final ConcurrentHashMap<String,Boolean> cancelledIds=new ConcurrentHashMap<>();
    private final CountDownLatch connected=new CountDownLatch(1);
    private volatile Messenger remote;private volatile String failure;
    private boolean bound;private volatile boolean closed;
    private static final class Pending {
        final CountDownLatch done=new CountDownLatch(1);volatile JSONObject result;
        boolean sent;volatile boolean cancelled;
    }
    private final Messenger replies=new Messenger(new Handler(Looper.getMainLooper()) {
        @Override public void handleMessage(Message message) {
            if(message.what!=Node24Service.RESULT)return;
            try {
                JSONObject result=new JSONObject(message.getData().getString("payload"));
                Pending pending=active.get(result.getString("id"));
                if(pending!=null){pending.result=result;pending.done.countDown();}
            }catch(Exception error){android.util.Log.w("Node24Client","Invalid result",error);}
        }
    });
    private final ServiceConnection connection=new ServiceConnection() {
        public void onServiceConnected(ComponentName name,IBinder binder){remote=new Messenger(binder);connected.countDown();}
        public void onServiceDisconnected(ComponentName name){fail("Node24 service disconnected");}
        public void onBindingDied(ComponentName name){fail("Node24 service binding died");}
        public void onNullBinding(ComponentName name){fail("Node24 service returned no binder");}
    };
    public Node24Client(Context context){this.context=context.getApplicationContext();}
    private void fail(String reason){failure=reason;connected.countDown();for(Pending pending:active.values())pending.done.countDown();}
    private synchronized void bind() throws IOException {
        if(closed)throw new IOException("Node client closed");
        if(!bound){
            Intent intent=new Intent(context,Node24Service.class);
            if(!context.bindService(intent,connection,Context.BIND_AUTO_CREATE))throw new IOException("Cannot bind Node24 service");
            bound=true;
        }
    }
    public JSONObject evaluate(JSONObject request) throws Exception {
        if(Looper.myLooper()==Looper.getMainLooper())throw new IllegalStateException("Evaluate Node on a worker thread");
        if(request.toString().length()>196608)throw new IOException("Node request exceeds Binder payload limit");
        String id=request.getString("id");Pending pending=new Pending();
        if(active.putIfAbsent(id,pending)!=null)throw new IOException("Duplicate Node execution id");
        try {
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
            data.putString("payload",request.toString());message.setData(data);message.replyTo=replies;
            // Serialize EXECUTE/CANCEL for this id so a cancel cannot get delivered first
            // and then be lost when EXECUTE is sent after connection establishment.
            synchronized(pending) {
                if(isCancelled(id,pending))return cancelledResult(id);
                remote.send(message);pending.sent=true;
            }
            // task-63：UI/keepRunning 脚本会被 :node24 服务阻塞保活（holdMillis，最长 10 分钟），
            // 主进程等待上限必须相应放宽，否则脚本还在显示 overlay 时就报 response timeout。
            long holdMillis=request.optLong("holdMillis",0L);
            if(holdMillis<=0L && request.optBoolean("uiScript",false)) holdMillis=600_000L;
            long waitMillis=request.getLong("timeoutMillis")+holdMillis+15000L;
            if(!pending.done.await(waitMillis,TimeUnit.MILLISECONDS)) {
                cancel(id);throw new IOException("Node service response timeout");
            }
            if(isCancelled(id,pending))return cancelledResult(id);
            if(pending.result==null)throw new IOException(failure==null?"Node service returned no result":failure);
            return pending.result;
        }catch(InterruptedException interrupted) {
            // Do this before active.remove: an interrupted outer :script execution must
            // still cancel its nested Node task instead of leaving it running alone.
            cancel(id);Thread.currentThread().interrupt();throw interrupted;
        }finally{active.remove(id,pending);cancelledIds.remove(id);}
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
    @Override public synchronized void close() {
        if(closed)return;for(String id:active.keySet())cancel(id);closed=true;
        if(bound){context.unbindService(connection);bound=false;}fail("Node client closed");
    }
}
