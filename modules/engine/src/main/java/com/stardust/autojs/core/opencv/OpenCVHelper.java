package com.stardust.autojs.core.opencv;

import android.content.Context;

import androidx.annotation.Nullable;

import android.os.Build;
import android.os.Looper;
import android.util.Log;

import org.opencv.android.OpenCVLoader;
import org.opencv.core.Core;

import java.util.ArrayList;
import java.util.List;


/**
 * Created by Stardust on 2018/4/2.
 */

public class OpenCVHelper {

    public interface InitializeCallback {
        void onInitFinish();
    }

    private static final String LOG_TAG = "OpenCVHelper";
    private static volatile boolean sInitialized = false;
    private static boolean sInitializing = false;
    private static final List<InitializeCallback> sPendingCallbacks = new ArrayList<>();

    public static MatOfPoint newMatOfPoint(Mat mat) {
        return new MatOfPoint(mat);
    }

    public static void release(@Nullable MatOfPoint mat) {
        if (mat == null)
            return;
        mat.release();
    }

    public static void release(@Nullable Mat mat) {
        if (mat == null)
            return;
        mat.release();
    }

    public synchronized static boolean isInitialized() {
        return sInitialized;
    }

    public static String getVersionInfo() {
        String abi = Build.SUPPORTED_ABIS.length == 0 ? "unknown" : Build.SUPPORTED_ABIS[0];
        return "OpenCV initialized: version=" + Core.VERSION + ", ABI=" + abi;
    }

    public static void initIfNeeded(Context context, InitializeCallback callback) {
        synchronized (OpenCVHelper.class) {
            if (sInitialized) {
                callback.onInitFinish();
                return;
            }
            sPendingCallbacks.add(callback);
            if (sInitializing) {
                return;
            }
            sInitializing = true;
        }

        Runnable initializer = () -> {
            boolean initialized = false;
            try {
                initialized = OpenCVLoader.initDebug();
                if (initialized) {
                    Log.i(LOG_TAG, getVersionInfo());
                } else {
                    Log.e(LOG_TAG, "OpenCV initialization returned false");
                }
            } catch (Throwable error) {
                Log.e(LOG_TAG, "OpenCV initialization failed", error);
            }

            List<InitializeCallback> callbacks;
            synchronized (OpenCVHelper.class) {
                sInitialized = initialized;
                sInitializing = false;
                callbacks = new ArrayList<>(sPendingCallbacks);
                sPendingCallbacks.clear();
                OpenCVHelper.class.notifyAll();
            }
            for (InitializeCallback pendingCallback : callbacks) {
                pendingCallback.onInitFinish();
            }
        };

        if (Looper.getMainLooper() == Looper.myLooper()) {
            new Thread(initializer, "OpenCV-initializer").start();
        } else {
            initializer.run();
        }
    }
}
