package com.jdkshen.aijspro.mcp

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import org.jf.smali.Smali
import org.jf.smali.SmaliOptions
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * task-25 11.2 第一刀：vm_survey 的纯函数（聚类/阈值、线性反汇编覆盖与停点、块起点、打分）
 * + 合成 minimal DEX 上的结构断言（候选/负载/簇/CFG）+ 工具级 path 派发。
 */
class ToolkitVmSurveyTest {
    @get:Rule val temp = TemporaryFolder()

    // ---------------------------------------------------------------- 2) 聚类

    @Test fun exactSameSignatureClustersGroupIdenticalHistograms() {
        val a = ToolkitVmSurvey.HistogramMember("m-a", "classes.dex", mapOf("PACKED_SWITCH" to 3, "RETURN" to 1))
        val b = ToolkitVmSurvey.HistogramMember("m-b", "classes.dex", mapOf("RETURN" to 1, "PACKED_SWITCH" to 3))
        val c = ToolkitVmSurvey.HistogramMember("m-c", "classes.dex", mapOf("PACKED_SWITCH" to 3, "RETURN" to 2))
        val clusters = ToolkitVmSurvey.cluster(listOf(a, b, c), 0)
        assertEquals(2, clusters.size)
        val big = clusters.first { it.members.size == 2 }
        assertFalse("精确同签名不该标 approximate", big.approximate)
        assertEquals("PACKED_SWITCH:3,RETURN:1", big.signature)
        assertEquals(listOf("m-a", "m-b"), big.members.map { it.id })
        assertEquals(listOf("c0", "c1"), clusters.map { it.clusterId })
    }

    @Test fun approxClusteringMergesOnlyAtOrBelowTheThreshold() {
        val base = mapOf("A" to 50, "B" to 50)
        val near = mapOf("A" to 48, "B" to 52)
        val far = mapOf("A" to 80, "B" to 20)
        assertEquals(2.0, ToolkitVmSurvey.distancePercent(base, near), 0.001)
        assertEquals(30.0, ToolkitVmSurvey.distancePercent(base, far), 0.001)
        assertEquals(0.0, ToolkitVmSurvey.distancePercent(base, base), 0.001)
        val members = listOf(
            ToolkitVmSurvey.HistogramMember("m-base", "classes.dex", base),
            ToolkitVmSurvey.HistogramMember("m-near", "classes.dex", near),
            ToolkitVmSurvey.HistogramMember("m-far", "classes.dex", far),
        )
        assertEquals(3, ToolkitVmSurvey.cluster(members, 1).size)
        val atBoundary = ToolkitVmSurvey.cluster(members, 2)
        assertEquals(2, atBoundary.size)
        assertEquals(2, atBoundary.first { it.members.size == 2 }.members.size)
        assertTrue(atBoundary.first { it.members.size == 2 }.approximate)
        assertEquals(1, ToolkitVmSurvey.cluster(members, 30).size)
        assertEquals(2, ToolkitVmSurvey.cluster(members, 29).size)
    }

    // ---------------------------------------------------------------- 5) 线性反汇编

    @Test fun linearDisassemblyStopsOnUnknownIndexAndReportsCoverage() {
        val bytes = byteArrayOf(0x01, 0x01, 0x02, 0xFF.toByte(), 0x01)
        val rules = listOf(
            ToolkitVmSurvey.OpcodeRule(0x01, "mov", 2),
            ToolkitVmSurvey.OpcodeRule(0x02, "ret", 1),
        )
        val result = ToolkitVmSurvey.disassemble(bytes, rules)
        assertEquals(2, result.instructions.size)
        assertEquals(0, result.instructions[0].offset)
        assertEquals("mov", result.instructions[0].mnemonic)
        assertEquals("0101", result.instructions[0].bytesHex)
        assertEquals(2, result.instructions[1].offset)
        assertEquals(3, result.coveredBytes)
        assertEquals(5, result.totalBytes)
        assertEquals(60.0, result.coveragePercent, 0.001)
        assertEquals(3, result.stopsAt)
        assertEquals("unknown_opcode_index:255", result.stopReason)
        assertTrue(result.ruleWarnings.isEmpty())
    }

