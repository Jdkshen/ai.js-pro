package com.jdkshen.aijspro.automation;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import java.util.concurrent.atomic.AtomicBoolean;

/** UI reads a short-lived cache; worker callers can obtain an authoritative same-UID status. */
public final class AutomationConnectionState {
    private static volatile Bundle cached;
    private static volatile long fetchedAt;
    private static final AtomicBoolean refreshing=new AtomicBoolean();
    private AutomationConnectionState() { }

    public static boolean isReady(Context context) {
        if (":script".equals(com.stardust.autojs.execution.ScriptProcessIdentity.suffix(context))) {
            return com.stardust.view.accessibility.AccessibilityService.Companion.getInstance()!=null;
        }
        Bundle state=cached;
        if (state==null || SystemClock.uptimeMillis()-fetchedAt>250L) {
            if (Looper.myLooper()!=Looper.getMainLooper()) state=query(context);
            else refresh(context);
        }
        return state!=null && state.getBoolean("ready");
    }

    public static void refresh(Context context) {
        if (!refreshing.compareAndSet(false,true)) return;
        Context app=context.getApplicationContext();
        Thread task=new Thread(() -> { try { query(app); } finally { refreshing.set(false); } },"automation-status");
        task.setDaemon(true); task.start();
    }

    public static boolean awaitReady(Context context,long timeoutMillis) {
        if (Looper.myLooper()==Looper.getMainLooper()) return isReady(context);
        long started=SystemClock.uptimeMillis();
        do {
            if (isReady(context)) return true;
            try { Thread.sleep(50L); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); return false; }
        } while (timeoutMillis<0 || SystemClock.uptimeMillis()-started<timeoutMillis);
        return false;
    }

    private static Bundle query(Context context) {
        Bundle result;
        try {
            result=context.getContentResolver().call(Uri.parse("content://"+context.getPackageName()+".automation.state"),
                    "status",null,null);
        } catch (RuntimeException error) { result=new Bundle(); result.putString("error",error.toString()); }
        cached=result; fetchedAt=SystemClock.uptimeMillis(); return result;
    }
}
