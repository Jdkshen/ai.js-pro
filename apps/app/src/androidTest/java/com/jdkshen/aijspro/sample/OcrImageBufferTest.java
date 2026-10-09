package com.jdkshen.aijspro.sample;

import android.graphics.Bitmap;
import com.stardust.autojs.engine.OcrImageBuffer;
import com.stardust.autojs.core.image.ImageWrapper;
import org.junit.Test;
import static org.junit.Assert.*;

public class OcrImageBufferTest {
    @Test public void reuseResizeAndCloseReleaseTheOwnedImage() {
        OcrImageBuffer buffer = new OcrImageBuffer();
        ImageWrapper first = buffer.acquire(80, 80);
        Bitmap bitmap = first.getBitmap();
        for (int i=0;i<400;i++) assertSame(first,buffer.acquire(80,80));
        assertFalse(bitmap.isRecycled());
        ImageWrapper next=buffer.acquire(120,90);
        assertNotSame(first,next);
        assertTrue(first.isRecycled());
        assertTrue(bitmap.isRecycled());
        buffer.close();
        buffer.close();
        assertTrue(next.isRecycled());
        ImageWrapper reopened=buffer.acquire(80,80);
        assertFalse(reopened.isRecycled());
        buffer.close();
    }
}
