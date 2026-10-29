package com.stardust.autojs.runtime.api.remote;

import android.content.Context;
import com.jdkshen.aijspro.flutterpoc.scriptproc.AutomationHostClient;
import com.stardust.autojs.execution.ScriptProcessIdentity;

/** Local automation in :script; other execution/host processes use the same backend over IPC. */
public final class ScriptAutomationBootstrap {
    private ScriptAutomationBootstrap() { }
    public static void install(Context context) {
        boolean local=":script".equals(ScriptProcessIdentity.suffix(context));
        RemoteAutomationBridge.setIsolationProcess(!local);
        if (local) return;
        AutomationHostClient client=AutomationHostClient.getInstance(context);
        RemoteAutomationBridge.install(client);
        Thread warmup=new Thread(client::connect,"automation-prewarm");
        warmup.setDaemon(true); warmup.start();
        com.jdkshen.aijspro.automation.AutomationConnectionState.refresh(context);
    }
}
