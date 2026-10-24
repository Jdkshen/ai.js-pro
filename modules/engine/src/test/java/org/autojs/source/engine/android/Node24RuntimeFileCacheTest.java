// SPDX-License-Identifier: MIT
package org.autojs.source.engine.android;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Pure JVM tests for the file/metadata half of the Node24 runtime cache. */
public class Node24RuntimeFileCacheTest {
    private static final String APP = "com.test.app:1240:1.0.2:1000:2000";
    private static final String UPDATED_APP = "com.test.app:1241:1.0.3:1000:2001";

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @After
    public void clearReadOnlyForTemporaryFolderCleanup() {
        makeTreeWritable(temporary.getRoot());
    }

    @Test
    public void firstPrepareExtractsThenMetadataHitSkipsHashingAndAssets() throws Exception {
        File directory = new File(temporary.getRoot(), "runtime");
        Map<String, byte[]> assets = bundle("core-1", "libc++-1", "bridge-1");
        Map<String, String> pins = pins(assets);

        Node24RuntimeFileCache.Result first = Node24RuntimeFileCache.prepare(
                directory, APP, pins, source(assets));
        assertFalse(first.stats.cacheHit);
        assertTrue(first.stats.fullVerify);
        assertEquals(3, first.stats.extractFiles);
        assertEquals(0, first.stats.hashFiles);
        assertTrue(first.stats.stateCommitted);
        assertTrue(first.stats.summary(), first.stats.summary().contains("cache=miss"));
        assertTrue(first.stats.summary(), first.stats.summary().contains("extractFiles=3"));
        assertFiles(directory, assets);
        assertNoTemporaryFiles(directory);
        String committedState = read(directory, Node24RuntimeFileCache.STATE_FILE);

        Node24RuntimeFileCache.Result second = Node24RuntimeFileCache.prepare(
                directory, APP, pins, unusedSource());
        assertTrue(second.stats.cacheHit);
        assertFalse(second.stats.fullVerify);
        assertEquals(0, second.stats.hashFiles);
        assertEquals(0, second.stats.extractFiles);
        assertTrue(second.stats.summary(), second.stats.summary().contains("cache=hit"));
        assertEquals("cache hit must not rewrite the state file",
                committedState, read(directory, Node24RuntimeFileCache.STATE_FILE));
        assertFiles(directory, assets);
    }

    @Test
    public void truncatedAndTamperedFilesAreFullyVerifiedAndRecovered() throws Exception {
        File directory = new File(temporary.getRoot(), "runtime");
        Map<String, byte[]> assets = bundle("core-1", "libc++-1", "bridge-1");
        Map<String, String> pins = pins(assets);
        Node24RuntimeFileCache.prepare(directory, APP, pins, source(assets));

        File bridge = new File(directory, "libajsnode24bridge.so");
        makeWritable(bridge);
        try (FileOutputStream output = new FileOutputStream(bridge)) {
            output.write(bytes("tampered"));
        }
        Node24RuntimeFileCache.Result tampered = Node24RuntimeFileCache.prepare(
                directory, APP, pins, source(assets));
        assertFalse(tampered.stats.cacheHit);
        assertTrue(tampered.stats.fullVerify);
        assertEquals("all three pins are checked on a miss", 3, tampered.stats.hashFiles);
        assertEquals(1, tampered.stats.extractFiles);
        assertArrayEquals(assets.get("libajsnode24bridge.so"), readBytes(bridge));
        assertFalse(bridge.canWrite());

        File core = new File(directory, "libnode.so");
        makeWritable(core);
        try (FileOutputStream output = new FileOutputStream(core)) {
            output.write(bytes("truncated"));
        }
        Node24RuntimeFileCache.Result truncated = Node24RuntimeFileCache.prepare(
                directory, APP, pins, source(assets));
        assertFalse(truncated.stats.cacheHit);
        assertEquals(3, truncated.stats.hashFiles);
        assertEquals(1, truncated.stats.extractFiles);
        assertArrayEquals(assets.get("libnode.so"), readBytes(core));
        assertFalse(core.canWrite());
        assertNoTemporaryFiles(directory);
    }

