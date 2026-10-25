package com.jdkshen.aijspro.external;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Explicit, non-exported main-process endpoint. Child processes do not own host task tables. */
public final class StopAllScriptsReceiver extends BroadcastReceiver {
    static final String ACTION = "com.jdkshen.aijspro.STOP_ALL_SCRIPT_PROCESSES";
    @Override public void onReceive(Context context, Intent intent) {
        if (intent != null && ACTION.equals(intent.getAction())) HostScripts.stopAllScripts(context);
    }
}
