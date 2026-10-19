package com.jdkshen.aijspro.log;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Every full-screen log entry opens the same advanced-style Flutter page. */
public final class UnifiedLogNavigation {
    private UnifiedLogNavigation() { }

    public static boolean open(Context context) {
        try {
            String activity = "com.jdkshen.aijspro.flutterpoc.GlassFlutterActivity";
            Class.forName(activity, false, context.getClassLoader());
            boolean saved = context.getSharedPreferences("flutter-glass", Context.MODE_PRIVATE)
                    .edit().putString("pending_action", "{\"action\":\"log\"}").commit();
            if (!saved) return false;
            context.startActivity(new Intent().setClassName(context, activity).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            return true;
        } catch (Throwable error) {
            Log.w("UnifiedLogNavigation", "open logs failed", error);
            return false;
        }
    }
}
