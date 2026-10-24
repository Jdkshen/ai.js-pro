// SPDX-License-Identifier: MIT
package org.autojs.source.engine.android;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.json.JSONObject;

/**
 * Owns the integrity cache for the three bundled Node24 runtime files.
 *
 * <p>The fast path trusts only a state file that matches every SHA pin, the application
 * install identity, the canonical runtime directory and each file's metadata. A missing,
 * malformed or stale state causes a full SHA verification of all files. Content is only
 * replaced by writing a verified {@code .part} file and atomically renaming it over the
 * target, while holding the existing cross-process extraction lock. A process that has
 * already mapped an old library keeps that inode; a later process sees the new one.
 *
 * <p>Filesystem-only by design: callers supply the bundled asset stream. There is no
 * network path in this class.
 */
final class Node24RuntimeFileCache {
    static final String[] RUNTIME_FILES = {
            "libc++_shared.so", "libnode.so", "libajsnode24bridge.so"
    };
    static final String STATE_FILE = ".runtime-cache.json";
    static final int STATE_SCHEMA = 1;

    private static final int BUFFER_SIZE = 65536;
    private static final long MAX_STATE_BYTES = 256L * 1024L;

    @FunctionalInterface
    interface Source {
        InputStream open(String name) throws IOException;
    }

    static final class Stats {
        boolean cacheHit;
        boolean fullVerify;
        boolean stateCommitted;
        boolean stateCommitFailed;
        String reason = "";
        int hashFiles;
        int extractFiles;
        long hashBytes;
        long extractBytes;
        long lockMs;
        long cacheUs;
        long hashMs;
        long extractMs;
        long stateMs;
        long prepareMs;

        String summary() {
            return "cache=" + (cacheHit ? "hit" : "miss")
                    + " reason=" + (reason.isEmpty() ? "unknown" : reason)
                    + " fullVerify=" + fullVerify
                    + " hashFiles=" + hashFiles
                    + " hashBytes=" + hashBytes
                    + " hashMs=" + hashMs
                    + " extractFiles=" + extractFiles
                    + " extractBytes=" + extractBytes
                    + " extractMs=" + extractMs
                    + " cacheUs=" + cacheUs
                    + " lockMs=" + lockMs
                    + " stateMs=" + stateMs
                    + " prepareMs=" + prepareMs
                    + " state=" + stateLabel();
        }

        private String stateLabel() {
            if (cacheHit) return "reused";
            if (stateCommitted) return "committed";
            if (stateCommitFailed) return "failed";
            return "skipped";
        }
    }

    static final class Result {
        final File directory;
        final Stats stats;

        private Result(File directory, Stats stats) {
            this.directory = directory;
            this.stats = stats;
        }
    }

    private static final class Metadata {
        final String path;
        final long size;
        final long modified;
        final boolean readOnly;
        final String fileKey;

        Metadata(String path, long size, long modified, boolean readOnly, String fileKey) {
            this.path = path;
            this.size = size;
            this.modified = modified;
            this.readOnly = readOnly;
            this.fileKey = fileKey;
        }
    }

    private static final class State {
        final String directory;
        final Map<String, Metadata> files;

        State(String directory, Map<String, Metadata> files) {
            this.directory = directory;
            this.files = files;
        }
    }

    private Node24RuntimeFileCache() {
    }

    /**
     * Validate the complete runtime pin set. Unknown or missing entries are rejected rather
     * than silently ignored: the loader knows exactly these three files.
     */
    static Map<String, String> pinsFromJson(JSONObject runtimeJson) throws IOException {
        if (runtimeJson == null || runtimeJson.length() != RUNTIME_FILES.length) {
            throw new IOException("Invalid Node runtime pin set");
        }
        LinkedHashMap<String, String> pins = new LinkedHashMap<>();
        for (String name : RUNTIME_FILES) {
            Object value = runtimeJson.opt(name);
            if (!(value instanceof String)
                    || !((String) value).matches("[0-9a-f]{64}")) {
                throw new IOException("Invalid Node runtime pin: " + name);
            }
            pins.put(name, (String) value);
        }
        return Collections.unmodifiableMap(pins);
    }

