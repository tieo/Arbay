package io.github.tieo.arbay

import io.github.tieo.arbay.model.Condition
import kotlin.test.Test
import kotlin.test.assertEquals

class ConditionParseTest {
    @Test
    fun `eBay condition labels of every site it is asked on`() {
        // Unread, a Kingston 2TB sold for parts on ebay.de was a working drive on ebay.it, and an
        // alert rule learning the cheapest working drive could have learned its price.
        mapOf(
            "Per ricambi o non funzionante" to Condition.PARTS_ONLY,
            "Pour pièces détachées/ne fonctionne pas" to Condition.PARTS_ONLY,
            "Para piezas o no funciona" to Condition.PARTS_ONLY,
            "For parts or not working" to Condition.PARTS_ONLY,
            "Nur Ersatzteile" to Condition.PARTS_ONLY,
            "Usato" to Condition.USED,
            "Occasion" to Condition.USED,
            "De segunda mano" to Condition.USED,
            "Usado" to Condition.USED,
            "Pre-Owned" to Condition.USED,
            "Gebraucht" to Condition.USED,
            "Nuovo" to Condition.NEW,
            "Nuovo (altro)" to Condition.NEW,
            "Neuf" to Condition.NEW,
            "Nuevo" to Condition.NEW,
            "Brand New" to Condition.NEW,
            "Ricondizionato" to Condition.REFURBISHED,
            "Reconditionné" to Condition.REFURBISHED,
            "Reacondicionado" to Condition.REFURBISHED,
            "Come nuovo" to Condition.LIKE_NEW,
            "Vorführgerät" to Condition.LIKE_NEW,
            "Aussteller" to Condition.LIKE_NEW,
        ).forEach { (label, expected) -> assertEquals(expected, Condition.parse(label), label) }
    }
}