    @Test fun linearDisassemblyStopsOnTruncatedInstructionAndMaxInstructions() {
        val truncated = ToolkitVmSurvey.disassemble(byteArrayOf(0x01), listOf(ToolkitVmSurvey.OpcodeRule(0x01, "mov", 2)))
        assertTrue(truncated.instructions.isEmpty())
        assertEquals(0, truncated.stopsAt)
        assertEquals("truncated_instruction", truncated.stopReason)
        assertEquals(0.0, truncated.coveragePercent, 0.001)

        val clean = byteArrayOf(0x02, 0x02, 0x02)
        val capped = ToolkitVmSurvey.disassemble(clean, listOf(ToolkitVmSurvey.OpcodeRule(0x02, "ret", 1)), maxInstructions = 2)
        assertEquals(2, capped.instructions.size)
        assertEquals(2, capped.stopsAt)
        assertEquals("max_instructions", capped.stopReason)
        assertEquals(66.67, capped.coveragePercent, 0.01)

        val endOfBlob = ToolkitVmSurvey.disassemble(clean, listOf(ToolkitVmSurvey.OpcodeRule(0x02, "ret", 1)))
        assertEquals("end_of_blob", endOfBlob.stopReason)
        assertNull(endOfBlob.stopsAt)
        assertEquals(100.0, endOfBlob.coveragePercent, 0.001)
    }

    @Test fun linearDisassemblyWarnsOnInvalidRulesInsteadOfSilentlyApplying() {
        val result = ToolkitVmSurvey.disassemble(
            byteArrayOf(0x02, 0x02),
            listOf(
                ToolkitVmSurvey.OpcodeRule(0x01, "a", 0),
                ToolkitVmSurvey.OpcodeRule(300, "b", 1),
                ToolkitVmSurvey.OpcodeRule(0x02, "c", 1),
                ToolkitVmSurvey.OpcodeRule(0x02, "c2", 1),
            ),
        )
        assertEquals(3, result.ruleWarnings.size)
        assertEquals(2, result.instructions.size)
        assertEquals("c2", result.instructions[1].mnemonic)
    }

    // ---------------------------------------------------------------- 6) 块起点

    @Test fun blockStartsComeFromBranchTargetsAndTerminators() {
        val offsets = listOf(0, 4, 8, 12, 16)
        val (starts, dropped) = ToolkitVmSurvey.blockStarts(
            codeSize = 20, instructionOffsets = offsets,
            branchTargets = listOf(12, 99, 6), terminatingOffsets = listOf(4),
        )
        assertEquals(listOf(0, 8, 12), starts)
        assertEquals(listOf(99, 6), dropped)
        val (only, none) = ToolkitVmSurvey.blockStarts(20, offsets, emptyList(), emptyList())
        assertEquals(listOf(0), only)
        assertTrue(none.isEmpty())
        val (again, outOfRange) = ToolkitVmSurvey.blockStarts(20, offsets, listOf(-4, 20), emptyList())
        assertEquals(listOf(0), again)
        assertEquals(listOf(-4, 20), outOfRange)
    }

    // ---------------------------------------------------------------- 1) 打分

    @Test fun scoreIsExplainableAndBounded() {
        val plain = ToolkitVmSurvey.MethodFeatures(8, 0, 0, 0, 0, 0, 0, 0)
        val (plainScore, plainParts) = ToolkitVmSurvey.scoreOf(plain, 64)
        assertEquals(0, plainScore)
        assertTrue(plainParts.isEmpty())

        val shell = ToolkitVmSurvey.MethodFeatures(
            instructionCount = 400, switchCount = 2, switchCases = 64, backBranches = 4,
            arrayOps = 30, invokeCount = 12, bigLiterals = 4, classPayloadBytes = 8192,
        )
        val (score, parts) = ToolkitVmSurvey.scoreOf(shell, 64)
        assertEquals(100, score)
        assertTrue(parts.any { it.first == "switch_dispatch" })
        assertTrue(parts.any { it.first == "large_constant_payload" })
        assertTrue("分数必须是各项之和（封顶 100）", parts.sumOf { it.second } >= score)
        val small = ToolkitVmSurvey.scoreOf(shell.copy(classPayloadBytes = 8), 64).second
        assertFalse(small.any { it.first == "large_constant_payload" })
    }

    // ---------------------------------------------------------------- 1/3/4/6 结构断言（合成 DEX）

