package com.jdkshen.aijspro.autojs.build

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** task-131 批次 A：样例资源包与产物宿主的摘要判定（摘不出来就 KEEP，确定不同才 DROP）。 */
class ProjectResSidecarPolicyTest {
    @get:Rule val temp = TemporaryFolder()

    private fun sidecar(name: String, body: String): File {
        val dir = temp.newFolder(name)
        val file = File(dir, ProjectResSidecarPolicy.SIDECAR_NAME)
        file.writeText(body)
        return file
    }

    private fun arsc(name: String, body: String): File {
        val file = File(temp.newFolder(name), "resources.arsc")
        file.writeBytes(body.toByteArray())
        return file
    }

    @Test fun extractsDigestTolerantly() {
        val digest = "a".repeat(64)
        assertEquals(digest, ProjectResSidecarPolicy.extractHostDigest("{\"appResourcesSha256\":\"" + digest + "\"}"))
        assertEquals(digest, ProjectResSidecarPolicy.extractHostDigest("{\"hostResourcesArscSha256\":\"" + digest + "\"}"))
        assertEquals(digest, ProjectResSidecarPolicy.extractHostDigest("{\"whatever\":\"" + digest + "\"}"))
        assertNull(ProjectResSidecarPolicy.extractHostDigest("{\"versionCode\":3}"))
        assertNull(ProjectResSidecarPolicy.extractHostDigest(null))
        assertNull(ProjectResSidecarPolicy.extractHostDigest(""))
    }

    @Test fun equalDigestKeepsEverything() {
        val arsc = arsc("host-ok", "arsc-bytes")
        val digest = ProjectResSidecarPolicy.sha256(arsc)!!
        val sidecar = sidecar("keep", "{\"appResourcesSha256\":\"" + digest + "\"}")
        val decision = ProjectResSidecarPolicy.decide(sidecar, arsc)
        assertEquals(ProjectResSidecarPolicy.Action.KEEP, decision.action)
        assertEquals(digest, decision.expectedDigest)
    }

    @Test fun differentDigestDropsThePair() {
        val arsc = arsc("host-new", "arsc-bytes-v2")
        val sidecar = sidecar("drop", "{\"appResourcesSha256\":\"" + "b".repeat(64) + "\"}")
        val decision = ProjectResSidecarPolicy.decide(sidecar, arsc)
        assertEquals(ProjectResSidecarPolicy.Action.DROP, decision.action)
        assertEquals(ProjectResSidecarPolicy.APK_NAME, ProjectResSidecarPolicy.apkFor(sidecar)!!.name)
    }

    @Test fun unknownNeverDrops() {
        val arsc = arsc("host-any", "x")
        assertEquals(ProjectResSidecarPolicy.Action.KEEP,
            ProjectResSidecarPolicy.decide(sidecar("no-digest", "{\"versionCode\":1}"), arsc).action)
        assertEquals(ProjectResSidecarPolicy.Action.KEEP,
            ProjectResSidecarPolicy.decide(null, arsc).action)
        val digest = "c".repeat(64)
        val withDigest = sidecar("no-arsc", "{\"appResourcesSha256\":\"" + digest + "\"}")
        assertEquals(ProjectResSidecarPolicy.Action.KEEP,
            ProjectResSidecarPolicy.decide(withDigest, null).action)
        assertEquals(ProjectResSidecarPolicy.Action.KEEP,
            ProjectResSidecarPolicy.decide(withDigest, File(temp.root, "missing.arsc")).action)
    }
}
