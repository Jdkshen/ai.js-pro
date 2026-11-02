package com.stardust.autojs.runtime.tool;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * On-demand storage for the optional native C/C++ toolchain.
 *
 * <p>The store never executes files from an archive. It validates an archive by its
 * manifest SHA-256, extracts only regular files under a private staging directory,
 * activates a version only after extraction completes, and keeps the previous active
 * version intact when an install or active-pointer update fails.</p>
 */
public final class ToolchainStore {
    public static final String COMPONENT_ID = "cpp";

    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_MANIFEST_BYTES = 64 * 1024;
    private static final long MAX_ARCHIVE_BYTES = 512L * 1024L * 1024L;
    private static final long MAX_EXPANDED_BYTES = 2L * 1024L * 1024L * 1024L;
    private static final int MAX_ENTRY_COUNT = 50_000;
    private static final int MAX_EXECUTABLES = 64;
    private static final int MAX_ENTRY_PATH_CHARS = 4096;
    private static final int MAX_URL_CHARS = 8192;
    private static final int MAX_ACTIVE_BYTES = 256 * 1024;
    private static final int MAX_METADATA_BYTES = 256 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final int MAX_REDIRECTS = 5;

    private static final String VERSIONS_DIR = "versions";
    private static final String ACTIVE_FILE = "active.json";
    private static final String LOCK_FILE = ".install.lock";
    private static final String METADATA_FILE = ".toolchain.json";
    private static final String DOWNLOAD_PREFIX = ".download-";
    private static final String STAGE_PREFIX = ".stage-";
    private static final String ACTIVE_TEMP_PREFIX = ".active-";
    private static final String ACTIVE_TEMP_SUFFIX = ".tmp";

    private static final String[] STATUS_FIELDS = new String[]{
            "version", "abi", "minSdk", "sha256", "size", "unpackedSize",
            "executables", "license", "source", "installedAt"
    };

