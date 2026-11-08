package com.jdkshen.aijspro.mcp

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * task-23：apk_read_signature 必须把「只读了证书元数据」与「真的验签了」分开说清楚。
 *
 * 背景（.4 §4.2 + Lead 在 K40 的实测）：真正做密码学验签的是 apk_verify{workspaceId}，
 * 但 apk_read_signature 过去硬编码 verified:false + coverage=certificate metadata，
 * 调用方看到「验签失败/未验证」不会想到去调 apk_verify，白跑一轮。
 */
class McpApkSignatureReadTest {
    @get:Rule val temp = TemporaryFolder()

    private lateinit var store: File
    private lateinit var home: File

    private fun init() {
        store = temp.newFolder("store")
        home = temp.newFolder("home")
    }

    /** 合成最小 APK：ZIP + AndroidManifest.xml + classes.dex（未签名）。 */
    private fun workspace(id: String): String {
        val dir = File(store, id)
        assertTrue(dir.mkdirs())
        File(dir, "workspace.json").writeText(ToolkitIo.json(
            "workspaceId" to id, "temporary" to true, "size" to 8L,
            "createdAt" to System.currentTimeMillis(), "sourcePath" to ("/probe/" + id + ".apk"),
        ).toString(), Charsets.UTF_8)
        ZipOutputStream(File(dir, "base.apk").outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write("<manifest package='com.example.sig'/>".toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("classes.dex"))
            zip.write(byteArrayOf(0x64, 0x65, 0x78, 0x0a, 0x30, 0x33, 0x35, 0x00))
            zip.closeEntry()
        }
        return id
    }

    private fun call(args: JsonObject): JsonObject =
        McpApkToolkit(home, store, JsonObject()).call("apk_read_signature", args).getAsJsonObject("data")

    @Test fun defaultCallReadsMetadataOnlyAndNeverClaimsVerified() {
        init()
        val id = workspace("11111111-1111-1111-1111-111111111111")
        val data = call(ToolkitIo.json("workspaceId" to id))
        assertEquals("not_run", data.get("verification").asString)
        assertFalse(data.get("verified").asBoolean)
        assertTrue(data.get("verificationReason").asString.contains("不是"))
        assertTrue(data.get("verifyWith").asString.contains("apk_verify"))
        assertTrue(data.get("coverage").asString.contains("certificate metadata"))
        assertTrue(data.has("signature"))
        // 元数据段自己也不能声称验签通过
        assertFalse(data.getAsJsonObject("signature").get("verified").asBoolean)
        assertFalse(data.has("schemes"))
    }

    @Test fun verifyTrueRunsCryptographicVerificationInTheSameCall() {
        init()
        val id = workspace("22222222-2222-2222-2222-222222222222")
        val data = call(ToolkitIo.json("workspaceId" to id, "verify" to true))
        assertEquals("executed", data.get("verification").asString)
        assertTrue(data.get("verificationBackend").asString.contains("apksig"))
        assertTrue(data.get("verifyWith").asString.contains("apk_verify"))
        assertFalse(data.get("verified").asBoolean)  // 未签名夹具 ⇒ 不可能是 true
        val schemes = data.getAsJsonObject("schemes")
        assertNotNull("verify=true 必须给逐方案状态", schemes)
        for (scheme in listOf("V1", "V2", "V3", "V4")) {
            assertTrue("缺少方案 " + scheme, schemes.has(scheme))
            assertTrue(schemes.getAsJsonObject(scheme).has("status"))
        }
        assertTrue(data.has("verificationStatus"))
    }

    @Test fun missingWorkspaceFailsStructuredInsteadOfSilentlySucceeding() {
        init()
        val failure = runCatching { call(ToolkitIo.json("workspaceId" to "33333333-3333-3333-3333-333333333333", "verify" to true)) }
            .exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure is ToolkitException)
        assertEquals("WORKSPACE_EXPIRED", (failure as ToolkitException).code)
    }

    /** schema 必须声明 verify/includeEditSessions（否则会退化成 string，客户端按 boolean 传会被拒）。 */
    @Test fun schemaDeclaresVerifyAndIncludeEditSessionsAsBoolean() {
        val schemas = JsonParser().parse(File("src/main/assets/mcp/toolkit-apk-schemas.json").readText()).asJsonObject
        val verify = schemas.getAsJsonObject("apk_read_signature").getAsJsonObject("properties").getAsJsonObject("verify")
        assertEquals("boolean", verify.get("type").asString)
        assertEquals(false, verify.get("default").asBoolean)
        val includeEditSessions = schemas.getAsJsonObject("apk_workspace_gc").getAsJsonObject("properties")
            .getAsJsonObject("includeEditSessions")
        assertEquals("boolean", includeEditSessions.get("type").asString)
        assertEquals(false, includeEditSessions.get("default").asBoolean)
    }
}
