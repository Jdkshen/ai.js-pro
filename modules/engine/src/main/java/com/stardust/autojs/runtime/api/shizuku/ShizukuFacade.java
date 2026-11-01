package com.stardust.autojs.runtime.api.shizuku;

import androidx.annotation.Keep;
import com.stardust.autojs.runtime.api.ShizukuClient;
import com.stardust.autojs.runtime.api.ShizukuShell;
import org.json.JSONObject;

/** Same JSON/argument contract for Rhino, QuickJS and Node's asynchronous Java bridge. */
@Keep
public final class ShizukuFacade {
    private ShizukuFacade() { }
    public static boolean isAvailable() { return ShizukuShell.isAvailable(); }
    public static boolean hasPermission() { return ShizukuShell.hasPermission(); }
    public static boolean requestPermission(int timeoutMillis) {
        if (timeoutMillis < 1000 || timeoutMillis > 120000) throw new IllegalArgumentException("授权timeout必须为1000..120000毫秒");
        return ShizukuShell.requestPermission(timeoutMillis);
    }
    public static String stateJson() { return ShizukuClient.state().toString(); }
    public static String executeJson(String command, String optionsJson) throws Exception {
        ShizukuCommands.validateCommand(command);
        JSONObject options = new JSONObject(optionsJson == null ? "{}" : optionsJson);
        int timeout = integer(options, "timeout", 10000, 1, 3600000);
        int max = integer(options, "maxOutput", 1048576, 0, 16777216);
        ShizukuShell.Result result = ShizukuShell.execute(command, timeout, max);
        return new JSONObject().put("code", result.code).put("result", result.output).put("error", result.error).toString();
    }
    static int integer(JSONObject options, String name, int fallback, int min, int max) {
        if (!options.has(name)) return fallback;
        Object raw = options.opt(name);
        if (!(raw instanceof Number)) throw new IllegalArgumentException(name + "必须为整数");
        double value = ((Number) raw).doubleValue();
        if (Double.isNaN(value) || Double.isInfinite(value) || value != Math.rint(value) || value < min || value > max)
            throw new IllegalArgumentException(name + "必须为" + min + ".." + max + "整数");
        return (int) value;
    }
}
