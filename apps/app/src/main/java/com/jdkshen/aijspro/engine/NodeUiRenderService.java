package com.jdkshen.aijspro.engine;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

/** Legacy UI, dialogs and fallback overlays live together, outside the app's main process. */
public final class NodeUiRenderService extends NodeUiBridgeService {
    private static final String ACTION = "com.jdkshen.aijspro.NODE_UI_RENDER_CONTROL";
    private final BroadcastReceiver controls = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!ACTION.equals(intent.getAction())) return;
            if ("closeAll".equals(intent.getStringExtra("command"))) closeRenderingWindows();
            else if ("touchable".equals(intent.getStringExtra("command"))) {
                NodeUiBridgeService.setOverlaysTouchable(intent.getBooleanExtra("value", true));
            }
        }
    };
    @Override protected boolean rendersUi() { return true; }
    @Override public void onCreate() {
        super.onCreate();
        IntentFilter filter = new IntentFilter(ACTION);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(controls, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(controls, filter);
    }
    @Override public void onDestroy() {
        unregisterReceiver(controls);
        super.onDestroy();
    }
    public static void closeAll(Context context) { control(context, "closeAll", false); }
    public static void setOverlaysTouchable(Context context, boolean touchable) { control(context, "touchable", touchable); }
    private static void control(Context context, String command, boolean value) {
        // A registered, non-exported receiver does not start a new renderer just to clean it up.
        context.sendBroadcast(new Intent(ACTION).setPackage(context.getPackageName())
                .putExtra("command", command).putExtra("value", value));
    }
}
