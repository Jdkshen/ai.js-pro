package com.stardust.autojs.core.image.capture;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.stardust.app.OnActivityResultDelegate;
import com.stardust.util.IntentExtras;

/**
 * Created by Stardust on 2017/5/22.
 */

@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
public class ScreenCaptureRequestActivity extends Activity {

    private OnActivityResultDelegate.Mediator mOnActivityResultDelegateMediator = new OnActivityResultDelegate.Mediator();
    private ScreenCaptureRequester mScreenCaptureRequester;
    private ScreenCaptureRequester.Callback mCallback;
    private static final String EXTRA_RESULT = "aijs.capture.result";
    private static final String EXTRA_DATA = "aijs.capture.data";

    public static void request(Context context, ScreenCaptureRequester.Callback callback) {
        Intent intent = new Intent(context, ScreenCaptureRequestActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        // IntentExtras references are process-local; carry the reply endpoint over Binder.
        intent.putExtra(EXTRA_RESULT, new ResultReceiver(new Handler(Looper.getMainLooper())) {
            @Override protected void onReceiveResult(int resultCode, Bundle resultData) {
                Intent data = resultData == null ? null : resultData.getParcelable(EXTRA_DATA);
                callback.onRequestResult(resultCode, data);
            }
        });
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ResultReceiver receiver = getIntent().getParcelableExtra(EXTRA_RESULT);
        if (receiver == null) {
            finish();
            return;
        }
        mCallback = (resultCode, data) -> {
            Bundle response = new Bundle();
            if (data != null) response.putParcelable(EXTRA_DATA, data);
            receiver.send(resultCode, response);
        };
        mScreenCaptureRequester = new ScreenCaptureRequester.ActivityScreenCaptureRequester(mOnActivityResultDelegateMediator, this);
        mScreenCaptureRequester.setOnActivityResultCallback(mCallback);
        if (savedInstanceState == null) mScreenCaptureRequester.request();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mCallback = null;
        if (mScreenCaptureRequester == null)
            return;
        if (!isChangingConfigurations()) mScreenCaptureRequester.cancel();
        mScreenCaptureRequester = null;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        mOnActivityResultDelegateMediator.onActivityResult(requestCode, resultCode, data);
        finish();
    }

}
