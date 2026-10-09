package com.stardust.autojs.core.image.capture;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import android.util.Log;
import android.view.OrientationEventListener;

import com.stardust.autojs.runtime.exception.ScriptException;
import com.stardust.autojs.runtime.exception.ScriptInterruptedException;
import com.stardust.lang.ThreadCompat;
import com.stardust.util.ScreenMetrics;

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeoutException;

/**
 * Created by Stardust on 2017/5/17.
 */
@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
public class ScreenCapturer {

    public static final long DEFAULT_CAPTURE_TIMEOUT_MILLIS = ScreenCaptureState.DEFAULT_TIMEOUT_MILLIS;
    public static final long MAX_CAPTURE_TIMEOUT_MILLIS = ScreenCaptureState.MAX_TIMEOUT_MILLIS;

    public static final int ORIENTATION_AUTO = Configuration.ORIENTATION_UNDEFINED;
    public static final int ORIENTATION_LANDSCAPE = Configuration.ORIENTATION_LANDSCAPE ;
    public static final int ORIENTATION_PORTRAIT = Configuration.ORIENTATION_PORTRAIT ;

    /** The requested capture configuration, matching Auto.js Pro's Options. */
    public static final class Options {
        public final int width;
        public final int height;
        public final int orientation;
        public final int density;
        public final boolean async;

        public Options(int width, int height, int orientation, int density, boolean async) {
            this.width = width;
            this.height = height;
            this.orientation = orientation;
            this.density = density;
            this.async = async;
        }
    }


    private static final String LOG_TAG = "ScreenCapturer";
    private ImageReader mImageReader;
    private ImageReader mUnderUsingImageReader;
    private ImageReader mRetiredImageReader;
    private MediaProjection mMediaProjection;
    private MediaProjection.Callback mProjectionCallback;
    private VirtualDisplay mVirtualDisplay;
    private HandlerThread mImageAcquireThread;
    private volatile Image mUnderUsingImage;
    private final AtomicReference<Image> mCachedImage = new AtomicReference<>();
    private final Object mImageAvailableLock = new Object();
    private volatile Exception mException;
    private final int mScreenDensity;
    private Handler mHandler;
    private volatile boolean mReleased;
    private final ScreenCaptureState mCaptureState = new ScreenCaptureState();
    private long mUnderUsingImageGeneration = -1L;
    private Context mContext;
    private int mOrientation = -1;
    private int mDetectedOrientation;
    private OrientationEventListener mOrientationEventListener;
    /** 请求的捕获尺寸（Pro 的 `requestScreenCapture(width, height)`），<=0 表示用屏幕尺寸。 */
    private final int mRequestedWidth;
    private final int mRequestedHeight;
    /** `requestScreenCapture(..., {async:true})`：帧逐张交给 createListener，而不是进缓存。 */
    private final boolean mAsyncStream;
    private volatile ScreenCaptureListener mCaptureListener;

    /** `images.requestScreenCapture({async:true})` 的帧回调；image 的所有权交给回调（用后必须 close()）。 */
    public interface ScreenCaptureListener {
        void onCaptureAvailable(Image image);
    }

    public ScreenCapturer(Context context, Intent data, int orientation, int screenDensity, Handler handler) {
        this(context, data, orientation, screenDensity, handler, -1, -1, false);
    }