    private val sampleSmali = "" +
        ".class public Lvmsurvey/Sample;\n" +
        ".super Ljava/lang/Object;\n" +
        "\n" +
        ".field public static final BLOB:Ljava/lang/String; = \"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\"\n" +
        "\n" +
        ".method public static plain(I)I\n" +
        "    .locals 1\n" +
        "    const/4 v0, 0x0\n" +
        "    return v0\n" +
        ".end method\n" +
        "\n" +
        ".method public static dispatch(I)I\n" +
        "    .locals 1\n" +
        "    packed-switch p0, :pswitch_data_0\n" +
        "    const/4 v0, -0x1\n" +
        "    return v0\n" +
        "    :pswitch_0\n" +
        "    const/4 v0, 0x1\n" +
        "    return v0\n" +
        "    :pswitch_1\n" +
        "    const/4 v0, 0x2\n" +
        "    return v0\n" +
        "    :pswitch_data_0\n" +
        "    .packed-switch 0x0\n" +
        "        :pswitch_0\n" +
        "        :pswitch_1\n" +
        "    .end packed-switch\n" +
        ".end method\n" +
        "\n" +
        ".method public static branchy(I)I\n" +
        "    .locals 1\n" +
        "    if-eqz p0, :cond_0\n" +
        "    const/4 v0, 0x1\n" +
        "    return v0\n" +
        "    :cond_0\n" +
        "    const/4 v0, 0x0\n" +
        "    return v0\n" +
        ".end method\n"

    private val plainOnlySmali = "" +
        ".class public Lvmsurvey/Plain;\n" +
        ".super Ljava/lang/Object;\n" +
        "\n" +
        ".method public static only(I)I\n" +
        "    .locals 1\n" +
        "    const/4 v0, 0x0\n" +
        "    return v0\n" +
        ".end method\n"

    private fun dexOf(smali: String): ByteArray {
        val work = temp.newFolder("smali-" + System.nanoTime())
        val source = File(work, "sample.smali")
        source.writeText(smali, Charsets.UTF_8)
        val output = File(work, "classes.dex")
        assertTrue("smali assemble failed", Smali.assemble(SmaliOptions().apply { outputDexFile = output.path; jobs = 1 }, listOf(source.path)))
        return output.readBytes()
    }

    private fun surveyOfDex(bytes: ByteArray, method: String? = null): JsonObject =
        ToolkitVmSurvey.survey(
            dexes = listOf("classes.dex" to ToolkitDex(bytes).classes),
            limit = 20,
            minScore = 0,
            payloadMinBytes = 16,
            approxPercent = 0,
            cfgMethodLocator = method,
        )

    @Test fun surveyFindsDispatchCandidatePayloadClusterAndCfgOnSyntheticDex() {
        val data = surveyOfDex(dexOf(sampleSmali), "dex_method:Lvmsurvey/Sample;->branchy(I)I")
        assertEquals("vm-survey/1", data.get("version").asString)
        assertEquals("static_heuristic", data.get("confidence").asString)
        val scanned = data.getAsJsonObject("scanned")
        assertEquals(1, scanned.get("dexCount").asInt)
        assertEquals(1, scanned.get("classCount").asInt)
        assertEquals(3, scanned.get("methodCount").asInt)
        assertFalse(scanned.get("truncated").asBoolean)

        val candidates = data.getAsJsonArray("candidates").map { it.asJsonObject }
        assertTrue("至少要有 dispatch 候选", candidates.isNotEmpty())
        val dispatch = candidates.first { it.get("method").asString.startsWith("dispatch(") }
        val plain = candidates.firstOrNull { it.get("method").asString.startsWith("plain(") }
        assertTrue(dispatch.get("score").asInt > 0)
        assertTrue(
            "dispatch 分数必须高于 plain：dispatch=" + dispatch.get("score").asInt + " " + dispatch.getAsJsonObject("contributions") +
                " plain=" + (plain?.get("score")?.asInt ?: -1) + " " + (plain?.getAsJsonObject("contributions") ?: "null"),
            plain == null || dispatch.get("score").asInt > plain.get("score").asInt,
        )
        assertTrue(dispatch.get("evidenceRef").asString.startsWith("dex_method:Lvmsurvey/Sample;->dispatch"))
        assertTrue(dispatch.getAsJsonObject("features").get("switchCount").asInt >= 1)
        assertTrue(dispatch.getAsJsonObject("contributions").keySet().contains("switch_dispatch"))

        // 分数分布摘要：与 candidateCount 同口径，且能区分「像壳」与「只是有特征」
        val buckets = data.getAsJsonObject("scoreBuckets")
        assertEquals(data.get("candidateCount").asInt, buckets.entrySet().sumOf { it.value.asInt })
        assertTrue("dispatch(带 switch) 应落在 40-59 桶：" + buckets, buckets.get("40-59").asInt >= 1)
        assertTrue("branchy(只有大负载) 应落在 1-39 桶：" + buckets, buckets.get("1-39").asInt >= 1)
        assertTrue(data.get("scoreBucketsNote").asString.contains("candidateCount"))

        assertTrue(data.get("clusterCount").asInt >= 2)
        val signatures = data.getAsJsonArray("clusters").map { it.asJsonObject.get("signature").asString }
        assertEquals(signatures.size, signatures.toSet().size)

        val payloads = data.getAsJsonArray("payloads").map { it.asJsonObject }
        val blob = payloads.first { it.get("field").asString.contains("BLOB") }
        assertEquals(64, blob.get("length").asInt)
        assertEquals("string", blob.get("kind").asString)
        assertEquals(64, blob.get("sha256").asString.length)
        assertEquals(32, blob.get("first16Hex").asString.length)
        assertTrue(blob.get("addr").isJsonNull)
        assertTrue(blob.get("addrNote").asString.contains("不要当成已定位"))

        val cfg = data.getAsJsonObject("cfg")
        assertEquals("Lvmsurvey/Sample;->branchy(I)I", cfg.get("method").asString)
        val blocks = cfg.getAsJsonArray("blockStarts").map { it.asInt }
        assertEquals(0, blocks.first())
        assertEquals(blocks.sorted(), blocks)
        assertTrue("有 if 就该有第二个块", blocks.size >= 2)
        assertEquals(cfg.get("instructionCount").asInt, cfg.getAsJsonObject("coverage").get("instructions").asInt)
        assertTrue(cfg.get("note").asString.contains("不推调用关系"))

        val notes = data.getAsJsonArray("notes").joinToString(" ") { it.asString }
        assertTrue(notes.contains("0 候选是合法结果"))
        assertTrue(notes.contains("不承诺任意 VMP 一键还原"))
        assertTrue(notes.contains("候选数量大不等于壳多"))
        assertFalse(data.toString().contains("已还原"))
    }

