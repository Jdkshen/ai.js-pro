package org.autojs.source.engine.android;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

import static org.junit.Assert.*;
import static org.autojs.source.engine.android.JvmTestAssertions.assertThrows;

/**
 * Pure-JVM checks for Node24Images protocol parsing/search rules. Bitmap decoding itself is an
 * Android/device concern and is exercised by the integration pass, not by this unit test.
 */
public class Node24ImagesTest {

    @Test public void colorComparisonUsesMaxRgbDistanceAndIgnoresAlpha() {
        int expected = 0xFF112233;
        assertTrue(Node24Images.colorMatches(expected, 0x00122433, 3));
        assertTrue(Node24Images.colorMatches(expected, 0x00122433, 2));
        assertFalse(Node24Images.colorMatches(expected, 0x00122433, 1));
        // alpha 完全不同也不影响 RGB 比较。
        assertTrue(Node24Images.colorMatches(expected, 0x00112233, 0));
    }

    @Test public void colorParserAcceptsSignedArgbAndHex() {
        assertEquals(-1, Node24Images.parseColor(-1, "color"));
        assertEquals(-1, Node24Images.parseColor(0xFFFFFFFFL, "color"));
        assertEquals(0x80FF0000, Node24Images.parseColor("#80FF0000", "color"));
        assertEquals(0xFF123456, Node24Images.parseColor("#123456", "color"));
        try {
            Node24Images.parseColor("#12345", "color");
            fail("expected invalid hex to fail");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("color"));
        }
        try {
            Node24Images.parseColor(null, "color");
            fail("expected null color to fail");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("color"));
        }
    }

    @Test public void regionSupportsRemainingImageAndRectangles() {
        Node24Images.Region remaining = Node24Images.parseRegion(
                new JSONArray().put(30).put(40), 200, 100);
        assertEquals(30, remaining.x);
        assertEquals(40, remaining.y);
        assertEquals(170, remaining.width);
        assertEquals(60, remaining.height);

        Node24Images.Region array = Node24Images.parseRegion(
                new JSONArray().put(5).put(6).put(7).put(8), 200, 100);
        assertEquals(5, array.x);
        assertEquals(6, array.y);
        assertEquals(7, array.width);
        assertEquals(8, array.height);

        JSONObject object = new JSONObject();
        try {
            object.put("x", 1).put("y", 2).put("width", 3).put("height", 4);
        } catch (Exception impossible) {
            fail(impossible.toString());
        }
        Node24Images.Region rectangle = Node24Images.parseRegion(object, 200, 100);
        assertEquals(1, rectangle.x);
        assertEquals(2, rectangle.y);
        assertEquals(3, rectangle.width);
        assertEquals(4, rectangle.height);

        assertEquals(200, Node24Images.parseRegion(null, 200, 100).width);
        assertEquals(100, Node24Images.parseRegion(null, 200, 100).height);
    }

    @Test public void regionRejectsOutOfBoundsAndDegenerateRects() {
        for (Object raw : new Object[]{
                new JSONArray().put(-1).put(0),
                new JSONArray().put(0).put(-1),
                new JSONArray().put(0).put(0).put(0).put(10),
                new JSONArray().put(0).put(0).put(10).put(0),
                new JSONArray().put(95).put(0).put(10).put(10),
                new JSONArray().put(0).put(0).put(1).put(2).put(3)}) {
            try {
                Node24Images.parseRegion(raw, 100, 100);
                fail("expected invalid region to fail: " + raw);
            } catch (IllegalArgumentException expected) {
                assertNotNull(expected.getMessage());
            }
        }
    }

    @Test public void searchReturnsFirstMatchInRowMajorOrder() {
        int target = 0xFF00FF00;
        int[] pixels = {
                0xFFFF0000, 0xFF00FF00, 0xFFFF0000,
                0xFF00FF00, 0xFF00FF00, 0xFF0000FF
        };
        int[] first = Node24Images.findFirstColor(pixels, 3, 2, target, 0);
        assertNotNull(first);
        assertEquals(1, first[0]);
        assertEquals(0, first[1]);
        assertNull(Node24Images.findFirstColor(pixels, 3, 2, 0xFF123456, 0));
        assertNull(Node24Images.findFirstColor(new int[2], 3, 2, target, 0));
    }

    @Test public void thresholdAndOffsetColorsAreValidated() {
        JSONObject request = new JSONObject();
        try {
            request.put("threshold", 300);
        } catch (Exception impossible) {
            fail(impossible.toString());
        }
        try {
            Node24Images.parseThreshold(request);
            fail("expected threshold > 255 to fail");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("threshold"));
        }

        JSONArray offsets = new JSONArray();
        try {
            offsets.put(new JSONObject().put("offsetX", 1).put("offsetY", -2)
                    .put("color", 0xFF010203));
            request.put("offsetColors", offsets);
        } catch (Exception impossible) {
            fail(impossible.toString());
        }
        Node24Images.OffsetColor[] parsed = Node24Images.parseOffsetColors(request);
        assertEquals(1, parsed.length);
        assertEquals(1, parsed[0].offsetX);
        assertEquals(-2, parsed[0].offsetY);
        assertEquals(0xFF010203, parsed[0].color);

        try {
            Node24Images.parseOffsetColors(new JSONObject());
            fail("expected empty offsetColors to fail");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("offsetColors"));
        }
    }

    @Test public void imagePathsMustBeAbsoluteAndNulFree() {
        File absolute = new File(System.getProperty("java.io.tmpdir"), "node24-image.png");
        assertEquals(absolute, Node24Images.safeFile(absolute.getAbsolutePath()));
        for (String invalid : new String[]{"relative.png", "", absolute.getAbsolutePath() + "\0bad"}) {
            try {
                Node24Images.safeFile(invalid);
                fail("expected invalid path to fail: " + invalid);
            } catch (IllegalArgumentException expected) {
                assertNotNull(expected.getMessage());
            }
        }
    }

    @Test public void configuredPixelBudgetRejectsOversizedImage() {
        Node24Images images = new Node24Images(2, 100, 200);
        images.checkDimensions(10, 10); // exactly at the budget
        try {
            images.checkDimensions(11, 10);
            fail("expected pixel budget to fail");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("上限"));
        }
    }

    @Test public void defaultImagePixelCapIsSixteenMillion() {
        Node24Images images = new Node24Images();
        images.checkDimensions(4000, 4000); // exactly 16M
        try {
            images.checkDimensions(4001, 4000);
            fail("expected default 16M cap to fail");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("上限"));
        }
    }

    @Test public void registerBudgetRejectsTotalPixelLimit() {
        Node24Images images = new Node24Images(64, 16_000_000L, 150L);
        images.checkRegisterBudget(20, 7); // 140 pixels, under total budget
        try {
            images.checkRegisterBudget(20, 8); // 160 pixels, over total budget
            fail("expected total pixel budget to fail");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("总像素"));
        }
    }

    @Test public void recycleUnknownHandleIsIdempotent() {
        Node24Images images = new Node24Images();
        JSONObject request = new JSONObject();
        try {
            request.put("action", "recycle").put("id", 999);
        } catch (Exception impossible) {
            fail(impossible.toString());
        }
        JSONObject response = images.handle(request);
        assertTrue(response.optBoolean("ok"));
        assertTrue(response.optBoolean("value"));
        images.close();
        images.close();
    }

    // ------------------------------------------------------------------
    // Phase A Raw 截图（.n24r）：纯 JVM 头/长度/预算校验（不依赖 Android Bitmap）
    // ------------------------------------------------------------------

    @Test public void rawHeaderAcceptsValidLayoutAndPixelLength() {
        byte[] header = rawHeader(1280, 2772);
        long expected = RawScreenshotFormat.HEADER_BYTES + (long) (1280 * 4) * 2772;
        RawScreenshotFormat.Header parsed = Node24Images.validateRawHeader(header, expected);
        assertEquals(1280, parsed.width);
        assertEquals(2772, parsed.height);
        assertEquals(1280 * 4, parsed.rowBytes);
        assertEquals(RawScreenshotFormat.FORMAT_ARGB_8888, parsed.format);
    }

    @Test public void rawHeaderRejectsBadMagicVersionRowBytesAndDimensions() {
        byte[] good = rawHeader(100, 50);
        long expected = RawScreenshotFormat.HEADER_BYTES + (long) (100 * 4) * 50;
        // magic(0) / version(4) / format(20)
        for (int offset : new int[]{0, 4, 20}) {
            byte[] broken = good.clone();
            broken[offset] = (byte) (broken[offset] + 1);
            assertThrows(IllegalArgumentException.class,
                    () -> Node24Images.validateRawHeader(broken, expected));
        }
        // rowBytes != width*4（offset 16..19，小端 400 -> 401）
        byte[] rowBytes = good.clone();
        rowBytes[16] = (byte) 0x01;
        assertThrows(IllegalArgumentException.class,
                () -> Node24Images.validateRawHeader(rowBytes, expected));
        // width = 0
        byte[] zeroWidth = good.clone();
        for (int i = 8; i <= 11; i++) {
            zeroWidth[i] = 0;
        }
        assertThrows(IllegalArgumentException.class,
                () -> Node24Images.validateRawHeader(zeroWidth, expected));
        // height = -1
        byte[] negativeHeight = good.clone();
        for (int i = 12; i <= 15; i++) {
            negativeHeight[i] = (byte) 0xFF;
        }
        assertThrows(IllegalArgumentException.class,
                () -> Node24Images.validateRawHeader(negativeHeight, expected));
        // width 溢出：width=0x40000000，rowBytes 同时写成 0（int 乘法溢出后自洽），由 Node24Images 显式挡掉。
        byte[] overflow = good.clone();
        overflow[8] = 0;
        overflow[9] = 0;
        overflow[10] = 0;
        overflow[11] = 0x40;
        for (int i = 16; i <= 19; i++) {
            overflow[i] = 0;
        }
        assertThrows(IllegalArgumentException.class,
                () -> Node24Images.validateRawHeader(overflow, expected));
    }

    @Test public void rawHeaderRejectsTruncatedOrOversizedFileLength() {
        byte[] good = rawHeader(100, 50);
        long expected = RawScreenshotFormat.HEADER_BYTES + (long) (100 * 4) * 50;
        Node24Images.validateRawHeader(good, expected); // 精确长度合法
        assertThrows(IllegalArgumentException.class,
                () -> Node24Images.validateRawHeader(good, expected - 1));
        assertThrows(IllegalArgumentException.class,
                () -> Node24Images.validateRawHeader(good, expected + 1));
        assertThrows(IllegalArgumentException.class,
                () -> Node24Images.validateRawHeader(good, RawScreenshotFormat.HEADER_BYTES));
        assertThrows(IllegalArgumentException.class,
                () -> Node24Images.validateRawHeader(new byte[RawScreenshotFormat.HEADER_BYTES], expected));
    }

    @Test public void rawDimensionsRespectImageAndTotalPixelBudgets() {
        Node24Images images = new Node24Images(2, 100, 200);
        images.checkDimensions(10, 10); // 100 像素，正好等于单图上限
        assertThrows(IllegalArgumentException.class, () -> images.checkDimensions(11, 10));
        images.checkRegisterBudget(10, 10); // 总预算 200，仍可行
        // 单图预算 100 已过 checkDimensions，这里直接测总像素预算（220 > 200）。
        assertThrows(IllegalStateException.class, () -> images.checkRegisterBudget(20, 11));
    }

    @Test public void rawScreenshotPathRequiresAbsoluteN24rPath() {
        String temp = System.getProperty("java.io.tmpdir");
        File ok = new File(temp, "node24-shot.n24r");
        assertEquals(ok, Node24Images.validateRawScreenshotPath(ok.getAbsolutePath()));
        for (String invalid : new String[]{"relative.n24r", "", temp + File.separator + "shot.png",
                ok.getAbsolutePath() + "\0bad"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> Node24Images.validateRawScreenshotPath(invalid));
        }
    }

    @Test public void rawDimensionsAboveDefaultBudgetAreRejectedBeforeAllocation() {
        byte[] header = rawHeader(5000, 4000); // 20M 像素 > 默认 16M 上限
        long expected = RawScreenshotFormat.HEADER_BYTES + (long) (5000 * 4) * 4000;
        RawScreenshotFormat.Header parsed = Node24Images.validateRawHeader(header, expected);
        Node24Images images = new Node24Images();
        assertThrows(IllegalArgumentException.class,
                () -> images.checkDimensions(parsed.width, parsed.height));
    }

    // ------------------------------------------------------------------
    // Phase B：readRawStream(InputStream) 纯 JVM 用例（ByteArrayInputStream，不创建 Bitmap）
    // ------------------------------------------------------------------

    @Test public void rawStreamHeaderAndPixelsAreReadFully() throws Exception {
        byte[] raw = rawBytes(100, 50);
        ByteArrayInputStream input = new ByteArrayInputStream(raw);
        RawScreenshotFormat.Header header = Node24Images.readRawStreamHeader(input);
        assertEquals(100, header.width);
        assertEquals(50, header.height);
        assertEquals(100 * 4, header.rowBytes);
        ByteBuffer pixels = ByteBuffer.allocate(100 * 4 * 50);
        Node24Images.readRawPixelBytes(input, pixels);
        assertEquals(0, pixels.remaining());
        assertEquals(-1, input.read()); // 恰好读到流末尾，没有多余数据
    }

    @Test public void rawStreamRejectsShortHeaderAndEmptyStream() {
        assertThrows(IOException.class,
                () -> Node24Images.readRawStreamHeader(new ByteArrayInputStream(new byte[0])));
        assertThrows(IOException.class,
                () -> Node24Images.readRawStreamHeader(new ByteArrayInputStream(new byte[31])));
        assertThrows(IllegalArgumentException.class,
                () -> Node24Images.readRawStreamHeader(null));
    }

    @Test public void rawStreamRejectsBadMagic() {
        byte[] badMagic = rawBytes(100, 50);
        badMagic[0] = (byte) (badMagic[0] + 1);
        assertThrows(IllegalArgumentException.class,
                () -> Node24Images.readRawStreamHeader(new ByteArrayInputStream(badMagic)));
    }

    @Test public void rawStreamRejectsTruncatedPixelData() throws Exception {
        byte[] header = rawHeader(100, 50);
        byte[] truncated = new byte[RawScreenshotFormat.HEADER_BYTES + 100];
        System.arraycopy(header, 0, truncated, 0, header.length);
        ByteArrayInputStream input = new ByteArrayInputStream(truncated);
        Node24Images.readRawStreamHeader(input); // 头合法
        ByteBuffer pixels = ByteBuffer.allocate(100 * 4 * 50);
        assertThrows(IOException.class, () -> Node24Images.readRawPixelBytes(input, pixels));
    }

    // ------------------------------------------------------------------
    // Phase E：Raw 像素 direct buffer 单槽复用（纯 JVM）
    // ------------------------------------------------------------------

    @Test public void rawPixelBufferSlotReusesBufferAndResetsSlice() {
        Node24Images.RawPixelBufferSlot slot = new Node24Images.RawPixelBufferSlot();
        ByteBuffer first = slot.acquire(1024);
        assertTrue(first.isDirect());
        assertEquals(1024, first.capacity());
        assertEquals(0, first.position());
        assertEquals(1024, first.limit());
        first.put((byte) 0x5A); // 模拟已读入部分像素

        ByteBuffer smaller = slot.acquire(512);
        assertSame(first, smaller); // 同尺寸/更小尺寸复用同一块 direct buffer
        assertEquals(0, smaller.position());
        assertEquals(512, smaller.limit());
        assertEquals(1024, slot.cachedCapacity());

        ByteBuffer again = slot.acquire(1024);
        assertSame(first, again);
        assertEquals(0, again.position());
        assertEquals(1024, again.limit());
    }

    @Test public void rawPixelBufferSlotReallocatesOnlyForLargerFrame() {
        Node24Images.RawPixelBufferSlot slot = new Node24Images.RawPixelBufferSlot();
        ByteBuffer small = slot.acquire(1024);
        ByteBuffer large = slot.acquire(4096);
        assertNotSame(small, large);
        assertTrue(large.isDirect());
        assertEquals(4096, large.capacity());
        assertEquals(4096, slot.cachedCapacity());
        // 更大的缓存继续服务后续更小的帧
        assertSame(large, slot.acquire(2048));
        assertEquals(2048, slot.acquire(2048).limit());
    }

    @Test public void rawPixelBufferSlotSkipsOversizedFramesAndReleases() {
        Node24Images.RawPixelBufferSlot slot = new Node24Images.RawPixelBufferSlot(1024);
        assertTrue(slot.caches(1024));
        assertFalse(slot.caches(1025));
        assertFalse(slot.caches(0));
        assertFalse(slot.caches(-1));

        ByteBuffer oversized = slot.acquire(2048); // 超出注入上限：不缓存
        assertTrue(oversized.isDirect());
        assertEquals(2048, oversized.capacity());
        assertEquals(0, slot.cachedCapacity());

        slot.acquire(512); // 预算内 → 缓存
        assertEquals(512, slot.cachedCapacity());
        slot.acquire(2048); // 再遇超大帧：旧缓存也放掉，避免「缓存 + 超大临时 buffer」叠加
        assertEquals(0, slot.cachedCapacity());

        slot.release();
        assertEquals(0, slot.cachedCapacity());
        assertThrows(IllegalArgumentException.class, () -> slot.acquire(0));
    }

    @Test public void rawPixelBufferSlotDefaultLimitCoversFullScreenAnd4k() {
        Node24Images.RawPixelBufferSlot slot = new Node24Images.RawPixelBufferSlot();
        assertTrue(slot.caches(1280 * 4 * 2772)); // 本机全屏 13.53MiB
        assertTrue(slot.caches(1080 * 4 * 2160)); // 小米 8 8.90MiB
        assertTrue(slot.caches(3840 * 4 * 2160)); // 4K ≈ 31.6MiB
        assertTrue(slot.caches(Node24Images.RawPixelBufferSlot.MAX_CACHED_BYTES));
        assertFalse(slot.caches(Node24Images.RawPixelBufferSlot.MAX_CACHED_BYTES + 1));
    }

    @Test public void rawPixelAllocationReusesSlotAcrossFramesAndReleasesOnClose() throws Exception {
        Node24Images images = new Node24Images(2, 1000, 2000);
        RawScreenshotFormat.Header header = Node24Images.parseRawHeader(rawHeader(100, 50));
        long pixelBytes = RawScreenshotFormat.pixelBytes(header);

        ByteBuffer first = images.allocateRawPixels(header, pixelBytes, "单测");
        ByteBuffer second = images.allocateRawPixels(header, pixelBytes, "单测");
        assertSame(first, second); // readRawFile/readRawStream 共用同一块 direct buffer
        assertEquals(0, second.position());
        assertEquals(pixelBytes, second.limit());
        assertEquals(pixelBytes, images.rawPixelBufferCapacity());

        images.close();
        assertEquals(0, images.rawPixelBufferCapacity()); // close() 必须释放缓存
    }

    private static byte[] rawHeader(int width, int height) {
        ByteBuffer header = RawScreenshotFormat.newHeader(width, height);
        byte[] bytes = new byte[RawScreenshotFormat.HEADER_BYTES];
        header.get(bytes);
        return bytes;
    }

    /** 构造「32B 头 + 全零 ARGB 像素」的完整 Raw 字节流。 */
    private static byte[] rawBytes(int width, int height) {
        ByteBuffer header = RawScreenshotFormat.newHeader(width, height);
        byte[] bytes = new byte[RawScreenshotFormat.HEADER_BYTES + width * 4 * height];
        header.get(bytes, 0, RawScreenshotFormat.HEADER_BYTES);
        return bytes;
    }
}
