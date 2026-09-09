package com.jdkshen.aijspro.network;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class VersionServiceTest {

    @Test
    public void comparesReleaseVersionsNumericallyAndIgnoresSuffixes() {
        assertEquals(1, VersionService.compareVersions("1.0.10", "1.0.9"));
        assertEquals(-1, VersionService.compareVersions("1.0.0", "1.0.1"));
        assertEquals(0, VersionService.compareVersions("1.0.1-lite", "1.0.1"));
    }
}
