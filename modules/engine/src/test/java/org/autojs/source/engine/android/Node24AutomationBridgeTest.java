package org.autojs.source.engine.android;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Pure-JVM checks for the Node24 automation request normalization. */
public class Node24AutomationBridgeTest {

    @Test public void pressUsesOneStrokeWithExplicitDuration() throws Exception {
        JSONArray strokes = new JSONArray(Node24AutomationBridge.pressGestureJson(120, 240, 350L));
        assertEquals(1, strokes.length());
        JSONArray points = strokes.getJSONArray(0);
        assertEquals(2, points.length());
        assertEquals(120, points.getJSONObject(0).getInt("x"));
        assertEquals(240, points.getJSONObject(0).getInt("y"));
        assertEquals(0L, points.getJSONObject(0).getLong("t"));
        assertEquals(120, points.getJSONObject(1).getInt("x"));
        assertEquals(240, points.getJSONObject(1).getInt("y"));
        assertEquals(350L, points.getJSONObject(1).getLong("t"));
    }

    @Test public void gestureNormalizationExtractsStartTimeAndRebasesEachStroke() throws Exception {
        JSONArray strokes = new JSONArray();
        strokes.put(new JSONArray()
                .put(new JSONObject().put("x", 1).put("y", 2).put("t", 40))
                .put(new JSONObject().put("x", 3).put("y", 4).put("t", 290)));
        strokes.put(new JSONArray()
                .put(new JSONObject().put("x", 5).put("y", 6).put("t", 1000))
                .put(new JSONObject().put("x", 7).put("y", 8).put("t", 1250)));

        JSONArray normalized = new JSONArray(Node24AutomationBridge.normalizeStrokes(strokes));
        assertEquals(2, normalized.length());

        JSONArray first = normalized.getJSONArray(0);
        assertEquals(40L, first.getJSONObject(0).getLong("startTime"));
        assertEquals(0L, first.getJSONObject(0).getLong("t"));
        assertEquals(250L, first.getJSONObject(1).getLong("t"));
        assertFalse(first.getJSONObject(1).has("startTime"));

        JSONArray second = normalized.getJSONArray(1);
        assertEquals(1000L, second.getJSONObject(0).getLong("startTime"));
        assertEquals(0L, second.getJSONObject(0).getLong("t"));
        assertEquals(250L, second.getJSONObject(1).getLong("t"));
        assertEquals(5, second.getJSONObject(0).getInt("x"));
    }

    @Test public void gestureNormalizationSupportsSinglePointDelayAndZeroStart() throws Exception {
        JSONArray delayed = new JSONArray().put(new JSONArray()
                .put(new JSONObject().put("x", 9).put("y", 8).put("t", 350)));
        JSONObject point = new JSONArray(Node24AutomationBridge.normalizeStrokes(delayed))
                .getJSONArray(0).getJSONObject(0);
        assertEquals(350L, point.getLong("startTime"));
        assertEquals(0L, point.getLong("t"));

        JSONArray immediate = new JSONArray().put(new JSONArray()
                .put(new JSONObject().put("x", 1).put("y", 2)));
        JSONObject zero = new JSONArray(Node24AutomationBridge.normalizeStrokes(immediate))
                .getJSONArray(0).getJSONObject(0);
        assertEquals(0L, zero.getLong("startTime"));
        assertEquals(0L, zero.getLong("t"));

        JSONArray atLimit = singleStrokePoint(1, 2, 60_000L);
        JSONObject limit = new JSONArray(Node24AutomationBridge.normalizeStrokes(atLimit))
                .getJSONArray(0).getJSONObject(0);
        assertEquals(60_000L, limit.getLong("startTime"));
        assertEquals(0L, limit.getLong("t"));
    }

    @Test public void gestureNormalizationDropsUnknownFields() throws Exception {
        JSONArray strokes = new JSONArray();
        strokes.put(new JSONArray().put(new JSONObject()
                .put("x", 1).put("y", 2).put("t", 0).put("ignored", "yes")));
        JSONObject point = new JSONArray(Node24AutomationBridge.normalizeStrokes(strokes))
                .getJSONArray(0).getJSONObject(0);
        assertFalse(point.has("ignored"));
        assertTrue(point.has("x"));
        assertTrue(point.has("y"));
        assertTrue(point.has("t"));
    }

