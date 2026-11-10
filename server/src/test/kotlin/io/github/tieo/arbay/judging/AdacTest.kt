package io.github.tieo.arbay.judging

import kotlin.test.Test
import kotlin.test.assertEquals

class AdacTest {
    @Test
    fun `a catalogue version reads its DC power and stands in as a cost row`() {
        val v = Adac.CatalogVersion(
            name = "VW ID.4 Pro 4MOTION", powerKw = 210, charging = "AC:11 DC:135",
            fixed = 135, workshop = 98, running = 108, depreciation = 623, total = 964,
        )
        assertEquals(135.0, v.dcKw)
        val row = v.asRow()!!
        assertEquals("VW", row.make)
        assertEquals("ID.4 Pro 4MOTION", row.model)
        assertEquals(964, row.total)
        assertEquals(null, v.copy(workshop = null).asRow())
    }
}
