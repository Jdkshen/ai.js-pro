package com.jdkshen.buildtools.plugin.toolchain.core;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.FileInputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CatalogAssetTest {
    @Test public void catalogIsAdvisoryAndHonest() throws Exception {
        JSONObject catalog = ComponentStore.readJson(
                new FileInputStream("src/main/assets/buildtools/catalog.json"));
        assertEquals(1, catalog.getInt("schemaVersion"));
        JSONArray candidates = catalog.getJSONArray("candidates");
        assertEquals(5, candidates.length());
        boolean sawEcj = false, sawClang = false, sawApk = false;
        for (int i = 0; i < candidates.length(); i++) {
            JSONObject candidate = candidates.getJSONObject(i);
            assertTrue(candidate.has("id"));
            if ("ecj".equals(candidate.getString("id"))) sawEcj = candidate.has("verifiedOn");
            if ("android-arm64-clang".equals(candidate.getString("id"))) sawClang = candidate.has("constraints");
            if ("android-apk-packaging".equals(candidate.getString("id"))) sawApk = candidate.has("result");
        }
        assertTrue(sawEcj && sawClang && sawApk);
    }
}
