package com.jdkshen.aijspro.autojs.key;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Accessibility owns key observation; host UI owns recorder and inspector shortcuts. */
public final class VolumeKeyRelayReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent) {
        GlobalKeyObserver.getSingleton().onRelayedVolumeDown(intent.getLongExtra("eventTime",0L));
    }
}