    @Test fun surveyWithNoSwitchAndNoPayloadReportsZeroCandidatesHonestly() {
        val data = surveyOfDex(dexOf(plainOnlySmali))
        assertEquals(0, data.get("candidateCount").asInt)
        assertEquals(0, data.getAsJsonArray("candidates").size())
        assertEquals(0, data.getAsJsonArray("payloads").size())
        assertTrue(data.get("dispatchArray").isJsonNull)
        val notes = data.getAsJsonArray("notes").joinToString(" ") { it.asString }
        assertTrue(notes.contains("0 候选是合法结果"))
        assertTrue(notes.contains("大于等于 16 字节"))
        assertTrue(notes.contains("未找到可映射的 dispatch 数组"))
        assertTrue(data.get("cfg").isJsonNull)
        assertTrue(data.get("disassembly").isJsonNull)
    }

    @Test fun surveyDisassemblesUserSuppliedBlobWithRules() {
        val data = ToolkitVmSurvey.survey(
            dexes = listOf("classes.dex" to ToolkitDex(dexOf(sampleSmali)).classes),
            opcodeRules = listOf(ToolkitVmSurvey.OpcodeRule(0x01, "mov", 2), ToolkitVmSurvey.OpcodeRule(0x02, "ret", 1)),
            blob = byteArrayOf(0x01, 0x00, 0x02, 0x09),
            blobSource = "blobHex",
        )
        val disassembly = data.getAsJsonObject("disassembly")
        assertEquals("user_supplied_rules", disassembly.get("confidence").asString)
        assertEquals(2, disassembly.getAsJsonArray("instructions").size())
        assertEquals(75.0, disassembly.get("coveragePercent").asDouble, 0.001)
        assertEquals(3, disassembly.get("stopsAt").asInt)
        assertEquals("unknown_opcode_index:9", disassembly.get("stopReason").asString)
    }

