package com.jdkshen.aijspro.engine;

import android.os.Looper;

import com.jdkshen.aijspro.Pref;
import com.jdkshen.aijspro.external.foreground.ForegroundService;
import com.stardust.app.GlobalAppContext;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Node `settings` 模块的主进程读写桥。
 *
 * <p>只映射高级版原模块导出的 5 个布尔开关，统一读写 {@link Pref}，不引入独立存储。
 * 必须在主线程执行：前台服务启停与 {@link Pref} 的监听器初始化都依赖主线程。
 */
public final class NodeSettingsBridge {

    public static final String DOMAIN = "settings";

    private static final String ACTION_GET = "get";
    private static final String ACTION_SET = "set";

    private static final String STABLE_MODE = "stableMode";
    private static final String ENABLE_ACCESSIBILITY_SERVICE_BY_ROOT = "enableAccessibilityServiceByRoot";
    private static final String STOP_ALL_ON_VOLUME_UP = "stopAllOnVolumeUp";
    private static final String NO_CONSOLE_ON_LAUNCH = "noConsoleOnLaunch";
    private static final String FOREGROUND_SERVICE = "foregroundService";

    private NodeSettingsBridge() {
    }

    /**
     * 执行一条已解析的 settings 请求。
     *
     * @return {@code {ok:true,value:boolean}}；校验或读写失败时抛出异常，由调用方转成
     *         {@code {ok:false,error:...}}，这里不吞异常。
     */
    public static JSONObject execute(JSONObject request) throws JSONException {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("settings 命令必须在主线程执行");
        }
        if (request == null) throw new IllegalArgumentException("settings 请求不能为空");
        if (!DOMAIN.equals(request.optString("domain", ""))) {
            throw new IllegalArgumentException("settings 请求的 domain 必须是 settings");
        }

        String action = request.optString("action", "");
        String name = request.optString("name", "");
        requireKnownName(name);

        if (ACTION_GET.equals(action)) {
            return new JSONObject().put("ok", true).put("value", get(name));
        }
        if (ACTION_SET.equals(action)) {
            Object raw = request.opt("value");
            if (!(raw instanceof Boolean)) {
                throw new IllegalArgumentException("settings.set 的 value 必须是 boolean");
            }
            boolean value = (Boolean) raw;
            set(name, value);
            return new JSONObject().put("ok", true).put("value", get(name));
        }
        throw new IllegalArgumentException("settings 不支持 action: " + action + "（仅支持 get/set）");
    }

    private static void requireKnownName(String name) {
        switch (name) {
            case STABLE_MODE:
            case ENABLE_ACCESSIBILITY_SERVICE_BY_ROOT:
            case STOP_ALL_ON_VOLUME_UP:
            case NO_CONSOLE_ON_LAUNCH:
            case FOREGROUND_SERVICE:
                return;
            default:
                throw new IllegalArgumentException("未知的设置项: " + name);
        }
    }

    private static boolean get(String name) {
        switch (name) {
            case STABLE_MODE:
                return Pref.isStableModeEnabled();
            case ENABLE_ACCESSIBILITY_SERVICE_BY_ROOT:
                return Pref.shouldEnableAccessibilityServiceByRoot();
            case STOP_ALL_ON_VOLUME_UP:
                return Pref.isRunningVolumeControlEnabled();
            case NO_CONSOLE_ON_LAUNCH:
                return Pref.isConsoleHiddenOnStart();
            case FOREGROUND_SERVICE:
                return Pref.isForegroundServiceEnabled();
            default:
                throw new IllegalArgumentException("未知的设置项: " + name);
        }
    }

    private static void set(String name, boolean value) {
        switch (name) {
            case STABLE_MODE:
                Pref.setStableModeEnabled(value);
                return;
            case ENABLE_ACCESSIBILITY_SERVICE_BY_ROOT:
                Pref.setEnableAccessibilityServiceByRoot(value);
                return;
            case STOP_ALL_ON_VOLUME_UP:
                Pref.setRunningVolumeControlEnabled(value);
                return;
            case NO_CONSOLE_ON_LAUNCH:
                Pref.setConsoleHiddenOnStart(value);
                return;
            case FOREGROUND_SERVICE:
                // 与 MiuixServiceActivity 的开关一致：先启停服务，再落 Pref。
                if (value) {
                    ForegroundService.start(GlobalAppContext.get());
                } else {
                    ForegroundService.stop(GlobalAppContext.get());
                }
                Pref.setForegroundServiceEnabled(value);
                return;
            default:
                throw new IllegalArgumentException("未知的设置项: " + name);
        }
    }
}
