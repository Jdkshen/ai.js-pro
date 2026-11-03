/*
 *  Copyright (C) 2010 Ryszard Wiśniewski <brut.alll@gmail.com>
 *  Copyright (C) 2010 Connor Tumbleson <connor.tumbleson@gmail.com>
 *
 *  Android compatibility replacement:
 *  Copyright (C) 2026 AI.js Pro contributors
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package brut.androlib.res.decoder;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import brut.androlib.exceptions.AndrolibException;
import brut.androlib.exceptions.NinePatchNotFoundException;
import brut.androlib.res.data.LayoutBounds;
import brut.androlib.res.data.NinePatchData;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Android implementation of Apktool's nine-patch stream decoder.
 *
 * <p>The upstream decoder uses {@code ImageIO}, {@code BufferedImage}, and AWT.
 * Those classes do not exist on Android. This implementation asks Android's
 * native PNG decoder for the pixels and for the serialized {@code npTc}
 * nine-patch chunk, then reconstructs the one-pixel transparent nine-patch
 * border expected by Apktool on disk.</p>
 *
 * <p>This class deliberately has the same FQCN as the upstream class so the
 * original class can be removed from the Apktool JAR and this class inserted
 * before D8 packaging.</p>
 */
public final class ResNinePatchStreamDecoder implements ResStreamDecoder {

    private static final int MAX_INPUT_BYTES = 64 * 1024 * 1024;
    private static final int MAX_DIMENSION = 8192;
    private static final long MAX_PIXELS = 16_000_000L;

    /** sizeof(Res_png_9patch): 4 count bytes plus seven int32 fields. */
    private static final int NINE_PATCH_HEADER_BYTES = 32;

    private static final int NPTC_MAGIC = 0x6E705463; // "npTc"
    private static final int NPLB_MAGIC = 0x6E704C62; // "npLb"

    private static final int BLACK_TICK = 0xFF000000;

    public ResNinePatchStreamDecoder() {
        // Public no-arg constructor, matching the Apktool class.
    }

    @Override
    public final void decode(InputStream in, OutputStream out) {
        if (out == null) {
            throw sneakyThrow(new AndrolibException("Nine-patch output stream is null."));
        }

        Bitmap src = null;
        Bitmap dst = null;
        try {
            byte[] data = readBounded(in);
            if (data.length == 0) {
                // Upstream returns without producing output for an empty resource.
                return;
            }

            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            validateDimensions(bounds.outWidth, bounds.outHeight);

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = false;
            options.inScaled = false;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            options.inPremultiplied = false;

            src = BitmapFactory.decodeByteArray(data, 0, data.length, options);
            if (src == null) {
                throw new AndrolibException("Could not decode nine-patch PNG with BitmapFactory.");
            }

            int width = src.getWidth();
            int height = src.getHeight();
            validateDimensions(width, height);

            byte[] chunk = src.getNinePatchChunk();
            if (chunk == null || chunk.length == 0) {
                // Matches upstream behavior: an ordinary PNG is not silently
                // rewritten as an uncut/fake nine-patch.
                throw new NinePatchNotFoundException();
            }

            NinePatchData np = parseNinePatchChunk(
                    chunk, ByteOrder.nativeOrder(), width, height);

            dst = Bitmap.createBitmap(width + 2, height + 2, Bitmap.Config.ARGB_8888);
            dst.eraseColor(0);
            copyInterior(src, dst, width, height);
            drawBorder(dst, np, width, height);

            if (!dst.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                throw new AndrolibException("Failed to encode reconstructed nine-patch PNG.");
            }
        } catch (AndrolibException ex) {
            // The R8-processed Apktool JAR stripped the checked exception from
            // ResStreamDecoder's bytecode. Throw the exact checked type at
            // runtime so existing Apktool catch sites still observe it.
            throw sneakyThrow(ex);
        } catch (IllegalArgumentException ex) {
            // BitmapFactory/Bitmap reject a number of malformed PNGs this way.
            throw sneakyThrow(new AndrolibException(
                    "Invalid nine-patch PNG: " + ex.getMessage(), ex));
        } finally {
            if (src != null) {
                src.recycle();
            }
            if (dst != null) {
                dst.recycle();
            }
        }
    }

