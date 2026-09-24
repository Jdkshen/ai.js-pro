package com.stardust.autojs.core.console;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link ScriptRunLog} 的文件逻辑：建文件 / 落行 / 结尾与堆栈 / 轮转 / 命名。
 * 纯文件操作，不依赖 android 环境。
 */
public class ScriptRunLogTest {

    /** android.util.Log.INFO 的值；这里用字面量，避免单测里触碰 android 类。 */
    private static final int INFO = 4;

    @Rule
    public TemporaryFolder mTemp = new TemporaryFolder();

    @Test
    public void writesHeaderLinesAndFooter() throws Exception {
        File dir = mTemp.newFolder("logs");
        ScriptRunLog runLog = ScriptRunLog.open(dir, "象棋走棋.js", "quickjs");
        assertNotNull(runLog);
        runLog.line(INFO, "你好");
        runLog.line(INFO, "第二行");
        runLog.finish(null);

        File[] files = dir.listFiles();
        assertNotNull(files);
        assertEquals(1, files.length);
        assertTrue("文件名应带脚本名：" + files[0].getName(), files[0].getName().endsWith("_象棋走棋.js.log"));

        String text = new String(Files.readAllBytes(files[0].toPath()), StandardCharsets.UTF_8);
        assertTrue(text.contains("脚本: 象棋走棋.js"));
        assertTrue(text.contains("引擎: quickjs"));
        assertTrue(text.contains("[I] 你好"));
        assertTrue(text.contains("[I] 第二行"));
        assertTrue(text.contains("正常结束"));
    }

    @Test
    public void finishWithExceptionWritesStackAndReason() throws Exception {
        File dir = mTemp.newFolder("logs");
        ScriptRunLog runLog = ScriptRunLog.open(dir, "boom", "rhino");
        assertNotNull(runLog);
        runLog.line(INFO, "before crash");
        runLog.finish(new IllegalStateException("boom-msg"));

        File file = dir.listFiles()[0];
        String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        assertTrue(text.contains("异常退出"));
        assertTrue(text.contains("IllegalStateException"));
        assertTrue(text.contains("boom-msg"));
    }

    @Test
    public void linesAfterFinishAreIgnored() throws Exception {
        File dir = mTemp.newFolder("logs");
        ScriptRunLog runLog = ScriptRunLog.open(dir, "late", "quickjs");
        assertNotNull(runLog);
        runLog.finish(null);
        runLog.line(INFO, "迟到的输出");
        runLog.finish(null);

        String text = new String(Files.readAllBytes(dir.listFiles()[0].toPath()), StandardCharsets.UTF_8);
        assertFalse(text.contains("迟到的输出"));
        assertEquals("第二次 finish 不应重复写结尾", 1,
                text.split("正常结束", -1).length - 1);
    }

    @Test
    public void pruneRemovesOldScriptLogsButKeepsCrashFile() throws Exception {
        File dir = mTemp.newFolder("logs");
        File old = new File(dir, "2026-01-01_00-00-00_old.log");
        assertTrue(old.createNewFile());
        assertTrue(old.setLastModified(System.currentTimeMillis() - 8L * 24 * 60 * 60 * 1000));
        File crash = new File(dir, "app-crash.log");
        assertTrue(crash.createNewFile());
        assertTrue(crash.setLastModified(System.currentTimeMillis() - 8L * 24 * 60 * 60 * 1000));

        assertNotNull(ScriptRunLog.open(dir, "fresh", "quickjs"));

        assertFalse("过期的脚本日志应被轮转掉", old.exists());
        assertTrue("应用崩溃记录不参与脚本日志轮转", crash.exists());
    }

    @Test
    public void sameSecondTwoRunsGetDistinctFiles() throws Exception {
        File dir = mTemp.newFolder("logs");
        assertNotNull(ScriptRunLog.open(dir, "same", "quickjs"));
        assertNotNull(ScriptRunLog.open(dir, "same", "quickjs"));
        assertEquals(2, dir.listFiles().length);
    }

    @Test
    public void sanitizeKeepsChineseAndStripsPathAndIllegalChars() {
        assertEquals("象棋走棋.js", ScriptRunLog.sanitize("/sdcard/脚本/象棋走棋.js"));
        assertEquals("a-b", ScriptRunLog.sanitize("a:b"));
        assertEquals("script", ScriptRunLog.sanitize("   "));
        assertEquals("script", ScriptRunLog.sanitize(null));
    }
}