    public ScreenCapturer(Context context, Intent data, int orientation, int screenDensity, Handler handler,
                          int requestedWidth, int requestedHeight, boolean asyncStream) {
        mContext = context;
        mScreenDensity = screenDensity;
        mHandler = handler;
        mRequestedWidth = requestedWidth;
        mRequestedHeight = requestedHeight;
        mAsyncStream = asyncStream;
        MediaProjectionManager projectionManager = (MediaProjectionManager)
                context.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        mMediaProjection = projectionManager.getMediaProjection(
                Activity.RESULT_OK, (Intent) data.clone());
        if (mMediaProjection == null) {
            throw new SecurityException("Unable to start screen capture projection");
        }
        if (mHandler == null) {
            mImageAcquireThread = new HandlerThread("ScreenCaptureImages");
            mImageAcquireThread.start();
            mHandler = new Handler(mImageAcquireThread.getLooper());
        }
        mProjectionCallback = new MediaProjection.Callback() {
            @Override
            public void onStop() {
                onProjectionStopped();
            }

            @Override
            public void onCapturedContentResize(int width, int height) {
                synchronized (ScreenCapturer.this) {
                    if (mReleased || width <= 0 || height <= 0) {
                        return;
                    }
                    // 对齐 Pro：申请时指定了 width/height 就固定用请求尺寸，
                    // 不让系统的「内容尺寸」回调把捕获面换回屏幕尺寸（K40 实测会立刻覆盖）。
                    if (mRequestedWidth > 0 && mRequestedHeight > 0) {
                        return;
                    }
                    try {
                        refreshCaptureSurface(width, height, true);
                    } catch (Exception error) {
                        mException = error;
                        notifyImageWaiters();
                    }
                }
            }

            @Override
            public void onCapturedContentVisibilityChanged(boolean isVisible) {
                synchronized (ScreenCapturer.this) {
                    if (mReleased || !mCaptureState.setVisible(isVisible)) {
                        return;
                    }
                    discardCachedImage();
                    Log.i(LOG_TAG, "Captured content visible: " + isVisible);
                    notifyImageWaiters();
                }
            }
        };
        synchronized (this) {
            try {
                mMediaProjection.registerCallback(mProjectionCallback, mHandler);
                setOrientation(orientation);
                observeOrientation();
            } catch (RuntimeException error) {
                release();
                throw error;
            }
        }
    }

    private void observeOrientation() {
        mOrientationEventListener = new OrientationEventListener(mContext) {
            @Override
            public void onOrientationChanged(int o) {
                int orientation = mContext.getResources().getConfiguration().orientation;
                if (!mCaptureState.hasCapturedContentSize()
                        && (mOrientation == ORIENTATION_AUTO || mOrientation < 0)
                        && mDetectedOrientation != orientation) {
                    mDetectedOrientation = orientation;
                    try {
                        refreshVirtualDisplay(orientation);
                    }catch (Exception e){
                        e.printStackTrace();
                        mException = e;
                    }
                }
            }

        };
        if (mOrientationEventListener.canDetectOrientation()) {
            mOrientationEventListener.enable();
        }
    }

    public synchronized void setOrientation(int orientation) {
        if (mReleased) {
            throw new IllegalStateException("Screen capture session has stopped; request permission again");
        }
        boolean same = mOrientation == orientation;
        mOrientation = orientation;
        mDetectedOrientation = mContext.getResources().getConfiguration().orientation;
        // 首次（还没捕获面）或方向变化时创建/刷新捕获面。
        // ⚠️ mOrientation 初值也是 -1，而 Pro 的「不指定方向」也用 -1：不能靠相等性短路，
        // 否则 2 参形态 requestScreenCapture(width, height) 永远不建捕获面（实测会捕获超时）。
        if (!mCaptureState.hasCapturedContentSize()
                && (!same || mVirtualDisplay == null || mImageReader == null)) {
            int effective = (mOrientation == ORIENTATION_AUTO || mOrientation < 0)
                    ? mDetectedOrientation : mOrientation;
            refreshVirtualDisplay(effective);
        }
    }


    private synchronized void refreshVirtualDisplay(int orientation) {
        // Before Android 14 (or before its first geometry callback), use screen metrics.
        // Once the system reports captured bounds, they are authoritative: rotating the
        // host must not overwrite a single-app window with full-device dimensions.
        if (mCaptureState.hasCapturedContentSize()) {
            return;
        }
        // 对齐 Pro：请求里带了 width/height 时按请求尺寸创建捕获面（固定尺寸，不随旋转变化）
        if (mRequestedWidth > 0 && mRequestedHeight > 0) {
            refreshCaptureSurface(mRequestedWidth, mRequestedHeight, false);
            return;
        }
        refreshCaptureSurface(ScreenMetrics.getOrientationAwareScreenWidth(orientation),
                ScreenMetrics.getOrientationAwareScreenHeight(orientation), false);
    }

