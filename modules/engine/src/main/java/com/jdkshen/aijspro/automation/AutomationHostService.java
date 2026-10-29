package com.jdkshen.aijspro.automation;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import com.stardust.autojs.host.IAutomationHost;

/** Binder transport; the bounded backend belongs to the accessibility process. */
public class AutomationHostService extends Service {
    @Override public IBinder onBind(Intent intent) { return localBackend(this).asBinder(); }
    @Override public int onStartCommand(Intent intent,int flags,int startId) { return START_NOT_STICKY; }

    public static IAutomationHost localBackend(Context context) {
        return AutomationHostBackend.get(context).endpoint();
    }
}
