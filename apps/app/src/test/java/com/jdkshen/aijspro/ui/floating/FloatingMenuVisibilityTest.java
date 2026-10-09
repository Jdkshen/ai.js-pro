package com.jdkshen.aijspro.ui.floating;

import org.junit.Test;

import static org.junit.Assert.*;

public class FloatingMenuVisibilityTest {
    private static final class Menu implements FloatingMenuVisibility.Target {
        boolean hidden;
        int hides, restores;

        @Override
        public void setTemporarilyHidden(boolean value) {
            if (hidden != value) {
                if (value) hides++; else restores++;
            }
            hidden = value;
        }
    }

    @Test
    public void overlappingCapturesCannotRestoreEachOthersMenu() {
        FloatingMenuVisibility state = new FloatingMenuVisibility();
        Menu menu = new Menu();
        state.attach(menu);
        int a = state.acquire(), b = state.acquire();
        assertTrue(menu.hidden);
        assertEquals(1, menu.hides);
        state.release(a);
        state.release(a);
        assertTrue(menu.hidden);
        state.release(b);
        assertFalse(menu.hidden);
        assertEquals(1, menu.restores);
    }

    @Test
    public void pageResumeDuringCaptureKeepsMenuHidden() {
        FloatingMenuVisibility state = new FloatingMenuVisibility();
        Menu menu = new Menu();
        state.attach(menu);
        int lease = state.acquire();
        state.attach(menu);
        assertTrue(menu.hidden);
        assertEquals(1, menu.hides);
        state.release(lease);
        assertFalse(menu.hidden);
    }

    @Test
    public void turningMenuOffDuringCaptureDoesNotReopenIt() {
        FloatingMenuVisibility state = new FloatingMenuVisibility();
        Menu menu = new Menu();
        state.attach(menu);
        int lease = state.acquire();
        state.detach(menu);
        state.release(lease);
        assertEquals(0, menu.restores);
        assertFalse(state.isSuppressed());
    }

    @Test
    public void turningMenuOnDuringCaptureWaitsForLastOwner() {
        FloatingMenuVisibility state = new FloatingMenuVisibility();
        int a = state.acquire(), b = state.acquire();
        Menu menu = new Menu();
        state.attach(menu);
        assertTrue(menu.hidden);
        state.release(a);
        assertTrue(menu.hidden);
        state.release(b);
        assertFalse(menu.hidden);
    }

    @Test
    public void oldMenuClosureCannotDetachReplacement() {
        FloatingMenuVisibility state = new FloatingMenuVisibility();
        Menu old = new Menu(), replacement = new Menu();
        state.attach(old);
        int lease = state.acquire();
        state.detach(old);
        state.attach(replacement);
        state.detach(old);
        state.release(lease);
        assertEquals(0, old.restores);
        assertFalse(replacement.hidden);
        assertEquals(1, replacement.restores);
    }

    @Test
    public void invalidReleaseCannotEndAnotherCapture() {
        FloatingMenuVisibility state = new FloatingMenuVisibility();
        Menu menu = new Menu();
        state.attach(menu);
        int lease = state.acquire();
        state.release(0);
        state.release(-1);
        assertTrue(menu.hidden);
        state.release(lease);
    }
}
