package com.jdkshen.aijspro.mcp

import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ToolkitApkSearchMemberAnchorTest {
    @get:Rule val temp = TemporaryFolder()

    private fun args(vararg pairs: Pair<String, Any?>) = ToolkitIo.json(*pairs)

    private fun search(dex: ByteArray, vararg pairs: Pair<String, Any?>): ToolkitApkSearch.Result {
        val toolkit = ToolkitDex(dex)
        val engine = ToolkitApkSearch(listOf("classes.dex"), { dex }, { null },
            { sequenceOf("classes.dex" to toolkit) })
        return engine.search(args("target" to "smali", *pairs))
    }

    @Test
    fun smaliHitsKeepClassLocatorAndGainPreciseMemberAnchors() {
        val dex = ToolkitDexFixtures.assemble(temp.root, "search", SEARCH)
        val result = search(dex, "query" to "second-anchor", "snippetMaxChars" to 80,
            "includeMatchOffsets" to true)
        assertFalse(result.partial)
        assertEquals(1, result.hits.size)
        val hit = result.hits.single()
        val nested = hit.getAsJsonObject("hit")

        // Old contract: the hit still points at the class and keeps the original kind/scope/snippet.
        assertEquals("dex_class:Lfixture/Search;", hit.tString("locator"))
        assertEquals("classes.dex", hit.tString("dexEntry"))
        assertEquals("smali", hit.tString("searchScope"))
        assertEquals("smali_text", hit.tString("kind"))
        assertEquals("smali_text", hit.tString("matchKind"))
        assertEquals("smali_text", nested.tString("kind"))
        assertTrue(hit.tString("snippet").contains("second-anchor"))
        assertTrue(hit.getAsJsonObject("matchOffsets").get("start").asInt >= 0)

        // New member anchor: exact method locator, DEX-qualified identity, method + match line ranges.
        val member = "dex_method:Lfixture/Search;->second()Ljava/lang/String;"
        assertEquals(member, hit.tString("memberLocator"))
        assertEquals(member, nested.tString("memberLocator"))
        assertEquals("dex_entry:classes.dex!$member", hit.tString("memberUniqueLocator"))
        assertNotEquals(hit.tString("locator"), hit.tString("memberLocator"))

        val memberRange = hit.getAsJsonObject("memberLineRange")
        assertTrue(memberRange.get("start").asInt in 1..memberRange.get("end").asInt)
        val matchRange = hit.getAsJsonObject("lineRange")
        assertTrue(matchRange.get("start").asInt in memberRange.get("start").asInt..memberRange.get("end").asInt)
        assertTrue(matchRange.get("end").asInt in memberRange.get("start").asInt..memberRange.get("end").asInt)
        assertEquals(matchRange.get("start").asInt, hit.get("lineStart").asInt)
        assertEquals(matchRange.get("end").asInt, hit.get("lineEnd").asInt)
        assertEquals("instruction", hit.tString("anchorKind"))
        assertEquals(0, hit.get("codeUnitOffset").asInt)
        assertEquals(matchRange.get("start").asInt, hit.get("instructionLine").asInt)
        assertEquals(hit.get("instructionLine").asInt, nested.get("instructionLine").asInt)
    }

    @Test
    fun matchedMethodRangeTracksTheRenderedSecondMethodAndFirstMatchWinsPerClass() {
        val dex = ToolkitDexFixtures.assemble(temp.root, "search2", SEARCH)
        val definition = ToolkitDexSupport.classes(ToolkitDex(dex)).single { it.type == "Lfixture/Search;" }
        val rendered = ToolkitDexSupport.renderClass(ToolkitDex(dex), definition)
        val expectedSecondLine = rendered.lines().indexOfFirst { line ->
            line.trim().startsWith(".method") && line.contains("second(")
        } + 1

        val second = search(dex, "query" to "second-anchor").hits.single()
        assertEquals(expectedSecondLine, second.getAsJsonObject("memberLineRange").get("start").asInt)

        // Smali search still returns one class-level hit for a class even when the same query
        // appears in more than one method; the added anchor describes the first match.
        val repeated = ToolkitDexFixtures.assemble(temp.root, "search3", REPEATED)
        val repeatedHit = search(repeated, "query" to "same-needle").hits.single()
        assertEquals(1, search(repeated, "query" to "same-needle").hits.size)
        assertEquals("dex_method:Lfixture/Repeated;->first()V", repeatedHit.tString("memberLocator"))
    }

    @Test
    fun classHeaderMatchKeepsOldFieldsWithoutFabricatingAMember() {
        val dex = ToolkitDexFixtures.assemble(temp.root, "search4", SEARCH)
        val hit = search(dex, "query" to "Search").hits.single()
        assertEquals("dex_class:Lfixture/Search;", hit.tString("locator"))
        assertFalse(hit.has("memberLocator"))
        assertFalse(hit.has("lineRange"))
        assertFalse(hit.getAsJsonObject("hit").has("memberLocator"))
    }

    companion object {
        private val SEARCH = """
            .class public Lfixture/Search;
            .super Ljava/lang/Object;
            .method public static first()Ljava/lang/String;
                .registers 1
                const-string v0, "first-anchor"
                return-object v0
            .end method
            .method public static second()Ljava/lang/String;
                .registers 1
                const-string v0, "second-anchor"
                return-object v0
            .end method
        """.trimIndent()

        private val REPEATED = """
            .class public Lfixture/Repeated;
            .super Ljava/lang/Object;
            .method public static first()V
                .registers 1
                const-string v0, "same-needle"
                return-void
            .end method
            .method public static second()V
                .registers 1
                const-string v0, "same-needle"
                return-void
            .end method
        """.trimIndent()
    }
}
