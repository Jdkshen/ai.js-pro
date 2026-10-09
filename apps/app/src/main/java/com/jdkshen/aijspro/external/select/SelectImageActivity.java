package com.jdkshen.aijspro.external.select;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;

import com.jdkshen.aijspro.R;
import com.stardust.autojs.core.image.ImageWrapper;
import com.stardust.autojs.core.image.capture.SelectImageRequests;

import java.io.InputStream;

/**
 * images.select() 的宿主活动（透明、无界面）：
 * 拉起系统图片选择器（ACTION_GET_CONTENT），把结果回传给脚本侧登记的 SelectImageRequests 回调。
 */
public class SelectImageActivity extends Activity {

    private static final int REQUEST_PICK_IMAGE = 19621;

    private SelectImageRequests.Handler mHandler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int requestId = getIntent() == null
                ? 0
                : getIntent().getIntExtra(SelectImageRequests.EXTRA_REQUEST_ID, 0);
        mHandler = SelectImageRequests.resolve(requestId);
        if (mHandler == null) {
            finish();
            return;
        }
        Intent pick = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*");
        startActivityForResult(Intent.createChooser(pick, getString(R.string.text_select_image)),
                REQUEST_PICK_IMAGE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        SelectImageRequests.Handler handler = mHandler;
        if (handler == null) {
            finish();
            return;
        }
        mHandler = null;
        ImageWrapper image = null;
        Uri uri = (requestCode == REQUEST_PICK_IMAGE && resultCode == RESULT_OK && data != null)
                ? data.getData()
                : null;
        if (uri != null) {
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                Bitmap bitmap = BitmapFactory.decodeStream(input);
                if (bitmap != null) {
                    image = ImageWrapper.ofBitmap(bitmap);
                }
            } catch (Throwable ignored) {
            }
        }
        handler.onPicked(image, uri);
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        SelectImageRequests.Handler handler = mHandler;
        if (handler != null) {
            mHandler = null;
            // 活动在未收到结果的情况下被关闭（如直接返回键/FINISH）：按取消处理，避免脚本永远等待。
            handler.onPicked(null, null);
        }
    }
}
