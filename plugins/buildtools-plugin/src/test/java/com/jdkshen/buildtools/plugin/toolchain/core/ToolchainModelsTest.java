package com.jdkshen.buildtools.plugin.toolchain.core;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ToolchainModelsTest {
    @Test public void parsesDescriptorAndCapabilities() throws Exception {
        JSONObject json = new JSONObject("{\"schemaVersion\":1,\"id\":\"x\",\"version\":\"1\","
                + "\"provider\":\"p\",\"packageName\":\"com.example\",\"abi\":\"arm64-v8a\","
                + "\"minApi\":26,\"license\":\"Apache-2.0\",\"components\":[],"
                + "\"capabilities\":{\"java_source\":{\"state\":\"NOT_INSTALLED\",\"error\":\"missing\"}}}");
        ToolchainDescriptor d = ToolchainDescriptor.parse(json);
        assertEquals("", d.validate());
        assertEquals(CapabilityState.State.NOT_INSTALLED, d.getCapabilities().get("java_source").getState());
        assertTrue(d.toJson().has("capabilities"));
    }
    @Test public void rejectsBadComponentHash() {
        ComponentSpec spec = new ComponentSpec("ecj", "1", "not-a-hash", "https://example/x.jar",
                1, "arm64-v8a", 26, "EPL-2.0", "java/ecj.jar", Arrays.asList("android-stub"));
        assertTrue(spec.validate().contains("sha256"));
    }
    @Test public void invocationAndArtifactContracts() throws Exception {
        ResolvedInvocation invocation = new ResolvedInvocation("/system/bin/sh",
                Arrays.asList("-c", "echo ok"), null, "/tmp", Arrays.asList("dex"));
        assertEquals("", invocation.validate());
        assertTrue(invocation.toJson().has("environment"));
        BuildArtifact artifact = new BuildArtifact("job", "/tmp/out.dex", "dex", 3,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", 1L, true);
        assertEquals("", artifact.validate());
        assertEquals("dex", artifact.toJson().getString("type"));
    }
}
