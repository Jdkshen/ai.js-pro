package com.jdkshen.aijspro.ui.main;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.drawable.ColorDrawable;
import android.graphics.Bitmap;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.ViewGroup;
import android.view.View;
import android.widget.TextView;
import android.widget.EditText;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.viewpager.widget.ViewPager;

import com.jdkshen.aijspro.R;
import com.jdkshen.aijspro.theme.AijsMiuixThemeKt;
import com.jdkshen.aijspro.theme.AppThemeRepository;
import com.jdkshen.aijspro.ui.editor.ProCodeEditorActivity;
import com.jdkshen.aijspro.ui.terminal.EmbeddedTerminalActivity;
import com.jdkshen.aijspro.ui.log.MiuixLogActivity;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Real Activity and system configuration regressions, including recreation costs. */
public class ThemeSwitchTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private SharedPreferences prefs;
    private boolean originalNight;
    private boolean originalFollow;
    private String originalSystemMode;
    private MainActivity main;
    private Activity editor;
    private File fixture;
    private Instrumentation.ActivityMonitor monitor;

    @Before public void setUp() throws Exception {
        Context context = instrumentation.getTargetContext();
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
        originalNight = prefs.getBoolean("key_night_mode", false);
        originalFollow = prefs.getBoolean("follow_system_theme", false);
        originalSystemMode = shell("cmd uimode night").trim().replace("Night mode: ", "");
        choose(false, false);
        monitor = instrumentation.addMonitor(MainActivity.class.getName(), null, false);
        main = (MainActivity) instrumentation.startActivitySync(new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        awaitDark(main, false);
    }

    @After public void tearDown() throws Exception {
        if (originalSystemMode != null) shell("cmd uimode night " + originalSystemMode);
        choose(originalFollow, originalNight);
        instrumentation.runOnMainSync(() -> {
            if (editor != null) editor.finish();
            if (main != null) main.finish();
        });
        if (monitor != null) instrumentation.removeMonitor(monitor);
        if (fixture != null) fixture.delete();
    }

    @Test public void manualToggleKeepsPagerAndNativeRowsInPlace() {
        ViewPager pager = main.findViewById(R.id.viewpager);
        Object adapter = pager.getAdapter();
        int creations = monitor.getHits();
        for (int round = 0; round < 3; round++) {
            long started = SystemClock.elapsedRealtime();
            choose(false, true);
            awaitDark(main, true);
            assertRowColor(0xFFE6E6E6);
            Log.i("ThemeSwitchTest", "dark settled ms=" + (SystemClock.elapsedRealtime() - started));
            choose(false, false);
            awaitDark(main, false);
            assertRowColor(0xFF212121);
            instrumentation.runOnMainSync(() -> {
                assertSame(adapter, pager.getAdapter());
                for (int page = 0; page < 5; page++) pager.setCurrentItem(page, false);
                pager.setCurrentItem(0, false);
            });
        }
        assertFalse(main.isDestroyed());
        assertEquals("theme toggles must not recreate the five cached pages", creations, monitor.getHits());
    }

    @Test public void followingSystemUpdatesNativeAndComposeAndPreservesManualChoice() throws Exception {
        choose(false, false);
        shell("cmd uimode night yes");
        awaitDark(main, false);
        choose(true, false);
        awaitDark(main, true);
        assertComposeBarColor(0xFF000000);
        assertRowColor(0xFFE6E6E6);
        shell("cmd uimode night no");
        awaitDark(main, false);
        assertComposeBarColor(0xFFF7F7F7);
        assertRowColor(0xFF212121);
        shell("cmd uimode night yes");
        awaitDark(main, true);
        choose(false, false);
        awaitDark(main, false);
        assertFalse(prefs.getBoolean("key_night_mode", true));
    }

    @Test public void editorFollowsSystemDespiteSavedManualLightChoice() throws Exception {
        shell("cmd uimode night yes");
        choose(true, false);
        awaitDark(main, true);
        fixture = new File(instrumentation.getTargetContext().getCacheDir(), "theme-switch-regression.js");
        try (FileOutputStream output = new FileOutputStream(fixture)) {
            output.write("// theme regression\nconst value = 1;\n".getBytes(StandardCharsets.UTF_8));
        }
        editor = instrumentation.startActivitySync(ProCodeEditorActivity.intent(
                instrumentation.getTargetContext(), fixture).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        awaitDark(editor, true);
        assertEditorBackground(0xFF1E1E1E);
        shell("cmd uimode night no");
        awaitDark(editor, false);
        assertEditorBackground(0xFFFFFFFF);
        assertFalse(editor.isDestroyed());
    }

    @Test public void terminalRepaintsWithoutLosingItsCommand() throws Exception {
        editor = instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(),
                EmbeddedTerminalActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        assertEditorBackground(0xFFF7F7F7);
        ViewGroup content = editor.findViewById(android.R.id.content);
        ViewGroup root = (ViewGroup) content.getChildAt(0);
        EditText command = (EditText) root.getChildAt(1);
        instrumentation.runOnMainSync(() -> command.setText("echo theme-regression"));
        choose(false, true);
        assertEditorBackground(0xFF000000);
        choose(false, false);
        assertEditorBackground(0xFFF7F7F7);
        shell("cmd uimode night yes");
        choose(true, false);
        assertEditorBackground(0xFF000000);
        shell("cmd uimode night no");
        assertEditorBackground(0xFFF7F7F7);
        await(() -> assertEquals("echo theme-regression", command.getText().toString()));
        assertFalse(editor.isDestroyed());
    }

    @Test public void existingNativeLogListRespondsToThemeChanges() {
        editor = instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(),
                MiuixLogActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        assertLogBackground(0xFFF7F7F7);
        choose(false, true);
        assertLogBackground(0xFF000000);
        choose(false, false);
        assertLogBackground(0xFFF7F7F7);
    }

    private void assertLogBackground(int expected) {
        await(() -> {
            View list = editor.findViewById(R.id.log_list);
            assertNotNull(list);
            assertEquals(expected, ((ColorDrawable) list.getBackground()).getColor());
        });
    }

    private void choose(boolean follow, boolean night) {
        instrumentation.runOnMainSync(() -> prefs.edit()
                .putBoolean("follow_system_theme", follow).putBoolean("key_night_mode", night).apply());
    }

    private void awaitDark(Activity activity, boolean expected) {
        await(() -> {
            assertFalse(activity.isDestroyed());
            assertEquals(expected, (activity.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES);
            assertEquals(expected, AijsMiuixThemeKt.isAijsDarkThemeResolved(activity));
            assertEquals(expected, AppThemeRepository.get(activity).getPalette().isDark);
        });
    }

    private void assertRowColor(int expected) {
        await(() -> {
            TextView row = main.findViewById(R.id.name);
            assertNotNull(row);
            assertEquals(expected, row.getCurrentTextColor());
        });
    }

    private void assertEditorBackground(int expected) {
        await(() -> {
            ViewGroup content = editor.findViewById(android.R.id.content);
            assertEquals(expected, ((ColorDrawable) content.getChildAt(0).getBackground()).getColor());
        });
    }

    private void assertComposeBarColor(int expected) {
        int[] location = new int[2];
        int[] height = new int[1];
        instrumentation.runOnMainSync(() -> {
            ViewGroup appBar = main.findViewById(R.id.app_bar);
            View bar = appBar.getChildAt(0);
            bar.getLocationOnScreen(location);
            height[0] = bar.getHeight();
        });
        long deadline = SystemClock.elapsedRealtime() + 5000;
        int actual;
        do {
            Bitmap screenshot = instrumentation.getUiAutomation().takeScreenshot();
            assertNotNull(screenshot);
            actual = screenshot.getPixel(location[0] + 3, location[1] + height[0] / 2);
            screenshot.recycle();
            if (actual == expected) return;
            SystemClock.sleep(20);
        } while (SystemClock.elapsedRealtime() < deadline);
        assertEquals("Compose navigation must match the native night mode", expected, actual);
    }

    private void await(Runnable assertions) {
        long deadline = SystemClock.elapsedRealtime() + 5000;
        AssertionError last;
        do {
            instrumentation.waitForIdleSync();
            AtomicReference<AssertionError> failure = new AtomicReference<>();
            instrumentation.runOnMainSync(() -> {
                try { assertions.run(); }
                catch (AssertionError error) { failure.set(error); }
            });
            last = failure.get();
            if (last == null) return;
            SystemClock.sleep(20);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw last;
    }

    private String shell(String command) throws Exception {
        ParcelFileDescriptor descriptor = instrumentation.getUiAutomation().executeShellCommand(command);
        try (ParcelFileDescriptor.AutoCloseInputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
