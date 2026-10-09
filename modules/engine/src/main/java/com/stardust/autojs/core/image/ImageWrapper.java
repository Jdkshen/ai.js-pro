package com.stardust.autojs.core.image;

import android.graphics.Bitmap;
import android.media.Image;
import android.os.Build;

import com.stardust.autojs.core.opencv.Mat;
import com.stardust.autojs.core.opencv.OpenCVHelper;
import com.stardust.pio.UncheckedIOException;

import org.opencv.android.Utils;
import org.opencv.imgcodecs.Imgcodecs;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;

import androidx.annotation.RequiresApi;

/**
 * Created by Stardust on 2017/11/25.
 */
public class ImageWrapper {

    private Mat mMat;
    private int mWidth;
    private int mHeight;
    private Bitmap mBitmap;

    {
        // PDF §2.9：$debug.setMemoryLeakDetectionEnabled(true) 后，没有手动 recycle 的图片会被报出来
        com.stardust.autojs.runtime.api.MemoryLeakDetector.watch(this, "Image");
    }

    protected ImageWrapper(Mat mat) {
        mMat = mat;
        mWidth = mat.cols();
        mHeight = mat.rows();
    }

    protected ImageWrapper(Bitmap bitmap) {
        mBitmap = bitmap;
        mWidth = bitmap.getWidth();
        mHeight = bitmap.getHeight();
    }

    protected ImageWrapper(Bitmap bitmap, Mat mat) {
        mBitmap = bitmap;
        mMat = mat;
        mWidth = bitmap.getWidth();
        mHeight = bitmap.getHeight();
    }

    public ImageWrapper(int width, int height) {
        this(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888));
    }


    @RequiresApi(api = Build.VERSION_CODES.KITKAT)
    public static ImageWrapper ofImage(Image image) {
        if (image == null) {
            return null;
        }
        return new ImageWrapper(toBitmap(image));
    }

    public static ImageWrapper ofMat(Mat mat) {
        if (mat == null) {
            return null;
        }
        return new ImageWrapper(mat);
    }


    public static ImageWrapper ofBitmap(Bitmap bitmap) {
        if (bitmap == null) {
            return null;
        }
        return new ImageWrapper(bitmap);
    }

    @RequiresApi(api = Build.VERSION_CODES.KITKAT)
    public static Bitmap toBitmap(Image image) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        buffer.position(0);
        int pixelStride = plane.getPixelStride();
        int rowPadding = plane.getRowStride() - pixelStride * image.getWidth();
        Bitmap bitmap = Bitmap.createBitmap(image.getWidth() + rowPadding / pixelStride, image.getHeight(), Bitmap.Config.ARGB_8888);
        bitmap.copyPixelsFromBuffer(buffer);
        if (rowPadding == 0) {
            return bitmap;
        }
        return Bitmap.createBitmap(bitmap, 0, 0, image.getWidth(), image.getHeight());
    }

    public int getWidth() {
        ensureNotRecycled();
        return mWidth;
    }

    public int getHeight() {
        ensureNotRecycled();
        return mHeight;
    }

    public Mat getMat() {
        ensureNotRecycled();
        if (mMat == null && mBitmap != null) {
            mMat = new Mat();
            Utils.bitmapToMat(mBitmap, mMat);
        }
        return mMat;
    }

    public void saveTo(String path) {
        ensureNotRecycled();
        if (mBitmap != null) {
            try {
                mBitmap.compress(Bitmap.CompressFormat.PNG, 100, new FileOutputStream(path));
            } catch (FileNotFoundException e) {
                throw new UncheckedIOException(e);
            }
        } else {
            Imgcodecs.imwrite(path, mMat);
        }
    }

    public int pixel(int x, int y) {
        ensureNotRecycled();
        if (x < 0 || y < 0 || x >= mWidth || y >= mHeight) {
            throw new ArrayIndexOutOfBoundsException("Point (" + x + ", " + y
                    + ") out of bounds of " + mWidth + "x" + mHeight);
        }
        if (mBitmap != null) {
            return mBitmap.getPixel(x, y);
        }
        // OpenCV indexes pixels by row, column; the script API takes x, y.
        double[] channels = mMat.get(y, x);
        if (channels == null) {
            throw new IllegalStateException("No pixel at (" + x + ", " + y + ")");
        }
        switch (channels.length) {
            case 1:
                int gray = (int) channels[0];
                return 0xff000000 | (gray << 16) | (gray << 8) | gray;
            case 3:
                return 0xff000000 | ((int) channels[0] << 16)
                        | ((int) channels[1] << 8) | (int) channels[2];
            case 4:
                return ((int) channels[3] << 24) | ((int) channels[0] << 16)
                        | ((int) channels[1] << 8) | (int) channels[2];
            default:
                throw new IllegalStateException("Unsupported image channel count: " + channels.length);
        }
    }

    public Bitmap getBitmap() {
        ensureNotRecycled();
        if (mBitmap == null && mMat != null) {
            mBitmap = Bitmap.createBitmap(mMat.width(), mMat.height(), Bitmap.Config.ARGB_8888);
            Utils.matToBitmap(mMat, mBitmap);
        }
        return mBitmap;
    }

    public void recycle() {
        if (mBitmap != null) {
            mBitmap.recycle();
            mBitmap = null;
        }
        if (mMat != null) {
            OpenCVHelper.release(mMat);
            mMat = null;
        }
        com.stardust.autojs.runtime.api.MemoryLeakDetector.markRecycled(this);
    }

    public boolean isRecycled() {
        return mBitmap == null && mMat == null;
    }

    public void ensureNotRecycled() {
        if (isRecycled())
            throw new IllegalStateException("image has been recycled");
    }

    public ImageWrapper clone() {
        ensureNotRecycled();
        if (mBitmap == null) {
            return ImageWrapper.ofMat(mMat.clone());
        }
        if (mMat == null) {
            return ImageWrapper.ofBitmap(mBitmap.copy(mBitmap.getConfig(), true));
        }
        return new ImageWrapper(mBitmap.copy(mBitmap.getConfig(), true), mMat.clone());
    }
}
