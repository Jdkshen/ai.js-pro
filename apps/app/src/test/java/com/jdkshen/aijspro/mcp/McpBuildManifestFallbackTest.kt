package com.jdkshen.aijspro.mcp

import com.google.gson.JsonObject
import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.arsc.chunk.xml.ResXmlDocument
import org.jf.smali.Smali
import org.jf.smali.SmaliOptions
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * task-31（§4.1 构建侧残留）：构建前会话检查必须复用 apk_verify 的分层 AXML 解析 ——
 * AXML 需要资源上下文不再硬失败（转 warnings 里的 MANIFEST_PRIMARY_DECODER_FALLBACK），
 * 真损坏/格式不支持/后端异常仍失败但错误码可区分，且两处 failureKind/primaryError 同源一致。
 */
class McpBuildManifestFallbackTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var home: File
    private lateinit var store: File
    private lateinit var tools: McpApkToolkit

    private fun args(vararg pairs: Pair<String, Any?>): JsonObject = ToolkitIo.json(*pairs)

    private fun axmlManifest(): ByteArray = ResXmlDocument().let { xml ->
        val root = xml.newElement("manifest")
        root.getOrCreateAttribute("package", 0).setValueAsString("com.example.probe")
        root.getOrCreateNamespace("http://schemas.android.com/apk/res/android", "android")
        root.newElement("uses-sdk").getOrCreateAndroidAttribute("minSdkVersion", 0x0101020c).apply {
            setType(0x10.toByte()); data = 21
        }
        root.newElement("application").getOrCreateAndroidAttribute("name", 0x01010003).setValueAsString(".ProbeApp")
        xml.refreshFull()
        xml.bytes
    }

    private fun dexBytes(): ByteArray {
        val smali = temp.newFile("Probe.smali").apply {
            writeText("""
                .class public Lfixture/Probe;
                .super Ljava/lang/Object;
                .method public static value()I
                    .registers 1
                    const/4 v0, 0x3
                    return v0
                .end method
            """.trimIndent())
        }
        val dex = File(temp.root, "classes.dex")
        assertTrue(Smali.assemble(SmaliOptions().apply { outputDexFile = dex.path; jobs = 1 }, listOf(smali.path)))
        return dex.readBytes()
    }

    /** manifest=null 时用坏 AXML；withResources=false 时故意不放 resources.arsc（缺资源上下文）。 */
    private fun fixture(name: String, manifest: ByteArray? = axmlManifest(), withResources: Boolean = false): File {
        val apk = File(home, name)
        ZipOutputStream(apk.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write(manifest ?: byteArrayOf(0x03, 0x00, 0x08, 0x00, 0x20, 0x00, 0x00, 0x00) + ByteArray(24))
            zip.closeEntry()
            if (withResources) {
                val table = TableBlock()
                table.newPackage(0x7f, "com.example.probe").getOrCreate("", "string", "note").setValueAsString("v1")
                table.refreshFull()
                zip.putNextEntry(ZipEntry("resources.arsc")); zip.write(table.bytes); zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("assets/note.txt")); zip.write("v1".toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("classes.dex")); zip.write(dexBytes()); zip.closeEntry()
        }
        return apk
    }

    @Before fun setup() {
        home = temp.newFolder("home")
        store = temp.newFolder("private")
        tools = McpApkToolkit(home, store, JsonObject())
    }

    private fun openAndSession(apk: File): Pair<String, String> {
        val workspace = tools.call("apk_workspace_open", args("path" to apk.name, "temporary" to true))
            .getAsJsonObject("data").tString("workspaceId")
        val session = tools.call("apk_session_create", args("workspaceId" to workspace))
            .getAsJsonObject("data").tString("editSessionId")
        return workspace to session
    }

    @Test fun buildSucceedsWhenManifestNeedsResourceContextAndReportsFallbackWarning() {
        val apk = fixture("probe-no-arsc.apk", withResources = false)
        val (workspace, session) = openAndSession(apk)
        val checked = tools.call("apk_session_check", args(
            "workspaceId" to workspace, "editSessionId" to session, "runBuildChecks" to true,
        )).getAsJsonObject("data")
        assertTrue("会话检查不能因为缺资源上下文失败：" + checked, checked.get("valid").asBoolean)
        assertEquals(0, checked.get("failedCount").asInt)
        assertEquals(1, checked.get("warningCount").asInt)
        val warning = checked.getAsJsonArray("warnings").get(0).asJsonObject
        assertEquals("MANIFEST_PRIMARY_DECODER_FALLBACK", warning.get("code").asString)
        assertEquals("resource_context_missing", warning.get("failureKind").asString)
        assertTrue(warning.get("primaryError").asString.isNotEmpty())
        assertEquals("com.example.probe", warning.get("packageName").asString)
        assertTrue(checked.getAsJsonArray("nextActions").get(0).asString.contains("apk_verify"))

        val built = tools.call("apk_build", args(
            "workspaceId" to workspace, "editSessionId" to session,
            "outputName" to "probe-built.apk", "overwrite" to true, "sign" to false, "organize" to false,
        )).getAsJsonObject("data")
        assertEquals(1, built.get("warningCount").asInt)
        val buildWarning = built.getAsJsonArray("warnings").get(0).asJsonObject
        assertEquals("MANIFEST_PRIMARY_DECODER_FALLBACK", buildWarning.get("code").asString)
        assertTrue(File(home, "probe-built.apk").isFile)
        assertTrue(built.tString("sha256", "").isNotEmpty())
    }

    @Test fun corruptBinaryManifestStillFailsWithDistinguishableCode() {
        val apk = fixture("probe-corrupt.apk", manifest = null)
        val (workspace, session) = openAndSession(apk)
        val failure = runCatching {
            tools.call("apk_build", args(
                "workspaceId" to workspace, "editSessionId" to session,
                "outputName" to "corrupt-built.apk", "overwrite" to true, "sign" to false, "organize" to false,
            ))
        }.exceptionOrNull()
        assertNotNull("坏 AXML 必须失败", failure)
        val message = failure!!.message.orEmpty()
        assertTrue(message, message.contains("会话检查失败"))
        val allowed = listOf("MANIFEST_UNREADABLE", "MANIFEST_FORMAT_UNSUPPORTED", "MANIFEST_BACKEND_ERROR")
        assertTrue("错误码必须可区分（不能还是笼统的 BUILD_CHECK_FAILED）：" + message, allowed.any { message.contains(it) })
        assertFalse("坏 AXML 不该被兜底解析放过：" + message, message.contains("MANIFEST_PRIMARY_DECODER_FALLBACK"))
        assertTrue("必须保留原文 primaryError：" + message, message.contains("primaryError"))
        assertFalse(File(home, "corrupt-built.apk").exists())
    }

    @Test fun apkVerifyAndBuildReportTheSameFailureKindAndPrimaryError() {
        val apk = fixture("probe-same-source.apk", withResources = false)
        val verify = tools.call("apk_verify", args("path" to apk.name)).getAsJsonObject("data")
        val verifyIssue = verify.getAsJsonArray("issues").map { it.asJsonObject }
            .first { it.get("code").asString == "MANIFEST_PRIMARY_DECODER_FALLBACK" }
        val (workspace, session) = openAndSession(apk)
        val built = tools.call("apk_build", args(
            "workspaceId" to workspace, "editSessionId" to session,
            "outputName" to "same-source-built.apk", "overwrite" to true, "sign" to false, "organize" to false,
        )).getAsJsonObject("data")
        val buildWarning = built.getAsJsonArray("warnings").get(0).asJsonObject
        assertEquals(
            "apk_verify 与 apk_build 的 failureKind 必须同源",
            verifyIssue.get("failureKind").asString, buildWarning.get("failureKind").asString,
        )
        assertEquals(
            "apk_verify 与 apk_build 的 primaryError 必须同源",
            verifyIssue.get("primaryError").asString, buildWarning.get("primaryError").asString,
        )
    }
}
