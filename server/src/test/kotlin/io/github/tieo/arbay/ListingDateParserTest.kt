package io.github.tieo.arbay

import io.github.tieo.arbay.crawler.ListingDateParser
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.todayIn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ListingDateParserTest {

    private val tz = TimeZone.UTC

    @Test
    fun parsesHeuteAsToday() {
        val d = ListingDateParser.parse("Heute, 14:32", tz)
        val today = Clock.System.todayIn(tz)
        assertEquals(today.toString(), d.toString().substring(0, 10))
    }

    @Test
    fun parsesGesternAsYesterday() {
        val d = ListingDateParser.parse("Gestern, 20:14", tz)
        val yesterday = Clock.System.todayIn(tz).minus(1, DateTimeUnit.DAY)
        assertEquals(yesterday.toString(), d.toString().substring(0, 10))
    }

    @Test
    fun parsesExplicitGermanDate() {
        val d = ListingDateParser.parse("05.07.2026", tz)
        assertEquals("2026-07-05", d.toString().substring(0, 10))
    }

    @Test
    fun parsesVorTagen() {
        val d = ListingDateParser.parse("vor 3 Tagen", tz)!!
        val expected = Clock.System.todayIn(tz).minus(3, DateTimeUnit.DAY)
        assertEquals(expected.toString(), d.toString().substring(0, 10))
    }

    @Test
    fun blankOrUnknownYieldsNull() {
        assertNull(ListingDateParser.parse(null, tz))
        assertNull(ListingDateParser.parse("", tz))
        assertNull(ListingDateParser.parse("Kostenloser Versand", tz))
    }

    @Test
    fun icalendarIconTextStillParses() {
        // Real Kleinanzeigen card text after icon strip.
        assertTrue(ListingDateParser.parse("Gestern, 09:05", tz) != null)
    }
}
