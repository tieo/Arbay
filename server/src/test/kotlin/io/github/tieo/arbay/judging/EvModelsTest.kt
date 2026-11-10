package io.github.tieo.arbay.judging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class EvModelsTest {
    @Test
    fun `a version is filed under the model the car sites know`() {
        val model = EvModels.keyOf("Tesla Model Y Long Range AWD")
        assertNotNull(model)
        assertEquals(model, EvModels.keyOf("Tesla Model Y"))
        assertEquals(EvModels.keyOf("VW ID.4"), EvModels.keyOf("Volkswagen ID.4 Pro 4MOTION"))
    }
}