    /**
     * Public helper kept for API parity with the Apktool JAR. Reads the raw
     * npTc chunk from an already-compiled PNG (PNG chunk byte order).
     */
    public static NinePatchData findNinePatchData(byte[] data) {
        try {
            byte[] chunk = findPngChunk(data, NPTC_MAGIC);
            return parseNinePatchChunk(chunk, ByteOrder.BIG_ENDIAN, 0, 0);
        } catch (AndrolibException ex) {
            throw sneakyThrow(ex);
        }
    }

    /**
     * Public helper kept for API parity with the Apktool JAR. Optical bounds
     * are exposed for callers but are not used by Android's Bitmap decoder.
     */
    public static LayoutBounds findLayoutBounds(byte[] data) {
        try {
            byte[] chunk = findPngChunk(data, NPLB_MAGIC);
            if (chunk.length < 16) {
                throw new AndrolibException("Invalid npLb chunk: expected 16 bytes, got "
                        + chunk.length + ".");
            }
            ByteBuffer buffer = ByteBuffer.wrap(chunk).order(ByteOrder.BIG_ENDIAN);
            // Matches upstream LayoutBounds.read(), which reverses these ints.
            int left = Integer.reverseBytes(buffer.getInt());
            int top = Integer.reverseBytes(buffer.getInt());
            int right = Integer.reverseBytes(buffer.getInt());
            int bottom = Integer.reverseBytes(buffer.getInt());
            return new LayoutBounds(left, top, right, bottom);
        } catch (AndrolibException ex) {
            throw sneakyThrow(ex);
        }
    }

    /**
     * Parse the 32-byte header and variable integer arrays from an npTc
     * payload. Android obtains this payload in native byte order. The static
     * helper above uses big-endian PNG chunk order.
     *
     * Layout (all multi-byte fields in the given order):
     *   0  u8  wasDeserialized
     *   1  u8  numXDivs
     *   2  u8  numYDivs
     *   3  u8  numColors
     *   4  i32 xDivsOffset       (ignored; arrays are contiguous)
     *   8  i32 yDivsOffset       (ignored)
     *   12 i32 paddingLeft
     *   16 i32 paddingRight
     *   20 i32 paddingTop
     *   24 i32 paddingBottom
     *   28 i32 colorsOffset     (ignored)
     *   32 xDivs[numXDivs], yDivs[numYDivs], colors[numColors]
     */
    private static NinePatchData parseNinePatchChunk(
            byte[] chunk, ByteOrder order, int width, int height) throws AndrolibException {
        if (chunk == null || chunk.length < NINE_PATCH_HEADER_BYTES) {
            throw invalidChunk("payload is shorter than the 32-byte header.");
        }

        int wasDeserialized = chunk[0] & 0xFF;
        int numXDivs = chunk[1] & 0xFF;
        int numYDivs = chunk[2] & 0xFF;
        int numColors = chunk[3] & 0xFF;

        if (wasDeserialized != 0 && wasDeserialized != 1) {
            throw invalidChunk("wasDeserialized is " + wasDeserialized + ".");
        }
        if ((numXDivs & 1) != 0) {
            throw invalidChunk("x-div count is odd: " + numXDivs + ".");
        }
        if ((numYDivs & 1) != 0) {
            throw invalidChunk("y-div count is odd: " + numYDivs + ".");
        }

        long required = NINE_PATCH_HEADER_BYTES
                + 4L * ((long) numXDivs + numYDivs + numColors);
        if (required > chunk.length) {
            throw invalidChunk("declared arrays need " + required
                    + " bytes but chunk has " + chunk.length + ".");
        }

        ByteBuffer buffer = ByteBuffer.wrap(chunk).order(order);
        int paddingLeft = buffer.getInt(12);
        int paddingRight = buffer.getInt(16);
        int paddingTop = buffer.getInt(20);
        int paddingBottom = buffer.getInt(24);

        if (paddingLeft < 0 || paddingRight < 0 || paddingTop < 0 || paddingBottom < 0) {
            throw invalidChunk("negative padding ("
                    + paddingLeft + ", " + paddingRight + ", "
                    + paddingTop + ", " + paddingBottom + ").");
        }
        if (width > 0) {
            long horizontal = (long) paddingLeft + paddingRight;
            if (horizontal > width) {
                throw invalidChunk("horizontal padding " + horizontal
                        + " exceeds content width " + width + ".");
            }
        }
        if (height > 0) {
            long vertical = (long) paddingTop + paddingBottom;
            if (vertical > height) {
                throw invalidChunk("vertical padding " + vertical
                        + " exceeds content height " + height + ".");
            }
        }

        int position = NINE_PATCH_HEADER_BYTES;
        int[] xDivs = new int[numXDivs];
        for (int i = 0; i < numXDivs; i++) {
            xDivs[i] = buffer.getInt(position);
            position += 4;
        }
        int[] yDivs = new int[numYDivs];
        for (int i = 0; i < numYDivs; i++) {
            yDivs[i] = buffer.getInt(position);
            position += 4;
        }
        // The colors array is optional optimization data and is intentionally
        // not copied to the reconstructed image; the literal RGBA pixels win.

        if (width > 0) {
            validateDivs(xDivs, width, "x");
        } else {
            validateDivsWithoutDimension(xDivs, "x");
        }
        if (height > 0) {
            validateDivs(yDivs, height, "y");
        } else {
            validateDivsWithoutDimension(yDivs, "y");
        }

        return new NinePatchData(
                xDivs, yDivs, paddingLeft, paddingRight, paddingTop, paddingBottom);
    }