    @Test public void gestureRejectsEmptyTooManyAndInvalidPoints() {
        assertInvalidGesture(new JSONArray());
        JSONArray tooMany = new JSONArray();
        for (int i = 0; i < 11; i++) {
            try {
                tooMany.put(new JSONArray().put(new JSONObject().put("x", i).put("y", i).put("t", 0)));
            } catch (Exception impossible) {
                fail(impossible.toString());
            }
        }
        assertInvalidGesture(tooMany);

        JSONArray missingX = new JSONArray();
        try {
            missingX.put(new JSONArray().put(new JSONObject().put("y", 1).put("t", 0)));
        } catch (Exception impossible) {
            fail(impossible.toString());
        }
        assertInvalidGesture(missingX);

        JSONArray negativeTime = new JSONArray();
        try {
            negativeTime.put(new JSONArray().put(new JSONObject()
                    .put("x", 1).put("y", 1).put("t", -1)));
        } catch (Exception impossible) {
            fail(impossible.toString());
        }
        assertInvalidGesture(negativeTime);
    }

    @Test public void gestureRejectsNegativeCoordinatesDecreasingTimeAndOverLimit() {
        assertInvalidGesture(singleStrokePoint(-1, 1, 0));
        assertInvalidGesture(singleStrokePoint(1, -1, 0));

        JSONArray decreasing = new JSONArray();
        try {
            decreasing.put(new JSONArray()
                    .put(new JSONObject().put("x", 1).put("y", 1).put("t", 100))
                    .put(new JSONObject().put("x", 2).put("y", 2).put("t", 50)));
        } catch (Exception impossible) {
            fail(impossible.toString());
        }
        assertInvalidGesture(decreasing);

        assertInvalidGesture(singleStrokePoint(1, 1, 60_001L));

        JSONArray secondStrokeOverLimit = new JSONArray();
        try {
            secondStrokeOverLimit.put(new JSONArray()
                    .put(new JSONObject().put("x", 1).put("y", 1).put("t", 1_000)));
            secondStrokeOverLimit.put(new JSONArray()
                    .put(new JSONObject().put("x", 2).put("y", 2).put("t", 60_001)));
        } catch (Exception impossible) {
            fail(impossible.toString());
        }
        assertInvalidGesture(secondStrokeOverLimit);
    }

    private static JSONArray singleStrokePoint(int x, int y, long t) {
        try {
            return new JSONArray().put(new JSONArray()
                    .put(new JSONObject().put("x", x).put("y", y).put("t", t)));
        } catch (Exception impossible) {
            fail(impossible.toString());
            return null;
        }
    }

    @Test public void accessibilityErrorsAreHonestAndDistinct() {
        assertTrue(Node24AutomationBridge.accessibilityRequirementMessage(false).contains("未开启"));
        assertTrue(Node24AutomationBridge.accessibilityRequirementMessage(true).contains("未运行"));
        assertNotEquals(Node24AutomationBridge.accessibilityRequirementMessage(false),
                Node24AutomationBridge.accessibilityRequirementMessage(true));
    }

    // ------------------------------------------------------------------
    // Phase A：无障碍截图 Raw(.n24r) 优先、PNG 回退（纯 JVM）
    // ------------------------------------------------------------------

    @Test public void screenshotTemporaryFileUsesFreshUuidAndRawPngSuffixes() {
        UUID id = UUID.randomUUID();
        File directory = new File("cache");
        File raw = Node24AutomationBridge.screenshotTemporaryFile(directory, id, true);
        File png = Node24AutomationBridge.screenshotTemporaryFile(directory, id, false);
        assertEquals("node24-shot-" + id + ".n24r", raw.getName());
        assertEquals("node24-shot-" + id + ".png", png.getName());
        assertEquals(directory, raw.getParentFile());
        assertNotEquals(raw.getName(), png.getName());
    }

    @Test public void screenshotUsableFileRejectsMissingEmptyAndDirectory() throws Exception {
        File directory = newScreenshotTestDirectory();
        try {
            assertFalse(Node24AutomationBridge.isUsableScreenshotFile(null));
            assertFalse(Node24AutomationBridge.isUsableScreenshotFile(
                    new File(directory, "missing.png")));
            File empty = new File(directory, "empty.png");
            writeBytes(empty, new byte[0]);
            assertFalse(Node24AutomationBridge.isUsableScreenshotFile(empty));
            assertFalse(Node24AutomationBridge.isUsableScreenshotFile(directory));
            File written = new File(directory, "written.n24r");
            writeBytes(written, new byte[]{1, 2, 3});
            assertTrue(Node24AutomationBridge.isUsableScreenshotFile(written));
        } finally {
            deleteTree(directory);
        }
    }

