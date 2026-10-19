package com.jdkshen.aijspro.pluginclient;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Pro 的 {@code DeveloperModeAuthorizer} 等价物：{@code debug.authorize} 的令牌校验。
 *
 * <p>Pro 的流程（反编译）：token 未信任时弹 {@code DebugAuthConfirmActivity}，界面显示
 * {@code md5(token)} 大写、每 2 个字符一段、用 {@code ':'} 连接（如 {@code A1:B2:C3:D4:E5:F6}），
 * 用户在电脑上比对一致后点确认；20 秒超时按拒绝处理；已确认的 token 记进
 * {@code TrayPreferences("debug").getStringSet("devices")}，下次直接放行。
 *
 * <p>这里照抄同一套语义（同一个 prefs 名/键名，便于以后与 Pro 的数据互通），
 * 唯一的实现差异是用 {@link CountDownLatch} 代替 Kotlin {@code CompletableDeferred}。
 */
public final class ProRpcAuthorizer {

    private static final String LOG_TAG = "ProRpcAuthorizer";
    /** 与 Pro 的 {@code TrayPreferences(app, "debug")} 对齐。 */
    static final String PREFS_NAME = "debug";
    /** 与 Pro 的 {@code getStringSet("devices")} 对齐。 */
    static final String KEY_DEVICES = "devices";
    /** Pro 的 {@code withTimeoutOrNull(20000)}。 */
    public static final long TIMEOUT_MILLIS = 20000;

    /** 正在等用户确认的令牌（同一个 token 只弹一次框，多个等待者共享结果）。 */
    private static final Map<String, Pending> PENDING = new HashMap<>();

    private ProRpcAuthorizer() {
    }

    /**
     * 校验一个令牌：已信任 → 直接放行；否则弹确认框并最多等 {@code timeoutMillis}。
     *
     * <p>阻塞调用，必须在工作线程执行。
     */
    public static boolean authorize(Context context, String token, long timeoutMillis) {
        if (context == null || token == null || token.isEmpty()) {
            return false;
        }
        if (isRemembered(context, token)) {
            return true;
        }
        Pending pending;
        boolean shouldLaunch = false;
        synchronized (PENDING) {
            pending = PENDING.get(token);
            if (pending == null) {
                pending = new Pending();
                PENDING.put(token, pending);
                shouldLaunch = true;
            }
        }
        if (shouldLaunch && !launchConfirmActivity(context, token)) {
            complete(token, false);
        }
        boolean accepted = false;
        try {
            if (pending.latch.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
                accepted = pending.accepted;
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
        if (!accepted) {
            complete(token, false);
        }
        synchronized (PENDING) {
            PENDING.remove(token, pending);
        }
        if (accepted) {
            remember(context, token);
            Log.i(LOG_TAG, "debug token approved by the user");
        } else {
            Log.i(LOG_TAG, "debug token rejected (or timed out)");
        }
        return accepted;
    }

    /** 电脑端显示的确认码：{@code md5(token)} 大写、每 2 字符一段、{@code ':'} 分隔。 */
    public static String displayCode(String token) {
        String md5 = md5Hex(token).toUpperCase(Locale.US);
        StringBuilder builder = new StringBuilder(md5.length() + md5.length() / 2);
        for (int i = 0; i < md5.length(); i += 2) {
            if (builder.length() > 0) {
                builder.append(':');
            }
            builder.append(md5, i, Math.min(i + 2, md5.length()));
        }
        return builder.toString();
    }

    public static boolean isRemembered(Context context, String token) {
        return prefs(context).getStringSet(KEY_DEVICES, new HashSet<String>()).contains(token);
    }

    public static void remember(Context context, String token) {
        Set<String> devices = new HashSet<>(prefs(context)
                .getStringSet(KEY_DEVICES, new HashSet<String>()));
        if (devices.add(token)) {
            prefs(context).edit().putStringSet(KEY_DEVICES, devices).apply();
        }
    }

    /** 供确认框回调：把结果交给正在等待的 {@link #authorize}。 */
    static void complete(String token, boolean accepted) {
        Pending pending;
        synchronized (PENDING) {
            pending = PENDING.get(token);
        }
        if (pending == null) {
            return;
        }
        pending.accepted = accepted;
        pending.latch.countDown();
    }

    private static boolean launchConfirmActivity(Context context, String token) {
        Intent intent = new Intent(context, ProRpcAuthActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra(ProRpcAuthActivity.EXTRA_TOKEN, token);
        try {
            context.startActivity(intent);
            return true;
        } catch (RuntimeException error) {
            // Android 10+ 的后台启动限制等：没法弹框就当拒绝，别让电脑端一直等。
            Log.w(LOG_TAG, "cannot show the debug authorization dialog", error);
            return false;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static String md5Hex(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5")
                    .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                builder.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new AssertionError(error);
        }
    }

    private static final class Pending {

        final CountDownLatch latch = new CountDownLatch(1);
        volatile boolean accepted;
    }
}