    private static void validateDivs(int[] divs, int dimension, String axis)
            throws AndrolibException {
        validateDivsWithoutDimension(divs, axis);
        for (int i = 0; i < divs.length; i += 2) {
            int start = divs[i];
            int end = divs[i + 1];
            if (end > dimension) {
                throw invalidChunk(axis + "-div end " + end
                        + " exceeds content size " + dimension + ".");
            }
            if (i > 0 && start < divs[i - 1]) {
                throw invalidChunk(axis + "-div ranges overlap or are unsorted.");
            }
        }
    }

    private static void validateDivsWithoutDimension(int[] divs, String axis)
            throws AndrolibException {
        for (int i = 0; i < divs.length; i += 2) {
            int start = divs[i];
            int end = divs[i + 1];
            if (start < 0 || end <= start) {
                throw invalidChunk(axis + "-div range [" + start + ", " + end + ") is invalid.");
            }
            if (i > 0 && start < divs[i - 1]) {
                throw invalidChunk(axis + "-div ranges overlap or are unsorted.");
            }
        }
    }

    private static void validateDimensions(int width, int height) throws AndrolibException {
        if (width <= 0 || height <= 0) {
            throw new AndrolibException("Invalid nine-patch dimensions: "
                    + width + "x" + height + ".");
        }
        if (width > MAX_DIMENSION || height > MAX_DIMENSION) {
            throw new AndrolibException("Nine-patch dimensions exceed "
                    + MAX_DIMENSION + ": " + width + "x" + height + ".");
        }
        long pixels = (long) width * (long) height;
        if (pixels > MAX_PIXELS) {
            throw new AndrolibException("Nine-patch pixel count exceeds "
                    + MAX_PIXELS + ": " + width + "x" + height + " = " + pixels + ".");
        }
    }