    /** ① 修 bug：只给 blobHex + opcodeRules 时必须能跑通（blob-only，不需要 workspace/path）。 */
    @Test fun blobOnlyModeRunsWithoutWorkspaceOrPath() {
        val root = temp.newFolder("tool-blob")
        val store = temp.newFolder("store-blob")
        val toolkit = McpApkToolkit(root, store, JsonObject())
        val data = toolkit.call("vm_survey", ToolkitIo.json(
            "blobHex" to "0011223344",
            "opcodeRules" to listOf(
                ToolkitIo.json("index" to 0, "mnemonic" to "nop", "length" to 1),
                ToolkitIo.json("index" to 17, "mnemonic" to "ldi", "length" to 2),
            ),
        )).getAsJsonObject("data")
        assertTrue(data.get("blobOnly").asBoolean)
        assertEquals(0, data.getAsJsonArray("sources").size())
        assertEquals(0, data.getAsJsonObject("scanned").get("dexCount").asInt)
        assertEquals(0, data.get("candidateCount").asInt)
        assertTrue(data.get("workspaceId").isJsonNull)
        assertTrue(data.get("path").isJsonNull)
        val disassembly = data.getAsJsonObject("disassembly")
        assertEquals("blobHex", disassembly.get("blobSource").asString)
        assertEquals("user_supplied_rules", disassembly.get("confidence").asString)
        assertEquals(2, disassembly.getAsJsonArray("instructions").size())
        assertEquals(3, disassembly.get("coveredBytes").asInt)
        assertEquals(60.0, disassembly.get("coveragePercent").asDouble, 0.001)
        assertEquals(3, disassembly.get("stopsAt").asInt)
        // 0x00(nop,1) -> 0x11(ldi,2) -> 下一个是 0x33=51，没规则，停在这里
        assertEquals("unknown_opcode_index:51", disassembly.get("stopReason").asString)
        val notes = data.getAsJsonArray("notes").joinToString(" ") { it.asString }
        assertTrue("blob-only 必须自证没有扫 DEX：" + notes, notes.contains("blob-only"))
        assertFalse(data.toString().contains("已还原"))

        // 三个入口都没给：仍然报缺参数（结构化）
        val failure = runCatching { toolkit.call("vm_survey", ToolkitIo.json("limit" to 5)) }.exceptionOrNull()
        assertTrue(failure is ToolkitException)
        assertEquals("INVALID_ARGUMENT", (failure as ToolkitException).code)
    }

    @Test fun toolDispatchesVmSurveyFromPathAndSchemaDeclaresIntegerParams() {
        val root = temp.newFolder("tool")
        val apk = File(root, "sample.apk")
        ZipOutputStream(apk.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write("<manifest package='com.example.vm'/>".toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("classes.dex"))
            zip.write(dexOf(sampleSmali))
            zip.closeEntry()
        }
        val data = McpApkToolkit(root, temp.newFolder("store"), JsonObject())
            .call("vm_survey", ToolkitIo.json("path" to "sample.apk", "payloadMinBytes" to 16))
            .getAsJsonObject("data")
        assertEquals("static_heuristic", data.get("confidence").asString)
        assertEquals(listOf("classes.dex"), data.getAsJsonArray("sources").map { it.asString })
        assertTrue(data.get("candidateCount").asInt >= 1)
        assertEquals("sample.apk", data.get("path").asString)
        assertTrue(data.get("workspaceId").isJsonNull)

        val schemas = com.google.gson.JsonParser().parse(File("src/main/assets/mcp/toolkit-apk-schemas.json").readText()).asJsonObject
        val props = schemas.getAsJsonObject("vm_survey").getAsJsonObject("properties")
        for (name in listOf("limit", "minScore", "payloadMinBytes", "approxPercent", "maxInstructions")) {
            assertEquals(name + " 必须是 integer", "integer", props.getAsJsonObject(name).get("type").asString)
        }
        assertEquals("array", props.getAsJsonObject("opcodeRules").get("type").asString)
        assertTrue(
            "schema 必须说明 blob-only：只给 blobHex 时可省 workspaceId/path",
            props.getAsJsonObject("blobHex").get("description").asString.contains("blob-only"),
        )
    }

    // ---------------------------------------------------------------- 权重可配置（task-35）

    /** 合成夹具：一个大常量类（256 字节 String）+ 一个 packed-switch 类，两个类互不共享负载。 */
    private val payloadSmali = "" +
        ".class public Lvmpayload/Blob;\n" +
        ".super Ljava/lang/Object;\n" +
        "\n" +
        ".field public static final DATA:Ljava/lang/String; = \"" + "ab".repeat(128) + "\"\n" +
        "\n" +
        ".method public static touch()V\n" +
        "    .locals 0\n" +
        "    return-void\n" +
        ".end method\n"

    private val dispatchOnlySmali = "" +
        ".class public Lvmdispatch/Loop;\n" +
        ".super Ljava/lang/Object;\n" +
        "\n" +
        ".method public static run(I)I\n" +
        "    .locals 1\n" +
        "    packed-switch p0, :sw\n" +
        "    const/4 v0, -0x1\n" +
        "    return v0\n" +
        "    :a\n" +
        "    const/4 v0, 0x1\n" +
        "    return v0\n" +
        "    :b\n" +
        "    const/4 v0, 0x2\n" +
        "    return v0\n" +
        "    :sw\n" +
        "    .packed-switch 0x0\n" +
        "        :a\n" +
        "        :b\n" +
        "    .end packed-switch\n" +
        ".end method\n"

