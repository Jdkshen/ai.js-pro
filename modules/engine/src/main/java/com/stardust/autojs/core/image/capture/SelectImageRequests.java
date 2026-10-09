package com.stardust.autojs.core.image.capture;

import android.net.Uri;

import com.stardust.autojs.core.image.ImageWrapper;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * images.select() 的跨模块请求登记处：
 * 脚本侧（engine）登记回调 → 通过 ACTION_SELECT_IMAGE 拉起 app 侧透明活动
 * → 活动拿到结果后按 requestId 回调脚本侧。
 */
public final class SelectImageRequests {

    public interface Handler {
        void onPicked(ImageWrapper image, Uri uri);
    }

    public static final String ACTION_SELECT_IMAGE = "com.jdkshen.aijspro.action.SELECT_IMAGE";
    public static final String EXTRA_REQUEST_ID = "com.jdkshen.aijspro.extra.SELECT_IMAGE_REQUEST_ID";

    private static final AtomicInteger NEXT_ID = new AtomicInteger(1);
    private static final ConcurrentHashMap<Integer, Handler> HANDLERS = new ConcurrentHashMap<>();

    private SelectImageRequests() {
    }

    public static int register(Handler handler) {
        int id = NEXT_ID.incrementAndGet();
        HANDLERS.put(id, handler);
        return id;
    }

    /** 由 app 侧活动调用：取出并移除请求；拿到 null 时活动应立即结束。 */
    public static Handler resolve(int id) {
        return HANDLERS.remove(id);
    }

    /** 活动启动失败等异常情况下清理登记。 */
    public static void discard(int id) {
        HANDLERS.remove(id);
    }
}
