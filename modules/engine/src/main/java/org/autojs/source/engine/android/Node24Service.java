// SPDX-License-Identifier: MIT
package org.autojs.source.engine.android;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.*;
import android.system.Os;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.autojs.source.engine.Node24Engine;
import org.json.JSONArray;
import org.json.JSONObject;

/** Non-exported, one-active-run service in :node24. No legacy application boot. */
public class Node24Service extends Service {
    public static final int EXECUTE=1,RESULT=2,CANCEL=3,EVENT=4,LOG=5;
    // task-167：主进程 -> :node24 的任务状态广播/控制通道（主进程任务页要能看到 uiScript keep-alive 任务）。
    public static final String ACTION_NODE24_STATE="com.jdkshen.aijspro.NODE24_STATE";
    public static final String ACTION_NODE24_CONTROL="com.jdkshen.aijspro.NODE24_CONTROL";
    /** task-175 spike：同进程多 Node24Engine 并行/取消验证（仅 debug 触发）。 */
    public static final String ACTION_NODE24_SPIKE="com.jdkshen.aijspro.NODE24_SPIKE";
    public static final String EXTRA_STATE="state",EXTRA_COMMAND="command",EXTRA_ID="id",
        EXTRA_FILENAME="filename",EXTRA_SOURCE="source",EXTRA_UI_SCRIPT="uiScript",
        EXTRA_STARTED_AT="startedAt",EXTRA_CANCELLED="cancelled";
    private BroadcastReceiver controlReceiver;
    /** uiScript/显式 hold 的 native 事件循环寿命上限；与 Node24Client 的等待预算一致。 */
    private static final long UI_HOLD_FALLBACK_MS=600_000L;
    /** UI 已关闭后给 JS 清理（destroy 回调/stopRunning）的宽限；超时则取消 native 执行。 */
    private static final long UI_CLOSE_GRACE_MS=1_000L;
    /** 未观察到 closeRequested 时，UI 从有到无的兜底宽限（避免把替换界面的短暂空窗当关闭）。 */
    private static final long UI_CLOSE_FALLBACK_MS=3_000L;
    /** Activity show/replacement 的最长等待（NodeUiActivityClient.show 为 10s）；此窗口内不算 UI 关闭。 */
    private static final long UI_REPLACEMENT_WINDOW_MS=10_000L;
    /** UI 生命周期轮询间隔（独立 daemon 线程，不在主线程/Binder 线程）。 */
    private static final long UI_WATCH_INTERVAL_MS=200L;
    /** 实时控制台与最终 logs 桶共用 64 KiB 预算；另限消息条数，避免逐行 Binder 消息打满事务队列。 */
    private static final int LIVE_LOG_BUDGET_BYTES=65536;
    private static final int LIVE_LOG_MAX_LINES=4096;
    private final AtomicReference<Job> active=new AtomicReference<>();
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    // task-174：单 job 槽忙时不再立刻 BUSY，而是有界排队（≤8s）；超时才回明确中文提示。
    private static final long PENDING_TIMEOUT_MS=8000L;
    private static final int MAX_PENDING_JOBS=4;
    private final java.util.ArrayDeque<PendingJob> pending=new java.util.ArrayDeque<>();
    private final Handler mainHandler=new Handler(Looper.getMainLooper());
    private final class PendingJob {
        final Job job;final long deadline;
        PendingJob(Job job,long deadline){this.job=job;this.deadline=deadline;}
    }
    private final Messenger messenger=new Messenger(new Handler(Looper.getMainLooper()) {
        @Override public void handleMessage(Message message) {
            if (message.sendingUid!=android.os.Process.myUid()) return;
            if (message.what==CANCEL) {
                Job job=active.get();
                if (job!=null && job.id.equals(message.getData().getString("id"))) job.cancel();
                return;
            }
            if (message.what==EVENT) {
                // task-58/59：主进程把 UI/生命周期事件投递到 :node24 的轮询队列（B 的 NodeUiHost 用）。
                Job job=active.get();
                if (job!=null) job.postEvent(message.getData());
                return;
            }
            if (message.what!=EXECUTE || message.replyTo==null) return;
            try {
                JSONObject request=new JSONObject(message.getData().getString("payload"));
                Job job=new Job(request,message.replyTo);
                if(!active.compareAndSet(null,job)) { enqueuePending(job); return; }
                broadcastState("started",job);
                worker.execute(job);
            } catch(Exception error) { android.util.Log.e("Node24Service","Invalid engine request",error); }
        }
    });
    /** task-174：当前 job 忙时排队；等待期间不再让客户端立刻 BUSY。 */
    private void enqueuePending(Job job) {
        Job running=active.get();
        if(running!=null && running.cancelled) {
            // 正在停止：进程马上会被结束，排队请求会随进程丢失；立刻回明确提示让用户稍后重试。
            replyBusy(job,"当前 Node 脚本正在停止，请稍后重试");
            return;
        }
        synchronized(pending) {
            if(pending.size()>=MAX_PENDING_JOBS) {
                replyBusy(job,"已有 Node 脚本正在运行（排队已满），请先停止后再运行");
                return;
            }
            pending.addLast(new PendingJob(job,SystemClock.elapsedRealtime()+PENDING_TIMEOUT_MS));
            android.util.Log.i("Node24Service","job queued id="+job.id
                    +" pending="+pending.size()+" active="+(active.get()==null?"none":active.get().id));
        }
        mainHandler.postDelayed(this::drainPending,PENDING_TIMEOUT_MS+100L);
    }

