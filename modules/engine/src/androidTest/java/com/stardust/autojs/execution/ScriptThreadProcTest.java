package com.stardust.autojs.execution;

import android.os.Process;

import androidx.test.runner.AndroidJUnit4;

import com.stardust.autojs.engine.ScriptEngine;
import com.stardust.autojs.script.ScriptSource;
import com.stardust.autojs.script.StringScriptSource;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ScriptThreadProcTest {
    @Test
    public void chineseWorkerPathLeavesNativeProcNameValid() throws Exception {
        String path = "/sdcard/脚本/象棋自动走棋/控制/worker.js";
        for (int run = 0; run < 32; run++) {
            RunnableScriptExecution execution = new RunnableScriptExecution(null, null) {
                @Override
                public ScriptSource getSource() {
                    return new StringScriptSource(path, "");
                }

                @Override
                public Object execute() {
                    try {
                        assertEquals(ScriptThreadNames.execution(getId()), readNativeName());
                    } catch (Exception error) {
                        throw new AssertionError(error);
                    }
                    return null;
                }

                @Override
                protected void onException(ScriptEngine engine, Throwable error) {
                    throw new AssertionError(error);
                }
            };
            runAndCheck(new Thread(execution));
        }
    }

    @Test
    public void workerNamesMatchNativeProcWithoutTruncation() throws Exception {
        Thread thread = new Thread(() -> {
            try {
                assertEquals(Thread.currentThread().getName(), readNativeName());
            } catch (Exception error) {
                throw new AssertionError(error);
            }
        });
        thread.setName(ScriptThreadNames.worker(thread.getId()));
        runAndCheck(thread);
    }

    private static void runAndCheck(Thread thread) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        thread.setUncaughtExceptionHandler((ignored, error) -> failure.set(error));
        thread.start();
        thread.join(5000);
        assertFalse("Worker did not finish", thread.isAlive());
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
    }

    private static String readNativeName() throws Exception {
        String file = "/proc/self/task/" + Process.myTid() + "/comm";
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[64];
            int count;
            while ((count = input.read(buffer)) != -1) {
                bytes.write(buffer, 0, count);
            }
        }
        byte[] raw = bytes.toByteArray();
        assertTrue("Native name exceeds 15 bytes plus newline", raw.length <= 16);
        String decoded = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(raw)).toString().trim();
        assertTrue(decoded, decoded.matches("[A-Za-z0-9-]+"));
        return decoded;
    }
}
