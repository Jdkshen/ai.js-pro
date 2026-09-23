package com.jdkshen.aijspro.ui.main;

import android.app.Activity;
import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.View;

import androidx.test.platform.app.InstrumentationRegistry;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.jdkshen.aijspro.R;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ViewPagerFragmentFabTest {
    @Test
    public void sampleShownBeforeBindingClearsPreviousPagesClickHandler() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            FloatingActionButton fab = newFab();
            TestPage files = new TestPage(0);
            files.setFab(fab);
            files.onPageShow();
            assertTrue(fab.performClick());
            files.onPageHide();

            TestPage sample = new TestPage(ViewPagerFragment.ROTATION_GONE);
            sample.onPageShow();
            assertTrue(sample.isShown());
            sample.setFab(fab);
            assertFalse(fab.performClick());
            assertEquals(View.GONE, fab.getVisibility());

            sample.onPageHide();
            files.onPageShow();
            assertEquals(View.VISIBLE, fab.getVisibility());
            assertTrue(fab.performClick());
            assertEquals(2, files.clicks);
        });
    }

    @Test
    public void visiblePageBindsAndRebindsItsClickHandlerAfterEarlyShow() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            TestPage page = new TestPage(0);
            page.onPageShow();
            FloatingActionButton fab = newFab();
            fab.setVisibility(View.GONE);
            page.setFab(fab);
            assertEquals(View.VISIBLE, fab.getVisibility());
            assertTrue(fab.performClick());
            page.setFab(null);
            page.onPageShow();
            FloatingActionButton replacement = newFab();
            page.setFab(replacement);
            assertTrue(replacement.performClick());
            assertEquals(2, page.clicks);
        });
    }

    @Test
    public void preloadedHiddenPageDoesNotTakeCurrentPagesFab() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            FloatingActionButton fab = newFab();
            TestPage files = new TestPage(0);
            files.setFab(fab);
            files.onPageShow();
            TestPage sample = new TestPage(ViewPagerFragment.ROTATION_GONE);
            sample.setFab(fab);
            assertTrue(fab.performClick());
            assertEquals(1, files.clicks);
            assertFalse(sample.isShown());
        });
    }

    private static FloatingActionButton newFab() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        return new FloatingActionButton(new ContextThemeWrapper(context, R.style.AppTheme));
    }

    private static class TestPage extends ViewPagerFragment {
        int clicks;

        TestPage(int rotation) { super(rotation); }

        @Override protected void onFabClick(FloatingActionButton fab) { clicks++; }

        @Override public boolean onBackPressed(Activity activity) { return false; }
    }
}
