package com.jdkshen.buildtools.plugin.toolchain.core;

import org.junit.Test;

import java.io.FileInputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ComponentStoreTest {
    @Test public void packagedDescriptorIsValidAndHonest() throws Exception {
        ToolchainDescriptor descriptor = ComponentStore.loadDescriptor(
                new FileInputStream("src/main/assets/buildtools/descriptor.json"));
        assertEquals("", descriptor.validate());
        assertEquals("arm64-v8a", descriptor.getAbi());
        assertEquals(CapabilityState.State.RUNNABLE, descriptor.getCapabilities().get("dex").getState());
        assertEquals(CapabilityState.State.NOT_INSTALLED, descriptor.getCapabilities().get("java_source").getState());
        assertTrue(descriptor.getComponents().isEmpty());
    }
}
