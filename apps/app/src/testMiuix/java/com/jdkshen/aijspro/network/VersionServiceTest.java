package com.jdkshen.aijspro.network;

import com.jdkshen.aijspro.network.entity.GitHubRelease;
import com.jdkshen.aijspro.network.entity.VersionInfo;

import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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

    private static final String[] ARM64_DEVICE = {"arm64-v8a", "armeabi-v7a"};

    /** 拼一份 update.json；每个入参是一行字段，不含尾逗号（避免拼出非法 JSON）。 */
    private static String manifest(String... fields) {
        StringBuilder builder = new StringBuilder("{\n");
        builder.append("  \"versionCode\": 466,\n")
                .append("  \"versionName\": \"1.0.3\",\n")
                .append("  \"releaseNotes\": \"## 更新内容\\n- 修复一些问题\"");
        for (String field : fields) {
            builder.append(",\n").append(field);
        }
        return builder.append("\n}\n").toString();
    }

    @Test
    public void parsesManifestAndResolvesRelativeApkUrl() throws Exception {
        VersionInfo info = VersionService.fromManifest(manifest(
                "  \"apkUrl\": \"apk/aijspro-miuix-compat-arm64-v8a.apk\",\n"
                        + "  \"apkSha256\": \"sha256:abc\",\n"
                        + "  \"deprecated\": 465,\n"
                        + "  \"oldVersions\": [{ \"versionCode\": 465, \"issues\": \"旧版本有问题\" }]\n"),
                "http://192.168.1.5:8080/update.json", true, ARM64_DEVICE);

        assertEquals(466, info.versionCode);
        assertEquals("1.0.3", info.versionName);
        assertTrue(info.releaseNotes.contains("修复一些问题"));
        assertEquals(465, info.deprecated);
        assertEquals(1, info.oldVersions.size());
        assertEquals(465, info.oldVersions.get(0).versionCode);
        assertEquals("旧版本有问题", info.oldVersions.get(0).issues);
        // 相对地址要相对 update.json 所在的目录解析：这样才能把整套文件丢进一个目录
        assertEquals("http://192.168.1.5:8080/apk/aijspro-miuix-compat-arm64-v8a.apk",
                info.downloadUrl);
        assertEquals("sha256:abc", info.downloadDigest);
    }

    @Test
    public void absoluteApkUrlIsKeptAsIs() throws Exception {
        VersionInfo info = VersionService.fromManifest(manifest(
                "  \"apkUrl\": \"https://example.com/a.apk\""),
                "http://192.168.1.5:8080/update.json", true, ARM64_DEVICE);
        assertEquals("https://example.com/a.apk", info.downloadUrl);
    }

    @Test
    public void picksTheAssetThatMatchesFlavorAndDevice() throws Exception {
        VersionInfo info = VersionService.fromManifest(manifest(
                "  \"assets\": [\n"
                        + "    { \"name\": \"aijspro-miuix-compat-x86_64.apk\", \"url\": \"x86.apk\", \"abi\": \"x86_64\" },\n"
                        + "    { \"name\": \"aijspro-miuix-lite-arm64-v8a.apk\", \"url\": \"lite.apk\", \"abi\": \"arm64-v8a\" },\n"
                        + "    { \"name\": \"aijspro-miuix-compat-armeabi-v7a.apk\", \"url\": \"v7a.apk\", \"abi\": \"armeabi-v7a\" },\n"
                        + "    { \"name\": \"aijspro-miuix-compat-arm64-v8a.apk\", \"url\": \"arm64.apk\", \"abi\": \"arm64-v8a\", \"sha256\": \"sha256:arm\" }\n"
                        + "  ]"),
                "http://192.168.1.5:8080/update.json", true, ARM64_DEVICE);

        // 只留当前设备可用的两套（compat + arm64/v7a），并且首选就是 arm64
        assertEquals(2, info.downloads.size());
        assertEquals("aijspro-miuix-compat-arm64-v8a.apk", info.downloads.get(0).name);
        assertEquals("http://192.168.1.5:8080/arm64.apk", info.downloadUrl);
        assertEquals("sha256:arm", info.downloadDigest);
    }

    @Test
    public void manifestWithoutAnyCompatibleApkIsRejected() throws Exception {
        try {
            VersionService.fromManifest(manifest(
                    "  \"assets\": [\n"
                            + "    { \"name\": \"aijspro-miuix-compat-x86_64.apk\", \"url\": \"x86.apk\", \"abi\": \"x86_64\" }\n"
                            + "  ]"),
                    "http://192.168.1.5:8080/update.json", true, ARM64_DEVICE);
            fail("没有可用 APK 时应当报错，而不是让界面去下载一个装不上的包");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("没有适用于当前设备"));
        }
    }

    @Test
    public void malformedManifestIsRejectedWithAReadableMessage() throws Exception {
        try {
            VersionService.fromManifest("not json at all", "http://x/update.json", true, ARM64_DEVICE);
            fail("非法 JSON 应当报错");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("JSON"));
        }
        try {
            VersionService.fromManifest(manifest("  \"apkUrl\": \"a.apk\""),
                    "http://x/update.json", true, ARM64_DEVICE);
            // versionCode 合法 → 不报错；这里只是确认上一个断言没被误伤
        } catch (IOException unexpected) {
            fail("合法 manifest 不该报错：" + unexpected.getMessage());
        }
    }

    @Test
    public void abiFieldMustMatchTheDevice() {
        assertTrue(VersionService.isAbiSupported(null, ARM64_DEVICE));
        assertTrue(VersionService.isAbiSupported("", ARM64_DEVICE));
        assertTrue(VersionService.isAbiSupported("arm64-v8a", ARM64_DEVICE));
        assertTrue(VersionService.isAbiSupported(" ARM64-V8A ", ARM64_DEVICE));
        assertFalse(VersionService.isAbiSupported("x86_64", ARM64_DEVICE));
    }

    @Test
    public void manifestWithByteOrderMarkStillParses() throws Exception {
        // 记事本 / PowerShell 存出来的 JSON 常带 BOM，不能因此让更新源变成「用不了」
        String withBom = "\uFEFF" + manifest("  \"apkUrl\": \"a.apk\"");
        VersionInfo info = VersionService.fromManifest(
                withBom, "http://192.168.1.5:8080/update.json", true, ARM64_DEVICE);
        assertEquals(466, info.versionCode);
        assertEquals("http://192.168.1.5:8080/a.apk", info.downloadUrl);
    }

    @Test
    public void manifestHistoryKeepsVersionNameAndDate() throws Exception {
        VersionInfo info = VersionService.fromManifest(manifest(
                "  \"apkUrl\": \"a.apk\",\n"
                        + "  \"oldVersions\": [\n"
                        + "    { \"versionCode\": 466, \"versionName\": \"1.0.3\", \"date\": \"2026-09-12\", \"issues\": \"- 自建更新源\" },\n"
                        + "    { \"versionCode\": 465, \"issues\": \"- 旧版本\" }\n"
                        + "  ]\n"),
                "http://192.168.1.5:8080/update.json", true, ARM64_DEVICE);

        assertEquals(2, info.oldVersions.size());
        assertEquals("1.0.3", info.oldVersions.get(0).versionName);
        assertEquals("2026-09-12", info.oldVersions.get(0).date);
        assertEquals("1.0.3  ·  2026-09-12", info.oldVersions.get(0).displayTitle());
        // 没写版本名/日期时退回 versionCode，界面上不会出现空标题
        assertEquals("versionCode 465", info.oldVersions.get(1).displayTitle());
    }

    @Test
    public void dialogHistorySkipsTheVersionBeingInstalled() throws Exception {
        VersionInfo info = VersionService.fromManifest(manifest(
                "  \"apkUrl\": \"a.apk\",\n"
                        + "  \"oldVersions\": [\n"
                        + "    { \"versionCode\": 466, \"issues\": \"本次\" },\n"
                        + "    { \"versionCode\": 465, \"issues\": \"上一版\" }\n"
                        + "  ]\n"),
                "http://192.168.1.5:8080/update.json", true, ARM64_DEVICE);

        // 「本次更新」已经在上面单独展示，历史列表里不再重复一遍
        assertEquals(2, info.oldVersions.size());
        assertEquals(1, info.historyForDialog().size());
        assertEquals(465, info.historyForDialog().get(0).versionCode);
    }

    @Test
    public void buildsLatestAndHistoryFromGitHubReleaseList() {
        GitHubRelease latest = release("v1.0.3+466", "versionCode: 466\n## 1.0.3\n- 自建更新源",
                "2026-09-12T08:00:00Z");
        GitHubRelease previous = release("v1.0.2+465", "versionCode: 465\n## 1.0.2\n- 修闪退",
                "2026-09-01T10:30:00Z");
        GitHubRelease draft = release("v1.0.4+467", "versionCode: 467", "2026-09-20T00:00:00Z");
        draft.draft = true;
        GitHubRelease preview = release("v1.0.9+500", "versionCode: 500", "2026-10-01T00:00:00Z");
        preview.prerelease = true;

        // GitHub 按时间倒序返回，且草稿/预览版不能当最新版本
        VersionInfo info = VersionService.fromGitHubReleases(
                Arrays.asList(preview, draft, latest, previous));

        assertEquals(466, info.versionCode);
        assertTrue(info.releaseNotes.contains("自建更新源"));
        assertEquals(1, info.oldVersions.size());
        assertEquals(465, info.oldVersions.get(0).versionCode);
        assertEquals("1.0.2", info.oldVersions.get(0).versionName);
        assertEquals("2026-09-01", info.oldVersions.get(0).date);
    }

    @Test
    public void emptyReleaseListMeansAlreadyLatest() {
        VersionInfo info = VersionService.fromGitHubReleases(Collections.emptyList());
        assertFalse(info.isNewer());
        assertEquals(0, info.oldVersions.size());
    }

    @Test
    public void publishedAtIsTrimmedToADate() {
        assertEquals("2026-09-12", VersionService.releaseDate("2026-09-12T08:00:00Z"));
        assertEquals("", VersionService.releaseDate(null));
        assertEquals("", VersionService.releaseDate(""));
    }

    private static GitHubRelease release(String tag, String body, String publishedAt) {
        GitHubRelease release = new GitHubRelease();
        release.tagName = tag;
        release.body = body;
        release.publishedAt = publishedAt;
        return release;
    }
}
