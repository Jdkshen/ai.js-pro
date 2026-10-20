package com.stardust.autojs.core.image.capture;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import androidx.annotation.RequiresApi;

import com.stardust.app.OnActivityResultDelegate;

/**
 * Created by Stardust on 2017/5/17.
 */

public interface ScreenCaptureRequester {

    void cancel();

    interface Callback {

        void onRequestResult(int result, Intent data);

    }

    void request();

    void setOnActivityResultCallback(Callback callback);

    abstract class AbstractScreenCaptureRequester implements ScreenCaptureRequester {

        protected Callback mCallback;
        protected Intent mResult;

        @Override
        public void setOnActivityResultCallback(Callback callback) {
            mCallback = callback;
        }

        public void onResult(int resultCode, Intent data) {
            mResult = data;
            if (mCallback != null)
                mCallback.onRequestResult(resultCode, data);
        }

        @Override
        public void cancel() {
            if (mResult != null)
                return;
            if (mCallback != null)
                mCallback.onRequestResult(Activity.RESULT_CANCELED, null);
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    class ActivityScreenCaptureRequester extends AbstractScreenCaptureRequester implements ScreenCaptureRequester, OnActivityResultDelegate {

        private static final int REQUEST_CODE_MEDIA_PROJECTION = 17777;
        private OnActivityResultDelegate.Mediator mMediator;
        private Activity mActivity;

        public ActivityScreenCaptureRequester(Mediator mediator, Activity activity) {
            mMediator = mediator;
            mActivity = activity;
            mMediator.addDelegate(REQUEST_CODE_MEDIA_PROJECTION, this);
        }


        @Override
        public void request() {
            MediaProjectionManager manager = (MediaProjectionManager)
                    mActivity.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            // 自动化必须拿整屏：单应用模式切换 App 后会黑帧（真机实测），
            // API 34+ 明确请求 defaultDisplay 范围，避免用户/自动授权误选单应用。
            Intent intent = ScreenCaptureIntents.create(manager);
            // 自动化场景：系统「共享屏幕」授权条自动点掉（只命中已知文案才点）。
            ScreenCaptureAutoConsent.arm();
            mActivity.startActivityForResult(intent, REQUEST_CODE_MEDIA_PROJECTION);
        }

        @Override
        public void onActivityResult(int requestCode, int resultCode, Intent data) {
            ScreenCaptureAutoConsent.disarm();
            mResult = data;
            mMediator.removeDelegate(this);
            onResult(resultCode, data);
        }
    }

}
