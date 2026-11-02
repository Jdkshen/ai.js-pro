package com.jdkshen.aijspro.mcp

import com.reandroid.arsc.chunk.TableBlock
import org.jf.smali.Smali
import org.jf.smali.SmaliOptions
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ToolkitSemanticGuardsTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun emptyBagsAndFormattedAttributesKeepTheirResourceType() {
        val table = TableBlock().apply { newPackage(0x7f, "fixture"); refreshFull() }
        val resources = ToolkitResources(table.bytes)
        for (xml in listOf("<style name=\"empty_style\"/>", "<string-array name=\"empty_array\"/>",
            "<attr name=\"size_mode\" format=\"integer\"/>")) {
            assertTrue(xml, resources.add("0x7f", "default", xml).isComplex)
        }
        val rich = resources.add("0x7f", "default", "<string name=\"rich\">a<b>bold</b></string>")
        assertFalse(rich.isComplex)
        try {
            resources.write(rich, "<color name=\"rich\">#ffffffff</color>")
            fail("changing the resource type must fail")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun numericXmlEntitiesDoNotBecomeMarkupOrInvalidXmlCharacters() {
        assertEquals("&#60;&#38;&#34;中文&#0;&#55296;&#65535;",
            ToolkitResources.unescapeNumericRefs("&#60;&#38;&#34;&#20013;&#25991;&#0;&#55296;&#65535;"))
    }

    @Test fun dexMemberLookupUsesTheWholeName() {
        val source = temp.newFile("Names.smali").apply { writeText("""
            .class public Lfixture/Names;
            .super Ljava/lang/Object;
            .method public static barfoo()I
                .registers 1
                const/4 v0, 0x1
                return v0
            .end method
            .method public static foo()I
                .registers 1
                const/4 v0, 0x2
                return v0
            .end method
        """.trimIndent()) }
        val output = temp.root.resolve("names.dex")
        assertTrue(Smali.assemble(SmaliOptions().apply { outputDexFile = output.path; jobs = 1 }, listOf(source.path)))
        val method = ToolkitDex(output.readBytes()).render("dex_method:Lfixture/Names;->foo()I")
        assertTrue(method.contains("foo()I"))
        assertFalse(method.contains("barfoo()I"))
        assertTrue(method.contains("0x2"))
    }

    @Test fun zeroLengthRegexAtTheEndKeepsSearchComplete() {
        val search = ToolkitApkSearch(listOf("asset.txt"), { error("not a content read") }, { null }, { emptySequence() })
        val result = search.search(ToolkitIo.json("target" to "files", "query" to "$", "queryType" to "regex",
            "snippetMaxChars" to 1, "includeMatchOffsets" to true))
        assertFalse(result.partial)
        assertEquals(1, result.hits.size)
        assertEquals(9, result.hits.single().getAsJsonObject("matchOffsets").get("start").asInt)
    }
}