    @Test
    public void changedBridgePinRehashesEveryFileInsteadOfTrustingTheCoreDirectory() throws Exception {
        File directory = new File(temporary.getRoot(), "runtime");
        Map<String, byte[]> original = bundle("core-1", "libc++-1", "bridge-1");
        Map<String, String> oldPins = pins(original);
        Node24RuntimeFileCache.prepare(directory, APP, oldPins, source(original));

        // Only the bridge changes; libnode's SHA and therefore the old directory name are unchanged.
        Map<String, byte[]> updated = bundle("core-1", "libc++-1", "bridge-2");
        Map<String, String> newPins = pins(updated);
        Node24RuntimeFileCache.Result result = Node24RuntimeFileCache.prepare(
                directory, APP, newPins, source(updated));

        assertFalse(result.stats.cacheHit);
        assertEquals("pins-changed", result.stats.reason);
        assertTrue(result.stats.fullVerify);
        assertEquals(3, result.stats.hashFiles);
        assertEquals(1, result.stats.extractFiles);
        assertArrayEquals(updated.get("libajsnode24bridge.so"),
                readBytes(new File(directory, "libajsnode24bridge.so")));
        assertArrayEquals(original.get("libnode.so"), readBytes(new File(directory, "libnode.so")));

        Node24RuntimeFileCache.Result hit = Node24RuntimeFileCache.prepare(
                directory, APP, newPins, unusedSource());
        assertTrue(hit.stats.cacheHit);
    }

    @Test
    public void appInstallIdentityChangeForcesFullVerificationButNotExtraction() throws Exception {
        File directory = new File(temporary.getRoot(), "runtime");
        Map<String, byte[]> assets = bundle("core-1", "libc++-1", "bridge-1");
        Map<String, String> pins = pins(assets);
        Node24RuntimeFileCache.prepare(directory, APP, pins, source(assets));

        Node24RuntimeFileCache.Result result = Node24RuntimeFileCache.prepare(
                directory, UPDATED_APP, pins, unusedSource());
        assertFalse(result.stats.cacheHit);
        assertEquals("app-changed", result.stats.reason);
        assertTrue(result.stats.fullVerify);
        assertEquals(3, result.stats.hashFiles);
        assertEquals(0, result.stats.extractFiles);
        assertTrue(result.stats.stateCommitted);
        assertFiles(directory, assets);

        assertTrue(Node24RuntimeFileCache.prepare(
                directory, UPDATED_APP, pins, unusedSource()).stats.cacheHit);
    }

    @Test
    public void fileMetadataChangeForcesFullVerificationAndRefreshesState() throws Exception {
        File directory = new File(temporary.getRoot(), "runtime");
        Map<String, byte[]> assets = bundle("core-1", "libc++-1", "bridge-1");
        Map<String, String> pins = pins(assets);
        Node24RuntimeFileCache.prepare(directory, APP, pins, source(assets));

        File core = new File(directory, "libnode.so");
        long originalModified = core.lastModified();
        makeWritable(core);
        assertTrue(core.setLastModified(originalModified + 5000));
        assertTrue(core.setReadOnly());
        assertFalse(core.canWrite());

        Node24RuntimeFileCache.Result changed = Node24RuntimeFileCache.prepare(
                directory, APP, pins, unusedSource());
        assertFalse(changed.stats.cacheHit);
        assertEquals("metadata-changed", changed.stats.reason);
        assertTrue(changed.stats.fullVerify);
        assertEquals(3, changed.stats.hashFiles);
        assertEquals("matching content is not needlessly re-extracted", 0,
                changed.stats.extractFiles);
        assertTrue(changed.stats.stateCommitted);

        Node24RuntimeFileCache.Result hit = Node24RuntimeFileCache.prepare(
                directory, APP, pins, unusedSource());
        assertTrue(hit.stats.cacheHit);
    }