    /** 从排队请求里取下一个可执行 job；过期请求回明确中文 BUSY。 */
    private void drainPending() {
        synchronized(pending) {
            while(!pending.isEmpty()) {
                PendingJob head=pending.peekFirst();
                if(head==null) return;
                if(SystemClock.elapsedRealtime()>head.deadline) {
                    pending.pollFirst();
                    replyBusy(head.job,"已有 Node 脚本正在运行（Node UI/悬浮窗或上一个任务未结束），已等待 8 秒仍未释放；请先停止当前脚本再运行");
                    continue;
                }
                if(active.compareAndSet(null,head.job)) {
                    pending.pollFirst();
                    broadcastState("started",head.job);
                    worker.execute(head.job);
                }
                return;
            }
        }
    }

    /** task-174：取消时把排队请求全部回明确提示，避免它们等 8s 或随进程丢失导致客户端超时。 */
    private void failPendingForCancel() {
        synchronized(pending) {
            while(!pending.isEmpty()) {
                PendingJob item=pending.pollFirst();
                if(item!=null) replyBusy(item.job,"当前 Node 脚本正在停止，请稍后重试");
            }
        }
    }

    private void replyBusy(Job job,String message) {
        if(job==null) return;
        android.util.Log.w("Node24Service","job busy id="+job.id+" message="+message);
        try { job.reply(new JSONObject().put("status","BUSY").put("success",false).put("message",message)); }
        catch(Exception ignored) { }
    }

    @Override public void onCreate() {
        super.onCreate();
        // task-167：主进程任务页「停止」/「停止全部」通过广播下发 cancel/cancelAll（非导出，同一 UID）。
        try {
            controlReceiver=new BroadcastReceiver() {
                @Override public void onReceive(Context context,Intent intent) {
                    if(intent==null)return;
                    Job job=active.get();
                    if(job==null)return;
                    String command=intent.getStringExtra(EXTRA_COMMAND);
                    if("cancelAll".equals(command)) job.cancel();
                    else if("cancel".equals(command) && job.id.equals(intent.getStringExtra(EXTRA_ID))) job.cancel();
                    else if("query".equals(command)) broadcastState("started",job);
                }
            };
            IntentFilter filter=new IntentFilter(ACTION_NODE24_CONTROL);
            if(Build.VERSION.SDK_INT>=33) registerReceiver(controlReceiver,filter,Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(controlReceiver,filter);
        } catch(Throwable error) { android.util.Log.w("Node24Service","register control receiver failed",error); }
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent!=null && ACTION_NODE24_SPIKE.equals(intent.getAction())){
            new Thread(this::runMultiEngineSpike,"node24-multi-spike").start();
            return START_NOT_STICKY;
        }
        return super.onStartCommand(intent,flags,startId);
    }