    private static byte[] readBounded(InputStream in) throws AndrolibException {
        if (in == null) {
            throw new AndrolibException("Nine-patch input stream is null.");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        try {
            int count;
            while ((count = in.read(buffer)) != -1) {
                if (count == 0) {
                    continue;
                }
                total += count;
                if (total > MAX_INPUT_BYTES) {
                    throw new AndrolibException("Nine-patch PNG exceeds "
                            + MAX_INPUT_BYTES + " bytes.");
                }
                out.write(buffer, 0, count);
            }
        } catch (IOException ex) {
            throw new AndrolibException("Could not read nine-patch PNG.", ex);
        }
        // Do not close 'in' or 'out': the caller owns the input stream and
        // ByteArrayOutputStream has no external resource.
        return out.toByteArray();
    }

    private static void copyInterior(Bitmap src, Bitmap dst, int width, int height) {
        int[] row = new int[width];
        for (int y = 0; y < height; y++) {
            src.getPixels(row, 0, width, 0, y, width, 1);
            dst.setPixels(row, 0, width, 1, y + 1, width, 1);
        }
    }

    private static void drawBorder(Bitmap dst, NinePatchData np, int width, int height) {
        if (np.xDivs.length == 0) {
            drawHLine(dst, 0, 1, width);
        } else {
            for (int i = 0; i < np.xDivs.length; i += 2) {
                drawHLine(dst, 0, np.xDivs[i] + 1, np.xDivs[i + 1]);
            }
        }

        if (np.yDivs.length == 0) {
            drawVLine(dst, 0, 1, height);
        } else {
            for (int i = 0; i < np.yDivs.length; i += 2) {
                drawVLine(dst, 0, np.yDivs[i] + 1, np.yDivs[i + 1]);
            }
        }

        drawHLine(dst, height + 1, np.paddingLeft + 1, width - np.paddingRight);
        drawVLine(dst, width + 1, np.paddingTop + 1, height - np.paddingBottom);

        // Android's Bitmap API exposes npTc, not the optional optical npLb /
        // npOl metadata. Upstream draws red optical ticks when npLb exists.
        // This implementation cannot reliably reproduce them from Bitmap, so
        // they are a documented fidelity limit (see README).
    }

    private static void drawHLine(Bitmap bitmap, int y, int x1, int x2) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        if (y < 0 || y >= height || x1 < 0 || x2 >= width || x1 > x2) {
            return;
        }
        for (int x = x1; x <= x2; x++) {
            bitmap.setPixel(x, y, BLACK_TICK);
        }
    }

    private static void drawVLine(Bitmap bitmap, int x, int y1, int y2) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        if (x < 0 || x >= width || y1 < 0 || y2 >= height || y1 > y2) {
            return;
        }
        for (int y = y1; y <= y2; y++) {
            bitmap.setPixel(x, y, BLACK_TICK);
        }
    }

    private static byte[] findPngChunk(byte[] data, int wantedMagic) throws AndrolibException {
        if (data == null) {
            throw new AndrolibException("Nine-patch PNG data is null.");
        }
        if (data.length < 8) {
            throw new NinePatchNotFoundException();
        }

        int position = 8;
        while (position + 8 <= data.length) {
            long length = readUnsignedIntBigEndian(data, position);
            int magic = readIntBigEndian(data, position + 4);
            if (length > Integer.MAX_VALUE || length > data.length - position - 12) {
                throw new AndrolibException("Invalid PNG chunk length " + length
                        + " at offset " + position + ".");
            }
            int chunkLength = (int) length;
            if (magic == wantedMagic) {
                if (chunkLength > data.length - position - 8) {
                    throw new AndrolibException("Truncated PNG nine-patch chunk.");
                }
                byte[] chunk = new byte[chunkLength];
                System.arraycopy(data, position + 8, chunk, 0, chunkLength);
                return chunk;
            }
            position += 12 + chunkLength;
        }
        throw new NinePatchNotFoundException();
    }

    private static long readUnsignedIntBigEndian(byte[] data, int offset) {
        return ((long) (data[offset] & 0xFF) << 24)
                | ((long) (data[offset + 1] & 0xFF) << 16)
                | ((long) (data[offset + 2] & 0xFF) << 8)
                | (long) (data[offset + 3] & 0xFF);
    }

    private static int readIntBigEndian(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24)
                | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }

    private static AndrolibException invalidChunk(String reason) {
        return new AndrolibException("Invalid nine-patch chunk: " + reason);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException sneakyThrow(Throwable throwable) throws T {
        throw (T) throwable;
    }
}
