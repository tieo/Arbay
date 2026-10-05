package io.github.tieo.arbay

import io.github.tieo.arbay.ui.screen.decimals
import io.github.tieo.arbay.ui.screen.grouped
import io.github.tieo.arbay.ui.screen.monthYear
import kotlin.test.Test
import kotlin.test.assertEquals

class FormattingTest {
    @Test
    fun `thousands are grouped`() {
        assertEquals("88,700", grouped(88_700))
        assertEquals("1.234.567", grouped(1_234_567, '.'))
        assertEquals("999", grouped(999))
        assertEquals("-12,500", grouped(-12_500))
    }

    @Test
    fun `a month is two digits`() {
        assertEquals("03/2019", monthYear(3, 2019))
        assertEquals("11/2018", monthYear(11, 2018))
    }

    @Test
    fun `decimals round half up to a fixed count`() {
        assertEquals("4.3", decimals(4.25, 1))
        assertEquals("0.07", decimals(0.069, 2))
        assertEquals("5.0", decimals(5.0, 1))
        assertEquals("-1.50", decimals(-1.5, 2))
        assertEquals("0.00", decimals(-0.001, 2))
    }
}