    @Test
    public void writableRuntimeFileIsRecoveredFromAssetsToRestoreReadOnlyState() throws Exception {
        File directory = new File(temporary.getRoot(), "runtime");
        Map<String, byte[]> assets = bundle("core-1", "libc++-1", "bridge-1");
        Map<String, String> pins = pins(assets);
        Node24RuntimeFileCache.prepare(directory, APP, pins, source(assets));

        File core = new File(directory, "libnode.so");
        makeWritable(core);
        Node24RuntimeFileCache.Result result = Node24RuntimeFileCache.prepare(
                directory, APP, pins, source(assets));

        assertFalse(result.stats.cacheHit);
        assertEquals("writable", result.stats.reason);
        assertEquals(3, result.stats.hashFiles);
        assertEquals(1, result.stats.extractFiles);
        assertFalse(core.canWrite());
        assertArrayEquals(assets.get("libnode.so"), readBytes(core));
    }

    @Test
    public void invalidStateIsNotTrustedAndIsAtomicallyReplaced() throws Exception {
        File directory = new File(temporary.getRoot(), "runtime");
        Map<String, byte[]> assets = bundle("core-1", "libc++-1", "bridge-1");
        Map<String, String> pins = pins(assets);
        Node24RuntimeFileCache.prepare(directory, APP, pins, source(assets));

        File state = new File(directory, Node24RuntimeFileCache.STATE_FILE);
        makeWritable(state);
        try (FileOutputStream output = new FileOutputStream(state)) {
            output.write(bytes("{not-json"));
        }

        Node24RuntimeFileCache.Result result = Node24RuntimeFileCache.prepare(
                directory, APP, pins, unusedSource());
        assertFalse(result.stats.cacheHit);
        assertEquals("state-invalid", result.stats.reason);
        assertEquals(3, result.stats.hashFiles);
        assertEquals(0, result.stats.extractFiles);
        assertTrue(result.stats.stateCommitted);
        assertNotNull(new JSONObject(read(directory, Node24RuntimeFileCache.STATE_FILE)));
        assertNoTemporaryFiles(directory);

        assertTrue(Node24RuntimeFileCache.prepare(
                directory, APP, pins, unusedSource()).stats.cacheHit);
    }

    @Test
    public void unavailableFileIdentityCannotAuthorizeACacheHit() throws Exception {
        File directory = new File(temporary.getRoot(), "runtime");
        Map<String, byte[]> assets = bundle("core-1", "libc++-1", "bridge-1");
        Map<String, String> pins = pins(assets);
        Node24RuntimeFileCache.prepare(directory, APP, pins, source(assets));
        File state = new File(directory, Node24RuntimeFileCache.STATE_FILE);
        JSONObject root = new JSONObject(read(directory, Node24RuntimeFileCache.STATE_FILE));
        root.getJSONObject("files").getJSONObject("libnode.so").put("fileKey", "");
        try (FileOutputStream output = new FileOutputStream(state)) {
            output.write(bytes(root.toString()));
        }
        Node24RuntimeFileCache.Result result = Node24RuntimeFileCache.prepare(
                directory, APP, pins, unusedSource());
        assertFalse(result.stats.cacheHit);
        assertEquals("file-identity-unavailable", result.stats.reason);
        assertEquals(3, result.stats.hashFiles);
        assertEquals(0, result.stats.extractFiles);
    }

    @Test
    public void failedExtractionLeavesNoStateOrPartFileAndCanRecover() throws Exception {
        File directory = new File(temporary.getRoot(), "runtime");
        Map<String, byte[]> good = bundle("core-1", "libc++-1", "bridge-1");
        Map<String, String> pins = pins(good);
        Map<String, byte[]> badBundle = bundle("core-1", "libc++-1", "wrong-bridge");
        // Keep the expected bridge pin from the good bundle so verification sees a mismatch.
        try {
            Node24RuntimeFileCache.prepare(directory, APP, pins, source(badBundle));
            fail("a bridge whose bytes do not match its pin must not be committed");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("hash mismatch"));
        }
        assertFalse("failed prepare must not publish a cache state",
                new File(directory, Node24RuntimeFileCache.STATE_FILE).exists());
        assertFalse(new File(directory, "libajsnode24bridge.so").exists());
        assertNoTemporaryFiles(directory);