    /**
     * task-175 spike：在同一进程创建 2 个 Node24Engine 并行执行，并测试第三个的 cancel。
     * 结果只打 logcat（SPIKE_*），不进入生产执行路径；用于确认 libajsnode24bridge 是否支持多实例。
     */
    private void runMultiEngineSpike(){
        android.util.Log.i("SPIKE","SPIKE_START pid="+android.os.Process.myPid());
        Node24Engine a=null,b=null,c=null;
        try {
            Node24Libraries.load(this);
            final Node24Engine ea=new Node24Engine(); a=ea;
            final Node24Engine eb=new Node24Engine(); b=eb;
            final long[] times=new long[2];
            final String[] results=new String[2];
            final long wallStart=android.os.SystemClock.elapsedRealtime();
            Thread ta=new Thread(()->{
                long t0=android.os.SystemClock.elapsedRealtime();
                try {
                    Node24Engine.Result r=ea.execute(
                        "\"nodejs\"; const t=Date.now(); setTimeout(()=>{ console.log('SPIKE_A_DONE '+(Date.now()-t)); },3000);",
                        "/spike-a.js",20000L);
                    results[0]="exit="+r.exitCode+" cancelled="+r.cancelled+" error="+r.error;
                } catch(Throwable e){ results[0]="ERROR "+e; }
                times[0]=android.os.SystemClock.elapsedRealtime()-t0;
                android.util.Log.i("SPIKE","SPIKE_A_END ms="+times[0]+" "+results[0]);
            },"spike-a");
            Thread tb=new Thread(()->{
                long t0=android.os.SystemClock.elapsedRealtime();
                try {
                    Node24Engine.Result r=eb.execute(
                        "\"nodejs\"; const t=Date.now(); let n=0; const id=setInterval(()=>{ n++; if(n>=6){ clearInterval(id); console.log('SPIKE_B_DONE '+(Date.now()-t)); } },500);",
                        "/spike-b.js",20000L);
                    results[1]="exit="+r.exitCode+" cancelled="+r.cancelled+" error="+r.error;
                } catch(Throwable e){ results[1]="ERROR "+e; }
                times[1]=android.os.SystemClock.elapsedRealtime()-t0;
                android.util.Log.i("SPIKE","SPIKE_B_END ms="+times[1]+" "+results[1]);
            },"spike-b");
            ta.start(); tb.start();
            ta.join(25000); tb.join(25000);
            android.util.Log.i("SPIKE","SPIKE_PARALLEL wall="+(android.os.SystemClock.elapsedRealtime()-wallStart)
                +" a="+times[0]+" b="+times[1]+" bothEnded="+(ta.isAlive()==false && tb.isAlive()==false));
            // cancel 测试：单实例长循环，1s 后 cancel。
            final Node24Engine ec=new Node24Engine(); c=ec;
            final Node24Engine.Result[] cancelResult=new Node24Engine.Result[1];
            Thread tc=new Thread(()->{
                try { cancelResult[0]=ec.execute("\"nodejs\"; setInterval(()=>{},1000);","/spike-c.js",30000L); }
                catch(Throwable e){ android.util.Log.i("SPIKE","SPIKE_C_ERROR "+e); }
            },"spike-c");
            tc.start(); Thread.sleep(1000L); ec.cancel();
            tc.join(8000);
            android.util.Log.i("SPIKE","SPIKE_CANCEL alive="+tc.isAlive()
                +" result="+(cancelResult[0]==null?"null":("cancelled="+cancelResult[0].cancelled+" exit="+cancelResult[0].exitCode)));
        } catch(Throwable error){
            android.util.Log.e("SPIKE","SPIKE_FAIL",error);
        } finally {
            try{ if(a!=null)a.close(); }catch(Throwable ignored){}
            try{ if(b!=null)b.close(); }catch(Throwable ignored){}
            try{ if(c!=null)c.close(); }catch(Throwable ignored){}
            android.util.Log.i("SPIKE","SPIKE_END");
        }
    }