    @Test public void screenshotAttemptDecodesSavedFileAndDeletesArtifacts() throws Exception {
        File directory = newScreenshotTestDirectory();
        try {
            AtomicReference<File> requested = new AtomicReference<>();
            AtomicReference<String> decodedPath = new AtomicReference<>();
            Node24AutomationBridge.ScreenshotCapture capture = path -> {
                File file = new File(path);
                requested.set(file);
                writeBytes(file, "raw-pixels".getBytes(StandardCharsets.US_ASCII));
                return file.getAbsolutePath();
            };
            JSONObject result = Node24AutomationBridge.captureScreenshotOnce(directory, true, capture,
                    path -> {
                        decodedPath.set(path);
                        return new JSONObject().put("width", 1280).put("height", 2772);
                    });
            assertEquals(1280, result.getInt("width"));
            assertNotNull(requested.get());
            assertTrue(requested.get().getName().endsWith(".n24r"));
            assertEquals(directory, requested.get().getParentFile());
            assertEquals(requested.get().getAbsolutePath(), decodedPath.get());
            assertFalse(requested.get().exists());
            assertDirectoryEmpty(directory);
        } finally {
            deleteTree(directory);
        }
    }

    @Test public void screenshotAttemptCleansUpWhenCaptureReturnsEmpty() throws Exception {
        File directory = newScreenshotTestDirectory();
        try {
            AtomicReference<File> requested = new AtomicReference<>();
            Node24AutomationBridge.ScreenshotCapture capture = path -> {
                File file = new File(path);
                requested.set(file);
                writeBytes(file, new byte[]{1}); // 模拟主进程失败时留下的半写文件
                return "";
            };
            try {
                Node24AutomationBridge.captureScreenshotOnce(directory, true, capture, path -> {
                    fail("主进程未返回文件时不应解码");
                    return null;
                });
                fail("expected screenshot failure");
            } catch (IllegalStateException expected) {
                assertEquals(Node24AutomationBridge.SCREENSHOT_MISSING_FILE_MESSAGE,
                        expected.getMessage());
            }
            assertFalse(requested.get().exists());
            assertDirectoryEmpty(directory);
        } finally {
            deleteTree(directory);
        }
    }

    @Test public void screenshotAttemptRejectsMissingOrEmptySavedFile() throws Exception {
        File directory = newScreenshotTestDirectory();
        try {
            Node24AutomationBridge.ScreenshotDecoder unreachable = path -> {
                fail("截图文件不可用时不应解码");
                return null;
            };
            try {
                Node24AutomationBridge.captureScreenshotOnce(directory, false, path -> path, unreachable);
                fail("expected missing screenshot failure");
            } catch (IllegalStateException expected) {
                assertEquals(Node24AutomationBridge.SCREENSHOT_EMPTY_FILE_MESSAGE,
                        expected.getMessage());
            }
            try {
                Node24AutomationBridge.captureScreenshotOnce(directory, false, path -> {
                    File file = new File(path);
                    writeBytes(file, new byte[0]);
                    return file.getAbsolutePath();
                }, unreachable);
                fail("expected empty screenshot failure");
            } catch (IllegalStateException expected) {
                assertEquals(Node24AutomationBridge.SCREENSHOT_EMPTY_FILE_MESSAGE,
                        expected.getMessage());
            }
            assertDirectoryEmpty(directory);
        } finally {
            deleteTree(directory);
        }
    }

    @Test public void screenshotAttemptCleansUpWhenDecoderFails() throws Exception {
        File directory = newScreenshotTestDirectory();
        try {
            AtomicReference<File> requested = new AtomicReference<>();
            Node24AutomationBridge.ScreenshotCapture capture = path -> {
                File file = new File(path);
                requested.set(file);
                writeBytes(file, new byte[]{7, 7, 7, 7});
                return file.getAbsolutePath();
            };
            try {
                Node24AutomationBridge.captureScreenshotOnce(directory, false, capture, path -> {
                    throw new IOException("decode boom");
                });
                fail("expected decode failure");
            } catch (IOException expected) {
                assertEquals("decode boom", expected.getMessage());
            }
            assertFalse(requested.get().exists());
            assertDirectoryEmpty(directory);
        } finally {
            deleteTree(directory);
        }
    }