    private fun dexOfFiles(vararg smali: String): ByteArray {
        val work = temp.newFolder("smali-multi-" + System.nanoTime())
        val sources = smali.mapIndexed { index, text -> File(work, "sample" + index + ".smali").apply { writeText(text, Charsets.UTF_8) } }
        val output = File(work, "classes.dex")
        assertTrue("smali assemble failed", Smali.assemble(SmaliOptions().apply { outputDexFile = output.path; jobs = 1 }, sources.map { it.path }))
        return output.readBytes()
    }

    private fun candidateOf(data: JsonObject, prefix: String): JsonObject =
        data.getAsJsonArray("candidates").map { it.asJsonObject }.first { it.get("method").asString.startsWith(prefix) }

    /** 冻结：默认权重必须与改造前的硬编码系数逐字段一致（权重表 + score + rawScore + contributions）。 */
    @Test fun defaultWeightsFreezeScoresAndContributions() {
        assertEquals(
            linkedMapOf(
                "switchCount" to 18, "switchCases" to 2, "dispatchLoopBackEdges" to 5, "arrayIndexOps" to 2,
                "invokeCount" to 1, "instructionCount" to 1, "classPayloadBytes" to 1,
            ),
            ToolkitVmSurvey.DEFAULT_WEIGHTS,
        )
        val shell = ToolkitVmSurvey.MethodFeatures(
            instructionCount = 400, switchCount = 2, switchCases = 64, backBranches = 4,
            arrayOps = 30, invokeCount = 12, bigLiterals = 4, classPayloadBytes = 8192,
        )
        // 36 + 32 + 20 + 14 + 32 + 8 + 8 + 8 = 158 -> score 封顶 100（与 dev-1422 硬编码系数逐项一致）
        val scored = ToolkitVmSurvey.scoreWeighted(shell, 64)
        assertEquals(100, scored.score)
        assertEquals(158, scored.rawScore)
        assertEquals(
            listOf(
                "switch_dispatch" to 36, "switch_cases" to 32, "dispatch_loop_backedge" to 20,
                "large_constant_payload" to 14, "array_index_ops" to 32, "big_literal_consts" to 8,
                "method_size" to 8, "call_fanout" to 8,
            ),
            scored.contributions,
        )
        // 历史 API（scoreOf）逐字段不变
        val legacy = ToolkitVmSurvey.scoreOf(shell, 64)
        assertEquals(100, legacy.first)
        assertEquals(scored.contributions, legacy.second)
        val plain = ToolkitVmSurvey.MethodFeatures(8, 0, 0, 0, 0, 0, 0, 0)
        assertEquals(0, ToolkitVmSurvey.scoreOf(plain, 64).first)
        assertTrue(ToolkitVmSurvey.scoreOf(plain, 64).second.isEmpty())
    }

    /** 工具层冻结：默认权重下候选的 score/rawScore/contributions 必须等于「用返回的 features 重算纯函数」。 */
    @Test fun surveyDefaultWeightsMatchTheFrozenPureFunction() {
        val data = surveyOfDex(dexOf(sampleSmali))
        assertEquals("default", data.get("weightsSource").asString)
        val table = data.getAsJsonObject("weights")
        ToolkitVmSurvey.ADJUSTABLE_WEIGHTS.forEach { key ->
            assertEquals(key, ToolkitVmSurvey.DEFAULT_WEIGHTS.getValue(key), table.get(key).asInt)
        }
        assertEquals(2, data.getAsJsonObject("weightsFixed").get("big_literal_consts").asInt)
        assertTrue(data.get("scoreFormula").asString.contains("min(100"))
        assertTrue(data.get("scoreFormula").asString.contains("rawScore"))
        assertEquals(8, data.getAsJsonObject("normalizations").keySet().size)
        assertTrue(data.get("weightsNote").asString.contains("INVALID_ARGUMENT"))
        assertTrue(data.getAsJsonArray("notes").joinToString(" ") { it.asString }.contains("weights"))
        val candidates = data.getAsJsonArray("candidates").map { it.asJsonObject }
        assertTrue(candidates.isNotEmpty())
        candidates.forEach { candidate ->
            val f = candidate.getAsJsonObject("features")
            val recomputed = ToolkitVmSurvey.scoreWeighted(
                ToolkitVmSurvey.MethodFeatures(
                    f.get("instructionCount").asInt, f.get("switchCount").asInt, f.get("switchCases").asInt,
                    f.get("dispatchLoopBackEdges").asInt, f.get("arrayIndexOps").asInt, f.get("invokeCount").asInt,
                    0, f.get("classPayloadBytes").asInt,
                ),
                16,
            )
            assertEquals(candidate.get("evidenceRef").asString, candidate.get("score").asInt, recomputed.score)
            assertEquals(candidate.get("evidenceRef").asString, candidate.get("rawScore").asInt, recomputed.rawScore)
            val expected = JsonObject().apply { recomputed.contributions.forEach { addProperty(it.first, it.second) } }
            assertEquals(candidate.get("evidenceRef").asString, expected, candidate.getAsJsonObject("contributions"))
        }
    }

