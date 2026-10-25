package com.jdkshen.aijspro.mcp

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class OverlayStateReaderTest {
    private fun snapshot() = JsonParser().parse("""{"pid":123,"updatedAt":1000,"entries":[{"kind":"NODE_UI","ownerEngineId":7}]}""").asJsonObject
    @Test fun oldStaticOverlayInALiveProcessMustRemainVisible() {
        val items = OverlayStateReader.decode(snapshot(), 100_000, mapOf(123 to "app:node24_1"))
        assertEquals(1, items.size)
        assertTrue(items[0].get("snapshotOld").asBoolean)
        assertEquals("app:node24_1", items[0].get("process").asString)
    }
    @Test fun deadProcessSnapshotIsNotAnOverlayLeak() {
        assertTrue(OverlayStateReader.decode(snapshot(), 2000, emptyMap()).isEmpty())
    }
    @Test fun invalidPidIsNeverIncluded() {
        val root = snapshot().apply { addProperty("pid", -1) }
        assertTrue(OverlayStateReader.decode(root, 2000, null).isEmpty())
    }
}
