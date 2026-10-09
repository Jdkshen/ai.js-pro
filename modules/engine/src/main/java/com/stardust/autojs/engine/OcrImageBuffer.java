package com.stardust.autojs.engine;

import android.graphics.Bitmap;
import com.stardust.autojs.core.image.ImageWrapper;

/** Owns both the reusable OCR bitmap and its tracked Image wrapper. */
public final class OcrImageBuffer implements AutoCloseable {
    private ImageWrapper image;

    public ImageWrapper acquire(int width, int height) {
        if (image != null && !image.isRecycled()
                && image.getWidth() == width && image.getHeight() == height) {
            return image;
        }
        close();
        image = ImageWrapper.ofBitmap(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888));
        return image;
    }

    @Override
    public void close() {
        if (image != null) {
            image.recycle();
            image = null;
        }
    }
}
