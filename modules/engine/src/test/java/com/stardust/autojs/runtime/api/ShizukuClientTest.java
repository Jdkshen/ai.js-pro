package com.stardust.autojs.runtime.api;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Targeted JVM coverage for the public diagnostic shape and process-role decision in
 * {@link ShizukuClient}. No Android/Shizuku call is made by these tests.
 */
public class ShizukuClientTest {

    @Test
    public void stateJsonContainsEveryContractKeyAndUnknownsAreNull() throws Exception {
        JSONObject state = ShizukuClient.stateJson(
                true, false, null, null, null, "com.example:script", null);

        assertEquals(7, state.length());
        assertTrue(state.getBoolean("sdkSupported"));
        assertFalse(state.getBoolean("binderAlive"));
        assertTrue(state.isNull("permissionGranted"));
        assertTrue(state.isNull("backendUid"));
        assertTrue(state.isNull("serverVersion"));
        assertEquals("com.example:script", state.getString("processName"));
        assertTrue(state.isNull("lastError"));
    }

    @Test
    public void stateJsonKeepsKnownBackendValuesAndError() throws Exception {
        JSONObject state = ShizukuClient.stateJson(
                true, true, Boolean.TRUE, 2000, 11, "com.example", "temporary error");

        assertTrue(state.getBoolean("sdkSupported"));
        assertTrue(state.getBoolean("binderAlive"));
        assertTrue(state.getBoolean("permissionGranted"));
        assertEquals(2000, state.getInt("backendUid"));
        assertEquals(11, state.getInt("serverVersion"));
        assertEquals("com.example", state.getString("processName"));
        assertEquals("temporary error", state.getString("lastError"));
    }

    @Test
    public void providerRoleIsOnlyTheMainProcess() {
        assertTrue(ShizukuClient.isProviderProcess("com.example", "com.example"));
        assertTrue(ShizukuClient.isProviderProcess("com.example", ""));
        assertFalse(ShizukuClient.isProviderProcess("com.example", "com.example:script"));
        assertFalse(ShizukuClient.isProviderProcess("com.example", "com.example:node24_1"));
        assertFalse(ShizukuClient.isProviderProcess("com.example", "aijs-rish"));
        assertFalse(ShizukuClient.isProviderProcess("", "com.example"));
        assertFalse(ShizukuClient.isProviderProcess(null, "com.example"));
    }
}