    private synchronized void refreshCaptureSurface(int screenWidth, int screenHeight,
                                                     boolean capturedContent) {
        if (mReleased || mMediaProjection == null) {
            throw new IllegalStateException("Screen capture session has stopped");
        }
        if (mImageReader != null && mCaptureState.hasSize(screenWidth, screenHeight)) {
            mCaptureState.resize(screenWidth, screenHeight, capturedContent);
            return;
        }
        ImageReader oldReader = mImageReader;
        if (oldReader != null) {
            oldReader.setOnImageAvailableListener(null, null);
        }
        discardCachedImage();

        ImageReader newReader = ImageReader.newInstance(
                screenWidth, screenHeight, PixelFormat.RGBA_8888, 3);
        try {
            if (mVirtualDisplay == null) {
                mVirtualDisplay = mMediaProjection.createVirtualDisplay(LOG_TAG,
                        screenWidth, screenHeight, mScreenDensity,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        newReader.getSurface(), null, null);
                if (mVirtualDisplay == null) {
                    throw new IllegalStateException("Unable to create screen capture display");
                }
            } else {
                // Consent belongs to one session: resize the existing display, never create
                // a second VirtualDisplay with the same token on rotation/window changes.
                mVirtualDisplay.resize(screenWidth, screenHeight, mScreenDensity);
                mVirtualDisplay.setSurface(newReader.getSurface());
            }
        } catch (RuntimeException error) {
            newReader.close();
            if (oldReader != null) {
                setImageListener(mHandler);
            }
            throw error;
        }
        mImageReader = newReader;
        mCaptureState.resize(screenWidth, screenHeight, capturedContent);
        if (oldReader != null) {
            if (oldReader == mUnderUsingImageReader) {
                // ImageReader.close() invalidates its outstanding Images. Keep the old
                // reader alive until the caller replaces its frame, e.g. after a native copy.
                mRetiredImageReader = oldReader;
            } else {
                oldReader.close();
            }
        }
        setImageListener(mHandler);
        Log.i(LOG_TAG, "Capture surface: " + screenWidth + "x" + screenHeight
                + ", captured bounds: " + capturedContent);
        notifyImageWaiters();
    }

    private void setImageListener(Handler handler) {
        mImageReader.setOnImageAvailableListener(reader -> {
            synchronized (ScreenCapturer.this) {
                // An old reader may still have a queued callback after resize/release.
                if (mReleased || reader != mImageReader) {
                    return;
                }
                try {
                    Image image = reader.acquireLatestImage();
                    if (image == null) {
                        return;
                    }
                    if (!mCaptureState.isVisible()) {
                        image.close();
                        return;
                    }
                    ScreenCaptureListener listener = mCaptureListener;
                    if (listener != null) {
                        // async 流：帧的所有权交给回调（回调负责 close），不进缓存
                        listener.onCaptureAvailable(image);
                    } else {
                        discardCachedImage();
                        mCachedImage.set(image);
                    }
                } catch (Exception e) {
                    mException = e;
                }
                notifyImageWaiters();
            }

        }, handler);
    }

    /** async 流开关：true 表示本会话用 {@link ScreenCaptureListener} 逐帧deliver。 */
    public boolean isAsyncStream() {
        return mAsyncStream;
    }

    public void setCaptureListener(ScreenCaptureListener listener) {
        mCaptureListener = listener;
    }

    private void discardCachedImage() {
        Image image = mCachedImage.getAndSet(null);
        if (image != null) {
            image.close();
        }
    }

    private void notifyImageWaiters() {
        synchronized (mImageAvailableLock) {
            mImageAvailableLock.notifyAll();
        }
    }

    @Nullable
    public Image capture() {
        return capture(false, DEFAULT_CAPTURE_TIMEOUT_MILLIS);
    }

