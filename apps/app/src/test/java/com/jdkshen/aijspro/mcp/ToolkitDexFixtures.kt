package com.jdkshen.aijspro.mcp

import org.jf.smali.Smali
import org.jf.smali.SmaliOptions
import org.junit.Assert.assertTrue
import java.io.File

/** Small generated-DEX fixtures shared by the DEX navigation/search tests. */
internal object ToolkitDexFixtures {
    fun assemble(root: File, name: String, vararg sources: String): ByteArray {
        val files = sources.mapIndexed { index, text ->
            File(root, "$name-$index.smali").apply { writeText(text) }
        }
        val output = File(root, "$name.dex")
        assertTrue("Smali assemble failed for $name", Smali.assemble(SmaliOptions().apply {
            outputDexFile = output.path
            apiLevel = 28
            jobs = 1
        }, files.map { it.path }))
        return output.readBytes()
    }

    fun nav(vararg entries: Pair<String, ByteArray>): ToolkitDexNavigation =
        ToolkitDexNavigation(entries.map { it.first to ToolkitDex(it.second) })
}