    @Test public void screenshotFallbackUsesNewUuidPngWhenRawDecodeFails() throws Exception {
        File directory = newScreenshotTestDirectory();
        try {
            List<String> requested = new ArrayList<>();
            Node24AutomationBridge.ScreenshotCapture capture = path -> {
                requested.add(path);
                File file = new File(path);
                writeBytes(file, new byte[]{1, 2, 3});
                return file.getAbsolutePath();
            };
            AtomicReference<String> pngDecoded = new AtomicReference<>();
            JSONObject result = Node24AutomationBridge.takeScreenshotWithFallback(directory, noPixels(), capture,
                    path -> {
                        throw new IOException("raw decode boom");
                    },
                    path -> {
                        pngDecoded.set(path);
                        return new JSONObject().put("format", "png");
                    });
            assertEquals("png", result.getString("format"));
            assertEquals(2, requested.size());
            assertTrue(requested.get(0).endsWith(".n24r"));
            assertTrue(requested.get(1).endsWith(".png"));
            assertNotEquals(requested.get(0), requested.get(1));
            assertEquals(requested.get(1), pngDecoded.get());
            assertDirectoryEmpty(directory);
        } finally {
            deleteTree(directory);
        }
    }

    @Test public void screenshotFallbackSkipsPngWhenRawSucceeds() throws Exception {
        File directory = newScreenshotTestDirectory();
        try {
            List<String> requested = new ArrayList<>();
            Node24AutomationBridge.ScreenshotCapture capture = path -> {
                requested.add(path);
                File file = new File(path);
                writeBytes(file, new byte[]{9});
                return file.getAbsolutePath();
            };
            AtomicInteger pngCalls = new AtomicInteger();
            JSONObject result = Node24AutomationBridge.takeScreenshotWithFallback(directory, noPixels(), capture,
                    path -> new JSONObject().put("format", "raw"),
                    path -> {
                        pngCalls.incrementAndGet();
                        return new JSONObject();
                    });
            assertEquals("raw", result.getString("format"));
            assertEquals(1, requested.size());
            assertTrue(requested.get(0).endsWith(".n24r"));
            assertEquals(0, pngCalls.get());
            assertDirectoryEmpty(directory);
        } finally {
            deleteTree(directory);
        }
    }

    @Test public void screenshotFallbackUsesPngWhenRawCaptureReturnsEmpty() throws Exception {
        File directory = newScreenshotTestDirectory();
        try {
            List<String> requested = new ArrayList<>();
            Node24AutomationBridge.ScreenshotCapture capture = path -> {
                requested.add(path);
                if (path.endsWith(".n24r")) {
                    return "";
                }
                File file = new File(path);
                writeBytes(file, new byte[]{5});
                return file.getAbsolutePath();
            };
            JSONObject result = Node24AutomationBridge.takeScreenshotWithFallback(directory, noPixels(), capture,
                    path -> {
                        fail("Raw 主进程失败后不应解码");
                        return null;
                    },
                    path -> new JSONObject().put("format", "png"));
            assertEquals("png", result.getString("format"));
            assertEquals(2, requested.size());
            assertTrue(requested.get(1).endsWith(".png"));
            assertDirectoryEmpty(directory);
        } finally {
            deleteTree(directory);
        }
    }

    @Test public void screenshotFallbackKeepsPngMessageAndPriorCausesWhenAllFail() throws Exception {
        File directory = newScreenshotTestDirectory();
        try {
            Node24AutomationBridge.ScreenshotCapture capture = path -> {
                if (path.endsWith(".n24r")) {
                    File file = new File(path);
                    writeBytes(file, new byte[]{1, 2});
                    return file.getAbsolutePath();
                }
                return "";
            };
            try {
                Node24AutomationBridge.takeScreenshotWithFallback(directory, noPixels(), capture,
                        path -> {
                            throw new IOException("raw decode boom");
                        },
                        path -> new JSONObject());
                fail("expected all three paths to fail");
            } catch (IllegalStateException expected) {
                assertEquals(Node24AutomationBridge.SCREENSHOT_MISSING_FILE_MESSAGE,
                        expected.getMessage());
                assertEquals(2, expected.getSuppressed().length);
                assertEquals("pixels unsupported", expected.getSuppressed()[0].getMessage());
                assertTrue(expected.getSuppressed()[1] instanceof IOException);
                assertEquals("raw decode boom", expected.getSuppressed()[1].getMessage());
            }
            assertDirectoryEmpty(directory);
        } finally {
            deleteTree(directory);
        }
    }

