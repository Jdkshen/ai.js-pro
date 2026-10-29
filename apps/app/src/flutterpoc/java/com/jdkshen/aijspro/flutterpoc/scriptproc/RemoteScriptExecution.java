package com.jdkshen.aijspro.flutterpoc.scriptproc;

import android.os.Bundle;
import com.stardust.autojs.engine.*;
import com.stardust.autojs.execution.*;
import com.stardust.autojs.runtime.exception.ScriptInterruptedException;
import com.stardust.autojs.script.*;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Control-plane metadata only; runtime, Java objects and UI stay in the execution process. */
final class RemoteScriptExecution implements ScriptExecution {
    private static final Executor CALLBACKS=Executors.newFixedThreadPool(2,operation -> {
        Thread thread=new Thread(operation,"script-result-callback"); thread.setDaemon(true); return thread;
    });
    private final String token;
    private final File request;
    private final ScriptSource source;
    private final ExecutionConfig config;
    private volatile ScriptExecutionListener listener;
    private final Engine engine=new Engine();
    private final AtomicBoolean ended=new AtomicBoolean();
    private final ArrayDeque<Runnable> callbacks=new ArrayDeque<>();
    private final ArrayDeque<Map<String,Object>> logs=new ArrayDeque<>();
    private int logChars; private long nextLog;
    private boolean callbackScheduled;
    private volatile boolean stopRequested;
    private volatile int id=NO_ID;

    RemoteScriptExecution(String token,File request,ScriptSource original,ExecutionConfig originalConfig,ScriptExecutionListener listener) {
        this.token=token; this.request=request; this.listener=listener;
        String name=original.getName(),filename=original.toString(),kind=original.getEngineName();
        source=new StringScriptSource(name,"") {
            @Override public String toString() { return filename; }
        };
        ((JavaScriptSource)source).setPreferredEngine(kind);
        config=ExecutionConfig.getDefault(); config.setWorkingDirectory(originalConfig.getWorkingDirectory());
        engine.setTag(ScriptEngine.TAG_SOURCE,source);
    }

    void markStopRequested() { stopRequested=true; engine.executionLifecycle().requestStop(); }

    void started(Bundle data) {
        if (ended.get()) return;
        id=data.getInt("executionId",NO_ID); engine.setId(data.getInt("engineId",NO_ID));
        String actual=data.getString("engine");
        if (actual!=null) ((JavaScriptSource)source).setPreferredEngine(actual);
        engine.setTag("host.process",data.getString("process")); engine.setTag("host.pid",data.getInt("pid"));
        engine.executionLifecycle().running();
        enqueue(() -> {
            ScriptExecutionListener target=listener;
            if (target!=null) try { target.onStart(this); }
            catch (Throwable error) { engine.forceStop(); }
        });
    }

    void finished(Bundle data) { complete(data,null); }
    void failed(Bundle data,String message) {
        Throwable error=stopRequested || "已停止".equals(message) || (data!=null && data.getBoolean("interrupted"))
                ? new ScriptInterruptedException() : new IllegalStateException(message==null ? "Script process exited" : message);
        if (data!=null) {
            engine.setTag("host.errorType",data.getString("errorType"));
            engine.setTag("host.errorStack",data.getString("errorStack"));
        }
        complete(data,error);
    }

    private void complete(Bundle data,Throwable error) {
        if (!ended.compareAndSet(false,true)) return;
        enqueue(() -> {
            try {
                engine.destroy();
                ScriptExecutionListener target=listener;
                if (target!=null) {
                    if (error==null && !stopRequested) target.onSuccess(this,data==null ? null : data.getString("result"));
                    else target.onException(this,error==null ? new ScriptInterruptedException() : error);
                }
            } finally { listener=null; ScriptHostRequest.discard(request); }
        });
    }

    synchronized void log(String content,int level,long time) {
        if (content==null) return;
        String text=content.length()>16384 ? content.substring(0,16384) : content;
        Map<String,Object> row=new HashMap<>(); row.put("id",++nextLog); row.put("level",level); row.put("content",text); row.put("time",time);
        logs.addLast(row); logChars+=text.length();
        while (logs.size()>1000 || logChars>128*1024) logChars-=String.valueOf(logs.removeFirst().get("content")).length();
    }
    synchronized List<Map<String,Object>> logs() { return new ArrayList<>(logs); }

    private void enqueue(Runnable operation) {
        synchronized (callbacks) {
            callbacks.add(operation); if (callbackScheduled) return; callbackScheduled=true;
        }
        CALLBACKS.execute(() -> {
            for (;;) {
                Runnable next;
                synchronized (callbacks) { next=callbacks.poll(); if (next==null) { callbackScheduled=false; return; } }
                try { next.run(); } catch (Throwable error) { android.util.Log.w("ScriptActor","Callback failed",error); }
            }
        });
    }

    @Override public ScriptEngine getEngine() { return engine; }
    @Override public ScriptSource getSource() { return source; }
    @Override public ScriptExecutionListener getListener() { return listener; }
    @Override public ExecutionConfig getConfig() { return config; }
    @Override public int getId() { return id; }

    private final class Engine extends ScriptEngine.AbstractScriptEngine<ScriptSource> {
        @Override public void init() { }
        @Override public void put(String name,Object value) { throw new UnsupportedOperationException("Use execution arguments"); }
        @Override public Object execute(ScriptSource source) { throw new UnsupportedOperationException("Remote execution is submitted once"); }
        @Override public void forceStop() { if (!isDestroyed()) PocScriptHost.stop(token); }
        @Override public String cwd() { return config.getWorkingDirectory(); }
        @Override public Object getTag(String key) { return "host.logs".equals(key) ? logs() : super.getTag(key); }
    }
}
