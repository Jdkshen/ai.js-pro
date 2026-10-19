// SPDX-License-Identifier: MIT
package org.autojs.source.engine.android;

import android.content.Context;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;

/** Called before the retained Application attaches or initializes old runtimes. */
public final class SourceEngineBootstrap {
    private static volatile Context application;
    private SourceEngineBootstrap() {}
    public static Context application() {
        Context value=application;if(value==null)throw new IllegalStateException("Application has not attached");
        Context app=value.getApplicationContext();return app==null?value:app;
    }
    public static boolean isNodeProcess(Context context) {
        application=context;
        try (FileInputStream input = new FileInputStream("/proc/self/cmdline")) {
            byte[] data = new byte[512];
            int count = input.read(data), end = 0;
            while (end < count && data[end] != 0) ++end;
            return new String(data, 0, end, StandardCharsets.UTF_8).equals(context.getPackageName()+":node24");
        } catch (Exception error) { throw new IllegalStateException("Cannot establish engine process identity", error); }
    }
}