    @Override public IBinder onBind(Intent intent) { return messenger.getBinder(); }
    @Override public void onDestroy() {
        try { if(controlReceiver!=null) unregisterReceiver(controlReceiver); } catch(Throwable ignored) { }
        controlReceiver=null;
        Job job=active.get();if(job!=null)job.cancel();worker.shutdown();super.onDestroy();
    }
    /** 任务状态广播：started/finished/cancelled + id/filename/uiScript/startedAt，供主进程任务页登记。 */
    private void broadcastState(String state,Job job) {
        if(job==null)return;
        try {
            Intent intent=new Intent(ACTION_NODE24_STATE).setPackage(getPackageName());
            intent.putExtra(EXTRA_STATE,state).putExtra(EXTRA_ID,job.id)
                .putExtra(EXTRA_FILENAME,job.filename).putExtra(EXTRA_SOURCE,job.source)
                .putExtra(EXTRA_UI_SCRIPT,job.uiScript).putExtra(EXTRA_STARTED_AT,job.startedAt)
                .putExtra(EXTRA_CANCELLED,job.cancelled);
            sendBroadcast(intent);
        } catch(Throwable error) { android.util.Log.w("Node24Service","broadcast state failed",error); }
    }
    private final class Job implements Runnable {
        final String id,source,filename;final long timeout;final long holdMillis;final boolean uiScript;final Messenger reply;
        final long startedAt=System.currentTimeMillis();
        volatile boolean cancelled;volatile boolean finished;volatile boolean executeReturned;volatile Node24Engine engine;volatile Node24HostBridge host;
        /** 实时日志只由 node24-output 线程写入；锁同时保护 seq/delivered 与 64 KiB/条数预算。 */
        private final java.util.concurrent.atomic.AtomicBoolean cleanupStarted=
                new java.util.concurrent.atomic.AtomicBoolean();
        private volatile Thread cleanupThread;
        private final Object logLock=new Object();
        private int logSeq,logBytes,logLines,deliveredLogs;
        private boolean logStreamStopped;
        Job(JSONObject request,Messenger reply) throws Exception {
            this.reply=reply;id=request.getString("id");source=request.getString("source");
            filename=request.getString("filename");timeout=request.getLong("timeoutMillis");
            holdMillis=Math.max(0L,Math.min(600_000L,request.optLong("holdMillis",0L)));
            uiScript=request.optBoolean("uiScript",false);
            if(!id.matches("[A-Za-z0-9_-]{1,64}") || source.length()>131072 || !filename.startsWith("/")
                || filename.length()>1024 || filename.indexOf(0)>=0 || timeout<1 || timeout>60000)
                throw new IOException("Invalid Node request");
        }
        synchronized void cancel() {
            if(cancelled) return;
            cancelled=true;
            // task-174：取消立即失败掉排队请求（进程即将结束，排队请求不可能再执行）。
            failPendingForCancel();
            // 先取 engine/host 快照并按顺序取消：先 cancel native（异步，execute 尚未返回时也可调用），
            // 再唤醒可能卡在投屏授权页的宿主线程。反过来先唤醒会让 JS 抢先退出并把 engine 置空，
            // 导致这里错过 native cancel，最终把取消误报成 success。
            Node24Engine current=engine;
            Node24HostBridge currentHost=host;
            if(current!=null)try{current.cancel();}catch(IllegalStateException alreadyClosed){}
            if(currentHost!=null) currentHost.cancelPendingProjection();
            if(currentHost!=null) startCleanup(currentHost);
            // task-181：池进程（:node24_1..N）取消只取消 native，不 800ms 杀进程；
            // 给 2.5s 宽限让 execute 正常以 CANCELLED 返回，超时仍未返回才 kill/invalidate。
            final boolean poolProcess = isPoolProcess();
            if (!poolProcess) {
                // legacy :node24 保持原行为：800ms 硬兜底（task-174）。
                Thread forceHalt = new Thread(() -> {
                    try { Thread.sleep(800L); } catch (InterruptedException ignored) { return; }
                    Job running=active.get();
                    if(running!=null && running!=this) return;
                    android.util.Log.w("Node24Service","cancel force halt id="+id
                            +" active="+(active.get()==null?"none":active.get().id));
                    haltProcess();
                }, "node24-cancel-forcehalt");
                forceHalt.setDaemon(true);
                forceHalt.start();
            }
            Thread watchdog = new Thread(() -> {
                try { Thread.sleep(poolProcess ? 2500L : 1500L); } catch (InterruptedException ignored) { return; }
                Job running=active.get();
                if(running!=null && running!=this) return;
                if (poolProcess && (finished || executeReturned)) {
                    // task-185：池进程里 execute 已返回（cancelled 结果已/正在回复），
                    // 此时还要做 closeUiAfterExecution/bridge.close 等清理；杀死进程会让
                    // Node24Client 变成 IOException: Node24 service disconnected，
                    // 用户主动关 Node UI 会被误报成脚本错误。清理完成自然会回复 CANCELLED。
                    android.util.Log.i("Node24Service","cancel pool graceful: finished="+finished
                            +" executeReturned="+executeReturned+" id="+id);
                    return;
                }
                android.util.Log.w("Node24Service","cancel watchdog: force halt id="+id
                        +" pool="+poolProcess+" finished="+finished);
                haltProcess();
            }, "node24-cancel-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();
        }

        /** task-175：池进程（:node24_1..N）取消后保持 warm，下一次运行可 <1s 复用；旧 :node24 继续退出。 */
        private boolean isPoolProcess() {
            try (java.io.FileInputStream input = new java.io.FileInputStream("/proc/self/cmdline")) {
                byte[] data = new byte[512];
                int count = input.read(data);
                int end = 0;
                while (end < count && data[end] != 0) ++end;
                String name = new String(data, 0, end, java.nio.charset.StandardCharsets.UTF_8);
                return name.contains(":node24_");
            } catch (Throwable ignored) {
                return false;
            }
        }

        private void haltProcess() {
            try { stopSelf(); } catch(Throwable ignored) { }
            try { android.os.Process.killProcess(android.os.Process.myPid()); } catch(Throwable ignored) { }
        }

        void finishAndHaltIfCancelled() {
            finished = true;
            if (!cancelled || isPoolProcess()) return;
            Thread halt = new Thread(() -> {
                try { Thread.sleep(350L); } catch (InterruptedException ignored) { }
                // task-174：有排队请求/新 job 接手时不能杀进程。
                if(active.get()!=null) return;
                synchronized(pending) { if(!pending.isEmpty()) drainPending(); }
                if(active.get()!=null) return;
                haltProcess();
            }, "node24-cancel-halt");
            halt.setDaemon(true);
            halt.start();
        }

        // task-174：旧的 800ms halt 已合并进 cancel() 的 forceHalt（1.5s + 新 job 检测）。
        /**
         * uiScript/显式 hold 用 hold 预算作为 native 事件循环寿命；普通非 UI 脚本保持原 timeout。
         * holdMillis 缺失（旧引擎）时按 UI 上限兜底；Node24Client 的等待预算包含该 hold。
         */
        long nativeTimeoutMillis() {
            if(!uiScript && holdMillis<=0L) return timeout;
            long budget=holdMillis>0L?holdMillis:UI_HOLD_FALLBACK_MS;
            return Math.max(1L,Math.min(UI_HOLD_FALLBACK_MS,budget));
        }
        /** native 已返回后关闭残留 UI：此时已没有 JS 事件循环能继续驱动界面。 */
        void closeUiAfterExecution(Node24HostBridge bridge) {
            boolean overlay=false,activity=false;
            try { overlay=bridge.hasUiOverlay(); } catch(Throwable ignored) { }
            try { activity=bridge.hasNodeUiActivity(); } catch(Throwable ignored) { }
            // task-164：floating overlay 不在 NodeUiHost.hasOverlay 状态里；只要 job 结束就无条件 closeAll，
            // 避免脚本结束/取消后浮窗常驻、多次运行叠加。
            try {
                boolean closed=bridge.closeUiOverlay();
                android.util.Log.i("Node24Service","post-execute ui close id="+id
                        +" overlay="+overlay+" activity="+activity+" requested="+closed);
            } catch(Throwable error) {
                android.util.Log.w("Node24Service","post-execute ui close failed id="+id,error);
            }
            // task-94：主进程 NodeUiActivity 也在这里关闭（在 active 清空前，避免误关下一任务的 session）。
            try {
                com.stardust.autojs.engine.node24.NodeUiActivityClient client=
                        com.stardust.autojs.engine.node24.NodeUiActivityClient.get();
                if(client.hasActivity() && !client.isClosed()) client.close();
            } catch(Throwable closeError) {
                android.util.Log.w("Node24Service","close NodeUiActivity failed",closeError);
            }
        }
        /** task-186：UI/Binder 清理放到独立线程，Job 只做有界等待，避免阻塞重跑窗口。 */
        void startCleanup(Node24HostBridge bridge) {
            if(bridge==null || !cleanupStarted.compareAndSet(false,true)) return;
            Thread cleanup=new Thread(() -> {
                long t0=SystemClock.elapsedRealtime();
                try { closeUiAfterExecution(bridge); }
                catch(Throwable ignored) { }
                long t1=SystemClock.elapsedRealtime();
                try { bridge.close(); }
                catch(Throwable ignored) { }
                android.util.Log.i("Node24Service","cleanup done id="+id
                        +" uiClose="+(t1-t0)+"ms bridgeClose="+(SystemClock.elapsedRealtime()-t1)+"ms");
            }, "node24-cleanup");
            cleanup.setDaemon(true);
            cleanupThread=cleanup;
            cleanup.start();
        }
        void awaitCleanup(long millis) {
            Thread cleanup=cleanupThread;
            if(cleanup==null) return;
            try { cleanup.join(millis); }
            catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
        void postEvent(Bundle data) {
            Node24HostBridge bridge=host;
            if(bridge==null || data==null) return;
            try {
                String payload=data.getString("payload");
                JSONObject json=payload==null||payload.isEmpty()?null:new JSONObject(payload);
                bridge.enqueueEvent(data.getString("type"),json);
            } catch(Throwable error) { android.util.Log.w("Node24Service","Invalid host event",error); }
        }
        void reply(JSONObject result) {
            try {
                result.put("id",id).put("engine","NODE24_SOURCE");
                Message response=Message.obtain(null,RESULT);Bundle data=new Bundle();data.putString("payload",result.toString());response.setData(data);reply.send(response);
            } catch(Exception error) { android.util.Log.w("Node24Service","Client disconnected",error); }
        }
        /**
         * task-168：把 OutputCapture 的一行实时送给主进程。seq 与该行在最终 logs 数组中的下标一致，
         * 客户端因此可以只回填没有实时投递的行。发送量有界；一旦超限/失败/取消就永久停止实时流，
         * 剩余内容仍由 RESULT.logs 兜底，不会无界刷 Binder。
         */
        void sendLog(String line) {
            if(line==null || line.isEmpty() || reply==null) return;
            synchronized(logLock) {
                if(logStreamStopped) return;
                if(cancelled) { logStreamStopped=true; return; }
                int size=line.getBytes(StandardCharsets.UTF_8).length;
                if(logLines>=LIVE_LOG_MAX_LINES || size>LIVE_LOG_BUDGET_BYTES-logBytes) {
                    logStreamStopped=true; return;
                }
                int seq=logSeq;
                try {
                    Message message=Message.obtain(null,LOG);
                    Bundle data=new Bundle();
                    data.putString("id",id);
                    data.putString("line",line);
                    data.putInt("seq",seq);
                    message.setData(data);
                    reply.send(message);
                    logSeq++;logBytes+=size;logLines++;deliveredLogs++;
                } catch(Throwable error) {
                    logStreamStopped=true;
                    android.util.Log.w("Node24Service","live log send failed id="+id,error);
                }
            }
        }
        int deliveredLogs() { synchronized(logLock) { return deliveredLogs; } }
        @Override public void run() {
            long start=SystemClock.elapsedRealtime();JSONObject result=new JSONObject();
            boolean replied=false;
            // task-65：stage 记录当前步骤；外层 catch 打 Log.e，任何一步异常都能定位。
            String stage="job-start";
            long nativeTimeout=nativeTimeoutMillis();
            Node24HostBridge bridge=null;
            android.util.Log.i("Node24Service","job start build=task65-safe id="+id
                    +" holdMillis="+holdMillis+" uiScript="+uiScript
                    +" nativeTimeoutMillis="+nativeTimeout+" scriptLen="+source.length());
            try {
                stage="runtime-load";
                File runtime=Node24Libraries.load(Node24Service.this);
                // task-58：注入 node24/prelude.js（+ 可选 node24/ui.js），并给 Node 提供 Java 反射宿主桥。
                stage="prelude-wrap";
                String prepared=Node24Prelude.wrap(Node24Service.this,source,filename);
                stage="host-bridge";
                bridge=new Node24HostBridge(Node24Service.this,
                        Node24Prelude.projectDir(filename));
                android.util.Log.i("Node24Service","host bridge ready id="+id+" "+bridge.describeState());
                // task-164：清掉上一次运行残留的 floating overlay/Node UI。
                try { bridge.closeUiOverlay(); } catch(Throwable ignored) { }
                try (Node24Engine current=new Node24Engine(); OutputCapture output=new OutputCapture(this::sendLog)) {
                    UiLifecycleWatcher watcher=null;
                    try {
                        stage="execute";
                        engine=current;host=bridge;if(cancelled)current.cancel();
                        watcher=new UiLifecycleWatcher(id,bridge,current);
                        Node24Engine.Result value;
                        try {
                            watcher.start();
                            value=current.execute(prepared,filename,nativeTimeout,bridge);
                            executeReturned=true;
                        } catch(Throwable executeError) {
                            executeReturned=true;
                            android.util.Log.e("Node24Service","execute threw id="+id,executeError);
                            throw executeError;
                        } finally {
                            watcher.stop();
                        }
                        android.util.Log.i("Node24Service","execute returned id="+id+" exitCode="+value.exitCode
                                +" cancelled="+value.cancelled+" timedOut="+value.timedOut
                                +" uiCloseCancel="+watcher.cancelledByUiClose());
                        stage="post-execute";
                        // task-65：先无条件打一行，再逐项求值；每项都安全（bridge 内部已 try/catch）。
                        String bridgeState;
                        try { bridgeState=bridge.describeState(); }
                        catch(Throwable stateError) { bridgeState="state-ERR:"+stateError.getClass().getSimpleName(); }
                        try {
                            android.util.Log.i("Node24Service","post-execute id="+id+" "+bridgeState
                                    +" nativeTimeoutMillis="+nativeTimeout
                                    +" uiCloseCancel="+watcher.cancelledByUiClose());
                        } catch(Throwable logError) {
                            android.util.Log.e("Node24Service","post-execute log failed",logError);
                        }
                        // native 已返回 = JS 事件循环结束/被取消/超时；holdMillis 已经作为 native 寿命消耗，
                        // 不再做第二段 10 分钟 Java holdAlive。残留 UI 由下面的 finally 立即关闭。
                        stage="close-output";
                        output.close();
                        stage="collect-result";
                        // Job.cancel 可能发生在 native 返回之后（例如唤醒授权等待后 JS 正常退出），
                        // 以 Job 标记为准合并，避免把取消回报成 success。
                        boolean wasCancelled=cancelled||value.cancelled;
                        String state=wasCancelled?"CANCELLED":value.timedOut?"TIMEOUT":value.succeeded()?"SUCCESS":"SCRIPT_ERROR";
                        result.put("status",state).put("success",!wasCancelled&&value.succeeded()).put("exitCode",value.exitCode)
                            .put("cancelled",wasCancelled).put("timedOut",value.timedOut).put("message",value.error)
                            .put("logs",output.lines()).put("logsDelivered",deliveredLogs())
                            .put("engineVersion","Node 24.22.0").put("sourceName",filename)
                            .put("uid",android.os.Process.myUid()).put("pid",android.os.Process.myPid())
                            .put("process",getPackageName()+":node24").put("runtimeDirectory",runtime.getPath())
                            .put("heldMillis",0L).put("nativeTimeoutMillis",nativeTimeout)
                            .put("uiCloseCancelled",watcher.cancelledByUiClose())
                            .put("prelude",Node24Prelude.describeAssets(Node24Service.this).toString())
                            .put("legacyNodeLoaded",Node24Libraries.maps().contains("/libv8.so"))
                            .put("legacyAutoJsApiAvailable",false).put("scopePolicy","isolated_per_evaluation");
                        // task-185：先回复结果再清理 UI/bridge：Node UI 关闭触发的取消若在清理期间
                        // 被 watchdog 杀进程，客户端会收到 disconnected 并误报脚本错误。
                        try {
                            try {result.put("elapsedMillis",SystemClock.elapsedRealtime()-start);}catch(Exception ignored){}
                            if(cancelled) {
                                try {result.put("status","CANCELLED").put("success",false).put("cancelled",true);}
                                catch(Exception ignored){}
                            }
                            reply(result);
                            replied=true;
                        } catch(Throwable replyError) {
                            android.util.Log.w("Node24Service","early reply failed id="+id,replyError);
                        }
                    } finally {
                        // 先停轮询线程，再启动异步清理；最多等 600ms 保证 BACK 后 ≤1s 可重跑，
                        // 清理未完成的部分在后台继续（bridge 是旧任务实例，不影响新任务）。
                        if(watcher!=null) watcher.stop();
                        startCleanup(bridge);
                        awaitCleanup(600L);
                        engine=null;host=null;
                    }
                }
            } catch(Throwable error) {
                android.util.Log.e("Node24Service","job failed id="+id+" stage="+stage
                        +" scriptLen="+source.length(),error);
                try { result.put("status","ENGINE_ERROR").put("success",false)
                        .put("stage",stage).put("message",error.toString()); }
                catch(Exception ignored) {}
            } finally {
                // 覆盖 runtime-load/Node24Engine 构造失败等未进入内层 finally 的路径。
                if(bridge!=null) startCleanup(bridge);
                engine=null;host=null;active.compareAndSet(this,null);
                broadcastState(cancelled?"cancelled":"finished",this);
                // task-174：排队请求在 active 释放后立刻接上（干净停止后 ≤1s 可再跑）。
                drainPending();
            }
            // 取消竞态兜底：Job.cancel 可能发生在结果收集或 finally 清理期间；最终回复前
            // 统一以 Job.cancelled 为准，取消绝不回报 success。
            if(cancelled) {
                try { result.put("status","CANCELLED").put("success",false).put("cancelled",true); }
                catch(Exception ignored) {}
            }
            try {result.put("elapsedMillis",SystemClock.elapsedRealtime()-start);}catch(Exception ignored){}
            if(!replied) reply(result);
            finishAndHaltIfCancelled();
        }
    }

    /**
     * 独立 daemon 线程观察 UI 生命周期（不占主线程/Binder 线程）。
     *
     * <p>只有「曾经看到过 Node UI」的任务才进入关闭判定：Back / ui.close / Activity finish 后，
     * closeRequested 或 UI 从有到无会给 JS 一个短清理窗口（destroy 回调 → stopRunning）。
     * 若 JS 忙循环无法轮询 destroy，则在窗口结束后 cancel native 执行，避免等 native timeout。
     * headless keepRunning、启动阶段、无 UI 脚本不会因为一直没有 UI 而被取消。
     */
    private static final class UiLifecycleWatcher implements Runnable {
        private final String id;
        private final Node24HostBridge bridge;
        private final Node24Engine engine;
        private final Thread thread;
        private volatile boolean running=true;
        private volatile boolean cancelledByUiClose;
        private boolean sawUi;
        private boolean closed;
        private long closedAt;
        private long missingSince;

        UiLifecycleWatcher(String id,Node24HostBridge bridge,Node24Engine engine) {
            this.id=id;this.bridge=bridge;this.engine=engine;
            thread=new Thread(this,"node24-ui-lifecycle");thread.setDaemon(true);
        }
        void start() { thread.start(); }
        /** 幂等停止；先禁止 cancel，再打断/join，保证 engine 不会被 close 后调用。 */
        void stop() {
            synchronized(this) { running=false; }
            thread.interrupt();
            if(Thread.currentThread()==thread) return;
            try { thread.join(1_500L); }
            catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
        boolean cancelledByUiClose() { return cancelledByUiClose; }
        @Override public void run() {
            while(running) {
                boolean activity=false,overlay=false;
                try { activity=bridge.hasNodeUiActivity(); } catch(Throwable ignored) { }
                try { overlay=bridge.hasUiOverlay(); } catch(Throwable ignored) { }
                long now=SystemClock.elapsedRealtime();
                if(activity || overlay) {
                    if(!sawUi) {
                        sawUi=true;
                        android.util.Log.i("Node24Service","ui lifecycle open id="+id
                                +" activity="+activity+" overlay="+overlay);
                    } else if(closed) {
                        android.util.Log.i("Node24Service","ui lifecycle reopened id="+id
                                +", defer close cancel");
                    }
                    closed=false;closedAt=0L;missingSince=0L;
                } else if(sawUi) {
                    boolean closeRequested=false;
                    try { closeRequested=bridge.isUiOverlayClosed(); } catch(Throwable ignored) { }
                    if(closeRequested) {
                        if(!closed) {
                            closed=true;closedAt=now;
                            android.util.Log.i("Node24Service","ui lifecycle close requested id="+id
                                    +" graceMillis="+UI_CLOSE_GRACE_MS);
                        }
                    } else {
                        boolean recentUi=false;
                        try { recentUi=bridge.hasRecentUiRequest(UI_REPLACEMENT_WINDOW_MS); }
                        catch(Throwable ignored) { }
                        if(recentUi) {
                            // 正在 show/替换 Activity：界面短暂空窗不能当作关闭。
                            missingSince=0L;
                        } else {
                            if(missingSince==0L) missingSince=now;
                            if(!closed && now-missingSince>=UI_CLOSE_FALLBACK_MS) {
                                closed=true;closedAt=now;
                                android.util.Log.i("Node24Service","ui lifecycle missing id="+id
                                        +" fallbackMillis="+UI_CLOSE_FALLBACK_MS);
                            }
                        }
                    }
                    if(closed && now-closedAt>=UI_CLOSE_GRACE_MS) {
                        synchronized(this) {
                            if(!running) return;
                            cancelledByUiClose=true;
                            android.util.Log.i("Node24Service","ui lifecycle grace expired id="+id
                                    +", cancel native execute");
                            // 与 Job.cancel 相同：先唤醒可能仍在等待投屏授权弹窗的宿主线程，
                            // 否则 UI 已关闭时 job 仍要空等到 55s 授权超时。
                            bridge.cancelPendingProjection();
                            try { engine.cancel(); }
                            catch(Throwable error) {
                                android.util.Log.w("Node24Service","ui lifecycle cancel failed id="+id,error);
                            }
                        }
                        return;
                    }
                }
                try { Thread.sleep(UI_WATCH_INTERVAL_MS); }
                catch(InterruptedException interrupted) {
                    if(!running) return;
                }
            }
        }
    }

    /** Capture C/Node stdout+stderr in this dedicated process, with a bounded buffer. */
    static final class OutputCapture implements AutoCloseable {
        /** 每读到一行（EOF 时的尾行也算）回调一次；用简单接口保持最小 SDK 兼容。 */
        interface LineSink { void onLine(String line); }
        private final FileDescriptor originalOut,originalErr;
        private final ParcelFileDescriptor[] pipe;
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        private final ByteArrayOutputStream lineBuffer=new ByteArrayOutputStream();
        private final LineSink liveSink;
        private final Thread reader;private boolean closed;
        OutputCapture() throws Exception { this(null); }
        OutputCapture(LineSink liveSink) throws Exception {
            this.liveSink=liveSink;
            originalOut=Os.dup(FileDescriptor.out);originalErr=Os.dup(FileDescriptor.err);
            pipe=ParcelFileDescriptor.createPipe();
            reader=new Thread(()->{
                try(InputStream input=new ParcelFileDescriptor.AutoCloseInputStream(pipe[0])) {
                    byte[] buffer=new byte[4096];int size;
                    while((size=input.read(buffer))!=-1) {
                        synchronized(bytes) {
                            int keep=Math.min(size,65536-bytes.size());if(keep>0)bytes.write(buffer,0,keep);
                        }
                        // follow-up(task-155)：console.log 在 UI 脚本运行期间也要可观测。
                        // task-168：每行同时经 LineSink 实时送回主进程；logcat 仍保留用于诊断。
                        for (int i = 0; i < size; i++) {
                            byte b = buffer[i];
                            if (b == '\n') {
                                flushConsoleLine();
                            } else if (b != '\r') {
                                synchronized (lineBuffer) {
                                    if (lineBuffer.size() < 8192) lineBuffer.write(b);
                                }
                            }
                        }
                    }
                }catch(IOException ignored){} finally { flushConsoleLine(); }
            },"node24-output");reader.setDaemon(true);reader.start();
            try { Os.dup2(pipe[1].getFileDescriptor(),1);Os.dup2(pipe[1].getFileDescriptor(),2); }
            catch(Exception error) { close();throw error; }
            pipe[1].close();
        }
        @Override public synchronized void close() throws Exception {
            if(closed)return;closed=true;
            try { Os.dup2(originalOut,1);Os.dup2(originalErr,2); }
            finally { Os.close(originalOut);Os.close(originalErr);pipe[1].close(); }
            reader.join(2000);
            if(reader.isAlive()) { pipe[0].close();throw new IOException("Node output reader did not finish"); }
        }
        private void flushConsoleLine() {
            String line;
            synchronized (lineBuffer) {
                if (lineBuffer.size() == 0) return;
                line = new String(lineBuffer.toByteArray(), StandardCharsets.UTF_8);
                lineBuffer.reset();
            }
            if (line.isEmpty()) return;
            try { android.util.Log.i("Node24Console", line); } catch (Throwable ignored) { }
            LineSink sink=liveSink;
            if(sink!=null) try { sink.onLine(line); }
            catch(Throwable error) { android.util.Log.w("Node24Service","live log sink failed",error); }
        }

        JSONArray lines() { synchronized(bytes) {
            JSONArray lines=new JSONArray();String value=new String(bytes.toByteArray(),StandardCharsets.UTF_8);
            for(String line:value.split("\\r?\\n"))if(!line.isEmpty())lines.put(line);return lines;
        } }
    }
}