    /**
     * Returns the newest already available frame immediately by default. When {@code fresh} is
     * true, waits up to {@code timeoutMillis} for a frame newer than the one currently in use.
     * If no new frame arrives before the deadline, the last valid frame is returned. This keeps
     * static screens from blocking indefinitely while still allowing callers to request freshness.
     */
    @Nullable
    public Image capture(boolean fresh, long timeoutMillis) {
        Thread thread = ThreadCompat.currentThread();
        long boundedTimeout;
        synchronized (this) {
            boundedTimeout = ScreenCaptureState.timeoutForCapture(timeoutMillis,
                    mUnderUsingImage != null
                            && mCaptureState.canReuseFrame(mUnderUsingImageGeneration));
        }
        long deadlineNanos = SystemClock.elapsedRealtimeNanos() + boundedTimeout * 1_000_000L;
        while (!thread.isInterrupted()) {
            long remainingNanos = deadlineNanos - SystemClock.elapsedRealtimeNanos();
            synchronized (this) {
                if (mReleased) {
                    throw new ScriptException(new SecurityException(
                            "截图会话已被系统停止（可能是启动了其他录屏或锁屏）；请停止其他录屏后重新授权截图"));
                }
                Exception e = mException;
                if (e != null) {
                    mException = null;
                    throw new ScriptException(e);
                }
                if (!mCaptureState.isVisible()) {
                    throw new ScriptException(new IllegalStateException(
                            "单应用截图的目标当前不可见；请打开授权时选中的应用，或重新授权整个屏幕"));
                }
                Image cachedImage = mCachedImage.getAndSet(null);
                if (cachedImage != null) {
                    if (mUnderUsingImage != null) {
                        mUnderUsingImage.close();
                    }
                    if (mRetiredImageReader != null) {
                        mRetiredImageReader.close();
                        mRetiredImageReader = null;
                    }
                    mUnderUsingImage = cachedImage;
                    mUnderUsingImageReader = mImageReader;
                    mUnderUsingImageGeneration = mCaptureState.generation();
                    return cachedImage;
                }
                Image latestImage = mCaptureState.canReuseFrame(mUnderUsingImageGeneration)
                        ? mUnderUsingImage : null;
                if (latestImage != null && (!fresh || remainingNanos <= 0L)) {
                    return latestImage;
                }
                if (remainingNanos <= 0L) {
                    throw new ScriptException(new TimeoutException(
                            "等待有效截图帧超时（" + boundedTimeout
                                    + " ms）；请确认授权时选中的应用可见且未开启其他录屏"));
                }
            }
            synchronized (mImageAvailableLock) {
                if (mCachedImage.get() == null && mException == null && !mReleased) {
                    try {
                        long waitMillis = Math.max(1L,
                                Math.min(50L, (remainingNanos + 999_999L) / 1_000_000L));
                        mImageAvailableLock.wait(waitMillis);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new ScriptInterruptedException();
                    }
                }
            }
        }
        throw new ScriptInterruptedException();
    }

    public synchronized Options getOptions() {
        return new Options(mRequestedWidth, mRequestedHeight, mOrientation, mScreenDensity, mAsyncStream);
    }

    public int getScreenDensity() {
        return mScreenDensity;
    }

    public boolean isReleased() {
        return mReleased;
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    public synchronized void release() {
        if (mReleased) {
            // onStop keeps an outstanding raw frame alive until its script has finished
            // copying it. Explicit release/finalization must still dispose that frame.
            releaseDisplayResources(false);
            return;
        }
        mReleased = true;
        synchronized (mImageAvailableLock) {
            mImageAvailableLock.notifyAll();
        }
        if (mOrientationEventListener != null) {
            mOrientationEventListener.disable();
        }
        MediaProjection projection = mMediaProjection;
        mMediaProjection = null;
        if (projection != null && mProjectionCallback != null) {
            projection.unregisterCallback(mProjectionCallback);
        }
        releaseDisplayResources(false);
        if (projection != null) {
            projection.stop();
        }
        mProjectionCallback = null;
    }

    private synchronized void onProjectionStopped() {
        if (mReleased) {
            return;
        }
        mReleased = true;
        synchronized (mImageAvailableLock) {
            mImageAvailableLock.notifyAll();
        }
        mMediaProjection = null;
        mException = new SecurityException("Screen capture permission was revoked");
        if (mOrientationEventListener != null) {
            mOrientationEventListener.disable();
        }
        releaseDisplayResources(true);
    }

    private void releaseDisplayResources(boolean preserveUnderUsingFrame) {
        if (mImageAcquireThread != null) {
            mImageAcquireThread.quitSafely();
            mImageAcquireThread = null;
        }
        if (mVirtualDisplay != null) {
            mVirtualDisplay.release();
            mVirtualDisplay = null;
        }
        if (mImageReader != null) {
            mImageReader.setOnImageAvailableListener(null, null);
            if (preserveUnderUsingFrame && mImageReader == mUnderUsingImageReader) {
                mRetiredImageReader = mImageReader;
            } else {
                mImageReader.close();
            }
            mImageReader = null;
        }
        if (!preserveUnderUsingFrame) {
            if (mUnderUsingImage != null) {
                mUnderUsingImage.close();
                mUnderUsingImage = null;
            }
            mUnderUsingImageReader = null;
            if (mRetiredImageReader != null) {
                mRetiredImageReader.close();
                mRetiredImageReader = null;
            }
        }
        Image cachedImage = mCachedImage.getAndSet(null);
        if (cachedImage != null) {
            cachedImage.close();
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            release();
        } finally {
            super.finalize();
        }
    }

}