    private static final Pattern VERSION_PATTERN =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final Pattern ABI_PATTERN =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,31}");
    private static final Pattern SHA256_PATTERN =
            Pattern.compile("[0-9a-f]{64}");
    private static final Pattern WINDOWS_RESERVED =
            Pattern.compile("(?i)CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]");

    /**
     * Locking a FileChannel is enough across processes. This map additionally keeps two
     * ToolchainStore instances in the same JVM from hitting OverlappingFileLockException.
     */
    private static final Map<String, Object> JVM_LOCKS = new HashMap<>();

    private final File root;
    private final Path rootPath;
    private final Object monitor;

    public ToolchainStore(File root) {
        if (root == null) {
            throw new IllegalArgumentException("toolchain root 不能为空");
        }
        Path candidate = root.getAbsoluteFile().toPath().normalize();
        if (candidate.getNameCount() == 0) {
            throw new IllegalArgumentException("toolchain root 不能是文件系统根目录");
        }

        File validatedRoot;
        Path validatedPath;
        try {
            checkRootPath(candidate);
            File canonical = candidate.toFile().getCanonicalFile();
            Path canonicalPath = canonical.toPath().normalize();
            if (canonicalPath.getNameCount() == 0) {
                throw new IllegalArgumentException("toolchain root 不能是文件系统根目录");
            }
            checkRootPath(canonicalPath);
            if (Files.exists(canonicalPath, LinkOption.NOFOLLOW_LINKS)
                    && (!Files.isDirectory(canonicalPath, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(canonicalPath))) {
                throw new IllegalArgumentException("toolchain root 必须是普通目录且不能是符号链接");
            }
            validatedRoot = canonical;
            validatedPath = canonicalPath;
        } catch (IOException | IllegalArgumentException error) {
            throw new IllegalArgumentException("toolchain root 无效: " + error.getMessage(), error);
        }

        this.root = validatedRoot;
        this.rootPath = validatedPath;
        synchronized (JVM_LOCKS) {
            String key = validatedPath.toString();
            Object existing = JVM_LOCKS.get(key);
            if (existing == null) {
                existing = new Object();
                JVM_LOCKS.put(key, existing);
            }
            this.monitor = existing;
        }
    }

    /**
     * Describes the active component from a lock-free snapshot. Reading a status must
     * not wait for a large download or extraction holding the install lock. The method
     * returns an object with {@code installed} false when no active pointer exists.
     */
    public JSONObject status() throws Exception {
        if (!hasReadableRoot()) {
            return emptyStatus();
        }
        return statusLocked();
    }

    /**
     * Returns the active version directory from a lock-free snapshot, or null when no
     * active version exists. This is intentionally independent of the install lock.
     */
    public File activeDirectory() throws Exception {
        if (!hasReadableRoot()) {
            return null;
        }
        ActiveRecord active = readActiveLocked();
        return active == null ? null : active.directory.toFile();
    }

    /**
     * Installs or activates the single {@code cpp} component.
     *
     * <p>The manifest and archive are fetched over HTTP(S) only. A relative manifest URL
     * is resolved against {@code manifestUrl}. The archive is written to a private
     * temporary file while its declared byte count and SHA-256 are checked, then extracted
     * to a private sibling directory. No downloaded file is executed.</p>
     */
    public JSONObject install(final URL manifestUrl, final String abi, final int sdk,
                              final PrintStream progress) throws Exception {
        if (manifestUrl == null) {
            throw new IllegalArgumentException("manifestUrl 不能为空");
        }
        if (abi == null) {
            throw new IllegalArgumentException("abi 不能为空");
        }
        validateAbi(abi, "abi");
        if (sdk < 1) {
            throw new IllegalArgumentException("sdk 必须大于 0");
        }
        requireHttpUrl(manifestUrl);

        JSONObject manifestJson = fetchManifest(manifestUrl, progress);
        final ParsedManifest manifest = parseManifest(manifestUrl, manifestJson, abi, sdk);
        return withLock(new LockedAction<JSONObject>() {
            @Override
            public JSONObject run() throws Exception {
                return installLocked(manifest, progress);
            }
        });
    }

    /**
     * Clears the active pointer first, then deletes only version directories whose
     * metadata identifies this {@code cpp} component. Unknown directories inside
     * {@code versions} are left untouched. Temporary paths with this store's reserved
     * prefixes are also removed.
     */
    public JSONObject remove() throws Exception {
        return withLock(new LockedAction<JSONObject>() {
            @Override
            public JSONObject run() throws Exception {
                return removeLocked();
            }
        });
    }

    // ------------------------------------------------------------------
    // Locking and root containment
    // ------------------------------------------------------------------

    private <T> T withLock(LockedAction<T> action) throws Exception {
        synchronized (monitor) {
            ensureRootDirectory();
            checkRootPath(rootPath);
            Path lockPath = rootPath.resolve(LOCK_FILE);
            if (Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)
                    && (Files.isSymbolicLink(lockPath)
                    || !Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS))) {
                throw new IOException("toolchain 锁文件不是普通文件");
            }
            try (FileChannel channel = FileChannel.open(lockPath,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                checkRootPath(rootPath);
                if (Files.isSymbolicLink(lockPath)) {
                    throw new IOException("toolchain 锁文件不能是符号链接");
                }
                return action.run();
            }
        }
    }

    private void ensureRootDirectory() throws IOException {
        checkRootPath(rootPath);
        if (!Files.exists(rootPath, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(rootPath);
        }
        checkRootPath(rootPath);
        if (!Files.isDirectory(rootPath, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(rootPath)) {
            throw new IOException("toolchain root 不是安全的目录");
        }
    }

    /**
     * Read-only root validation for status/activeDirectory. A missing root means an empty
     * snapshot and must not be created or locked.
     */
    private boolean hasReadableRoot() throws IOException {
        if (!Files.exists(rootPath, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        checkRootPath(rootPath);
        if (Files.isSymbolicLink(rootPath)
                || !Files.isDirectory(rootPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("toolchain root 不是安全的目录");
        }
        return true;
    }

    /**
     * Rejects a filesystem root and any symbolic link in the root's path. Missing final
     * components are allowed; every existing prefix must be a real directory.
     */
    private static void checkRootPath(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        if (absolute.getNameCount() == 0) {
            throw new IOException("toolchain root 不能是文件系统根目录");
        }
        Path current = absolute.getRoot();
        if (current == null) {
            throw new IOException("toolchain root 必须是绝对路径");
        }
        for (Path part : absolute) {
            current = current.resolve(part);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(current)) {
                    throw new IOException("toolchain root 路径不能包含符号链接: " + current);
                }
                if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("toolchain root 路径组件不是目录: " + current);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Status and active pointer
    // ------------------------------------------------------------------

    private JSONObject statusLocked() throws Exception {
        JSONObject result = emptyStatus();
        ActiveRecord active = readActiveLocked();
        if (active == null) {
            return result;
        }
        result.put("installed", true);
        result.put("active", true);
        for (String field : STATUS_FIELDS) {
            if (active.json.has(field)) {
                result.put(field, active.json.get(field));
            }
        }
        result.put("directory", active.directory.toAbsolutePath().toString());
        return result;
    }

    private JSONObject emptyStatus() throws JSONException {
        JSONObject result = new JSONObject();
        result.put("schemaVersion", SCHEMA_VERSION);
        result.put("id", COMPONENT_ID);
        result.put("component", COMPONENT_ID);
        result.put("root", root.getPath());
        result.put("installed", false);
        result.put("active", false);
        return result;
    }

    private ActiveRecord readActiveLocked() throws Exception {
        Path activePath = rootPath.resolve(ACTIVE_FILE);
        if (!Files.exists(activePath, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        if (Files.isSymbolicLink(activePath)) {
            throw new IOException("active.json 不能是符号链接");
        }
        if (!Files.isRegularFile(activePath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("active.json 不是普通文件");
        }
        JSONObject json = readJsonFile(activePath, MAX_ACTIVE_BYTES, "active.json");
        if (!json.optBoolean("installed", true)) {
            return null;
        }
        if (!COMPONENT_ID.equals(json.optString("id", ""))) {
            throw new IOException("active.json 的组件标识不是 cpp");
        }

        String version;
        try {
            version = validateVersion(json.optString("version", ""));
        } catch (IllegalArgumentException error) {
            throw new IOException("active.json 的版本标识无效", error);
        }
        String expectedDirectory = VERSIONS_DIR + "/" + version;
        if (!expectedDirectory.equals(json.optString("directory", ""))) {
            throw new IOException("active.json 的目录指针无效");
        }

        Path versions = rootPath.resolve(VERSIONS_DIR);
        if (!Files.isDirectory(versions, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(versions)) {
            throw new IOException("active.json 指向的 versions 目录不存在或不安全");
        }
        Path versionDirectory = versions.resolve(version).normalize();
        if (!versions.equals(versionDirectory.getParent())) {
            throw new IOException("active.json 的版本目录越界");
        }
        if (!Files.isDirectory(versionDirectory, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(versionDirectory)) {
            throw new IOException("active.json 指向的版本目录不存在或不安全");
        }

        Path versionsReal = versions.toRealPath();
        Path directoryReal = versionDirectory.toRealPath();
        if (!versionsReal.equals(directoryReal.getParent())) {
            throw new IOException("active.json 的版本目录不在 versions 内");
        }
        return new ActiveRecord(json, versionDirectory);
    }

    // ------------------------------------------------------------------
    // Install
    // ------------------------------------------------------------------

    private JSONObject installLocked(ParsedManifest manifest, PrintStream progress) throws Exception {
        int cleanedTemporaryPaths = cleanupTemporaryPathsLocked();
        if (cleanedTemporaryPaths > 0) {
            progress(progress, "清理 " + cleanedTemporaryPaths + " 个中断暂存路径");
        }

        Path versions = rootPath.resolve(VERSIONS_DIR);
        ensureDirectory(versions);
        Path versionDirectory = versions.resolve(manifest.version).normalize();
        if (!versions.equals(versionDirectory.getParent())) {
            throw new IOException("版本目录越界");
        }

        if (Files.exists(versionDirectory, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(versionDirectory)
                    || !Files.isDirectory(versionDirectory, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("版本目录不是安全的普通目录: " + manifest.version);
            }
            JSONObject installed = readMetadata(versionDirectory);
            if (!sameManifestMetadata(manifest.json, installed)) {
                throw new IOException("版本 " + manifest.version
                        + " 已存在但元数据不一致；拒绝覆盖已安装版本");
            }
            verifyExecutables(versionDirectory, manifest.executables);
            JSONObject active = activationRecord(installed);
            writeActiveLocked(active, manifest.version);
            progress(progress, "复用已安装的 cpp " + manifest.version);
            return statusLocked();
        }

        Path download = null;
        Path stage = null;
        Path movedDirectory = null;
        boolean committed = false;
        try {
            download = downloadArchiveLocked(manifest, progress);
            stage = createUniqueDirectory(rootPath, STAGE_PREFIX);
            extractArchiveLocked(download, stage, manifest);
            verifyExecutables(stage, manifest.executables);
            makeExecutables(stage, manifest.executables);

            JSONObject metadata = buildMetadata(manifest);
            writeDurableFile(stage.resolve(METADATA_FILE), jsonBytes(metadata));

            if (Files.exists(versionDirectory, LinkOption.NOFOLLOW_LINKS)) {
                throw new FileAlreadyExistsException(versionDirectory.toString());
            }
            moveNewDirectory(stage, versionDirectory);
            movedDirectory = versionDirectory;
            stage = null;

            writeActiveLocked(activationRecord(metadata), manifest.version);
            committed = true;
            progress(progress, "已安装 cpp " + manifest.version + " (" + manifest.size + " 字节)");
            return statusLocked();
        } finally {
            if (download != null) {
                deleteQuietly(download);
            }
            if (stage != null) {
                deleteQuietly(stage);
            }
            if (movedDirectory != null && !committed) {
                // The version directory was created by this invocation. The previous
                // active pointer and any older version directories were not touched.
                deleteQuietly(movedDirectory);
            }
        }
    }

    private JSONObject removeLocked() throws Exception {
        // Clear the pointer before removing content so a crash cannot leave a pointer to
        // a partially deleted component. A symbolic link itself is removed, never followed.
        Path activePath = rootPath.resolve(ACTIVE_FILE);
        if (Files.exists(activePath, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isDirectory(activePath, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isSymbolicLink(activePath)) {
                throw new IOException("active.json 是目录，无法清理");
            }
            Files.delete(activePath);
        }

        int cleanupCount = cleanupTemporaryPathsLocked();
        List<String> removedVersions = new ArrayList<>();
        Path versions = rootPath.resolve(VERSIONS_DIR);
        if (Files.exists(versions, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(versions)
                    || !Files.isDirectory(versions, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("versions 不是安全的普通目录，拒绝递归删除");
            }
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(versions)) {
                for (Path child : stream) {
                    String name = child.getFileName().toString();
                    if (!Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)
                            || Files.isSymbolicLink(child)) {
                        continue;
                    }
                    JSONObject metadata;
                    try {
                        metadata = readMetadata(child);
                    } catch (Exception notOwnedOrInvalid) {
                        continue;
                    }
                    if (COMPONENT_ID.equals(metadata.optString("id", ""))
                            && name.equals(metadata.optString("version", ""))) {
                        safeDeleteTree(child, rootPath);
                        removedVersions.add(name);
                    }
                }
            }
        }
        Collections.sort(removedVersions);

        JSONObject result = emptyStatus();
        JSONArray removed = new JSONArray();
        for (String version : removedVersions) {
            removed.put(version);
        }
        result.put("removed", removed);
        result.put("removedCount", removedVersions.size());
        result.put("cleanedTemporaryPaths", cleanupCount);
        return result;
    }

    private int cleanupTemporaryPathsLocked() throws IOException {
        int count = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(rootPath)) {
            for (Path child : stream) {
                String name = child.getFileName().toString();
                if (name.startsWith(DOWNLOAD_PREFIX)
                        || name.startsWith(STAGE_PREFIX)
                        || (name.startsWith(ACTIVE_TEMP_PREFIX)
                        && name.endsWith(ACTIVE_TEMP_SUFFIX))) {
                    safeDeleteTree(child, rootPath);
                    count++;
                }
            }
        }
        return count;
    }

    private void writeActiveLocked(JSONObject active, String expectedVersion) throws Exception {
        Path activePath = rootPath.resolve(ACTIVE_FILE);
        if (Files.exists(activePath, LinkOption.NOFOLLOW_LINKS)
                && (Files.isSymbolicLink(activePath)
                || !Files.isRegularFile(activePath, LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException("active.json 不是普通文件，拒绝替换");
        }

        byte[] oldBytes = Files.exists(activePath, LinkOption.NOFOLLOW_LINKS)
                ? readFileBytes(activePath, MAX_ACTIVE_BYTES, "active.json") : null;
        byte[] newBytes = jsonBytes(active);
        if (newBytes.length > MAX_ACTIVE_BYTES) {
            throw new IOException("active.json 过大");
        }

        Path temporary = uniquePath(rootPath, ACTIVE_TEMP_PREFIX, ACTIVE_TEMP_SUFFIX);
        boolean replaced = false;
        try {
            writeDurableFile(temporary, newBytes);
            moveReplace(temporary, activePath);
            replaced = true;
            JSONObject written = readJsonFile(activePath, MAX_ACTIVE_BYTES, "active.json");
            String expectedDirectory = VERSIONS_DIR + "/" + expectedVersion;
            if (!COMPONENT_ID.equals(written.optString("id", ""))
                    || !expectedVersion.equals(written.optString("version", ""))
                    || !expectedDirectory.equals(written.optString("directory", ""))) {
                throw new IOException("active.json 写入后校验失败");
            }
        } catch (Exception failure) {
            deleteQuietly(temporary);
            if (replaced) {
                try {
                    restoreActiveLocked(activePath, oldBytes);
                } catch (Exception rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
            throw failure;
        }
        deleteQuietly(temporary);
    }

    private void restoreActiveLocked(Path activePath, byte[] oldBytes) throws IOException {
        if (oldBytes == null) {
            if (Files.exists(activePath, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isDirectory(activePath, LinkOption.NOFOLLOW_LINKS)
                        && !Files.isSymbolicLink(activePath)) {
                    throw new IOException("无法回滚 active.json：路径已变为目录");
                }
                Files.delete(activePath);
            }
            return;
        }
        Path temporary = uniquePath(rootPath, ACTIVE_TEMP_PREFIX, ACTIVE_TEMP_SUFFIX);
        try {
            writeDurableFile(temporary, oldBytes);
            moveReplace(temporary, activePath);
        } finally {
            deleteQuietly(temporary);
        }
    }

    // ------------------------------------------------------------------
    // Manifest and HTTP
    // ------------------------------------------------------------------

    private JSONObject fetchManifest(URL manifestUrl, PrintStream progress) throws Exception {
        progress(progress, "读取 cpp manifest");
        HttpURLConnection connection = openHttp(manifestUrl);
        try {
            long contentLength = connection.getContentLengthLong();
            if (contentLength > MAX_MANIFEST_BYTES) {
                throw new IOException("manifest 超过 65536 字节上限");
            }
            byte[] bytes;
            try (InputStream input = new BufferedInputStream(connection.getInputStream())) {
                bytes = readBounded(input, MAX_MANIFEST_BYTES, "manifest");
            }
            String text = new String(bytes, StandardCharsets.UTF_8);
            if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
                text = text.substring(1);
            }
            try {
                return new JSONObject(text);
            } catch (JSONException error) {
                throw new IOException("manifest 不是有效的 JSON 对象", error);
            }
        } finally {
            connection.disconnect();
        }
    }

    private ParsedManifest parseManifest(URL manifestUrl, JSONObject json,
                                         String requestedAbi, int sdk) throws Exception {
        long schemaVersion = requiredLong(json, "schemaVersion", 1L, SCHEMA_VERSION);
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("manifest.schemaVersion 必须是 1");
        }
        if (!COMPONENT_ID.equals(requiredString(json, "id"))) {
            throw new IllegalArgumentException("manifest.id 必须是 cpp");
        }

        String version;
        String manifestAbi;
        try {
            version = validateVersion(requiredString(json, "version"));
            manifestAbi = validateAbi(requiredString(json, "abi"), "manifest.abi");
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("manifest 标识无效: " + error.getMessage(), error);
        }
        if (!manifestAbi.equals(requestedAbi)) {
            throw new IllegalArgumentException("manifest.abi=" + manifestAbi
                    + " 与请求的 abi=" + requestedAbi + " 不一致");
        }

        long minSdk = requiredLong(json, "minSdk", 1L, 10_000L);
        if (sdk < minSdk) {
            throw new IllegalArgumentException("设备 sdk=" + sdk
                    + " 低于 manifest.minSdk=" + minSdk);
        }

        long size = requiredLong(json, "size", 1L, MAX_ARCHIVE_BYTES);
        long unpackedSize = requiredLong(json, "unpackedSize", 0L, MAX_EXPANDED_BYTES);
        String sha256 = requiredString(json, "sha256");
        if (!SHA256_PATTERN.matcher(sha256).matches()) {
            throw new IllegalArgumentException("manifest.sha256 必须是 64 位小写十六进制");
        }

        String relativeUrl = requiredString(json, "url").trim();
        if (relativeUrl.isEmpty() || relativeUrl.length() > MAX_URL_CHARS) {
            throw new IllegalArgumentException("manifest.url 无效");
        }
        URL archiveUrl;
        try {
            archiveUrl = new URL(manifestUrl, relativeUrl);
        } catch (MalformedURLException error) {
            throw new IllegalArgumentException("manifest.url 不是有效 URL", error);
        }
        requireHttpUrl(archiveUrl);

        Object rawExecutables = json.opt("executables");
        if (!(rawExecutables instanceof JSONArray)) {
            throw new IllegalArgumentException("manifest.executables 必须是数组");
        }
        JSONArray executableArray = (JSONArray) rawExecutables;
        if (executableArray.length() > MAX_EXECUTABLES) {
            throw new IllegalArgumentException("manifest.executables 超过 64 项上限");
        }
        List<String> executables = new ArrayList<>();
        Set<String> executableKeys = new HashSet<>();
        for (int i = 0; i < executableArray.length(); i++) {
            Object raw = executableArray.get(i);
            if (!(raw instanceof String)) {
                throw new IllegalArgumentException("manifest.executables[" + i + "] 必须是字符串");
            }
            String executable;
            try {
                executable = validateRelativeEntry((String) raw, false);
            } catch (IOException error) {
                throw new IllegalArgumentException("manifest.executables[" + i + "] 无效", error);
            }
            String key = executable.toLowerCase(Locale.ROOT);
            if (key.equals(METADATA_FILE) || key.startsWith(METADATA_FILE + "/")) {
                throw new IllegalArgumentException("manifest.executables 不能指向保留元数据文件");
            }
            if (!executableKeys.add(key)) {
                throw new IllegalArgumentException("manifest.executables 含重复路径");
            }
            executables.add(executable);
        }
        return new ParsedManifest(json, version, manifestAbi, (int) minSdk, size,
                unpackedSize, sha256, archiveUrl, executables);
    }

    private Path downloadArchiveLocked(ParsedManifest manifest, PrintStream progress) throws Exception {
        HttpURLConnection connection = openHttp(manifest.archiveUrl);
        Path temporary = uniquePath(rootPath, DOWNLOAD_PREFIX, "");
        try {
            long contentLength = connection.getContentLengthLong();
            if (contentLength >= 0 && contentLength != manifest.size) {
                throw new IOException("archive Content-Length=" + contentLength
                        + " 与 manifest.size=" + manifest.size + " 不一致");
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long step = Math.max(1024L * 1024L, manifest.size / 10L);
            long nextProgress = step;
            long total = 0L;
            progress(progress, "下载 cpp " + manifest.version + " (" + manifest.size + " 字节)");

            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 OutputStream output = new BufferedOutputStream(Files.newOutputStream(
                         temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > manifest.size || total > MAX_ARCHIVE_BYTES) {
                        throw new IOException("下载的 archive 超过 manifest.size 或 512MiB 上限");
                    }
                    output.write(buffer, 0, read);
                    digest.update(buffer, 0, read);
                    if (total >= nextProgress || total == manifest.size) {
                        progress(progress, "cpp " + manifest.version + " 已下载 "
                                + total + "/" + manifest.size + " 字节");
                        while (nextProgress <= total) {
                            nextProgress += step;
                        }
                    }
                }
                output.flush();
            }
            if (total != manifest.size) {
                throw new IOException("archive 大小=" + total
                        + " 与 manifest.size=" + manifest.size + " 不一致");
            }
            String actualSha256 = hex(digest.digest());
            if (!manifest.sha256.equals(actualSha256)) {
                throw new IOException("archive SHA-256 与 manifest 不一致");
            }
            return temporary;
        } catch (Exception error) {
            deleteQuietly(temporary);
            throw error;
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection openHttp(URL initial) throws IOException {
        URL current = initial;
        for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            requireHttpUrl(current);
            HttpURLConnection connection = (HttpURLConnection) current.openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept-Encoding", "identity");
            int code;
            try {
                code = connection.getResponseCode();
            } catch (IOException failure) {
                connection.disconnect();
                throw failure;
            }
            if (code == HttpURLConnection.HTTP_OK) {
                return connection;
            }
            if (isRedirect(code)) {
                String location = connection.getHeaderField("Location");
                closeErrorStream(connection);
                connection.disconnect();
                if (location == null || location.isEmpty()) {
                    throw new IOException("HTTP 重定向缺少 Location");
                }
                current = new URL(current, location);
                requireHttpUrl(current);
                continue;
            }
            closeErrorStream(connection);
            connection.disconnect();
            throw new IOException("HTTP 状态码 " + code + "，仅接受 200");
        }
        throw new IOException("HTTP 重定向次数超过 " + MAX_REDIRECTS);
    }

    private static boolean isRedirect(int code) {
        return code == 301 || code == 302 || code == 303
                || code == 307 || code == 308;
    }

    private static void closeErrorStream(HttpURLConnection connection) {
        InputStream error = connection.getErrorStream();
        if (error != null) {
            try {
                error.close();
            } catch (IOException ignored) {
                // The status code is the useful failure, not a broken error stream.
            }
        }
    }

    private static void requireHttpUrl(URL url) {
        if (url == null) {
            throw new IllegalArgumentException("URL 不能为空");
        }
        String protocol = url.getProtocol();
        if (!"http".equalsIgnoreCase(protocol) && !"https".equalsIgnoreCase(protocol)) {
            throw new IllegalArgumentException("仅允许 http/https URL，拒绝协议: " + protocol);
        }
        if (url.getHost() == null || url.getHost().isEmpty()) {
            throw new IllegalArgumentException("URL 缺少主机名");
        }
        if (url.toString().length() > MAX_URL_CHARS) {
            throw new IllegalArgumentException("URL 超过 8192 字符上限");
        }
    }

    // ------------------------------------------------------------------
    // Extraction and executable marking
    // ------------------------------------------------------------------

    private long extractArchiveLocked(Path archive, Path stage, ParsedManifest manifest)
            throws Exception {
        Path stageReal = stage.toRealPath();
        Set<String> seenPaths = new HashSet<>();
        long total = 0L;
        int entryCount = 0;
        byte[] buffer = new byte[64 * 1024];

        try (ZipInputStream zip = new ZipInputStream(
                new BufferedInputStream(Files.newInputStream(archive)))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entryCount++;
                if (entryCount > MAX_ENTRY_COUNT) {
                    throw new IOException("archive 条目数超过 50000 上限");
                }
                String rawName = entry.getName();
                boolean directoryEntry = rawName != null && rawName.endsWith("/");
                String relative = validateRelativeEntry(rawName, true);
                String key = relative;
                String reservedKey = relative.toLowerCase(Locale.ROOT);
                if (reservedKey.equals(METADATA_FILE) || reservedKey.startsWith(METADATA_FILE + "/")) {
                    throw new IOException("archive 包含保留的元数据路径: " + relative);
                }
                if (!seenPaths.add(key)) {
                    throw new IOException("archive 含重复路径: " + relative);
                }

                Path target = resolveRelative(stage, relative);
                if (directoryEntry) {
                    ensureSafeDirectory(stage, target, stageReal);
                    int extra = zip.read(buffer);
                    if (extra > 0) {
                        total += extra;
                        checkExpandedTotal(total, manifest.unpackedSize);
                        throw new IOException("目录条目含数据: " + relative);
                    }
                } else {
                    Path parent = target.getParent();
                    if (parent == null) {
                        throw new IOException("archive 条目缺少父目录: " + relative);
                    }
                    ensureSafeDirectory(stage, parent, stageReal);
                    Path parentReal = parent.toRealPath();
                    if (!parentReal.startsWith(stageReal)) {
                        throw new IOException("archive 条目规范化后逃逸暂存目录");
                    }
                    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                        throw new IOException("archive 文件路径与已有条目冲突: " + relative);
                    }
                    try (OutputStream output = new BufferedOutputStream(
                            Files.newOutputStream(target, StandardOpenOption.CREATE_NEW,
                                    StandardOpenOption.WRITE))) {
                        int read;
                        while ((read = zip.read(buffer)) != -1) {
                            total += read;
                            checkExpandedTotal(total, manifest.unpackedSize);
                            output.write(buffer, 0, read);
                        }
                        output.flush();
                    }
                }
                zip.closeEntry();
            }
        }

        if (total != manifest.unpackedSize) {
            throw new IOException("archive 实际展开大小=" + total
                    + " 与 manifest.unpackedSize=" + manifest.unpackedSize + " 不一致");
        }
        return total;
    }

    private static void checkExpandedTotal(long total, long declared) throws IOException {
        if (total > declared) {
            throw new IOException("archive 展开字节数超过 manifest.unpackedSize");
        }
        if (total > MAX_EXPANDED_BYTES) {
            throw new IOException("archive 展开字节数超过 2GiB 上限");
        }
    }

    private static String validateRelativeEntry(String raw, boolean allowDirectory)
            throws IOException {
        if (raw == null || raw.isEmpty()) {
            throw new IOException("archive 条目名为空");
        }
        if (raw.length() > MAX_ENTRY_PATH_CHARS) {
            throw new IOException("archive 条目名超过 4096 字符");
        }
        boolean directory = raw.endsWith("/");
        if (directory && !allowDirectory) {
            throw new IOException("路径不能是目录: " + raw);
        }
        String relative = directory ? raw.substring(0, raw.length() - 1) : raw;
        if (relative.isEmpty()) {
            throw new IOException("archive 条目名为空");
        }
        if (relative.startsWith("/") || relative.startsWith("\\")) {
            throw new IOException("archive 含绝对路径: " + raw);
        }
        for (int i = 0; i < relative.length(); i++) {
            char ch = relative.charAt(i);
            if (ch == '\\' || ch == ':' || ch == '\0' || ch < 0x20 || ch == 0x7f) {
                throw new IOException("archive 条目名含非法字符: " + raw);
            }
        }
        String[] segments = relative.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new IOException("archive 含路径穿越或空路径段: " + raw);
            }
            if (segment.endsWith(".") || segment.endsWith(" ")) {
                throw new IOException("archive 条目名含跨平台不安全的结尾: " + raw);
            }
        }
        return relative;
    }

    private static Path resolveRelative(Path base, String relative) throws IOException {
        Path absoluteBase = base.toAbsolutePath().normalize();
        Path target = absoluteBase.resolve(relative).normalize();
        if (target.equals(absoluteBase) || !target.startsWith(absoluteBase)) {
            throw new IOException("archive 条目规范化后越界: " + relative);
        }
        return target;
    }

    private static void ensureSafeDirectory(Path stage, Path directory, Path stageReal)
            throws IOException {
        Path stageAbsolute = stage.toAbsolutePath().normalize();
        Path absolute = directory.toAbsolutePath().normalize();
        if (!absolute.startsWith(stageAbsolute)) {
            throw new IOException("目录不在暂存目录内");
        }
        Path current = stageAbsolute;
        if (absolute.equals(stageAbsolute)) {
            if (Files.isSymbolicLink(current)
                    || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("暂存目录不安全");
            }
            return;
        }
        for (Path part : stageAbsolute.relativize(absolute)) {
            current = current.resolve(part);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                rejectUnsafeDirectory(current);
            } else {
                try {
                    Files.createDirectory(current);
                } catch (FileAlreadyExistsException raced) {
                    rejectUnsafeDirectory(current);
                }
            }
            if (!current.toRealPath().startsWith(stageReal)) {
                throw new IOException("目录规范化后逃逸暂存目录");
            }
        }
    }

    private static void rejectUnsafeDirectory(Path path) throws IOException {
        if (Files.isSymbolicLink(path)
                || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("archive 路径被非普通目录占用: " + path.getFileName());
        }
    }

    private static void verifyExecutables(Path base, List<String> executables) throws IOException {
        Path baseReal = base.toRealPath();
        for (String executable : executables) {
            Path target = resolveRelative(base, executable);
            Path parent = target.getParent();
            if (parent == null) {
                throw new IOException("manifest.executables 路径缺少父目录: " + executable);
            }
            verifyDirectoryChain(base, parent);
            if (!parent.toRealPath().startsWith(baseReal)
                    || Files.isSymbolicLink(target)
                    || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("manifest.executables 指向的普通文件不存在: " + executable);
            }
            if (!target.toRealPath().startsWith(baseReal)) {
                throw new IOException("manifest.executables 规范化后越界: " + executable);
            }
        }
    }

    private static void verifyDirectoryChain(Path base, Path directory) throws IOException {
        Path baseAbsolute = base.toAbsolutePath().normalize();
        Path absolute = directory.toAbsolutePath().normalize();
        if (!absolute.startsWith(baseAbsolute)) {
            throw new IOException("manifest.executables 目录越界");
        }
        Path current = baseAbsolute;
        for (Path part : baseAbsolute.relativize(absolute)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)
                    || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("manifest.executables 经过符号链接或非目录");
            }
        }
    }

    private static void makeExecutables(Path base, List<String> executables) throws IOException {
        for (String executable : executables) {
            Path target = resolveRelative(base, executable);
            if (Files.isSymbolicLink(target)
                    || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("准备 chmod 的目标不是普通文件: " + executable);
            }
            PosixFileAttributeView view = Files.getFileAttributeView(
                    target, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (view == null) {
                continue;
            }
            try {
                Set<PosixFilePermission> permissions =
                        new HashSet<>(view.readAttributes().permissions());
                permissions.add(PosixFilePermission.OWNER_EXECUTE);
                permissions.add(PosixFilePermission.GROUP_EXECUTE);
                permissions.add(PosixFilePermission.OTHERS_EXECUTE);
                view.setPermissions(permissions);
            } catch (UnsupportedOperationException ignored) {
                // POSIX permissions are unavailable on this filesystem (notably Windows).
            }
        }
    }

    // ------------------------------------------------------------------
    // Metadata
    // ------------------------------------------------------------------

    private JSONObject buildMetadata(ParsedManifest manifest) throws JSONException {
        JSONObject metadata = new JSONObject();
        metadata.put("schemaVersion", SCHEMA_VERSION);
        metadata.put("id", COMPONENT_ID);
        metadata.put("version", manifest.version);
        metadata.put("abi", manifest.abi);
        metadata.put("minSdk", manifest.minSdk);
        metadata.put("sha256", manifest.sha256);
        metadata.put("size", manifest.size);
        metadata.put("unpackedSize", manifest.unpackedSize);
        JSONArray executableArray = new JSONArray();
        for (String executable : manifest.executables) {
            executableArray.put(executable);
        }
        metadata.put("executables", executableArray);
        copyOptional(manifest.json, metadata, "license");
        copyOptional(manifest.json, metadata, "source");
        metadata.put("installedAt", System.currentTimeMillis());
        return metadata;
    }

    private static JSONObject activationRecord(JSONObject metadata) throws JSONException {
        JSONObject active = new JSONObject(metadata.toString());
        active.put("installed", true);
        active.put("active", true);
        active.put("directory", VERSIONS_DIR + "/" + metadata.getString("version"));
        return active;
    }

    private JSONObject readMetadata(Path versionDirectory) throws Exception {
        return readJsonFile(versionDirectory.resolve(METADATA_FILE),
                MAX_METADATA_BYTES, "installed metadata");
    }

    private static boolean sameManifestMetadata(JSONObject expected, JSONObject actual) {
        try {
            if (requiredLong(actual, "schemaVersion", 1L, SCHEMA_VERSION) != SCHEMA_VERSION) {
                return false;
            }
            if (!COMPONENT_ID.equals(requiredString(actual, "id"))
                    || !requiredString(actual, "version").equals(requiredString(expected, "version"))
                    || !requiredString(actual, "abi").equals(requiredString(expected, "abi"))
                    || !requiredString(actual, "sha256").equals(requiredString(expected, "sha256"))) {
                return false;
            }
            if (requiredLong(actual, "minSdk", 1L, 10_000L)
                    != requiredLong(expected, "minSdk", 1L, 10_000L)
                    || requiredLong(actual, "size", 1L, MAX_ARCHIVE_BYTES)
                    != requiredLong(expected, "size", 1L, MAX_ARCHIVE_BYTES)
                    || requiredLong(actual, "unpackedSize", 0L, MAX_EXPANDED_BYTES)
                    != requiredLong(expected, "unpackedSize", 0L, MAX_EXPANDED_BYTES)) {
                return false;
            }
            if (!arrayStrings(actual, "executables").equals(
                    arrayStrings(expected, "executables"))) {
                return false;
            }
            for (String optional : new String[]{"license", "source"}) {
                boolean expectedHas = expected.has(optional);
                boolean actualHas = actual.has(optional);
                if (expectedHas != actualHas) {
                    return false;
                }
                if (expectedHas && !jsonValueEquals(expected.opt(optional), actual.opt(optional))) {
                    return false;
                }
            }
            return true;
        } catch (Exception invalidMetadata) {
            return false;
        }
    }

    private static List<String> arrayStrings(JSONObject object, String key) throws JSONException {
        Object raw = object.opt(key);
        if (!(raw instanceof JSONArray)) {
            throw new JSONException(key + " 不是数组");
        }
        JSONArray array = (JSONArray) raw;
        List<String> values = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            Object value = array.get(i);
            if (!(value instanceof String)) {
                throw new JSONException(key + " 含非字符串");
            }
            values.add((String) value);
        }
        return values;
    }

    private static Set<String> jsonKeys(JSONObject object) {
        Set<String> keys = new HashSet<>();
        Iterator<String> iterator = object.keys();
        while (iterator.hasNext()) keys.add(iterator.next());
        return keys;
    }

    private static boolean jsonValueEquals(Object left, Object right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        boolean leftNull = left == JSONObject.NULL;
        boolean rightNull = right == JSONObject.NULL;
        if (leftNull || rightNull) {
            return leftNull && rightNull;
        }
        if (left instanceof JSONObject && right instanceof JSONObject) {
            JSONObject leftObject = (JSONObject) left;
            JSONObject rightObject = (JSONObject) right;
            Set<String> leftKeys = jsonKeys(leftObject);
            Set<String> rightKeys = jsonKeys(rightObject);
            if (!leftKeys.equals(rightKeys)) {
                return false;
            }
            for (String key : leftKeys) {
                if (!jsonValueEquals(leftObject.opt(key), rightObject.opt(key))) {
                    return false;
                }
            }
            return true;
        }
        if (left instanceof JSONArray && right instanceof JSONArray) {
            JSONArray leftArray = (JSONArray) left;
            JSONArray rightArray = (JSONArray) right;
            if (leftArray.length() != rightArray.length()) {
                return false;
            }
            for (int i = 0; i < leftArray.length(); i++) {
                if (!jsonValueEquals(leftArray.opt(i), rightArray.opt(i))) {
                    return false;
                }
            }
            return true;
        }
        if (left instanceof Number && right instanceof Number) {
            return Double.compare(((Number) left).doubleValue(),
                    ((Number) right).doubleValue()) == 0;
        }
        return left.equals(right);
    }

    private static void copyOptional(JSONObject from, JSONObject to, String key)
            throws JSONException {
        if (from.has(key)) {
            to.put(key, from.get(key));
        }
    }

    // ------------------------------------------------------------------
    // File helpers
    // ------------------------------------------------------------------

    private static void ensureDirectory(Path directory) throws IOException {
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(directory)
                    || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("不是安全的普通目录: " + directory);
            }
            return;
        }
        try {
            Files.createDirectory(directory);
        } catch (FileAlreadyExistsException raced) {
            if (Files.isSymbolicLink(directory)
                    || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("目录创建后不安全: " + directory);
            }
        }
    }

    private static Path createUniqueDirectory(Path parent, String prefix) throws IOException {
        for (int attempt = 0; attempt < 20; attempt++) {
            Path candidate = uniquePath(parent, prefix, "");
            try {
                Path created = Files.createDirectory(candidate);
                if (Files.isSymbolicLink(created)
                        || !Files.isDirectory(created, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("暂存目录创建后不安全");
                }
                return created;
            } catch (FileAlreadyExistsException ignored) {
                // Extremely unlikely UUID collision; try a fresh one.
            }
        }
        throw new IOException("无法创建唯一暂存目录");
    }

    private static Path uniquePath(Path parent, String prefix, String suffix) {
        return parent.resolve(prefix + UUID.randomUUID().toString() + suffix);
    }

    private static void moveNewDirectory(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException fallback) {
            Files.move(source, target);
        }
    }

    private static void moveReplace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException fallback) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static byte[] jsonBytes(JSONObject object) throws JSONException {
        return object.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void writeDurableFile(Path path, byte[] bytes) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
    }

    private static JSONObject readJsonFile(Path path, int maxBytes, String label)
            throws IOException {
        byte[] bytes = readFileBytes(path, maxBytes, label);
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }
        try {
            return new JSONObject(text);
        } catch (JSONException error) {
            throw new IOException(label + " 不是有效的 JSON 对象", error);
        }
    }

    private static byte[] readFileBytes(Path path, int maxBytes, String label)
            throws IOException {
        if (Files.isSymbolicLink(path)
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(label + " 不是普通文件");
        }
        long declaredLength = Files.size(path);
        if (declaredLength > maxBytes) {
            throw new IOException(label + " 超过大小上限");
        }
        try (InputStream input = new BufferedInputStream(Files.newInputStream(path))) {
            return readBounded(input, maxBytes, label);
        }
    }

    private static byte[] readBounded(InputStream input, int maxBytes, String label)
            throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new IOException(label + " 超过大小上限");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static long requiredLong(JSONObject object, String key, long min, long max) {
        Object raw = object.opt(key);
        if (!(raw instanceof Number)) {
            throw new IllegalArgumentException("manifest." + key + " 必须是整数");
        }
        Number number = (Number) raw;
        double asDouble = number.doubleValue();
        if (!Double.isFinite(asDouble) || asDouble != Math.rint(asDouble)) {
            throw new IllegalArgumentException("manifest." + key + " 必须是整数");
        }
        if (asDouble < min || asDouble > max) {
            throw new IllegalArgumentException("manifest." + key + " 超出允许范围");
        }
        return number.longValue();
    }

    private static String requiredString(JSONObject object, String key) {
        Object raw = object.opt(key);
        if (!(raw instanceof String)) {
            throw new IllegalArgumentException("manifest." + key + " 必须是字符串");
        }
        return (String) raw;
    }

    private static String validateVersion(String value) {
        if (value == null || !VERSION_PATTERN.matcher(value).matches()
                || value.endsWith(".") || windowsReserved(value)) {
            throw new IllegalArgumentException("版本标识必须是 1-64 位安全 ASCII 字符");
        }
        return value;
    }

    private static String validateAbi(String value, String label) {
        if (value == null || !ABI_PATTERN.matcher(value).matches()
                || value.endsWith(".") || windowsReserved(value)) {
            throw new IllegalArgumentException(label + " 必须是 1-32 位安全 ASCII 字符");
        }
        return value;
    }

    private static boolean windowsReserved(String value) {
        int dot = value.indexOf('.');
        String base = dot < 0 ? value : value.substring(0, dot);
        return WINDOWS_RESERVED.matcher(base).matches();
    }

    private static String hex(byte[] bytes) {
        char[] alphabet = "0123456789abcdef".toCharArray();
        char[] result = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xff;
            result[i * 2] = alphabet[value >>> 4];
            result[i * 2 + 1] = alphabet[value & 0x0f];
        }
        return new String(result);
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            safeDeleteTree(path, rootPath);
        } catch (Exception ignored) {
            // Cleanup must not replace the primary installation failure.
        }
    }

    /**
     * Deletes a path without following symbolic links. The target's real parent must be
     * inside {@code allowedRoot}; every visited lexical path must remain below it.
     */
    private static void safeDeleteTree(Path target, Path allowedRoot) throws IOException {
        if (target == null || allowedRoot == null) {
            return;
        }
        Path absolute = target.toAbsolutePath().normalize();
        Path root = allowedRoot.toAbsolutePath().normalize();
        if (absolute.equals(root) || !absolute.startsWith(root)) {
            throw new IOException("拒绝对 root 之外执行清理: " + absolute);
        }
        Path parent = absolute.getParent();
        if (parent == null) {
            throw new IOException("清理路径缺少父目录");
        }
        Path rootReal = root.toRealPath();
        Path parentReal = parent.toRealPath();
        if (!parentReal.startsWith(rootReal)) {
            throw new IOException("清理路径的父目录逃逸 root");
        }
        if (Files.isSymbolicLink(absolute)) {
            Files.deleteIfExists(absolute);
            return;
        }
        if (!Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (!Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)) {
            Files.delete(absolute);
            return;
        }

        Files.walkFileTree(absolute, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs)
                    throws IOException {
                Path path = directory.toAbsolutePath().normalize();
                if (!path.startsWith(root) || !path.startsWith(absolute)) {
                    throw new IOException("拒绝清理 root 之外的目录");
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                Path path = file.toAbsolutePath().normalize();
                if (!path.startsWith(root) || !path.startsWith(absolute)) {
                    throw new IOException("拒绝清理 root 之外的文件");
                }
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException error)
                    throws IOException {
                if (error != null) {
                    throw error;
                }
                Path path = directory.toAbsolutePath().normalize();
                if (!path.startsWith(root) || !path.startsWith(absolute)) {
                    throw new IOException("拒绝清理 root 之外的目录");
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void progress(PrintStream progress, String message) {
        if (progress != null) {
            progress.println("[toolchain] " + message);
        }
    }

    private interface LockedAction<T> {
        T run() throws Exception;
    }

    private static final class ParsedManifest {
        final JSONObject json;
        final String version;
        final String abi;
        final int minSdk;
        final long size;
        final long unpackedSize;
        final String sha256;
        final URL archiveUrl;
        final List<String> executables;

        ParsedManifest(JSONObject json, String version, String abi, int minSdk, long size,
                       long unpackedSize, String sha256, URL archiveUrl,
                       List<String> executables) {
            this.json = json;
            this.version = version;
            this.abi = abi;
            this.minSdk = minSdk;
            this.size = size;
            this.unpackedSize = unpackedSize;
            this.sha256 = sha256;
            this.archiveUrl = archiveUrl;
            this.executables = executables;
        }
    }

    private static final class ActiveRecord {
        final JSONObject json;
        final Path directory;

        ActiveRecord(JSONObject json, Path directory) {
            this.json = json;
            this.directory = directory;
        }
    }
}
