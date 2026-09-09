package com.jdkshen.aijspro.network;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VersionServiceTest {

    @Test
    public void comparesReleaseVersionsNumericallyAndIgnoresSuffixes() {
        assertEquals(1, VersionService.compareVersions("1.0.10", "1.0.9"));
        assertEquals(-1, VersionService.compareVersions("1.0.0", "1.0.1"));
        assertEquals(0, VersionService.compareVersions("1.0.1-lite", "1.0.1"));
    }

    @Test
    public void filtersReleaseAssetsByFlavorAndDeviceAbi() {
        String[] arm64Device = {"arm64-v8a", "armeabi-v7a"};

        assertTrue(VersionService.isCompatibleApkAsset(
                "aijspro-miuix-compat-arm64-v8a.apk", true, arm64Device));
        assertFalse(VersionService.isCompatibleApkAsset(
                "aijspro-miuix-lite-arm64-v8a.apk", true, arm64Device));
        assertFalse(VersionService.isCompatibleApkAsset(
                "aijspro-miuix-compat-x86_64.apk", true, arm64Device));
        assertTrue(VersionService.isCompatibleApkAsset(
                "aijspro-miuix-compat-universal.apk", true, arm64Device));
        assertFalse(VersionService.isCompatibleApkAsset(
                "release-notes.txt", true, arm64Device));
    }
}