    @Test public void screenshotErrorMessagesMatchHistoricalPngPath() {
        assertEquals("无障碍截图失败：主进程未返回截图文件（请确认无障碍服务已开启且系统支持截图）",
                Node24AutomationBridge.SCREENSHOT_MISSING_FILE_MESSAGE);
        assertEquals("无障碍截图失败：主进程截图文件不存在或为空",
                Node24AutomationBridge.SCREENSHOT_EMPTY_FILE_MESSAGE);
    }

    // ------------------------------------------------------------------
    // Phase B：PFD 像素管道优先 + Phase A/PNG 回退（纯 JVM）
    // ------------------------------------------------------------------

    @Test public void screenshotLimitNormalizesNegativeAndOversizedValues() {
        assertEquals(0, Node24AutomationBridge.normalizeScreenshotLimit(Integer.MIN_VALUE));
        assertEquals(0, Node24AutomationBridge.normalizeScreenshotLimit(-1));
        assertEquals(0, Node24AutomationBridge.normalizeScreenshotLimit(0));
        assertEquals(1280, Node24AutomationBridge.normalizeScreenshotLimit(1280));
        assertEquals(Node24AutomationBridge.MAX_SCREENSHOT_LIMIT,
                Node24AutomationBridge.normalizeScreenshotLimit(Integer.MAX_VALUE));
    }

    @Test public void screenshotFallbackUsesPixelsWithoutTouchingFiles() throws Exception {
        AtomicInteger fileCalls = new AtomicInteger();
        JSONObject result = Node24AutomationBridge.takeScreenshotWithFallback(new File("unused"),
                () -> new JSONObject().put("format", "pixels"),
                path -> {
                    fileCalls.incrementAndGet();
                    throw new IOException("file capture should not be called");
                },
                path -> {
                    fileCalls.incrementAndGet();
                    throw new IOException("raw decode should not be called");
                },
                path -> {
                    fileCalls.incrementAndGet();
                    throw new IOException("png decode should not be called");
                });
        assertEquals("pixels", result.getString("format"));
        assertEquals(0, fileCalls.get());
    }

    @Test public void screenshotFallbackFallsBackWhenPixelsThrowRuntimeException() throws Exception {
        File directory = newScreenshotTestDirectory();
        try {
            List<String> requested = new ArrayList<>();
            Node24AutomationBridge.ScreenshotCapture capture = path -> {
                requested.add(path);
                File file = new File(path);
                writeBytes(file, new byte[]{3, 1, 4});
                return file.getAbsolutePath();
            };
            AtomicInteger pngCalls = new AtomicInteger();
            JSONObject result = Node24AutomationBridge.takeScreenshotWithFallback(directory,
                    () -> {
                        throw new IllegalStateException("pfd broken");
                    },
                    capture,
                    path -> new JSONObject().put("format", "raw-file"),
                    path -> {
                        pngCalls.incrementAndGet();
                        return new JSONObject();
                    });
            assertEquals("raw-file", result.getString("format"));
            assertEquals(1, requested.size());
            assertTrue(requested.get(0).endsWith(".n24r"));
            assertEquals(0, pngCalls.get());
            assertDirectoryEmpty(directory);
        } finally {
            deleteTree(directory);
        }
    }

    private static Node24AutomationBridge.ScreenshotPixelsReader noPixels() {
        return () -> {
            throw new IOException("pixels unsupported");
        };
    }

    private static File newScreenshotTestDirectory() throws IOException {
        File directory = File.createTempFile("n24-bridge-", "");
        assertTrue(directory.delete());
        assertTrue(directory.mkdirs());
        return directory;
    }

    private static void writeBytes(File file, byte[] bytes) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
        }
    }

    private static void assertDirectoryEmpty(File directory) {
        File[] files = directory.listFiles();
        assertNotNull(files);
        assertEquals(0, files.length);
    }

    private static void deleteTree(File directory) {
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }
        //noinspection ResultOfMethodCallIgnored
        directory.delete();
    }

    private static void assertInvalidGesture(JSONArray strokes) {
        try {
            Node24AutomationBridge.normalizeStrokes(strokes);
            fail("expected invalid gesture to fail");
        } catch (Exception expected) {
            assertNotNull(expected.getMessage());
        }
    }
}