    /** 自定义权重能改变排序，且 contributions 必须按实际权重缩放。 */
    @Test fun customWeightsChangeOrderingAndScaleContributions() {
        val dex = dexOfFiles(payloadSmali, dispatchOnlySmali)
        val base = ToolkitVmSurvey.survey(dexes = listOf("classes.dex" to ToolkitDex(dex).classes), payloadMinBytes = 16)
        assertEquals("default", base.get("weightsSource").asString)
        val basePayload = candidateOf(base, "touch(")
        val baseDispatch = candidateOf(base, "run(")
        assertTrue(
            "默认权重下 dispatch 应排在大常量方法之前：" + baseDispatch.get("score").asInt + " vs " + basePayload.get("score").asInt,
            baseDispatch.get("score").asInt > basePayload.get("score").asInt,
        )
        assertTrue(base.getAsJsonArray("candidates")[0].asJsonObject.get("method").asString.startsWith("run("))

        val custom = ToolkitVmSurvey.survey(
            dexes = listOf("classes.dex" to ToolkitDex(dex).classes),
            payloadMinBytes = 16,
            weights = ToolkitIo.json("classPayloadBytes" to 1000),
        )
        assertEquals("custom", custom.get("weightsSource").asString)
        val customTable = custom.getAsJsonObject("weights")
        assertEquals(1000, customTable.get("classPayloadBytes").asInt)
        assertEquals("未给的键必须保持默认", 18, customTable.get("switchCount").asInt)
        assertTrue(
            "把 classPayloadBytes 拉满后大常量方法应排到最前",
            custom.getAsJsonArray("candidates")[0].asJsonObject.get("method").asString.startsWith("touch("),
        )
        val customPayload = candidateOf(custom, "touch(")
        val customDispatch = candidateOf(custom, "run(")
        assertEquals(100, customPayload.get("score").asInt)
        // 默认权重下该权重是 1 => 默认 contribution 就是归一化值；自定义权重必须是它的 1000 倍
        val normalized = basePayload.getAsJsonObject("contributions").get("large_constant_payload").asInt
        val scaled = customPayload.getAsJsonObject("contributions").get("large_constant_payload").asInt
        assertEquals(1000 * normalized, scaled)
        assertEquals("只有这一项时 rawScore 必须等于它", scaled, customPayload.get("rawScore").asInt)
        assertEquals("dispatch 候选不受 classPayloadBytes 权重影响", baseDispatch.get("score").asInt, customDispatch.get("score").asInt)
        assertTrue(custom.getAsJsonArray("notes").joinToString(" ") { it.asString }.contains("weightsSource=custom"))
    }

    /** 坏权重：负 / 全 0 / 未知键 / 非对象 / 非整数 / 超上限 一律 INVALID_ARGUMENT，并列出可用键。 */
    @Test fun invalidWeightsAreRejectedWithAvailableKeys() {
        val dex = dexOfFiles(payloadSmali, dispatchOnlySmali)
        fun attempt(raw: JsonElement?): ToolkitException = assertThrows(ToolkitException::class.java) {
            ToolkitVmSurvey.survey(dexes = listOf("classes.dex" to ToolkitDex(dex).classes), weights = raw)
        }
        val negative = attempt(ToolkitIo.json("switchCount" to -1))
        assertEquals("INVALID_ARGUMENT", negative.code)
        assertTrue(negative.message!!.contains("负数"))
        val unknown = attempt(ToolkitIo.json("switch_count" to 3))
        assertEquals("INVALID_ARGUMENT", unknown.code)
        assertTrue("未知键必须列出可用键", unknown.message!!.contains("switchCount") && unknown.message!!.contains("classPayloadBytes"))
        // 只有**生效表**全为 0 才拒（单项 0 = 关闭该项，合法）；这里把 7 个键都给 0
        val allZero = attempt(ToolkitIo.json(
            "switchCount" to 0, "switchCases" to 0, "dispatchLoopBackEdges" to 0, "arrayIndexOps" to 0,
            "invokeCount" to 0, "instructionCount" to 0, "classPayloadBytes" to 0,
        ))
        assertEquals("INVALID_ARGUMENT", allZero.code)
        assertTrue(allZero.message!!.contains("全为 0"))
        // 单项 0 是合法用法
        assertTrue(ToolkitVmSurvey.weightsFrom(ToolkitIo.json("switchCount" to 0)).values.getValue("classPayloadBytes") > 0)
        assertEquals("INVALID_ARGUMENT", attempt(com.google.gson.JsonPrimitive(5)).code)
        assertEquals("INVALID_ARGUMENT", attempt(ToolkitIo.json("switchCount" to 1.5)).code)
        assertEquals("INVALID_ARGUMENT", attempt(ToolkitIo.json("switchCount" to 1000001)).code)
        // 纯函数层同样拒绝（便于不发版时先本地验）
        assertEquals("INVALID_ARGUMENT", assertThrows(ToolkitException::class.java) {
            ToolkitVmSurvey.weightsFrom(ToolkitIo.json("nope" to 1))
        }.code)
    }

