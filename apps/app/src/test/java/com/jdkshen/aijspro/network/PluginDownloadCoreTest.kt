package com.jdkshen.aijspro.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** task-131/132：下载器**纯逻辑核心**的测试（清单解析 / 强制 sha256 / 计划原因码）。 */
class PluginDownloadCoreTest {
    @get:Rule val temp = TemporaryFolder()

    private val goodEntry = "{\"id\":\"ocr\",\"displayName\":\"OCR 插件\",\"package\":\"com.jdkshen.ocr.plugin\"," +
        "\"version\":\"1.0.0\",\"versionCode\":1,\"minAppVersion\":1500,\"file\":\"ocr-plugin.apk\"," +
        "\"sizeBytes\":3,\"sha256\":\"ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad\"," +
        "\"requiresRestart\":true,\"abis\":[\"arm64-v8a\"],\"notes\":\"n\"}"

    private fun manifest(vararg entries: String) =
        "{\"schema\":1,\"generatedAt\":\"2026-01-01T00:00:00Z\",\"plugins\":[" + entries.joinToString(",") + "]}"

    private fun abcFile(): File {
        val file = File(temp.newFolder("apk"), "ocr-plugin.apk")
        file.writeBytes("abc".toByteArray())
        return file
    }

    @Test fun parsesValidManifest() {
        val result = PluginManifest.parse(manifest(goodEntry))
        assertTrue(result is PluginManifest.ParseResult.Success)
        val entry = (result as PluginManifest.ParseResult.Success).entries.single()
        assertEquals("ocr", entry.id)
        assertEquals("com.jdkshen.ocr.plugin", entry.packageName)
        assertEquals(1, entry.versionCode)
        assertEquals(1500, entry.minAppVersion)
        assertEquals(listOf("arm64-v8a"), entry.abis)
        assertTrue(entry.requiresRestart)
        assertTrue("合法 sha256 才可下载", entry.isDownloadable())
    }

    @Test fun rejectsBadManifests() {
        assertEquals("schema", (PluginManifest.parse("{\"schema\":2,\"plugins\":[" + goodEntry + "]}") as PluginManifest.ParseResult.Failure).reason)
        assertEquals("malformed", (PluginManifest.parse("{\"schema\":1,\"plugins\":[]}") as PluginManifest.ParseResult.Failure).reason)
        assertEquals("malformed", (PluginManifest.parse("not json") as PluginManifest.ParseResult.Failure).reason)
        val noSha = goodEntry.replace(Regex(",\"sha256\":\"[0-9a-f]+\""), "")
        assertEquals("malformed", (PluginManifest.parse(manifest(noSha)) as PluginManifest.ParseResult.Failure).reason)
        val badSha = goodEntry.replace("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", "zzz")
        assertEquals("malformed", (PluginManifest.parse(manifest(badSha)) as PluginManifest.ParseResult.Failure).reason)
        assertEquals("malformed", (PluginManifest.parse(manifest(goodEntry, goodEntry)) as PluginManifest.ParseResult.Failure).reason)
    }

    @Test fun pendingArtifactIsNotDownloadable() {
        val pending = goodEntry.replace("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", "pending")
        val entry = (PluginManifest.parse(manifest(pending)) as PluginManifest.ParseResult.Success).entries.single()
        assertFalse("pending 必须视为不可下载", entry.isDownloadable())
    }

    @Test fun verifierEnforcesSha256AndSize() {
        val file = abcFile()
        assertEquals("abc 的已知 sha256", "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", PluginVerifier.sha256(file))
        assertTrue(PluginVerifier.verify(file, PluginVerifier.sha256(file), 3L) is PluginVerifier.VerifyResult.Ok)
        assertTrue("篡改一字节必须报 checksum",
            PluginVerifier.verify(file, "0".repeat(64), 3L) is PluginVerifier.VerifyResult.ChecksumMismatch)
        assertTrue("大小不符必须报 size",
            PluginVerifier.verify(file, PluginVerifier.sha256(file), 999L) is PluginVerifier.VerifyResult.SizeMismatch)
        assertTrue("清单没给 sha ⇒ 不可安装",
            PluginVerifier.verify(file, "pending", 3L) is PluginVerifier.VerifyResult.ChecksumMismatch)
        assertTrue(PluginVerifier.verify(File(temp.root, "nope.apk"), "0".repeat(64), null) is PluginVerifier.VerifyResult.Unreadable)
    }

    @Test fun plannerCoversEveryPlanReason() {
        val entry = (PluginManifest.parse(manifest(goodEntry)) as PluginManifest.ParseResult.Success).entries.single()
        val base = PluginUpdatePlanner.DeviceState(1500, listOf("arm64-v8a"), emptyMap(), emptySet())
        assertEquals(PluginPlanReason.INSTALL, PluginUpdatePlanner.decide(entry, base).reason)
        assertEquals(PluginPlanReason.UPDATE,
            PluginUpdatePlanner.decide(entry, base.copy(installedVersionCode = mapOf("ocr" to 0))).reason)
        assertEquals(PluginPlanReason.UP_TO_DATE,
            PluginUpdatePlanner.decide(entry, base.copy(installedVersionCode = mapOf("ocr" to 1))).reason)
        assertEquals(PluginPlanReason.APP_TOO_OLD,
            PluginUpdatePlanner.decide(entry, base.copy(appVersionCode = 1400)).reason)
        assertEquals(PluginPlanReason.ABI_UNSUPPORTED,
            PluginUpdatePlanner.decide(entry, base.copy(deviceAbis = listOf("armeabi-v7a"))).reason)
        val pendingEntry = (PluginManifest.parse(manifest(
            goodEntry.replace("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", "pending")
        )) as PluginManifest.ParseResult.Success).entries.single()
        assertEquals(PluginPlanReason.PENDING_ARTIFACT, PluginUpdatePlanner.decide(pendingEntry, base).reason)
        assertTrue("只有 install/update 才可点", PluginUpdatePlanner.decide(entry, base).actionable)
        assertFalse(PluginUpdatePlanner.decide(entry, base.copy(appVersionCode = 1400)).actionable)
    }

    @Test fun failureReasonCodesAreTheAgreedFive() {
        assertEquals(
            // source / manifest 是后来新增的两类（来源不可信 / manifest 非法），
            // 顺序即协议顺序；改动这里等于改客户端契约，请同步 reason_codes.dart 的文案表。
            listOf("network", "checksum", "package", "permission", "missing", "source", "manifest"),
            PluginFailureReason.values().map { it.code }
        )
    }
}
