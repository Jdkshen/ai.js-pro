package com.jdkshen.aijspro.mcp

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.android.apksig.ApkSigner
import com.jdkshen.aijspro.autojs.build.sign.KeyStoreGenerator
import org.jf.smali.Smali
import org.jf.smali.SmaliOptions
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * task-30：chain_resume 编排链 —— checkpoint → 回编 → 对齐/签名 → 验签。
 *
 * 覆盖：检查点不存在 / expectedRevision 过期（结构化失败且无产物）、签名步缺失时中途停下并标 skipped、
 * steps="build" 时 verified=false 且说明跳过了什么、成功路径 finalArtifact.sha256 与磁盘一致、
 * 恢复成功时回显 restoredFrom 且会话真的回到检查点。
 */
class McpChainResumeTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var home: File
    private lateinit var store: File
    private lateinit var tools: McpApkToolkit
    private lateinit var source: File

    private fun args(vararg pairs: Pair<String, Any?>): JsonObject = ToolkitIo.json(*pairs)

    @Before fun setup() {
        home = temp.newFolder("home")
        store = temp.newFolder("private")
        // 故意不传 signer：回编可以成功，但 steps=all（要签名）时会在签名阶段失败 —— 用于「中途失败即停」。
        tools = McpApkToolkit(home, store, JsonObject())
        val smali = temp.newFile("Chain.smali").apply {
            writeText("""
                .class public Lfixture/Chain;
                .super Ljava/lang/Object;
                .method public static value()I
                    .registers 1
                    const/4 v0, 0x7
                    return v0
                .end method
            """.trimIndent())
        }
        val dex = File(temp.root, "classes.dex")
        assertTrue(Smali.assemble(SmaliOptions().apply { outputDexFile = dex.path; jobs = 1 }, listOf(smali.path)))
        // 真二进制 AXML（ARSCLib + android 资源 id）：apksig 需要能解析 uses-sdk/minSdkVersion，
        // 纯文本 Manifest 会让签名后的验签报 malformed binary resource。
        val manifest = com.reandroid.arsc.chunk.xml.ResXmlDocument().let { xml ->
            val root = xml.newElement("manifest")
            root.getOrCreateAttribute("package", 0).setValueAsString("com.example.chain")
            root.getOrCreateNamespace("http://schemas.android.com/apk/res/android", "android")
            root.newElement("uses-sdk").getOrCreateAndroidAttribute("minSdkVersion", 0x0101020c).apply {
                setType(0x10.toByte()); data = 21
            }
            root.newElement("application").getOrCreateAndroidAttribute("name", 0x01010003).setValueAsString(".ChainApp")
            xml.refreshFull()
            xml.bytes
        }
        // 真实（最小）资源表：ARSCLib 解码 AXML 需要 package，否则 build 的会话检查会报 "Can not decode without package"。
        val table = com.reandroid.arsc.chunk.TableBlock()
        table.newPackage(0x7f, "com.example.chain").getOrCreate("", "string", "note").setValueAsString("v1")
        table.refreshFull()
        source = File(home, "chain.apk")
        ZipOutputStream(source.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml")); zip.write(manifest); zip.closeEntry()
            zip.putNextEntry(ZipEntry("resources.arsc")); zip.write(table.bytes); zip.closeEntry()
            zip.putNextEntry(ZipEntry("assets/note.txt")); zip.write("v1".toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("classes.dex")); zip.write(dex.readBytes()); zip.closeEntry()
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun openWorkspace(): String =
        tools.call("apk_workspace_open", args("path" to source.name, "temporary" to true))
            .getAsJsonObject("data").tString("workspaceId")

    private fun openSession(workspace: String): String =
        tools.call("apk_session_create", args("workspaceId" to workspace))
            .getAsJsonObject("data").tString("editSessionId")

    private fun editNote(workspace: String, session: String, text: String) {
        val before = tools.call("apk_read_text", args(
            "workspaceId" to workspace, "editSessionId" to session, "locator" to "zip_entry:assets/note.txt",
        )).getAsJsonObject("data")
        tools.call("apk_edit_text", args(
            "workspaceId" to workspace, "editSessionId" to session, "locator" to "zip_entry:assets/note.txt",
            "targetVersion" to before.tString("targetVersion"),
            "edits" to listOf(args("mode" to "write_target", "writeText" to text)),
        ))
    }

    private fun checkpoint(workspace: String, session: String, revision: String): String =
        tools.call("apk_session_checkpoint", args(
            "workspaceId" to workspace, "editSessionId" to session, "expectedRevision" to revision,
        )).getAsJsonObject("data").tString("checkpointId")

    private fun revisionOf(workspace: String, session: String): String =
        JsonParser().parse(File(File(File(store, workspace), "sessions"), session).resolve("session.json").readText())
            .asJsonObject.tString("revision")

    private fun zipText(file: File, entry: String): String =
        ZipFile(file).use { it.getInputStream(it.getEntry(entry)).readBytes().toString(Charsets.UTF_8) }

    private fun chainStep(data: JsonObject, step: String): JsonObject =
        data.getAsJsonArray("chain").map { it.asJsonObject }.first { it.get("step").asString == step }

    private fun failure(block: () -> Unit): ToolkitException {
        try {
            block()
        } catch (error: ToolkitException) {
            return error
        }
        fail("expected ToolkitException")
        throw AssertionError("unreachable")
    }

    // ---------------------------------------------------------------- ① 恢复失败

    @Test fun missingCheckpointFailsStructurallyWithoutAnyArtifact() {
        val workspace = openWorkspace()
        val session = openSession(workspace)
        val revision = revisionOf(workspace, session)
        val out = File(home, "chain-missing.apk")
        val error = failure {
            tools.call("chain_resume", args(
                "workspaceId" to workspace, "editSessionId" to session,
                "checkpoint" to "11111111-2222-3333-4444-555555555555",
                "expectedRevision" to revision, "outPath" to out.name, "overwrite" to true,
            ))
        }
        assertEquals("CHAIN_RESTORE_FAILED", error.code)
        val message = error.message.orEmpty()
        assertTrue(message, message.contains("stage"))
        assertTrue(message, message.contains("availableCheckpoints"))
        assertTrue(message, message.contains("链未开始"))
        assertFalse("恢复失败不得产出任何产物", out.exists())
    }

    @Test fun staleExpectedRevisionFailsInsteadOfUsingCurrentRevision() {
        val workspace = openWorkspace()
        val session = openSession(workspace)
        val oldRevision = revisionOf(workspace, session)
        val checkpoint = checkpoint(workspace, session, oldRevision)
        editNote(workspace, session, "v2")
        val current = revisionOf(workspace, session)
        assertNotEquals(oldRevision, current)
        val out = File(home, "chain-stale.apk")
        val error = failure {
            tools.call("chain_resume", args(
                "workspaceId" to workspace, "editSessionId" to session,
                "checkpoint" to checkpoint, "expectedRevision" to oldRevision,
                "outPath" to out.name, "overwrite" to true,
            ))
        }
        assertEquals("CHAIN_RESTORE_FAILED", error.code)
        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("会话已变更"))
        assertFalse(out.exists())
        // 会话没有被改动（失败不产生副作用）
        assertEquals(current, revisionOf(workspace, session))
    }

    // ---------------------------------------------------------------- ② 中途失败即停

    @Test fun chainStopsAtBuildWhenSignerIsUnavailableAndMarksLaterStepsSkipped() {
        val workspace = openWorkspace()
        val session = openSession(workspace)
        val data = tools.call("chain_resume", args(
            "workspaceId" to workspace, "editSessionId" to session,
            "steps" to "all", "outPath" to "chain-should-not-exist.apk", "overwrite" to true,
        )).getAsJsonObject("data")
        assertFalse("整链绝不能标成功", data.get("ok").asBoolean)
        assertEquals("build", data.get("stoppedAt").asString)
        assertEquals("CHAIN_STEP_FAILED", data.get("errorCode").asString)
        val failureRow = data.getAsJsonObject("failure")
        assertEquals("build", failureRow.get("where").asString)
        assertEquals("sign", failureRow.get("phase").asString)
        assertTrue(failureRow.get("message").asString, failureRow.get("message").asString.contains("签名器"))
        assertEquals("failed", chainStep(data, "build").get("status").asString)
        assertEquals("skipped", chainStep(data, "align+sign").get("status").asString)
        assertEquals("skipped", chainStep(data, "verify").get("status").asString)
        assertTrue(chainStep(data, "verify").get("reason").asString.contains("前一步失败即停"))
        assertTrue(data.get("finalArtifact").isJsonNull)
        assertFalse(data.get("verified").asBoolean)
        assertFalse(data.get("runtimeVerified").asBoolean)
        val notes = data.getAsJsonArray("notes").joinToString(" ") { it.asString }
        assertTrue(notes, notes.contains("链路在 build 停止"))
        assertFalse(File(home, "chain-should-not-exist.apk").exists())
    }

    @Test fun buildFailureFromBadOutputPathAlsoStopsImmediately() {
        val workspace = openWorkspace()
        val session = openSession(workspace)
        // 目标已存在且没给 overwrite → 回编在写盘前就失败
        val existing = File(home, "already-there.apk").apply { writeText("not an apk") }
        val data = tools.call("chain_resume", args(
            "workspaceId" to workspace, "editSessionId" to session,
            "steps" to "all", "outPath" to existing.name,
        )).getAsJsonObject("data")
        assertFalse(data.get("ok").asBoolean)
        assertEquals("build", data.get("stoppedAt").asString)
        assertEquals("failed", chainStep(data, "build").get("status").asString)
        assertEquals("skipped", chainStep(data, "align+sign").get("status").asString)
        assertEquals("skipped", chainStep(data, "verify").get("status").asString)
        assertEquals("not an apk", existing.readText())
    }

    // ---------------------------------------------------------------- ③ 裁剪 steps

    @Test fun buildOnlyReportsVerifiedFalseAndExplainsWhatWasSkipped() {
        val workspace = openWorkspace()
        val session = openSession(workspace)
        val out = File(home, "chain-build-only.apk")
        val data = tools.call("chain_resume", args(
            "workspaceId" to workspace, "editSessionId" to session,
            "steps" to "build", "outPath" to out.name, "overwrite" to false, "organize" to false,
        )).getAsJsonObject("data")
        assertTrue(data.get("ok").asBoolean)
        assertTrue(data.get("stoppedAt").isJsonNull)
        assertEquals("build", data.get("stepsRequested").asString)
        assertEquals(listOf("build"), data.getAsJsonArray("stepsEffective").map { it.asString })
        assertEquals("ok", chainStep(data, "build").get("status").asString)
        assertEquals("skipped", chainStep(data, "align+sign").get("status").asString)
        assertEquals("skipped", chainStep(data, "verify").get("status").asString)
        assertFalse("没跑验签就不是 verified", data.get("verified").asBoolean)
        assertFalse(data.get("runtimeVerified").asBoolean)
        val artifact = data.getAsJsonObject("finalArtifact")
        assertEquals(out.path, artifact.get("path").asString)
        assertEquals(out.length(), artifact.get("bytes").asLong)
        assertEquals(ToolkitIo.sha(out), artifact.get("sha256").asString)
        assertEquals("current", data.getAsJsonObject("restoredFrom").get("source").asString)
        val notes = data.getAsJsonArray("notes").joinToString(" ") { it.asString }
        assertTrue(notes, notes.contains("跳过了 align+sign"))
        assertTrue(notes, notes.contains("跳过了 verify"))
        assertTrue(notes, notes.contains("verified=false 只代表没跑验签"))
    }

    @Test fun stepsRejectsUnknownValue() {
        val workspace = openWorkspace()
        val session = openSession(workspace)
        val error = failure {
            tools.call("chain_resume", args(
                "workspaceId" to workspace, "editSessionId" to session, "steps" to "build+sign",
            ))
        }
        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("steps 只能是"))
    }

    // ---------------------------------------------------------------- ④⑤ 成功路径 + 恢复回显

    @Test fun checkpointRestoreActuallyRollsBackAndIsEchoedInRestoredFrom() {
        val workspace = openWorkspace()
        val session = openSession(workspace)
        val revision = revisionOf(workspace, session)
        val checkpoint = checkpoint(workspace, session, revision)
        editNote(workspace, session, "v2")
        val current = revisionOf(workspace, session)
        assertNotEquals(revision, current)
        val out = File(home, "chain-restored.apk")
        val data = tools.call("chain_resume", args(
            "workspaceId" to workspace, "editSessionId" to session,
            "checkpoint" to checkpoint, "expectedRevision" to current,
            "steps" to "build", "outPath" to out.name, "overwrite" to false, "organize" to false,
        )).getAsJsonObject("data")
        assertTrue(data.get("ok").asBoolean)
        val restored = data.getAsJsonObject("restoredFrom")
        assertEquals("checkpoint", restored.get("source").asString)
        assertEquals(checkpoint, restored.get("checkpoint").asString)
        assertEquals(current, restored.get("previousRevision").asString)
        assertNotEquals(current, restored.get("revision").asString)
        assertTrue(out.isFile)
        assertEquals("v1", zipText(out, "assets/note.txt"))
        assertEquals(ToolkitIo.sha(out), data.getAsJsonObject("finalArtifact").get("sha256").asString)
    }

    @Test fun fullChainWithRealSignerAndVerificationReportsVerifiedTrue() {
        // 注入一个真实签名实现（KeyStoreGenerator 自签 + KeyStoreApkSigner），走 build 的 keystore 回退路径。
        val signedHome = temp.newFolder("home-signed")
        val signedStore = temp.newFolder("private-signed")
        // 直接用 apksig + KeyStoreGenerator 自签身份做签名（KeyStoreApkSigner 会调 android.util.Log，JVM 上是 Stub!）。
        val signing = McpApkToolkit(signedHome, signedStore, JsonObject(), signer = { input, output ->
            val key = KeyStoreGenerator.generate("chain-test", "aijs", "CN")
            val config = ApkSigner.SignerConfig.Builder("chain-test", key.privateKey, key.certificateChain.toList()).build()
            ApkSigner.Builder(listOf(config))
                .setInputApk(input)
                .setOutputApk(output)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .setMinSdkVersion(21)
                .build()
                .sign()
        })
        val apk = File(signedHome, "chain.apk").also { source.copyTo(it, overwrite = true) }
        val workspace = signing.call("apk_workspace_open", args("path" to apk.name, "temporary" to true))
            .getAsJsonObject("data").tString("workspaceId")
        val session = signing.call("apk_session_create", args("workspaceId" to workspace))
            .getAsJsonObject("data").tString("editSessionId")
        val out = File(signedHome, "chain-signed.apk")
        val data = signing.call("chain_resume", args(
            "workspaceId" to workspace, "editSessionId" to session,
            "steps" to "all", "outPath" to out.name, "overwrite" to false, "organize" to false,
        )).getAsJsonObject("data")
        assertTrue(data.toString(), data.get("ok").asBoolean)
        assertTrue("签名+验签成功时 verified 必须来自 verifier：$data", data.get("verified").asBoolean)
        assertTrue(data.get("runtimeVerified").asBoolean)
        assertTrue(data.getAsJsonObject("verification").get("schemes").isJsonObject)
        assertEquals("ok", chainStep(data, "build").get("status").asString)
        assertEquals("ok", chainStep(data, "align+sign").get("status").asString)
        assertEquals("ok", chainStep(data, "verify").get("status").asString)
        assertTrue(out.isFile)
        assertEquals(ToolkitIo.sha(out), data.getAsJsonObject("finalArtifact").get("sha256").asString)
    }

    @Test fun successPathSha256MatchesDiskAndSchemaDeclaresChainResume() {
        val workspace = openWorkspace()
        val session = openSession(workspace)
        editNote(workspace, session, "v3")
        val out = File(home, "chain-success.apk")
        val data = tools.call("chain_resume", args(
            "workspaceId" to workspace, "editSessionId" to session,
            "steps" to "build", "outPath" to out.name, "overwrite" to false, "organize" to false,
        )).getAsJsonObject("data")
        assertTrue(data.get("ok").asBoolean)
        assertEquals("v3", zipText(out, "assets/note.txt"))
        assertEquals(ToolkitIo.sha(out), data.getAsJsonObject("finalArtifact").get("sha256").asString)
        assertEquals(out.length(), data.getAsJsonObject("finalArtifact").get("bytes").asLong)
        assertEquals("chain-resume/1", data.get("version").asString)

        // schema 必须声明运行时读的参数（否则客户端按 boolean 传会被拒）
        val schemas = JsonParser().parse(File("src/main/assets/mcp/toolkit-apk-schemas.json").readText()).asJsonObject
        val props = schemas.getAsJsonObject("chain_resume").getAsJsonObject("properties")
        assertEquals("boolean", props.getAsJsonObject("overwrite").get("type").asString)
        assertEquals("boolean", props.getAsJsonObject("organize").get("type").asString)
        assertEquals("string", props.getAsJsonObject("steps").get("type").asString)
        assertEquals(4, props.getAsJsonObject("steps").getAsJsonArray("enum").size())
        assertEquals(listOf("workspaceId", "editSessionId"), schemas.getAsJsonObject("chain_resume").getAsJsonArray("required").map { it.asString })
    }
}