    static Result prepare(File requestedDirectory, String appIdentity, Map<String, String> pins,
                          Source source) throws IOException {
        if (requestedDirectory == null || appIdentity == null || appIdentity.isEmpty()
                || source == null) {
            throw new IOException("Invalid Node runtime cache arguments");
        }
        validatePins(pins);

        long prepareStarted = System.nanoTime();
        Stats stats = new Stats();
        File directory = requestedDirectory.getCanonicalFile();
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Cannot create Node runtime directory");
        }
        if (!directory.isDirectory()) {
            throw new IOException("Cannot create Node runtime directory");
        }

        long lockStarted = System.nanoTime();
        try (RandomAccessFile lockFile = new RandomAccessFile(new File(directory, ".extract.lock"), "rw");
             FileLock ignored = lockFile.getChannel().lock()) {
            stats.lockMs = elapsedMs(lockStarted);

            long cacheStarted = System.nanoTime();
            State state = readState(directory, appIdentity, pins, stats);
            boolean cacheHit = false;
            if (state != null) {
                try {
                    cacheHit = metadataMatches(state, stats);
                } catch (IOException | RuntimeException cacheCheckFailure) {
                    // An unreadable cache must not fail a runtime that can still be verified.
                    stats.reason = "cache-check-failed";
                }
            }
            stats.cacheUs = elapsedUs(cacheStarted);
            if (cacheHit) {
                stats.cacheHit = true;
                stats.reason = "hit";
                stats.prepareMs = elapsedMs(prepareStarted);
                return new Result(directory, stats);
            }

            stats.fullVerify = true;
            verifyAndRecover(directory, pins, source, stats);
            writeState(directory, appIdentity, pins, stats);
            stats.prepareMs = elapsedMs(prepareStarted);
            return new Result(directory, stats);
        }
    }

    private static void validatePins(Map<String, String> pins) throws IOException {
        if (pins == null || pins.size() != RUNTIME_FILES.length) {
            throw new IOException("Invalid Node runtime pin set");
        }
        for (String name : RUNTIME_FILES) {
            String value = pins.get(name);
            if (value == null || !value.matches("[0-9a-f]{64}")) {
                throw new IOException("Invalid Node runtime pin: " + name);
            }
        }
    }

    private static State readState(File directory, String appIdentity,
                                   Map<String, String> pins, Stats stats) {
        File stateFile = new File(directory, STATE_FILE);
        if (!stateFile.isFile()) {
            stats.reason = "state-missing";
            return null;
        }
        try {
            JSONObject root = new JSONObject(new String(readLimited(stateFile, MAX_STATE_BYTES),
                    StandardCharsets.UTF_8));
            if (root.optInt("schema", -1) != STATE_SCHEMA) {
                throw new IOException("Unsupported cache schema");
            }
            if (!root.has("app") || !appIdentity.equals(root.getString("app"))) {
                stats.reason = "app-changed";
                return null;
            }
            String cachedDirectory = root.getString("directory");
            if (!directory.getPath().equals(cachedDirectory)) {
                throw new IOException("Cache directory changed");
            }
            JSONObject cachedPins = root.optJSONObject("pins");
            if (!samePins(cachedPins, pins)) {
                stats.reason = "pins-changed";
                return null;
            }
            JSONObject cachedFiles = root.optJSONObject("files");
            if (cachedFiles == null || cachedFiles.length() != RUNTIME_FILES.length) {
                throw new IOException("Cache file set is incomplete");
            }
            LinkedHashMap<String, Metadata> files = new LinkedHashMap<>();
            for (String name : RUNTIME_FILES) {
                JSONObject item = cachedFiles.optJSONObject(name);
                if (item == null) throw new IOException("Cache file metadata missing");
                files.put(name, new Metadata(
                        item.getString("path"),
                        item.getLong("size"),
                        item.getLong("mtime"),
                        item.getBoolean("readonly"),
                        item.getString("fileKey")));
            }
            return new State(cachedDirectory, files);
        } catch (Exception invalidState) {
            stats.reason = "state-invalid";
            return null;
        }
    }

    private static boolean samePins(JSONObject cachedPins, Map<String, String> pins) {
        if (cachedPins == null || cachedPins.length() != pins.size()) return false;
        for (String name : RUNTIME_FILES) {
            String value = pins.get(name);
            if (!cachedPins.has(name) || !value.equals(cachedPins.optString(name, null))) {
                return false;
            }
        }
        return true;
    }

    private static boolean metadataMatches(State state, Stats stats) throws IOException {
        for (String name : RUNTIME_FILES) {
            File file = new File(state.directory, name);
            Metadata metadata = state.files.get(name);
            if (metadata == null || Files.isSymbolicLink(file.toPath()) || !file.isFile()) {
                stats.reason = "file-missing";
                return false;
            }
            if (metadata.fileKey.isEmpty()) {
                stats.reason = "file-identity-unavailable";
                return false;
            }
            if (file.canWrite() || !metadata.readOnly) {
                stats.reason = "writable";
                return false;
            }
            String canonical = file.getCanonicalPath();
            if (!canonical.equals(metadata.path)
                    || file.length() != metadata.size
                    || file.lastModified() != metadata.modified
                    || !fileKey(file).equals(metadata.fileKey)) {
                stats.reason = "metadata-changed";
                return false;
            }
        }
        return true;
    }

    private static void verifyAndRecover(File directory, Map<String, String> pins,
                                         Source source, Stats stats) throws IOException {
        for (String name : RUNTIME_FILES) {
            File target = new File(directory, name);
            String expected = pins.get(name);
            boolean recover = true;
            if (!Files.isSymbolicLink(target.toPath()) && target.isFile()) {
                long hashStarted = System.nanoTime();
                String actual = null;
                try {
                    actual = digest(target);
                    stats.hashFiles++;
                    stats.hashBytes += target.length();
                } catch (IOException unreadable) {
                    // A file that cannot be read is recovered from the bundled asset.
                }
                stats.hashMs += elapsedMs(hashStarted);
                if (expected.equals(actual) && !target.canWrite()) {
                    recover = false;
                }
            }
            if (recover) {
                long extractStarted = System.nanoTime();
                long extractedBytes = extractFile(directory, name, expected, source);
                stats.extractMs += elapsedMs(extractStarted);
                stats.extractFiles++;
                stats.extractBytes += extractedBytes;
            }
        }
    }

    private static long extractFile(File directory, String name, String expected,
                                    Source source) throws IOException {
        File target = new File(directory, name);
        File temporary = new File(directory, name + ".part");
        deleteIfExists(temporary);
        long copied = 0;
        try {
            MessageDigest hash = sha256();
            try (InputStream input = source.open(name)) {
                if (input == null) throw new IOException("Node runtime asset missing: " + name);
                try (FileOutputStream output = new FileOutputStream(temporary)) {
                    byte[] buffer = new byte[BUFFER_SIZE];
                    int size;
                    while ((size = input.read(buffer)) != -1) {
                        output.write(buffer, 0, size);
                        hash.update(buffer, 0, size);
                        copied += size;
                    }
                    output.getFD().sync();
                }
            }
            if (!toHex(hash.digest()).equals(expected)) {
                throw new IOException("Node runtime hash mismatch: " + name);
            }
            if (!temporary.setReadOnly()) {
                throw new IOException("Cannot make runtime read-only");
            }
            replaceAtomically(temporary, target);
            return copied;
        } catch (IOException | RuntimeException error) {
            deleteQuietly(temporary);
            throw error;
        }
    }

    private static void writeState(File directory, String appIdentity,
                                   Map<String, String> pins, Stats stats) {
        long started = System.nanoTime();
        File temporary = new File(directory, STATE_FILE + "."
                + Long.toHexString(System.nanoTime()) + ".tmp");
        boolean committed = false;
        try {
            JSONObject root = new JSONObject();
            root.put("schema", STATE_SCHEMA);
            root.put("app", appIdentity);
            root.put("directory", directory.getPath());

            JSONObject jsonPins = new JSONObject();
            for (String name : RUNTIME_FILES) jsonPins.put(name, pins.get(name));
            root.put("pins", jsonPins);

            JSONObject jsonFiles = new JSONObject();
            for (String name : RUNTIME_FILES) {
                File file = new File(directory, name);
                Metadata metadata = metadataOf(file);
                JSONObject item = new JSONObject();
                item.put("path", metadata.path);
                item.put("size", metadata.size);
                item.put("mtime", metadata.modified);
                item.put("readonly", metadata.readOnly);
                item.put("fileKey", metadata.fileKey);
                jsonFiles.put(name, item);
            }
            root.put("files", jsonFiles);

            byte[] data = (root.toString(2) + "\n").getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                output.write(data);
                output.getFD().sync();
            }
            replaceAtomically(temporary, new File(directory, STATE_FILE));
            committed = true;
            stats.stateCommitted = true;
        } catch (Exception writeFailure) {
            // Caching is an optimization: a failed state commit must not prevent loading a
            // fully verified runtime. The next process sees no/invalid state and re-verifies.
            stats.stateCommitFailed = true;
        } finally {
            if (!committed) deleteQuietly(temporary);
            stats.stateMs = elapsedMs(started);
        }
    }

    private static Metadata metadataOf(File file) throws IOException {
        if (!file.isFile()) throw new IOException("Runtime file missing");
        return new Metadata(file.getCanonicalPath(), file.length(), file.lastModified(),
                !file.canWrite(), fileKey(file));
    }

    private static String fileKey(File file) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(file.toPath(),
                    BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            Object key = attributes.fileKey();
            // Windows NIO can return no fileKey. Birth time is an additional metadata
            // identity there; it is not a cryptographic guarantee against same-UID edits.
            String identity = key == null ? "" : "key=" + key;
            if (attributes.creationTime().toMillis() > 0) {
                identity += "|created=" + attributes.creationTime();
            }
            try {
                Object changed = Files.getAttribute(file.toPath(), "unix:ctime",
                        LinkOption.NOFOLLOW_LINKS);
                if (changed != null) identity += "|changed=" + changed;
            } catch (IOException | RuntimeException unavailable) {
                // This optional Unix attribute is not supported by every filesystem.
            }
            return identity;
        } catch (IOException | RuntimeException unsupported) {
            return "";
        }
    }

    /**
     * Same-directory replacement so readers can never observe a partial state file. If a
     * filesystem rejects an atomic exchange over a read-only target (not expected on Android),
     * clear only the read-only attribute and retry; contents of the old target are untouched
     * until the rename succeeds.
     */
    private static void replaceAtomically(File source, File target) throws IOException {
        try {
            moveReplacing(source, target);
        } catch (IOException firstFailure) {
            boolean targetWasReadOnly = target.isFile() && !target.canWrite();
            if (!targetWasReadOnly || !target.setWritable(true)) throw firstFailure;
            try {
                moveReplacing(source, target);
            } catch (IOException retryFailure) {
                target.setReadOnly();
                throw retryFailure;
            }
        }
    }

    private static void moveReplacing(File source, File target) throws IOException {
        Path from = source.toPath();
        Path to = target.toPath();
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static byte[] readLimited(File file, long limit) throws IOException {
        if (file.length() > limit) throw new IOException("State file too large");
        try (InputStream input = new FileInputStream(file)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int size;
            while ((size = input.read(buffer)) != -1) {
                output.write(buffer, 0, size);
                if (output.size() > limit) throw new IOException("State file too large");
            }
            return output.toByteArray();
        }
    }

    private static String digest(File file) throws IOException {
        MessageDigest hash = sha256();
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int size;
            while ((size = input.read(buffer)) != -1) hash.update(buffer, 0, size);
        }
        return toHex(hash.digest());
    }

    private static MessageDigest sha256() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 unavailable", impossible);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            text.append(String.format(Locale.ROOT, "%02x", value & 255));
        }
        return text.toString();
    }

    private static void deleteIfExists(File file) throws IOException {
        if (!file.exists()) return;
        if (!file.delete() && !(file.setWritable(true) && file.delete())) {
            throw new IOException("Cannot remove incomplete runtime file");
        }
    }

    private static void deleteQuietly(File file) {
        if (file.exists() && !file.delete()) {
            // A failed move can leave a read-only .part on Windows. Never hide the
            // primary failure, but make a best effort to remove the incomplete file.
            file.setWritable(true);
            file.delete();
        }
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1000000L;
    }

    private static long elapsedUs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1000L;
    }
}