        Node24RuntimeFileCache.Result recovered = Node24RuntimeFileCache.prepare(
                directory, APP, pins, source(good));
        assertFalse(recovered.stats.cacheHit);
        assertTrue(recovered.stats.fullVerify);
        assertEquals(1, recovered.stats.extractFiles);
        assertTrue(recovered.stats.stateCommitted);
        assertFiles(directory, good);
        assertNoTemporaryFiles(directory);
    }

    @Test
    public void pinSetRequiresExactlyTheThreeRuntimeFilesWithLowercaseSha256() throws Exception {
        Map<String, byte[]> assets = bundle("core-1", "libc++-1", "bridge-1");
        JSONObject valid = jsonPins(assets);
        assertEquals(3, Node24RuntimeFileCache.pinsFromJson(valid).size());

        JSONObject missing = jsonPins(assets);
        missing.remove("libajsnode24bridge.so");
        assertInvalidPins(missing);

        JSONObject extra = jsonPins(assets);
        extra.put("extra.so", sha(bytes("extra")));
        assertInvalidPins(extra);

        JSONObject uppercase = jsonPins(assets);
        uppercase.put("libnode.so", sha(bytes("core-1")).toUpperCase(Locale.ROOT));
        assertInvalidPins(uppercase);
    }

    private static void assertInvalidPins(JSONObject value) {
        try {
            Node24RuntimeFileCache.pinsFromJson(value);
            fail("invalid pin set must be rejected");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("pin"));
        }
    }

    private static void assertFiles(File directory, Map<String, byte[]> assets) throws IOException {
        for (String name : Node24RuntimeFileCache.RUNTIME_FILES) {
            File file = new File(directory, name);
            assertTrue(name + " must exist", file.isFile());
            assertArrayEquals(name + " content", assets.get(name), readBytes(file));
            assertFalse(name + " must be read-only", file.canWrite());
        }
    }

    private static void assertNoTemporaryFiles(File directory) {
        File[] files = directory.listFiles();
        assertNotNull(files);
        for (File file : files) {
            String name = file.getName();
            assertFalse("leftover temporary file: " + name,
                    name.endsWith(".part") || name.endsWith(".tmp"));
        }
    }

    private static Map<String, byte[]> bundle(String core, String libcxx, String bridge) {
        Map<String, byte[]> assets = new LinkedHashMap<>();
        assets.put("libc++_shared.so", bytes(libcxx));
        assets.put("libnode.so", bytes(core));
        assets.put("libajsnode24bridge.so", bytes(bridge));
        return assets;
    }

    private static Map<String, String> pins(Map<String, byte[]> assets) throws Exception {
        Map<String, String> pins = new LinkedHashMap<>();
        for (String name : Node24RuntimeFileCache.RUNTIME_FILES) {
            pins.put(name, sha(assets.get(name)));
        }
        return pins;
    }

    private static JSONObject jsonPins(Map<String, byte[]> assets) throws Exception {
        JSONObject pins = new JSONObject();
        for (String name : Node24RuntimeFileCache.RUNTIME_FILES) {
            pins.put(name, sha(assets.get(name)));
        }
        return pins;
    }

    private static Node24RuntimeFileCache.Source source(final Map<String, byte[]> assets) {
        return new Node24RuntimeFileCache.Source() {
            @Override
            public InputStream open(String name) throws IOException {
                byte[] content = assets.get(name);
                if (content == null) throw new IOException("missing test asset: " + name);
                return new ByteArrayInputStream(content);
            }
        };
    }

    private static Node24RuntimeFileCache.Source unusedSource() {
        return new Node24RuntimeFileCache.Source() {
            @Override
            public InputStream open(String name) {
                throw new AssertionError("cache hit/full rehash must not read assets: " + name);
            }
        };
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String sha(byte[] value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        StringBuilder text = new StringBuilder();
        for (byte item : digest.digest(value)) {
            text.append(String.format(Locale.ROOT, "%02x", item & 255));
        }
        return text.toString();
    }

    private static byte[] readBytes(File file) throws IOException {
        return Files.readAllBytes(file.toPath());
    }

    private static String read(File directory, String name) throws IOException {
        return new String(readBytes(new File(directory, name)), StandardCharsets.UTF_8);
    }

    private static void makeWritable(File file) {
        file.setWritable(true);
    }

    private static void makeTreeWritable(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) makeTreeWritable(child);
            }
        }
        file.setWritable(true);
    }
}
