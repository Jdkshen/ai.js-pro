package com.jdkshen.aijspro.model.script;

import android.content.Context;
import android.os.SystemClock;

import androidx.test.platform.app.InstrumentationRegistry;

import com.jdkshen.aijspro.model.explorer.ExplorerFileItem;
import com.jdkshen.aijspro.ui.common.ScriptOperations;
import com.stardust.autojs.script.JavaScriptSource;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * B1 批次2a 设备侧验证：写原语经过真实调用点（ScriptOperations / ExplorerFileItem）落到磁盘。
 *
 * <p>删除路径已由 {@code ProjectUsabilityTest.deletionRemovesFileAndNestedFolderFromDisk} 覆盖，
 * 这里补「新建（含默认引擎指令、不覆写已有文件）」与「重命名（成功/失败语义）」两条。
 */
public class ScriptRepositoryDeviceTest {

    private final android.app.Instrumentation instrumentation =
            InstrumentationRegistry.getInstrumentation();

    private File newWorkDir() {
        Context ctx = instrumentation.getTargetContext();
        File dir = new File(ctx.getCacheDir(), "b1b2a-" + SystemClock.uptimeMillis());
        assertTrue(dir.mkdirs());
        return dir;
    }

    private static String readUtf8(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void createScriptFileWritesEngineDirectiveAndNeverOverwrites() throws Exception {
        File dir = newWorkDir();
        File target = new File(dir, "new.js");
        try {
            ScriptOperations operations = new ScriptOperations(
                    instrumentation.getTargetContext(), null);
            instrumentation.runOnMainSync(() ->
                    operations.createScriptFile(target.getPath(), null, false));

            assertTrue(target.isFile());
            String content = readUtf8(target);
            assertTrue("应带 QuickJS 引擎指令，实际: " + content,
                    content.startsWith(JavaScriptSource.QUICKJS_ENGINE_DIRECTIVE));

            // 再次创建（带内容）：已存在 → 不覆盖
            instrumentation.runOnMainSync(() ->
                    operations.createScriptFile(target.getPath(), "changed", false));
            assertEquals(content, readUtf8(target));
        } finally {
            target.delete();
            dir.delete();
        }
    }

    @Test
    public void explorerItemRenameMovesFileOnDiskAndKeepsOriginalOnFailure() throws Exception {
        File dir = newWorkDir();
        File source = new File(dir, "a.js");
        try {
            ScriptRepository.writeText(source, "var x = 1;\n");

            ExplorerFileItem renamed = new ExplorerFileItem(source, null).rename("b.js");
            assertEquals(new File(dir, "b.js").getPath(), renamed.getPath());
            assertTrue(new File(dir, "b.js").isFile());
            assertFalse(source.exists());
            assertEquals("var x = 1;\n", readUtf8(new File(dir, "b.js")));

            // 失败路径（源不存在）：返回原文件（保持「文件没变」判定）
            ExplorerFileItem missing = new ExplorerFileItem(new File(dir, "missing.js"), null);
            assertEquals(missing.getPath(), missing.rename("x.js").getPath());
        } finally {
            new File(dir, "b.js").delete();
            source.delete();
            dir.delete();
        }
    }
}
