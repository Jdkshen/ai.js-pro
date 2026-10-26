package com.jdkshen.aijspro.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class McpApiDocsTest {
    private fun args(json: String = "{}") = com.google.gson.JsonParser().parse(json).asJsonObject
    private val code = "const x = '中文 😀';\n  if (x < 3 && y > 0) {\n    log(x);\n  }\n"
    private fun asset(longContent: String = "说明\n\n```js\n${code}```") = "window.AIJS_API_REFERENCE=" + JsonObject().apply {
        addProperty("schemaVersion", 2); addProperty("scope", "传统参考仅 Rhino，不能推断其它引擎支持")
        add("records", JsonArray().apply {
            fun entry(id: String, title: String, kind: String, engines: List<String>, content: String) = JsonObject().apply {
                addProperty("id", id); addProperty("title", title); addProperty("kind", kind)
                addProperty("category", if (kind == "guide") "指南" else "传统 API")
                addProperty("source", "docs/test.md"); addProperty("text", if (id == "api-3") "ctx.loadResources(path)" else "文件说明 files.read(path) 中文")
                addProperty("content", content); add("engines", JsonArray().apply { engines.forEach { add(it) } })
            }
            add(entry("api-1", "files.read(path)", "legacy", listOf("rhino"), longContent))
            add(entry("api-2", "文件说明", "guide", listOf("rhino", "quickjs", "nodejs"), "files.read(path)"))
            add(entry("api-3", "Node 资源", "guide", listOf("nodejs"), "ctx.loadResources(path)"))
        })
    } + ";\n"
    private fun docs(content: String? = null) = McpApiDocs { if (content == null) asset() else asset(content) }
    private fun rejects(block: () -> Unit) {
        try { block(); fail("expected parameter rejection") } catch (_: IllegalArgumentException) { }
    }

    @Test fun exactTitleRanksBeforeBodyMatchesAndReadsCodeWithoutChangingIt() {
        val docs = docs(); val result = docs.search(args("""{"query":"files.read"}"""))
        assertEquals("api-1", result.getAsJsonArray("items")[0].asJsonObject.get("id").asString)
        val detail = docs.read(args("""{"id":"api-1","indexSha256":"${result.get("indexSha256").asString}"}"""))
        assertTrue(detail.get("content").asString.contains("```js\n${code}```"))
        assertEquals("documentation_only", detail.get("supportEvidence").asString)
        assertFalse(detail.get("truncated").asBoolean)
        assertFalse(detail.get("hasMore").asBoolean)
    }

    @Test fun nodeAndQuickjsNeverReceiveRhinoLegacyRecords() {
        for (engine in listOf("node", "NODE24", "nodejs", "quickjs")) {
            val result = docs().search(args("""{"engine":"$engine","query":"files.read"}"""))
            assertEquals(1, result.getAsJsonArray("items").size())
            assertEquals("api-2", result.getAsJsonArray("items")[0].asJsonObject.get("id").asString)
        }
        assertEquals(0, docs().search(args("""{"engine":"nodejs","kind":"legacy"}""")).get("total").asInt)
        rejects { docs().search(args("""{"engine":"nodejs","category":"传统 API"}""")) }
    }

    @Test fun browsingPaginationHasNoGapsAndEndsCleanly() {
        val docs = docs(); val ids = mutableListOf<String>(); var offset = 0
        do {
            val page = docs.search(args("""{"offset":$offset,"limit":1}"""))
            ids += page.getAsJsonArray("items").map { it.asJsonObject.get("id").asString }
            offset = page.get("nextOffset").asInt
        } while (page.get("hasMore").asBoolean)
        assertEquals(setOf("api-1", "api-2", "api-3"), ids.toSet()); assertEquals(3, ids.size)
        assertEquals(0, docs.search(args("""{"offset":3}""")).getAsJsonArray("items").size())
        assertEquals(0, docs.search(args("""{"query":"no_such_api"}""")).get("total").asInt)
        rejects { docs.search(args("""{"offset":4}""")) }
    }

    @Test fun contentPagesReassembleExactlyAndDoNotSplitEmoji() {
        val original = "a".repeat(127) + "😀" + code.repeat(10)
        val docs = docs(original); val parts = StringBuilder(); var offset = 0
        do {
            val page = docs.read(args("""{"id":"api-1","offset":$offset,"maxChars":128}"""))
            val part = page.get("content").asString
            assertTrue(part.length <= 128)
            assertFalse(part.isNotEmpty() && Character.isHighSurrogate(part.last()))
            parts.append(part); offset = page.get("nextOffset").asInt
        } while (page.get("hasMore").asBoolean)
        assertEquals(original, parts.toString())
        rejects { docs.read(args("""{"id":"api-1","offset":128}""")) }
    }

    @Test fun staleIdsMustNotSilentlyReadAnotherVersionWhenHashIsProvided() {
        val hash = docs().search(args()).get("indexSha256").asString
        rejects { docs("different").read(args("""{"id":"api-1","indexSha256":"$hash"}""")) }
        rejects { docs().read(args("""{"id":"api-999"}""")) }
        rejects { docs().read(args("""{"id":"../../etc/passwd"}""")) }
    }

    @Test fun invalidTypesAndRangesAreRejectedRatherThanSilentlyDefaulted() {
        val docs = docs()
        for (json in listOf("""{"limit":"1"}""", """{"offset":null}""", """{"limit":1.5}""",
            """{"offset":1e50}""", """{"limit":0}""", """{"limit":21}""", """{"engine":true}""",
            """{"engine":"typo"}""", """{"kind":"typo"}""", """{"offset":-1}""", """{"unknown":1}""")) rejects { docs.search(args(json)) }
        for (json in listOf("{}", """{"id":1}""", """{"id":"api-1","maxChars":127}""",
            """{"id":"api-1","maxChars":32001}""", """{"id":"api-1","offset":9999}""")) rejects { docs.read(args(json)) }
    }

    @Test fun simultaneousReadersLoadTheIndexOnceWithoutStartingAnyEngine() {
        val count = java.util.concurrent.atomic.AtomicInteger()
        val docs = McpApiDocs { count.incrementAndGet(); asset() }
        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        try {
            val tasks = (1..16).map { pool.submit<Int> { docs.search(args()).get("indexedRecords").asInt } }
            tasks.forEach { assertEquals(3, it.get().toInt()) }
            assertEquals(1, count.get())
        } finally { pool.shutdownNow() }
    }

    @Test fun actualBundledIndexCanBeQueriedAndEveryRecordCanBeRead() {
        val file = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .map { File(it, "apps/app/src/main/assets/docs/api-reference-data.js") }.first { it.isFile }
        val docs = McpApiDocs { file.readText(Charsets.UTF_8) }
        val page = docs.search(args("""{"engine":"nodejs","query":"loadResources"}"""))
        assertTrue(page.get("total").asInt > 0)
        assertEquals(756, page.get("indexedRecords").asInt)
        val raw = com.google.gson.JsonParser().parse(file.readText().removePrefix("window.AIJS_API_REFERENCE=").trim().removeSuffix(";")).asJsonObject
        assertTrue(docs.search(args("""{"engine":"quickjs","query":"6.3 Node"}""")).getAsJsonArray("items").none { it.asJsonObject.get("id").asString == "api-14" })
        raw.getAsJsonArray("records").forEach { element ->
            val entry = element.asJsonObject
            val detail = docs.read(args("""{"id":"${entry.get("id").asString}"}"""))
            assertEquals(entry.get("content").asString, detail.get("content").asString)
            assertFalse(detail.get("hasMore").asBoolean)
            assertTrue(detail.get("engineNote").asString.isNotEmpty())
            if (entry.get("kind").asString == "legacy") assertEquals(entry.get("link"), detail.get("link"))
        }
        assertTrue(docs.search(args("""{"limit":20}""")).toString().toByteArray(Charsets.UTF_8).size < 65536)
    }
}