    /** 权重 0 = 关闭该项：contributions 里仍出现且为 0；关到 0 分的候选诚实掉出候选集。 */
    @Test fun zeroWeightDisablesTermButStaysVisible() {
        val features = ToolkitVmSurvey.MethodFeatures(
            instructionCount = 31, switchCount = 3, switchCases = 0, backBranches = 0,
            arrayOps = 0, invokeCount = 0, bigLiterals = 0, classPayloadBytes = 0,
        )
        val zeroed = ToolkitVmSurvey.scoreWeighted(features, 64, ToolkitVmSurvey.weightsFrom(ToolkitIo.json("switchCount" to 0)))
        assertEquals(0, zeroed.score)
        assertEquals(0, zeroed.rawScore)
        assertEquals(listOf("switch_dispatch" to 0), zeroed.contributions)
        // 没显式给过的 0 贡献项保持历史行为：不出现
        assertTrue(ToolkitVmSurvey.scoreWeighted(ToolkitVmSurvey.MethodFeatures(4, 0, 0, 0, 0, 0, 0, 0), 64).contributions.isEmpty())

        // weightsSource / 生效表：空对象与 null 都是 default；单项覆盖是 custom 且其余保持默认
        assertEquals("default", ToolkitVmSurvey.weightsFrom(ToolkitIo.json()).source)
        val defaults = ToolkitVmSurvey.weightsFrom(null)
        assertEquals("default", defaults.source)
        assertEquals(ToolkitVmSurvey.DEFAULT_WEIGHTS, defaults.values)
        val custom = ToolkitVmSurvey.weightsFrom(ToolkitIo.json("invokeCount" to 7))
        assertEquals("custom", custom.source)
        assertEquals(7, custom.values["invokeCount"])
        assertEquals(18, custom.values["switchCount"])
        assertEquals(7, custom.table().get("invokeCount").asInt)

        // 工具层：switchCount 关掉后 dispatch 候选 0 分 -> 不进候选；大常量候选仍在
        val dex = dexOfFiles(payloadSmali, dispatchOnlySmali)
        val zeroedRun = ToolkitVmSurvey.survey(
            dexes = listOf("classes.dex" to ToolkitDex(dex).classes),
            payloadMinBytes = 16,
            weights = ToolkitIo.json("switchCount" to 0),
        )
        val methods = zeroedRun.getAsJsonArray("candidates").map { it.asJsonObject.get("method").asString }
        assertTrue("0 分的 dispatch 候选应诚实掉出候选集：" + methods, methods.none { it.startsWith("run(") })
        assertTrue(methods.any { it.startsWith("touch(") })
    }

    @Test fun schemaDeclaresWeightsObject() {
        val schemas = com.google.gson.JsonParser().parse(File("src/main/assets/mcp/toolkit-apk-schemas.json").readText()).asJsonObject
        val weights = schemas.getAsJsonObject("vm_survey").getAsJsonObject("properties").getAsJsonObject("weights")
        assertEquals("object", weights.get("type").asString)
        assertEquals(false, weights.get("additionalProperties").asBoolean)
        val props = weights.getAsJsonObject("properties")
        assertEquals(ToolkitVmSurvey.ADJUSTABLE_WEIGHTS.toSet(), props.keySet())
        ToolkitVmSurvey.ADJUSTABLE_WEIGHTS.forEach { key ->
            assertEquals(key + " 必须是 integer", "integer", props.getAsJsonObject(key).get("type").asString)
            assertEquals(key + " 最小值必须是 0", 0, props.getAsJsonObject(key).get("minimum").asInt)
        }
        assertTrue(weights.get("description").asString.contains("INVALID_ARGUMENT"))
        assertTrue(weights.get("description").asString.contains("rawScore"))
    }
}
