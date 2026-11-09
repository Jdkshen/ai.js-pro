package com.stardust.automator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * task-60（P0-3）：阻塞手势的线程约束（具名异常只在「必然死锁」那条路径；主线程只 warning）。
 */
public class GestureThreadGuardTest {

    @Test public void blockingGestureOnTheCallbackThreadIsRefusedWithAnActionableMessage() {
        String reason = GestureThreadGuard.refusesBlockingGesture("servant", true);
        assertNotNull(reason);
        assertTrue(reason, reason.contains("回调线程"));
        assertTrue(reason, reason.contains("卡死"));
        assertTrue(reason, reason.contains("gesturesAsync"));
        assertNull("不是回调线程就不该拒绝", GestureThreadGuard.refusesBlockingGesture("main", false));
        assertNull(GestureThreadGuard.refusesBlockingGesture("script", false));
    }

    @Test public void mainThreadIsWarnedNotRefused() {
        String warning = GestureThreadGuard.mainThreadWarning("main", "strokes=1");
        assertTrue(warning, warning.contains("主线程"));
        assertTrue(warning, warning.contains("strokes=1"));
        assertTrue(warning, warning.contains("gesturesAsync"));
    }

    @Test public void exceptionIsANamedRuntimeExceptionWithTheSameReason() {
        GestureOnMainThreadException error = new GestureOnMainThreadException("boom");
        assertTrue(error instanceof RuntimeException);
        assertEquals("boom", error.getMessage());
        assertEquals("boom", error.getReason());
    }

    @Test public void guardOnlyGuardsTheBlockingPathAndAsyncStaysUntouched() throws Exception {
        String source = readSource("modules/automator/src/main/java/com/stardust/automator/GlobalActionAutomator.kt",
                "src/main/java/com/stardust/automator/GlobalActionAutomator.kt");
        assertNotNull("找不到 GlobalActionAutomator.kt", source);
        int guardCalls = source.split("GestureThreadGuard\\.refusesBlockingGesture", -1).length - 1;
        assertEquals("阻塞路径只该有一处吞咽判定（新增第二处要显式改测试）", 1, guardCalls);
        assertEquals("主线程 warning 只该有一处", 1,
                source.split("GestureThreadGuard\\.mainThreadWarning", -1).length - 1);
        // gesturesAsync 不得带阻塞/等待（它本来就不等回调）
        int asyncAt = source.indexOf("fun gesturesAsync(");
        assertTrue(asyncAt > 0);
        String asyncBody = source.substring(asyncAt, Math.min(source.length(), asyncAt + 400));
        assertTrue(asyncBody, !asyncBody.contains("blockedGet") && !asyncBody.contains("refusesBlockingGesture"));
        // 阻塞路径确实是「无超时 wait 等回调」——这是主线程只警告、回调线程必须拒绝的取证依据
        assertTrue(source.contains("result.blockedGet()"));
    }

    private static String readSource(String... candidates) throws Exception {
        File dir = new File(".").getCanonicalFile();
        for (int depth = 0; depth < 5 && dir != null; depth++) {
            for (String candidate : candidates) {
                File file = new File(dir, candidate);
                if (file.isFile()) {
                    return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                }
            }
            dir = dir.getParentFile();
        }
        return null;
    }
}
