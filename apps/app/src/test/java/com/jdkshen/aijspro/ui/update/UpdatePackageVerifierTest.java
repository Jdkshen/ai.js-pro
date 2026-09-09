package com.jdkshen.aijspro.ui.update;

import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class UpdatePackageVerifierTest {

    @Test
    public void normalizeSha256AcceptsGitHubDigestFormat() throws Exception {
        String hex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        assertEquals(hex, UpdatePackageVerifier.normalizeSha256("SHA256: " + hex.toUpperCase()));
    }

    @Test
    public void normalizeSha256AllowsMissingDigestForOlderReleases() throws Exception {
        assertNull(UpdatePackageVerifier.normalizeSha256(null));
        assertNull(UpdatePackageVerifier.normalizeSha256(""));
    }

    @Test(expected = IOException.class)
    public void normalizeSha256RejectsMalformedDigest() throws Exception {
        UpdatePackageVerifier.normalizeSha256("sha256:not-a-digest");
    }
}
