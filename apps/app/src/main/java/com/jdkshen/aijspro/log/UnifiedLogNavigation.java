package com.jdkshen.aijspro.log;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Every full-screen log entry opens the same advanced-style Flutter page. */
public final class UnifiedLogNavigation {
    private UnifiedLogNavigation() { }

    public static boolean open(Context context) {
        try {
            Class<?> main = Class.forName("com.jdkshen.aijspro.flutterpoc.GlassFlutterActivity",
                    false, context.getClassLoader());
            if (Boolean.TRUE.equals(main.getMethod("revealLogsIfVisible").invoke(null))) return true;
            String activity = "com.jdkshen.aijspro.flutterpoc.UnifiedLogFlutterActivity";
            int flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP;
            if (!(context instanceof android.app.Activity)) flags |= Intent.FLAG_ACTIVITY_NEW_TASK;
            context.startActivity(new Intent().setClassName(context, activity).addFlags(
                    flags));
            return true;
        } catch (Throwable error) {
            Log.w("UnifiedLogNavigation", "open logs failed", error);
            return false;
        }
    }
}
