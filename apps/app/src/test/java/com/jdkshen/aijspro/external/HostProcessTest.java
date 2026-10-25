package com.jdkshen.aijspro.external;

import org.junit.Test;
import static org.junit.Assert.*;

public class HostProcessTest {
    @Test public void onlyMainRestoresApplicationServices() {
        String app = "com.jdkshen.aijspro";
        assertTrue(HostProcess.isMain(app, app));
        for (String suffix : new String[]{":script", ":native", ":node24", ":node24_1", ":node24_2", ":node24_3", ":background"})
            assertFalse(suffix, HostProcess.isMain(app, app + suffix));
    }
    @Test public void unknownIdentityKeepsLegacyStartup() {
        assertTrue(HostProcess.isMain("app", ""));
        assertTrue(HostProcess.isMain("app", null));
    }
}
