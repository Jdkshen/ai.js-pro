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
import android.os.Looper;
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

    public static final long DEFAULT_CAPTURE_TIMEOUT_MILLIS = 100L;
    public static final long MAX_CAPTURE_TIMEOUT_MILLIS = 5000L;

    public static final int ORIENTATION_AUTO = Configuration.ORIENTATION_UNDEFINED;
    public static final int ORIENTATION_LANDSCAPE = Configuration.ORIENTATION_LANDSCAPE ;
    public static final int ORIENTATION_PORTRAIT = Configuration.ORIENTATION_PORTRAIT ;


    private static final String LOG_TAG = "ScreenCapturer";
    private ImageReader mImageReader;
    private MediaProjection mMediaProjection;
    private MediaProjection.Callback mProjectionCallback;
    private VirtualDisplay mVirtualDisplay;
    private volatile Looper mImageAcquireLooper;
    private volatile Image mUnderUsingImage;
    private volatile AtomicReference<Image> mCachedImage = new AtomicReference<>();
    private final Object mImageAvailableLock = new Object();
    private volatile Exception mException;
    private final int mScreenDensity;
    private Handler mHandler;
    private volatile boolean mReleased;
    private Context mContext;
    private int mOrientation = -1;
    private int mDetectedOrientation;
    private OrientationEventListener mOrientationEventListener;

    public ScreenCapturer(Context context, Intent data, int orientation, int screenDensity, Handler handler) {
        mContext = context;
        mScreenDensity = screenDensity;
        mHandler = handler;
        MediaProjectionManager projectionManager = (MediaProjectionManager)
                context.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        mMediaProjection = projectionManager.getMediaProjection(
                Activity.RESULT_OK, (Intent) data.clone());
        mProjectionCallback = new MediaProjection.Callback() {
            @Override
            public void onStop() {
                onProjectionStopped();
            }
        };
        mMediaProjection.registerCallback(mProjectionCallback,
                mHandler != null ? mHandler : new Handler(Looper.getMainLooper()));
        setOrientation(orientation);
        observeOrientation();
    }

    private void observeOrientation() {
        mOrientationEventListener = new OrientationEventListener(mContext) {
            @Override
            public void onOrientationChanged(int o) {
                int orientation = mContext.getResources().getConfiguration().orientation;
                if (mOrientation == ORIENTATION_AUTO && mDetectedOrientation != orientation) {
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

    public void setOrientation(int orientation) {
        if (mOrientation == orientation)
            return;
        mOrientation = orientation;
        mDetectedOrientation = mContext.getResources().getConfiguration().orientation;
        refreshVirtualDisplay(mOrientation == ORIENTATION_AUTO ? mDetectedOrientation : mOrientation);
    }


    private synchronized void refreshVirtualDisplay(int orientation) {
        if (mReleased || mMediaProjection == null) {
            throw new IllegalStateException("Screen capture session has stopped");
        }
        if (mImageAcquireLooper != null) {
            mImageAcquireLooper.quit();
            mImageAcquireLooper = null;
        }
        int screenHeight = ScreenMetrics.getOrientationAwareScreenHeight(orientation);
        int screenWidth = ScreenMetrics.getOrientationAwareScreenWidth(orientation);
        ImageReader oldReader = mImageReader;
        if (oldReader != null) {
            oldReader.setOnImageAvailableListener(null, null);
        }
        Image oldCachedImage = mCachedImage.getAndSet(null);
        if (oldCachedImage != null) {
            oldCachedImage.close();
        }

        ImageReader newReader = ImageReader.newInstance(
                screenWidth, screenHeight, PixelFormat.RGBA_8888, 3);
        if (mVirtualDisplay == null) {
            mVirtualDisplay = mMediaProjection.createVirtualDisplay(LOG_TAG,
                    screenWidth, screenHeight, mScreenDensity,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    newReader.getSurface(), null, null);
            if (mVirtualDisplay == null) {
                newReader.close();
                throw new IllegalStateException("Unable to create screen capture display");
            }
        } else {
            // A MediaProjection consent token represents exactly one capture session. Reuse the
            // existing VirtualDisplay when orientation changes instead of stopping the projection
            // and passing the same consent Intent to getMediaProjection() a second time.
            mVirtualDisplay.resize(screenWidth, screenHeight, mScreenDensity);
            mVirtualDisplay.setSurface(newReader.getSurface());
        }
        mImageReader = newReader;
        if (oldReader != null) {
            oldReader.close();
        }
        startAcquireImageLoop();
    }

    private void startAcquireImageLoop() {
        if (mHandler != null) {
            setImageListener(mHandler);
            return;
        }
        new Thread(() -> {
            Log.d(LOG_TAG, "AcquireImageLoop: start");
            Looper.prepare();
            mImageAcquireLooper = Looper.myLooper();
            setImageListener(new Handler());
            Looper.loop();
            Log.d(LOG_TAG, "AcquireImageLoop: stop");
        }).start();
    }

    private void setImageListener(Handler handler) {
        mImageReader.setOnImageAvailableListener(reader -> {
            try {
                Image oldCacheImage = mCachedImage.getAndSet(null);
                if (oldCacheImage != null) {
                    oldCacheImage.close();
                }
                mCachedImage.set(reader.acquireLatestImage());
                synchronized (mImageAvailableLock) {
                    mImageAvailableLock.notifyAll();
                }
            } catch (Exception e) {
                mException = e;
                synchronized (mImageAvailableLock) {
                    mImageAvailableLock.notifyAll();
                }
            }

        }, handler);
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
        long boundedTimeout = Math.max(0L, Math.min(MAX_CAPTURE_TIMEOUT_MILLIS, timeoutMillis));
        long deadlineNanos = SystemClock.elapsedRealtimeNanos() + boundedTimeout * 1_000_000L;
        while (!thread.isInterrupted()) {
            Exception e = mException;
            if (e != null) {
                mException = null;
                throw new ScriptException(e);
            }
            Image cachedImage = mCachedImage.getAndSet(null);
            if (cachedImage != null) {
                if (mUnderUsingImage != null) {
                    mUnderUsingImage.close();
                }
                mUnderUsingImage = cachedImage;
                return cachedImage;
            }

            Image latestImage = mUnderUsingImage;
            if (!fresh && latestImage != null) {
                return latestImage;
            }
            if (mReleased) {
                throw new ScriptException(new IllegalStateException("Screen capture session has stopped"));
            }

            long remainingNanos = deadlineNanos - SystemClock.elapsedRealtimeNanos();
            if (remainingNanos <= 0L) {
                if (latestImage != null) {
                    return latestImage;
                }
                throw new ScriptException(new TimeoutException(
                        "Timed out waiting for the first screen capture frame after "
                                + boundedTimeout + " ms"));
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

    public int getScreenDensity() {
        return mScreenDensity;
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    public synchronized void release() {
        if (mReleased) {
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
        releaseDisplayResources();
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
        releaseDisplayResources();
    }

    private void releaseDisplayResources() {
        if (mImageAcquireLooper != null) {
            mImageAcquireLooper.quit();
            mImageAcquireLooper = null;
        }
        if (mVirtualDisplay != null) {
            mVirtualDisplay.release();
            mVirtualDisplay = null;
        }
        if (mImageReader != null) {
            mImageReader.setOnImageAvailableListener(null, null);
            mImageReader.close();
            mImageReader = null;
        }
        if (mUnderUsingImage != null) {
            mUnderUsingImage.close();
            mUnderUsingImage = null;
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
