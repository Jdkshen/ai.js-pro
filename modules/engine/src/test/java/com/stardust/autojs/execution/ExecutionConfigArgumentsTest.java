package com.stardust.autojs.execution;

import org.junit.Test;

import static org.junit.Assert.*;

public class ExecutionConfigArgumentsTest {
    @Test public void nullArgumentRetainsItsKeyAndValue() {
        ExecutionConfig config = new ExecutionConfig();
        config.setArgument("empty", null);
        config.setArgument("zero", 0);
        config.setArgument("disabled", false);
        assertTrue(config.getArguments().containsKey("empty"));
        assertNull(config.getArgument("empty"));
        assertEquals(0, config.getArgument("zero"));
        assertEquals(false, config.getArgument("disabled"));
    }

    @Test public void equalityAndHashIncludeNullArguments() {
        ExecutionConfig a = new ExecutionConfig();
        ExecutionConfig b = new ExecutionConfig();
        a.setArgument("value", null);
        assertNotEquals(a, b);
        b.setArgument("value", null);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }
}
