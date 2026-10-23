package com.stardust.autojs.engine.node24;

import android.app.Activity;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestManager;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.Request;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.CustomViewTarget;
import com.bumptech.glide.request.target.Target;
import com.bumptech.glide.request.transition.Transition;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Node 原生 UI 的 Glide 图片加载器。
 *
 * <p>与旧的 HttpURLConnection + BitmapFactory 路径相比，这里把下载/解码/缓存全部交给
 * Glide 4.8：默认内存缓存、磁盘缓存（{@link DiskCacheStrategy#AUTOMATIC}）、按
 * ImageView 尺寸下采样、BitmapPool 复用。这里不会手动 {@code Bitmap.recycle()}；
 * 资源释放由 Glide 的 Target/RequestManager 负责。
 *
 * <p>线程模型与 {@code NodeUiLocalSession.ImageLoader} 保持一致：调用方在 Java IO
 * 线程同步等待结果，Glide 的 with/into/clear 全部切到主线程。主线程调用会立即抛错，
 * 绝不在主线程上等待主线程。
 *
 * <p>每个 View 同时只有一个当前请求。新请求、显式 cancel、会话 close 都会让旧请求的
 * 回调失效；目标 Activity 被销毁、会话被关闭或 owner Activity 已变化时也不会
 * 写回旧图。结果约定：
 * <ul>
 *   <li>true：图片已在主线程写入 ImageView。</li>
 *   <li>false：被新请求替换、取消、关闭、owner 失效、等待超时或等待被中断。</li>
 *   <li>异常：URL 非法/协议不是 http(s)、加载失败、会话已关闭等。</li>
 * </ul>
 *
 * <p>loader 是会话级对象；{@link #close()} 后不可再用，下一次 Node UI 会话应创建新实例。
 */
public final class NodeUiImageLoader implements AutoCloseable {

    /** 当前 Node UI 会话的 Activity/存活谓词，由宿主接入。 */
    public interface Owner {
        /** 当前 Activity；Activity 重建/关闭期间可以为 null。 */
        Activity getActivity();

        /** Node UI 会话是否仍存活。 */
        boolean isActive();
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final String TAG = "NodeUiImageLoader";

    /** 网络超时（与旧实现一致），Glide 通过 RequestOptions.timeout 使用它。 */
    private static final int NETWORK_TIMEOUT_MS = 10_000;
    /** 等待主线程应用结果的上限；留出网络 + 主线程排队的余量。 */
    private static final long AWAIT_TIMEOUT_MS = 15_000L;

    private final Owner owner;
    private final NodeUiImageRequestState state = new NodeUiImageRequestState();
    private final ConcurrentHashMap<NodeUiImageRequestState.Ticket, Pending> pending =
            new ConcurrentHashMap<NodeUiImageRequestState.Ticket, Pending>();
    private final ConcurrentHashMap<NodeUiImageRequestState.Ticket, RequestHandle> handles =
            new ConcurrentHashMap<NodeUiImageRequestState.Ticket, RequestHandle>();

    /**
     * 每个 ImageView 最近一次成功应用的请求。value 用弱引用，避免 loader 反过来强引用
     * 已经离开界面的 View/Target；键也使用 WeakHashMap。
     */
    private final Map<ImageView, WeakReference<RequestHandle>> appliedHandles =
            Collections.synchronizedMap(new WeakHashMap<ImageView, WeakReference<RequestHandle>>());

    public NodeUiImageLoader(Owner owner) {
        if (owner == null) {
            throw new NullPointerException("owner");
        }
        this.owner = owner;
    }

    /**
     * 在 Java IO 线程加载 http(s) 图片并等待主线程写入 ImageView。
     *
     * @return true 表示已经写入；false 表示被替换/取消/关闭/owner 失效/超时/中断
     * @throws MalformedURLException    URL 无法解析
     * @throws IllegalArgumentException 协议不是 http/https
     * @throws IOException              Glide 加载或请求启动失败
     * @throws IllegalStateException    loader 已关闭、Node UI 已关闭、或从主线程调用
     */
    public boolean loadImageInto(ImageView view, String address) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException("loadImageInto 必须在非主线程调用，不能在主线程等待主线程");
        }
        if (view == null) {
            throw new NullPointerException("view");
        }
        if (address == null) {
            throw new NullPointerException("address");
        }
        if (state.isClosed()) {
            throw new IllegalStateException("图片加载器已关闭");
        }

        final Activity ownerActivity = owner.getActivity();
        if (!owner.isActive() || !isUsable(ownerActivity)) {
            throw new IllegalStateException("Node UI 已关闭");
        }

        // 先占位再校验 URL：与旧实现一致，一次新的 load 调用即使 URL 非法，也会让
        // 该 View 之前的请求失效，避免旧图在稍后悄悄写回。
        NodeUiImageRequestState.Begin begin = state.begin(view);
        final NodeUiImageRequestState.Ticket ticket = begin.ticket();
        final Pending result = new Pending();
        pending.put(ticket, result);
        if (begin.superseded() != null) {
            cancelInternal(begin.superseded());
        }

        final URL url;
        try {
            url = new URL(address);
        } catch (MalformedURLException error) {
            discard(ticket, result);
            throw error;
        }
        if (!"http".equals(url.getProtocol()) && !"https".equals(url.getProtocol())) {
            discard(ticket, result);
            throw new IllegalArgumentException("图片 URL 需要 http 或 https");
        }
        final String requestUrl = url.toString();

        boolean posted;
        try {
            posted = MAIN.post(new Runnable() {
                @Override
                public void run() {
                    startOnMain(ticket, result, ownerActivity, view, requestUrl);
                }
            });
        } catch (RuntimeException error) {
            discard(ticket, result);
            throw new IOException("无法投递图片请求到主线程：" + address, error);
        }
        if (!posted) {
            discard(ticket, result);
            throw new IOException("无法投递图片请求到主线程：" + address);
        }

        try {
            boolean finished;
            try {
                finished = result.await(AWAIT_TIMEOUT_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                cancelInternal(ticket);
                return false;
            }
            if (!finished) {
                // 超时也必须清掉 Glide 请求，不能让它稍后写回过期图片。
                cancelInternal(ticket);
                return false;
            }
            Throwable error = result.error();
            if (error != null) {
                if (error instanceof Exception) {
                    throw (Exception) error;
                }
                if (error instanceof Error) {
                    throw (Error) error;
                }
                throw new IOException("图片加载失败：" + address, error);
            }
            return result.applied();
        } finally {
            // 成功时 appliedHandles 中保留弱引用目标，但不再保留请求/结果表。
            pending.remove(ticket);
            handles.remove(ticket);
        }
    }

    /**
     * 取消某 View 当前仍在等待的请求。已经成功写入的图片不会被清掉。
     *
     * @return 是否确实取消了一个未完成请求
     */
    public boolean cancel(ImageView view) {
        if (view == null) {
            return false;
        }
        NodeUiImageRequestState.Ticket ticket = state.cancel(view);
        if (ticket == null) {
            return false;
        }
        cancelInternal(ticket);
        return true;
    }

    /**
     * 关闭 loader：让所有未完成请求的等待方立即得到 false，并取消对应 Glide 请求。
     * 重复调用安全；关闭后不能再次 loadImageInto。
     */
    @Override
    public void close() {
        List<NodeUiImageRequestState.Ticket> tickets = state.close();
        for (NodeUiImageRequestState.Ticket ticket : tickets) {
            Pending result = pending.remove(ticket);
            if (result != null) {
                result.finish(false);
            }
            RequestHandle handle = handles.remove(ticket);
            if (handle != null) {
                clearHandle(handle);
            }
        }
        // Completed targets still own displayed resources. Release them as well,
        // after any onResourceReady currently running on main has finished.
        MAIN.post(() -> {
            List<RequestHandle> applied = new java.util.ArrayList<>();
            synchronized (appliedHandles) {
                for (WeakReference<RequestHandle> reference : appliedHandles.values()) {
                    RequestHandle handle = reference.get();
                    if (handle != null) applied.add(handle);
                }
            }
            for (RequestHandle handle : applied) clearHandle(handle);
            appliedHandles.clear();
        });
    }

    public boolean isClosed() {
        return state.isClosed();
    }

    /** 仅供诊断：尚未完成的请求数。 */
    public int pendingRequestCount() {
        return state.pendingCount();
    }

    private void startOnMain(final NodeUiImageRequestState.Ticket ticket, final Pending result,
                             final Activity ownerActivity, final ImageView view,
                             final String address) {
        if (!state.isCurrent(ticket)) {
            result.finish(false);
            return;
        }
        if (!isOwnerCurrent(ownerActivity)) {
            cancelInternal(ticket);
            return;
        }

        // 真正的请求即将开始，才清理该 View 上一张已成功应用的图（主线程同步完成），
        // 避免 URL 校验失败时把已有图片无谓擦掉。清理后旧 target 释放旧 bitmap。
        clearAppliedImage(view);

        final RequestManager manager;
        try {
            // Glide.with(Activity) 会绑定 Activity 生命周期；必须在主线程调用。
            manager = Glide.with(ownerActivity);
        } catch (RuntimeException error) {
            failStart(ticket, result, address, error);
            return;
        }

        final AtomicReference<GlideException> failure = new AtomicReference<GlideException>();
        final ImageLoaderTarget target = new ImageLoaderTarget(
                ticket, result, ownerActivity, view, address, failure);
        final RequestHandle handle = new RequestHandle(manager, target);
        target.handle = handle;
        // 先登记 handle，超时/替换时才能从任意线程找到并取消这个 Glide 请求。
        handles.put(ticket, handle);
        if (!state.isCurrent(ticket)) {
            cancelInternal(ticket);
            return;
        }

        try {
            RequestOptions options = new RequestOptions()
                    // 默认内存缓存；磁盘缓存对远程 http(s) 使用 DATA，避免重复下载。
                    .skipMemoryCache(false)
                    .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                    // 保持 Glide 默认按 ImageView 尺寸下采样，禁止 override 到原图。
                    .downsample(DownsampleStrategy.DEFAULT)
                    .timeout(NETWORK_TIMEOUT_MS);
            manager.asDrawable()
                    .load(address)
                    .apply(options)
                    .listener(new RequestListener<Drawable>() {
                        @Override
                        public boolean onLoadFailed(GlideException error, Object model,
                                                    Target<Drawable> target, boolean isFirstResource) {
                            // 保留根因给 Target.onLoadFailed；返回 false 让 Target 继续走失败回调。
                            failure.set(error);
                            return false;
                        }

                        @Override
                        public boolean onResourceReady(Drawable resource, Object model,
                                                       Target<Drawable> target, DataSource dataSource,
                                                       boolean isFirstResource) {
                            return false;
                        }
                    })
                    .into(target);
            handle.request = target.getRequest();
        } catch (RuntimeException error) {
            handle.request = target.getRequest();
            failStart(ticket, result, address, error);
        }
    }

    private void failStart(NodeUiImageRequestState.Ticket ticket, Pending result,
                           String address, Throwable cause) {
        IOException error = new IOException("图片请求启动失败：" + address, cause);
        if (state.failIfCurrent(ticket)) {
            result.failure(error);
        } else {
            result.finish(false);
        }
        pending.remove(ticket);
        RequestHandle handle = handles.remove(ticket);
        if (handle != null) {
            clearHandle(handle);
        }
    }

    private void discard(NodeUiImageRequestState.Ticket ticket, Pending result) {
        state.cancel(ticket);
        Pending pendingResult = pending.remove(ticket);
        if (pendingResult != null) {
            pendingResult.finish(false);
        }
        RequestHandle handle = handles.remove(ticket);
        if (handle != null) {
            clearHandle(handle);
        }
    }

    private void cancelInternal(NodeUiImageRequestState.Ticket ticket) {
        state.cancel(ticket);
        Pending result = pending.remove(ticket);
        if (result != null) {
            result.finish(false);
        }
        RequestHandle handle = handles.remove(ticket);
        if (handle != null) {
            clearHandle(handle);
        }
    }

    /**
     * 新请求开始前清掉该 View 上一次已应用的 Glide Target，让旧 bitmap 能归还
     * BitmapPool。真正清空 View 的时机由 Target.onResourceCleared 根据“是否仍是
     * 最近一次成功应用”判断，避免旧 Target 的清理把新图擦掉。
     */
    private void clearAppliedImage(ImageView view) {
        WeakReference<RequestHandle> reference = appliedHandles.get(view);
        if (reference == null) {
            return;
        }
        RequestHandle handle = reference.get();
        if (handle == null) {
            appliedHandles.remove(view);
            return;
        }
        clearHandle(handle);
    }

    private void clearHandle(final RequestHandle handle) {
        if (handle == null) {
            return;
        }
        Runnable clear = new Runnable() {
            @Override
            public void run() {
                try {
                    // CustomViewTarget stores its request on the View. A queued
                    // clear for an old target must never clear a newer request
                    // that has since taken over that same View.
                    if (handle.request == null || handle.target.getRequest() != handle.request) return;
                    handle.manager.clear(handle.target);
                } catch (RuntimeException error) {
                    android.util.Log.w(TAG, "清理 Glide 图片请求失败", error);
                }
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            clear.run();
        } else {
            MAIN.post(clear);
        }
    }

    private boolean isOwnerCurrent(Activity expected) {
        return expected != null
                && owner.isActive()
                && owner.getActivity() == expected
                && isUsable(expected);
    }

    private static boolean isUsable(Activity activity) {
        return activity != null && !activity.isFinishing() && !activity.isDestroyed();
    }

    private static final class Pending {
        private final CountDownLatch done = new CountDownLatch(1);
        private final AtomicBoolean settled = new AtomicBoolean();
        private volatile boolean applied;
        private volatile Throwable error;

        boolean await(long timeoutMs) throws InterruptedException {
            return done.await(timeoutMs, TimeUnit.MILLISECONDS);
        }

        void finish(boolean value) {
            if (settled.compareAndSet(false, true)) {
                applied = value;
                done.countDown();
            }
        }

        void failure(Throwable failure) {
            if (settled.compareAndSet(false, true)) {
                error = failure;
                done.countDown();
            }
        }

        boolean applied() {
            return applied;
        }

        Throwable error() {
            return error;
        }
    }

    private static final class RequestHandle {
        final RequestManager manager;
        final Target<Drawable> target;
        Request request; // Set on main after into(), read only on main.

        RequestHandle(RequestManager manager, Target<Drawable> target) {
            this.manager = manager;
            this.target = target;
        }
    }

    private final class ImageLoaderTarget extends CustomViewTarget<ImageView, Drawable> {
        private final NodeUiImageRequestState.Ticket ticket;
        private final Pending result;
        private final Activity ownerActivity;
        private final String address;
        private final AtomicReference<GlideException> failure;
        private RequestHandle handle;
        private Drawable appliedDrawable;

        ImageLoaderTarget(NodeUiImageRequestState.Ticket ticket, Pending result,
                          Activity ownerActivity, ImageView view, String address,
                          AtomicReference<GlideException> failure) {
            super(view);
            this.ticket = ticket;
            this.result = result;
            this.ownerActivity = ownerActivity;
            this.address = address;
            this.failure = failure;
        }

        @Override
        public void onResourceReady(final Drawable resource, Transition<? super Drawable> transition) {
            if (resource == null) {
                onLoadFailed(null);
                return;
            }
            boolean applied = state.applyIfCurrent(ticket, new NodeUiImageRequestState.Action() {
                @Override
                public boolean run() {
                    if (!isOwnerCurrent(ownerActivity)) {
                        return false;
                    }
                    // 先记录再写入；onResourceCleared 只有在 View 仍显示这一张图时才清空。
                    appliedDrawable = resource;
                    view.setImageDrawable(resource);
                    return true;
                }
            });
            if (applied) {
                appliedHandles.put(view, new WeakReference<RequestHandle>(handle));
                result.finish(true);
                handles.remove(ticket);
            } else {
                result.finish(false);
                // 例如 owner 在加载完成瞬间已失效：图片不能写入，但仍要清掉 target，
                // 让 Glide 归还资源；post 到当前回调之后，避免在 onResourceReady 内重入 clear。
                final RequestHandle stale = handles.remove(ticket);
                if (stale != null) {
                    MAIN.post(new Runnable() {
                        @Override
                        public void run() {
                            clearHandle(stale);
                        }
                    });
                }
            }
        }

        @Override
        public void onLoadFailed(Drawable errorDrawable) {
            IOException error = new IOException("图片加载失败：" + address, failure.get());
            if (state.failIfCurrent(ticket)) {
                result.failure(error);
            } else {
                result.finish(false);
                // Glide forbids clearing the same request inside its callback.
                MAIN.post(() -> clearHandle(handle));
            }
            pending.remove(ticket);
            handles.remove(ticket);
        }

        @Override
        protected void onResourceCleared(Drawable placeholder) {
            WeakReference<RequestHandle> latestReference = appliedHandles.get(view);
            RequestHandle latest = latestReference == null ? null : latestReference.get();
            Drawable previous = appliedDrawable;
            // 无论是否还是最近一张图，都先松开本 target 对旧 drawable 的引用。
            appliedDrawable = null;
            if (latest == handle) {
                // 只有这张 target 仍是 View 上最近一次成功应用的图片时才动 View，
                // 否则可能把后来已经显示的新图擦掉。placeholder 通常为 null。
                appliedHandles.remove(view);
                if (view.getDrawable() == previous) {
                    view.setImageDrawable(placeholder);
                }
            }
            if (state.cancel(ticket)) {
                Pending cancelled = pending.remove(ticket);
                if (cancelled != null) {
                    cancelled.finish(false);
                }
            }
            handles.remove(ticket);
        }
    }
}
