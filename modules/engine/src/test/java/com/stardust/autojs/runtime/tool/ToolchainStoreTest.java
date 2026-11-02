package com.stardust.autojs.runtime.tool;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ToolchainStoreTest {
    private static final String MANIFEST_PATH = "/meta/manifest.json";
    private static final String ARCHIVE_RELATIVE = "../files/%E5%B7%A5%E5%85%B7%E9%93%BE.zip";
    private static final String ARCHIVE_PATH = "/files/%E5%B7%A5%E5%85%B7%E9%93%BE.zip";

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test(timeout = 2000)
    public void statusAndLaunchSnapshotDoNotWaitForInstallLock() throws Exception {
        File root = temp.newFolder("active-download");
        ToolchainStore store = new ToolchainStore(root);
        try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(
                new File(root, ".install.lock").toPath(),
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
             java.nio.channels.FileLock ignored = channel.lock()) {
            assertFalse(store.status().getBoolean("installed"));
            assertNull(store.activeDirectory());
        }
    }

    @Test
    public void retainsLinuxHeadersDifferingOnlyByCaseOnCaseSensitiveFilesystems() throws Exception {
        File probe = temp.newFolder("case-probe");
        Files.write(new File(probe, "lower").toPath(), new byte[]{1});
        boolean sensitive = !new File(probe, "LOWER").exists();
        ZipBuilder zip = new ZipBuilder();
        zip.file("include/xt_MARK.h", "upper");
        zip.file("include/xt_mark.h", "lower");
        byte[] archive = zip.finish();
        try (FixtureServer server = new FixtureServer()) {
            serve(server, manifest("case-v1", "arm64-v8a", 28, archive,
                    zip.unpackedSize, null, executables()), archive);
            ToolchainStore store = new ToolchainStore(new File(temp.newFolder("case-store"), "store"));
            try {
                store.install(server.url(MANIFEST_PATH), "arm64-v8a", 35, null);
                assertTrue("A case-insensitive filesystem must reject actual collisions", sensitive);
                assertEquals("upper", readText(new File(store.activeDirectory(), "include/xt_MARK.h")));
                assertEquals("lower", readText(new File(store.activeDirectory(), "include/xt_mark.h")));
            } catch (IOException error) {
                if (sensitive) throw error;
                assertFalse(store.status().getBoolean("installed"));
            }
        }
    }

    @Test
    public void installsCppWithQuotedChinesePathsAndPreservedMetadata() throws Exception {
        ZipBuilder zip = new ZipBuilder();
        zip.directory("bin");
        zip.file("bin/clang", "clang-binary");
        zip.file("bin/ld.lld", "lld-binary");
        zip.file("文档/引用 'quoted' 与中文.txt", "你好，工具链\n");
        byte[] archive = zip.finish();

        JSONObject manifest = manifest("r29-arm64-v1", "arm64-v8a", 28,
                archive, zip.unpackedSize, null, executables("bin/clang", "bin/ld.lld"));
        manifest.put("license", new JSONObject().put("name", "Apache-2.0"));
        manifest.put("source", "本地测试源");

        try (FixtureServer server = new FixtureServer()) {
            serve(server, manifest, archive);
            File base = temp.newFolder("中文 根 目录");
            File root = new File(base, "toolchain 商店");
            ToolchainStore store = new ToolchainStore(root);
            assertNull(store.activeDirectory());

            ByteArrayOutputStream log = new ByteArrayOutputStream();
            PrintStream progress = new PrintStream(log, true, "UTF-8");
            JSONObject status = store.install(server.url(MANIFEST_PATH),
                    "arm64-v8a", 34, progress);

            assertTrue(status.getBoolean("installed"));
            assertTrue(status.getBoolean("active"));
            assertEquals("cpp", status.getString("id"));
            assertEquals("r29-arm64-v1", status.getString("version"));
            assertEquals("arm64-v8a", status.getString("abi"));
            assertEquals(2, status.getJSONArray("executables").length());
            assertEquals("Apache-2.0", status.getJSONObject("license").getString("name"));
            assertEquals("本地测试源", status.getString("source"));
            assertTrue(log.toString("UTF-8").contains("字节"));
            assertEquals(1, server.hits(MANIFEST_PATH));
            assertEquals(1, server.hits(ARCHIVE_PATH));

            File active = store.activeDirectory();
            assertNotNull(active);
            assertEquals(new File(new File(root, "versions"), "r29-arm64-v1").getCanonicalFile(),
                    active.getCanonicalFile());
            assertEquals("clang-binary", readText(new File(active, "bin/clang")));
            assertEquals("你好，工具链\n",
                    readText(new File(active, "文档/引用 'quoted' 与中文.txt")));

            if (supportsPosix(active.toPath())) {
                assertTrue(Files.getPosixFilePermissions(
                        new File(active, "bin/clang").toPath())
                        .contains(PosixFilePermission.OWNER_EXECUTE));
                assertTrue(Files.getPosixFilePermissions(
                        new File(active, "bin/ld.lld").toPath())
                        .contains(PosixFilePermission.OWNER_EXECUTE));
                Set<PosixFilePermission> documentPermissions =
                        Files.getPosixFilePermissions(
                                new File(active, "文档/引用 'quoted' 与中文.txt").toPath());
                assertFalse(documentPermissions.contains(PosixFilePermission.OWNER_EXECUTE));
                assertFalse(documentPermissions.contains(PosixFilePermission.GROUP_EXECUTE));
                assertFalse(documentPermissions.contains(PosixFilePermission.OTHERS_EXECUTE));
            }

            JSONObject reread = store.status();
            assertEquals("r29-arm64-v1", reread.getString("version"));
            assertNoTemporaryPaths(root);
        }
    }

    @Test
    public void rejectsAbiAndSdkMismatchWithoutPublishing() throws Exception {
        ZipBuilder zip = new ZipBuilder();
        zip.file("bin/clang", "binary");
        byte[] archive = zip.finish();
        JSONObject manifest = manifest("r29-arm64-v1", "arm64-v8a", 28,
                archive, zip.unpackedSize, null, executables("bin/clang"));

        try (FixtureServer server = new FixtureServer()) {
            serve(server, manifest, archive);
            File root = new File(temp.newFolder("abi-sdk"), "store");
            ToolchainStore store = new ToolchainStore(root);

            try {
                store.install(server.url(MANIFEST_PATH), "x86_64", 34, null);
                fail("ABI mismatch must fail");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("abi"));
            }
            assertFalse(store.status().getBoolean("installed"));
            assertNull(store.activeDirectory());

            try {
                store.install(server.url(MANIFEST_PATH), "arm64-v8a", 27, null);
                fail("SDK mismatch must fail");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("minSdk"));
            }
            assertFalse(store.status().getBoolean("installed"));
            assertNull(store.activeDirectory());
            assertFalse(new File(new File(root, "versions"), "r29-arm64-v1").exists());
        }
    }

    @Test
    public void rejectsDigestMismatchWithoutPublishing() throws Exception {
        ZipBuilder zip = new ZipBuilder();
        zip.file("bin/clang", "binary");
        byte[] archive = zip.finish();
        String wrongSha = repeat('0', 64);
        JSONObject manifest = manifest("r29-arm64-v1", "arm64-v8a", 28,
                archive, zip.unpackedSize, wrongSha, executables("bin/clang"));

        try (FixtureServer server = new FixtureServer()) {
            serve(server, manifest, archive);
            File root = new File(temp.newFolder("digest"), "store");
            ToolchainStore store = new ToolchainStore(root);
            int archiveHits = server.hits(ARCHIVE_PATH);

            try {
                store.install(server.url(MANIFEST_PATH), "arm64-v8a", 34, null);
                fail("SHA-256 mismatch must fail");
            } catch (IOException expected) {
                assertTrue(server.hits(ARCHIVE_PATH) > archiveHits);
            }
            assertFalse(store.status().getBoolean("installed"));
            assertNull(store.activeDirectory());
            assertFalse(new File(new File(root, "versions"), "r29-arm64-v1").exists());
            assertNoTemporaryPaths(root);
        }
    }

    @Test
    public void rejectsTraversalAbsoluteBackslashDriveAndDuplicateEntries() throws Exception {
        try (FixtureServer server = new FixtureServer()) {
            assertUnsafeArchiveRejected(server, "../逃逸.txt", "穿越一", false);
            assertUnsafeArchiveRejected(server, "/绝对.txt", "绝对二", false);
            assertUnsafeArchiveRejected(server, "目录\\反斜杠.txt", "反斜杠三", false);
            assertUnsafeArchiveRejected(server, "C:/磁盘.txt", "盘符四", false);
            assertUnsafeArchiveRejected(server, "bin/clang", "重复五", true);
        }
    }

    @Test
    public void enforcesExpandedAndManifestBounds() throws Exception {
        ZipBuilder zip = new ZipBuilder();
        zip.file("payload/tool.bin", new byte[8192]);
        byte[] archive = zip.finish();

        try (FixtureServer server = new FixtureServer()) {
            File root = new File(temp.newFolder("bounds"), "store");
            ToolchainStore store = new ToolchainStore(root);

            JSONObject underdeclared = manifest("r-bound", "arm64-v8a", 28, archive,
                    zip.unpackedSize - 1, null, executables());
            serve(server, underdeclared, archive);
            int archiveHits = server.hits(ARCHIVE_PATH);
            try {
                store.install(server.url(MANIFEST_PATH), "arm64-v8a", 34, null);
                fail("Under-declared unpackedSize must fail while extracting");
            } catch (IOException expected) {
                assertTrue(server.hits(ARCHIVE_PATH) > archiveHits);
            }
            assertFalse(store.status().getBoolean("installed"));
            assertNull(store.activeDirectory());
            assertNoTemporaryPaths(root);

            JSONObject tooExpanded = manifest("r-bound2", "arm64-v8a", 28, archive,
                    zip.unpackedSize, null, executables());
            tooExpanded.put("unpackedSize", 2147483649L);
            server.put(MANIFEST_PATH, utf8(tooExpanded));
            int beforeExpanded = server.hits(ARCHIVE_PATH);
            try {
                store.install(server.url(MANIFEST_PATH), "arm64-v8a", 34, null);
                fail("unpackedSize above 2GiB must fail");
            } catch (IllegalArgumentException expected) {
                // expected
            }
            assertEquals(beforeExpanded, server.hits(ARCHIVE_PATH));
            assertFalse(store.status().getBoolean("installed"));

            JSONObject tooLargeArchive = manifest("r-bound3", "arm64-v8a", 28, archive,
                    zip.unpackedSize, null, executables());
            tooLargeArchive.put("size", 536870913L);
            server.put(MANIFEST_PATH, utf8(tooLargeArchive));
            int beforeArchive = server.hits(ARCHIVE_PATH);
            try {
                store.install(server.url(MANIFEST_PATH), "arm64-v8a", 34, null);
                fail("archive size above 512MiB must fail");
            } catch (IllegalArgumentException expected) {
                // expected
            }
            assertEquals(beforeArchive, server.hits(ARCHIVE_PATH));
            assertFalse(store.status().getBoolean("installed"));

            JSONObject oversizedManifest = manifest("r-bound4", "arm64-v8a", 28, archive,
                    zip.unpackedSize, null, executables());
            oversizedManifest.put("padding", new String(new char[70000]).replace('\0', 'x'));
            server.put(MANIFEST_PATH, utf8(oversizedManifest));
            try {
                store.install(server.url(MANIFEST_PATH), "arm64-v8a", 34, null);
                fail("manifest above 65536 bytes must fail");
            } catch (IOException expected) {
                // expected
            }
            assertFalse(store.status().getBoolean("installed"));
            assertNull(store.activeDirectory());
            assertNoTemporaryPaths(root);
        }
    }

    @Test
    public void failedUpdateKeepsOldActiveAndChangedSameVersionIsNotDeleted() throws Exception {
        ZipBuilder first = new ZipBuilder();
        first.file("bin/clang", "v1-binary");
        byte[] firstArchive = first.finish();
        JSONObject firstManifest = manifest("r29-arm64-v1", "arm64-v8a", 28,
                firstArchive, first.unpackedSize, null, executables("bin/clang"));

        try (FixtureServer server = new FixtureServer()) {
            File root = new File(temp.newFolder("atomic-update"), "store");
            ToolchainStore store = new ToolchainStore(root);
            serve(server, firstManifest, firstArchive);
            store.install(server.url(MANIFEST_PATH), "arm64-v8a", 34, null);

            File v1Directory = store.activeDirectory();
            assertNotNull(v1Directory);
            File v1Clang = new File(v1Directory, "bin/clang");
            assertEquals("v1-binary", readText(v1Clang));

            ZipBuilder second = new ZipBuilder();
            second.file("bin/clang", "v2-binary");
            byte[] secondArchive = second.finish();
            JSONObject badV2 = manifest("r29-arm64-v2", "arm64-v8a", 28,
                    secondArchive, second.unpackedSize, repeat('0', 64),
                    executables("bin/clang"));
            serve(server, badV2, secondArchive);
            try {
                store.install(server.url(MANIFEST_PATH), "arm64-v8a", 34, null);
                fail("Bad v2 digest must fail");
            } catch (IOException expected) {
                // expected
            }
            assertEquals(v1Directory.getCanonicalFile(),
                    store.activeDirectory().getCanonicalFile());
            assertEquals("v1-binary", readText(v1Clang));

            ZipBuilder changed = new ZipBuilder();
            changed.file("bin/clang", "changed-same-version");
            byte[] changedArchive = changed.finish();
            JSONObject sameVersionChanged = manifest("r29-arm64-v1", "arm64-v8a", 28,
                    changedArchive, changed.unpackedSize, null,
                    executables("bin/clang"));
            serve(server, sameVersionChanged, changedArchive);
            try {
                store.install(server.url(MANIFEST_PATH), "arm64-v8a", 34, null);
                fail("Changed metadata for an installed version must fail");
            } catch (IOException expected) {
                // expected
            }
            assertEquals("v1-binary", readText(v1Clang));
            assertEquals(v1Directory.getCanonicalFile(),
                    store.activeDirectory().getCanonicalFile());

            ZipBuilder goodV2 = new ZipBuilder();
            goodV2.file("bin/clang", "v2-binary-good");
            byte[] goodV2Archive = goodV2.finish();
            JSONObject goodV2Manifest = manifest("r29-arm64-v2", "arm64-v8a", 28,
                    goodV2Archive, goodV2.unpackedSize, null, executables("bin/clang"));
            serve(server, goodV2Manifest, goodV2Archive);
            JSONObject status = store.install(server.url(MANIFEST_PATH),
                    "arm64-v8a", 34, null);

            assertEquals("r29-arm64-v2", status.getString("version"));
            File v2Directory = store.activeDirectory();
            assertNotNull(v2Directory);
            assertNotEquals(v1Directory.getCanonicalFile(), v2Directory.getCanonicalFile());
            assertEquals("v2-binary-good", readText(new File(v2Directory, "bin/clang")));
            assertTrue(v1Directory.isDirectory());
            assertEquals("v1-binary", readText(v1Clang));
            assertNoTemporaryPaths(root);
        }
    }

    @Test
    public void removeClearsActiveAndOnlyDeletesOwnedCppVersions() throws Exception {
        ZipBuilder v1 = new ZipBuilder();
        v1.file("bin/clang", "v1");
        byte[] v1Archive = v1.finish();
        JSONObject v1Manifest = manifest("r29-arm64-v1", "arm64-v8a", 28,
                v1Archive, v1.unpackedSize, null, executables("bin/clang"));

        ZipBuilder v2 = new ZipBuilder();
        v2.file("bin/clang", "v2");
        byte[] v2Archive = v2.finish();
        JSONObject v2Manifest = manifest("r29-arm64-v2", "arm64-v8a", 28,
                v2Archive, v2.unpackedSize, null, executables("bin/clang"));

        try (FixtureServer server = new FixtureServer()) {
            File root = new File(temp.newFolder("remove"), "store");
            ToolchainStore store = new ToolchainStore(root);
            serve(server, v1Manifest, v1Archive);
            store.install(server.url(MANIFEST_PATH), "arm64-v8a", 34, null);
            serve(server, v2Manifest, v2Archive);
            store.install(server.url(MANIFEST_PATH), "arm64-v8a", 34, null);

            File versions = new File(root, "versions");
            File unknown = new File(versions, "not-owned");
            assertTrue(unknown.mkdirs());
            File keep = new File(unknown, "keep.txt");
            Files.write(keep.toPath(), "keep".getBytes(StandardCharsets.UTF_8));

            JSONObject removed = store.remove();
            assertFalse(removed.getBoolean("installed"));
            assertEquals(2, removed.getInt("removedCount"));
            assertNull(store.activeDirectory());
            assertFalse(new File(root, "active.json").exists());
            assertFalse(new File(versions, "r29-arm64-v1").exists());
            assertFalse(new File(versions, "r29-arm64-v2").exists());
            assertTrue(keep.isFile());
            assertEquals("keep", readText(keep));
            assertFalse(store.status().getBoolean("installed"));
            assertNoTemporaryPaths(root);
        }
    }

    @Test
    public void invalidActivePointerCannotEscapeVersions() throws Exception {
        File base = temp.newFolder("active-security");
        File root = new File(base, "store");
        assertTrue(root.mkdirs());
        File outside = new File(base, "outside");
        assertTrue(outside.mkdirs());
        File keep = new File(outside, "keep.txt");
        Files.write(keep.toPath(), "outside".getBytes(StandardCharsets.UTF_8));
        assertTrue(new File(root, "versions").mkdirs());

        JSONObject pointer = new JSONObject();
        pointer.put("schemaVersion", 1);
        pointer.put("id", "cpp");
        pointer.put("installed", true);
        pointer.put("version", "../../outside");
        pointer.put("directory", "../../outside");
        Files.write(new File(root, "active.json").toPath(),
                pointer.toString().getBytes(StandardCharsets.UTF_8));

        ToolchainStore store = new ToolchainStore(root);
        try {
            store.activeDirectory();
            fail("An active pointer outside versions must be rejected");
        } catch (IOException expected) {
            // expected
        }

        store.remove();
        assertFalse(new File(root, "active.json").exists());
        assertTrue(outside.isDirectory());
        assertTrue(keep.isFile());
        assertEquals("outside", readText(keep));
        assertNull(store.activeDirectory());
        assertNoTemporaryPaths(root);
    }

    // ------------------------------------------------------------------
    // Fixture helpers
    // ------------------------------------------------------------------

    private void assertUnsafeArchiveRejected(FixtureServer server, String name,
                                             String label, boolean duplicate)
            throws Exception {
        ZipBuilder zip = new ZipBuilder();
        zip.file(name, "first");
        if (duplicate) {
            zip.file("bin/clanX", "second");
        }
        byte[] archive = zip.finish();
        if (duplicate) {
            // The ZIP writer rejects duplicate names; patch both headers of this fixture.
            byte[] from = "bin/clanX".getBytes(StandardCharsets.UTF_8);
            byte[] to = "bin/clang".getBytes(StandardCharsets.UTF_8);
            int count = 0;
            for (int i = 0; i <= archive.length - from.length; i++) {
                boolean match = true;
                for (int j = 0; j < from.length; j++) if (archive[i+j] != from[j]) { match = false; break; }
                if (match) { System.arraycopy(to, 0, archive, i, to.length); count++; }
            }
            assertEquals(2, count);
        }
        JSONObject manifest = manifest("r-bad", "arm64-v8a", 28, archive,
                zip.unpackedSize, null, duplicate ? executables("bin/clang") : executables());
        serve(server, manifest, archive);

        File caseBase = temp.newFolder("case-" + label);
        File root = new File(caseBase, "store");
        ToolchainStore store = new ToolchainStore(root);
        int archiveHits = server.hits(ARCHIVE_PATH);
        try {
            store.install(server.url(MANIFEST_PATH), "arm64-v8a", 34, null);
            fail("Unsafe archive entry must be rejected: " + name);
        } catch (IOException expected) {
            assertTrue(server.hits(ARCHIVE_PATH) > archiveHits);
        }
        assertNull(store.activeDirectory());
        assertFalse(new File(new File(root, "versions"), "r-bad").exists());
        assertFalse(new File(caseBase, "逃逸.txt").exists());
        assertNoTemporaryPaths(root);
    }

    private static JSONObject manifest(String version, String abi, int minSdk,
                                       byte[] archive, long unpackedSize,
                                       String sha256Override, JSONArray executables) throws org.json.JSONException {
        JSONObject manifest = new JSONObject();
        manifest.put("schemaVersion", 1);
        manifest.put("id", "cpp");
        manifest.put("version", version);
        manifest.put("abi", abi);
        manifest.put("minSdk", minSdk);
        manifest.put("url", ARCHIVE_RELATIVE);
        manifest.put("sha256", sha256Override == null ? sha256(archive) : sha256Override);
        manifest.put("size", (long) archive.length);
        manifest.put("unpackedSize", unpackedSize);
        manifest.put("executables", executables);
        return manifest;
    }

    private static void serve(FixtureServer server, JSONObject manifest, byte[] archive) {
        server.put(MANIFEST_PATH, utf8(manifest));
        server.put(ARCHIVE_PATH, archive);
    }

    private static JSONArray executables(String... names) {
        JSONArray array = new JSONArray();
        for (String name : names) {
            array.put(name);
        }
        return array;
    }

    private static byte[] utf8(JSONObject object) {
        return object.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] result = digest.digest(bytes);
            StringBuilder hex = new StringBuilder(result.length * 2);
            for (byte value : result) {
                hex.append(String.format("%02x", value & 0xff));
            }
            return hex.toString();
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private static String repeat(char value, int count) {
        char[] chars = new char[count];
        for (int i = 0; i < count; i++) {
            chars[i] = value;
        }
        return new String(chars);
    }

    private static String readText(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static boolean supportsPosix(Path path) {
        return path.getFileSystem().supportedFileAttributeViews().contains("posix");
    }

    private static void assertNoTemporaryPaths(File root) {
        File[] children = root.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            String name = child.getName();
            boolean installTemporary = name.startsWith(".download-")
                    || name.startsWith(".stage-")
                    || (name.startsWith(".active-") && name.endsWith(".tmp"));
            assertFalse("leftover temporary path: " + name, installTemporary);
        }
    }

    private static final class ZipBuilder {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final ZipOutputStream zip;
        private long unpackedSize;

        ZipBuilder() throws IOException {
            zip = new ZipOutputStream(buffer, StandardCharsets.UTF_8);
        }

        ZipBuilder directory(String name) throws IOException {
            String entryName = name.endsWith("/") ? name : name + "/";
            zip.putNextEntry(new ZipEntry(entryName));
            zip.closeEntry();
            return this;
        }

        ZipBuilder file(String name, String content) throws IOException {
            return file(name, content.getBytes(StandardCharsets.UTF_8));
        }

        ZipBuilder file(String name, byte[] content) throws IOException {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(content);
            zip.closeEntry();
            unpackedSize += content.length;
            return this;
        }

        byte[] finish() throws IOException {
            zip.close();
            return buffer.toByteArray();
        }
    }

    private static final class FixtureServer implements Closeable {
        private final ServerSocket serverSocket;
        private final Thread thread;
        private final Object lock = new Object();
        private final Map<String, byte[]> routes = new HashMap<>();
        private final Map<String, Integer> hitCounts = new HashMap<>();

        FixtureServer() throws IOException {
            serverSocket = new ServerSocket(0, 50,
                    InetAddress.getByName("127.0.0.1"));
            thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    serve();
                }
            }, "toolchain-fixture-http");
            thread.setDaemon(true);
            thread.start();
        }

        URL url(String path) throws IOException {
            return new URL("http://127.0.0.1:" + serverSocket.getLocalPort() + path);
        }

        void put(String path, byte[] body) {
            synchronized (lock) {
                routes.put(path, body);
            }
        }

        int hits(String path) {
            synchronized (lock) {
                Integer count = hitCounts.get(path);
                return count == null ? 0 : count;
            }
        }

        private void serve() {
            while (!serverSocket.isClosed()) {
                try (Socket socket = serverSocket.accept()) {
                    handle(socket);
                } catch (IOException error) {
                    if (serverSocket.isClosed()) {
                        return;
                    }
                    // A broken response should not prevent subsequent fixture requests.
                }
            }
        }

        private void handle(Socket socket) throws IOException {
            InputStream input = new BufferedInputStream(socket.getInputStream());
            String requestLine = readLine(input);
            if (requestLine == null) {
                return;
            }
            String header;
            do {
                header = readLine(input);
            } while (header != null && !header.isEmpty());

            int firstSpace = requestLine.indexOf(' ');
            int secondSpace = firstSpace < 0 ? -1 : requestLine.indexOf(' ', firstSpace + 1);
            if (firstSpace < 0 || secondSpace < 0) {
                writeResponse(socket, 400, "Bad Request", null);
                return;
            }
            String target = requestLine.substring(firstSpace + 1, secondSpace);
            if (target.startsWith("http://") || target.startsWith("https://")) {
                target = new URL(target).getFile();
            }
            int query = target.indexOf('?');
            if (query >= 0) {
                target = target.substring(0, query);
            }

            byte[] body;
            synchronized (lock) {
                Integer count = hitCounts.get(target);
                hitCounts.put(target, count == null ? 1 : count + 1);
                body = routes.get(target);
            }
            if (body == null) {
                writeResponse(socket, 404, "Not Found", null);
            } else {
                writeResponse(socket, 200, "OK", body);
            }
        }

        private static void writeResponse(Socket socket, int status, String reason,
                                          byte[] body) throws IOException {
            byte[] payload = body == null ? new byte[0] : body;
            String headers = "HTTP/1.1 " + status + " " + reason + "\r\n"
                    + "Content-Length: " + payload.length + "\r\n"
                    + "Content-Type: application/octet-stream\r\n"
                    + "Connection: close\r\n\r\n";
            OutputStream output = socket.getOutputStream();
            output.write(headers.getBytes(StandardCharsets.ISO_8859_1));
            if (payload.length > 0) {
                output.write(payload);
            }
            output.flush();
        }

        private static String readLine(InputStream input) throws IOException {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            boolean sawAny = false;
            int read;
            while ((read = input.read()) != -1) {
                sawAny = true;
                if (read == '\n') {
                    break;
                }
                if (read != '\r') {
                    line.write(read);
                }
                if (line.size() > 16 * 1024) {
                    throw new IOException("HTTP fixture request line too long");
                }
            }
            if (!sawAny) {
                return null;
            }
            return new String(line.toByteArray(), StandardCharsets.ISO_8859_1);
        }

        @Override
        public void close() throws IOException {
            serverSocket.close();
            try {
                thread.join(3000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
