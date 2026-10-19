// SPDX-License-Identifier: MIT
package org.autojs.source.engine.android;

import android.app.Service;
import android.content.Intent;
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
public final class Node24Service extends Service {
    public static final int EXECUTE=1,RESULT=2,CANCEL=3,EVENT=4;
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
    private final AtomicReference<Job> active=new AtomicReference<>();
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
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
                if(!active.compareAndSet(null,job)) { job.reply(new JSONObject().put("status","BUSY").put("success",false));return; }
                worker.execute(job);
            } catch(Exception error) { android.util.Log.e("Node24Service","Invalid engine request",error); }
        }
    });
    @Override public IBinder onBind(Intent intent) { return messenger.getBinder(); }
    @Override public void onDestroy() {
        Job job=active.get();if(job!=null)job.cancel();worker.shutdown();super.onDestroy();
    }
    private final class Job implements Runnable {
        final String id,source,filename;final long timeout;final long holdMillis;final boolean uiScript;final Messenger reply;
        volatile boolean cancelled;volatile boolean finished;volatile Node24Engine engine;volatile Node24HostBridge host;
        Job(JSONObject request,Messenger reply) throws Exception {
            this.reply=reply;id=request.getString("id");source=request.getString("source");
            filename=request.getString("filename");timeout=request.getLong("timeoutMillis");
            holdMillis=Math.max(0L,Math.min(600_000L,request.optLong("holdMillis",0L)));
            uiScript=request.optBoolean("uiScript",false);
            if(!id.matches("[A-Za-z0-9_-]{1,64}") || source.length()>131072 || !filename.startsWith("/")
                || filename.length()>1024 || filename.indexOf(0)>=0 || timeout<1 || timeout>60000)
                throw new IOException("Invalid Node request");
        }
        void cancel() {
            cancelled=true;
            // 先取 engine/host 快照并按顺序取消：先 cancel native（异步，execute 尚未返回时也可调用），
            // 再唤醒可能卡在投屏授权页的宿主线程。反过来先唤醒会让 JS 抢先退出并把 engine 置空，
            // 导致这里错过 native cancel，最终把取消误报成 success。
            Node24Engine current=engine;
            Node24HostBridge currentHost=host;
            if(current!=null)try{current.cancel();}catch(IllegalStateException alreadyClosed){}
            if(currentHost!=null) currentHost.cancelPendingProjection();
            // task-166：先启动"尽早结束进程"的 daemon 线程，再做 UI 清理；
            // closeUiAfterExecution 内部可能因 Binder/主 Looper 阻塞十几秒，不能挡在 kill 前面。
            haltSoonOnCancel();
            if(currentHost!=null) {
                final Node24HostBridge cleanupHost = currentHost;
                Thread cleanup = new Thread(() -> {
                    try { closeUiAfterExecution(cleanupHost); }
                    catch(Throwable ignored) { }
                }, "node24-cancel-cleanup");
                cleanup.setDaemon(true);
                cleanup.start();
            }
            // native 超过 2.5s 仍未返回时再次兜底结束 :node24 进程。
            Thread watchdog = new Thread(() -> {
                try { Thread.sleep(2500L); } catch (InterruptedException ignored) { return; }
                if (finished) return;
                android.util.Log.w("Node24Service","cancel watchdog: job still running, force halt id="+id);
                try { if(host!=null) closeUiAfterExecution(host); } catch(Throwable ignored) { }
                haltProcess();
            }, "node24-cancel-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();
        }

        private void haltProcess() {
            try { stopSelf(); } catch(Throwable ignored) { }
            try { android.os.Process.killProcess(android.os.Process.myPid()); } catch(Throwable ignored) { }
        }

        void finishAndHaltIfCancelled() {
            finished = true;
            if (!cancelled) return;
            // task-166：用 daemon 线程而不是主 Looper postDelayed，避免停止路径主线程被
            // Node UI 清理/Binder 阻塞时进程迟迟不退（实测曾延迟 15s）。
            Thread halt = new Thread(() -> {
                try { Thread.sleep(350L); } catch (InterruptedException ignored) { }
                haltProcess();
            }, "node24-cancel-halt");
            halt.setDaemon(true);
            halt.start();
        }

        void haltSoonOnCancel() {
            Thread halt = new Thread(() -> {
                try { Thread.sleep(800L); } catch (InterruptedException ignored) { }
                if (finished) return;
                android.util.Log.w("Node24Service","cancel halt: force exit id="+id);
                try { if(host!=null) closeUiAfterExecution(host); } catch(Throwable ignored) { }
                haltProcess();
            }, "node24-cancel-halt-early");
            halt.setDaemon(true);
            halt.start();
        }
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
        @Override public void run() {
            long start=SystemClock.elapsedRealtime();JSONObject result=new JSONObject();
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
                try (Node24Engine current=new Node24Engine(); OutputCapture output=new OutputCapture()) {
                    UiLifecycleWatcher watcher=null;
                    try {
                        stage="execute";
                        engine=current;host=bridge;if(cancelled)current.cancel();
                        watcher=new UiLifecycleWatcher(id,bridge,current);
                        Node24Engine.Result value;
                        try {
                            watcher.start();
                            value=current.execute(prepared,filename,nativeTimeout,bridge);
                        } catch(Throwable executeError) {
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
                            .put("logs",output.lines()).put("engineVersion","Node 24.22.0").put("sourceName",filename)
                            .put("uid",android.os.Process.myUid()).put("pid",android.os.Process.myPid())
                            .put("process",getPackageName()+":node24").put("runtimeDirectory",runtime.getPath())
                            .put("heldMillis",0L).put("nativeTimeoutMillis",nativeTimeout)
                            .put("uiCloseCancelled",watcher.cancelledByUiClose())
                            .put("prelude",Node24Prelude.describeAssets(Node24Service.this).toString())
                            .put("legacyNodeLoaded",Node24Libraries.maps().contains("/libv8.so"))
                            .put("legacyAutoJsApiAvailable",false).put("scopePolicy","isolated_per_evaluation");
                    } finally {
                        // 先停轮询线程，再关 UI / engine；避免 watcher 在 engine.close() 后仍 cancel 或读 bridge。
                        if(watcher!=null) watcher.stop();
                        closeUiAfterExecution(bridge);
                        // 每个 job 结束都必须释放图片 Bitmap 句柄、投屏和自动化 Binder 连接。
                        try { bridge.close(); }
                        catch(Throwable closeError) {
                            android.util.Log.w("Node24Service","close host bridge failed id="+id,closeError);
                        }
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
                if(bridge!=null) {
                    try { bridge.close(); }
                    catch(Throwable closeError) {
                        android.util.Log.w("Node24Service","close host bridge failed id="+id,closeError);
                    }
                }
                engine=null;host=null;active.compareAndSet(this,null);
            }
            // 取消竞态兜底：Job.cancel 可能发生在结果收集或 finally 清理期间；最终回复前
            // 统一以 Job.cancelled 为准，取消绝不回报 success。
            if(cancelled) {
                try { result.put("status","CANCELLED").put("success",false).put("cancelled",true); }
                catch(Exception ignored) {}
            }
            try {result.put("elapsedMillis",SystemClock.elapsedRealtime()-start);}catch(Exception ignored){}
            reply(result);
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
        private final FileDescriptor originalOut,originalErr;
        private final ParcelFileDescriptor[] pipe;
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        private final ByteArrayOutputStream lineBuffer=new ByteArrayOutputStream();
        private final Thread reader;private boolean closed;
        OutputCapture() throws Exception {
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
                        // 按行转发到 logcat（Node24Console），job 结束仍会汇总 logs 给主进程控制台。
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
                }catch(IOException ignored){}
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
        }

        JSONArray lines() { synchronized(bytes) {
            JSONArray lines=new JSONArray();String value=new String(bytes.toByteArray(),StandardCharsets.UTF_8);
            for(String line:value.split("\\r?\\n"))if(!line.isEmpty())lines.put(line);return lines;
        } }
    }
}
