package io.github.tieo.arbay

import io.github.tieo.arbay.crawler.SerpResult
import io.github.tieo.arbay.crawler.SearchEngines
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Each engine's results page as the server got it on 2026-10-26, for "Lägler Hummel
 * Parkettschleifmaschine" (Brave's for a person's name, the name and contacts replaced, since Brave
 * refused the server the other), read by shape: every result with its title, address and snippet,
 * and nothing of the engine's own.
 */
class SerpRowsTest {
    private fun rows(name: String, host: String, base: String): List<SerpResult> =
        SearchEngines.serpRows(javaClass.getResource("/fixtures/serp/$name.html")!!.readText(), host, base, listOf("/goto?url="))

    private val hummel = "https://www.laegler.com/produkte/maschinen/hummel"

    @Test
    fun `bing lists its ten results and none of its footer`() {
        val r = rows("bing", "bing.", "https://www.bing.com/search")
        assertEquals(10, r.size, r.joinToString("\n"))
        assertEquals("https://www.laegler.com/", r[0].url)
        assertEquals("LÄGLER – Parkettschleifmaschinen für perfekte Schleifqualität", r[0].title)
        assertTrue(r[0].snippet.startsWith("LÄGLER® Parkettschleifmaschinen stehen weltweit"), r[0].snippet)
        assertTrue(r.none { "microsoft" in it.url || "&#" in it.snippet }, r.joinToString("\n"))
    }

    @Test
    fun `duckduckgo lists its ten results`() {
        val r = rows("duckduckgo", "duckduckgo.", "https://html.duckduckgo.com/html/")
        assertEquals(10, r.size, r.joinToString("\n"))
        assertEquals(hummel, r[0].url)
        assertEquals("Lägler - Hummel", r[0].title)
        assertTrue(r.all { it.snippet.isNotEmpty() }, r.joinToString("\n"))
    }

    @Test
    fun `google lists its results behind its redirects and leaves out its shopping cards`() {
        val r = rows("google", "google.", "https://www.google.com/search")
        assertTrue(r.size >= 10, r.joinToString("\n"))
        assertTrue(r.all { "/goto?url=" in it.url && it.snippet.isNotEmpty() }, r.joinToString("\n"))
        assertTrue(r.none { it.title.startsWith("Von ") }, r.joinToString("\n"))
        val special = r.first { it.title == "HUMMEL Special Edition 2025" }
        assertTrue(special.snippet.startsWith("LÄGLER Ihre Vorteile"), special.snippet)
    }

    @Test
    fun `qwant's snippets come without the path it prints before them`() {
        val r = rows("qwant", "qwant.", "https://www.qwant.com/")
        assertEquals(10, r.size, r.joinToString("\n"))
        val hummelRow = r.first { it.url == hummel }
        assertTrue(hummelRow.snippet.startsWith("Die HUMMEL von LÄGLER"), hummelRow.snippet)
        assertTrue(r.none { "‣" in it.snippet.take(40) }, r.joinToString("\n"))
    }

    @Test
    fun `brave's titles are read without headings and its footer left out`() {
        val r = rows("brave", "brave.", "https://search.brave.com/search")
        assertEquals(4, r.size, r.joinToString("\n"))
        assertEquals("Anna Beispiel – Vermögensberaterin in Neu-Ulm", r[0].title)
        assertTrue(r.none { "hackerone" in it.url }, r.joinToString("\n"))
    }

    @Test
    fun `startpage lists its results without its note on ads`() {
        val r = rows("startpage", "startpage.", "https://www.startpage.com/do/search")
        assertEquals(8, r.size, r.joinToString("\n"))
        assertEquals(hummel, r[0].url)
        assertEquals("HUMMEL - LÄGLER", r[0].title)
        assertTrue(r.none { "microsoft" in it.url || "Anonymer Ansicht" in it.snippet }, r.joinToString("\n"))
    }

    /** Marginalia's own index, for "vcds alternative diagnose software" on 2026-10-30. */
    @Test
    fun `marginalia lists its results with their snippets`() {
        val r = rows("marginalia", "marginalia", "https://marginalia-search.com/search")
        assertTrue(r.size >= 20, r.joinToString("\n"))
        val forum = r.first { "dieselschrauber.org" in it.url }
        assertEquals("VCDS and OBD diagnostic tools | Dieselschrauber", forum.title)
        assertTrue(forum.snippet.isNotEmpty(), r.joinToString("\n"))
        assertTrue(r.none { "marginalia" in it.url || "github.com" in it.url }, r.joinToString("\n"))
    }

    /** Bing Images' async page for "Stahlzarge weiß schwarze Dichtung" on 2026-10-30. */
    @Test
    fun `bing images lists each picture with the page it stands on`() {
        val r = SearchEngines.imageRows(javaClass.getResource("/fixtures/serp/bing-images.html")!!.readText())
        assertEquals(35, r.size, r.joinToString("\n"))
        assertTrue(r.all { it.image.startsWith("http") && it.page.startsWith("http") }, r.joinToString("\n"))
        val obi = r.first { "obi.de" in it.page }
        assertTrue(obi.image.startsWith("https://bilder.obi.de/"), obi.toString())
        assertTrue(r.count { it.size != null } >= 30, r.joinToString("\n") { it.size.toString() })
    }

    /** Yandex's site search over its whole index, for "Parkettschleifmaschine Hummel" on 2026-10-30. */
    @Test
    fun `yandex lists its results without its own links`() {
        val r = rows("yandex", "yandex.", "https://yandex.ru/search/site/")
        assertTrue(r.size >= 8, r.joinToString("\n"))
        assertTrue(r.none { "yandex." in it.url || "ya.ru" in it.url }, r.joinToString("\n"))
        val bluemel = r.first { "maschinenverleih-bluemel.de" in it.url }
        assertTrue(bluemel.snippet.startsWith("Auch wenn die Hummel"), bluemel.snippet)
    }
}
