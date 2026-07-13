package io.github.tieo.arbay

import io.github.tieo.arbay.crawler.KleinanzeigenUrlBuilder
import kotlin.test.*

class KleinanzeigenUrlBuilderTest {

    // ── Free items: basic URL structure ─────────────────────────────────────

    @Test
    fun `free items with location and radius - page 1`() {
        val url = KleinanzeigenUrlBuilder.freeItems(
            locationId = "1234",
            citySlug = "frankfurt-%28oder%29",
            radiusKm = 30,
            page = 1,
        )
        assertEquals(
            "https://www.kleinanzeigen.de/s-frankfurt-%28oder%29/preis::0/zu-verschenken/k0l1234r30",
            url,
        )
    }

    @Test
    fun `free items with location - page 2`() {
        val url = KleinanzeigenUrlBuilder.freeItems(
            locationId = "1234",
            citySlug = "frankfurt-%28oder%29",
            radiusKm = 100,
            page = 2,
        )
        assertEquals(
            "https://www.kleinanzeigen.de/s-frankfurt-%28oder%29/preis::0/seite:2/zu-verschenken/k0l1234r100",
            url,
        )
    }

    @Test
    fun `free items national - page 1`() {
        val url = KleinanzeigenUrlBuilder.freeItems(
            locationId = null,
            citySlug = null,
            radiusKm = null,
            page = 1,
        )
        assertEquals(
            "https://www.kleinanzeigen.de/s-preis::0/zu-verschenken/k0",
            url,
        )
    }

    @Test
    fun `free items national - page 5`() {
        val url = KleinanzeigenUrlBuilder.freeItems(
            locationId = null,
            citySlug = null,
            radiusKm = null,
            page = 5,
        )
        assertEquals(
            "https://www.kleinanzeigen.de/s-preis::0/seite:5/zu-verschenken/k0",
            url,
        )
    }

    // ── Free items: private-only filter ─────────────────────────────────────

    @Test
    fun `free items private only`() {
        val url = KleinanzeigenUrlBuilder.freeItems(
            locationId = "1234",
            citySlug = "frankfurt-%28oder%29",
            radiusKm = 30,
            page = 1,
            privateOnly = true,
        )
        assertEquals(
            "https://www.kleinanzeigen.de/s-frankfurt-%28oder%29/preis::0/anbieter:privat/zu-verschenken/k0l1234r30",
            url,
        )
    }

    // ── Free items: sort by date ────────────────────────────────────────────

    @Test
    fun `free items sorted by date`() {
        val url = KleinanzeigenUrlBuilder.freeItems(
            locationId = "1234",
            citySlug = "frankfurt-%28oder%29",
            radiusKm = 30,
            page = 1,
            sortByDate = true,
        )
        assertTrue(url.contains("sortingField=SORTING_DATE"))
    }

    // ── Regular search URL ──────────────────────────────────────────────────

    @Test
    fun `regular search - page 1`() {
        val url = KleinanzeigenUrlBuilder.regularSearch(
            query = "Sony WH-1000XM4",
            page = 1,
        )
        assertEquals(
            "https://www.kleinanzeigen.de/s-Sony+WH-1000XM4/k0",
            url,
        )
    }

    @Test
    fun `regular search - page 3`() {
        val url = KleinanzeigenUrlBuilder.regularSearch(
            query = "iPhone 15",
            page = 3,
        )
        assertEquals(
            "https://www.kleinanzeigen.de/s-seite:3/iPhone+15/k0",
            url,
        )
    }

    @Test
    fun `regular search with price range`() {
        val url = KleinanzeigenUrlBuilder.regularSearch(
            query = "Macbook",
            page = 1,
            minPriceCents = 50000L,
            maxPriceCents = 100000L,
        )
        assertEquals(
            "https://www.kleinanzeigen.de/s-preis:500:1000/Macbook/k0",
            url,
        )
    }

    @Test
    fun `regular search with location`() {
        val url = KleinanzeigenUrlBuilder.regularSearch(
            query = "Sofa",
            page = 1,
            locationId = "1234",
            radiusKm = 50,
        )
        assertEquals(
            "https://www.kleinanzeigen.de/s-Sofa/k0l1234r50",
            url,
        )
    }

    // ── Car search URL (Autos category c216) ────────────────────────────────

    @Test
    fun `car search - page 1`() {
        val url = KleinanzeigenUrlBuilder.carSearch(
            query = "Volkswagen Crafter",
            page = 1,
        )
        assertEquals(
            "https://www.kleinanzeigen.de/s-autos/volkswagen-crafter/k0c216",
            url,
        )
    }

    @Test
    fun `car search - page 3`() {
        val url = KleinanzeigenUrlBuilder.carSearch(
            query = "volkswagen crafter",
            page = 3,
        )
        assertEquals(
            "https://www.kleinanzeigen.de/s-autos/seite:3/volkswagen-crafter/k0c216",
            url,
        )
    }

    @Test
    fun `car search with location and radius`() {
        val url = KleinanzeigenUrlBuilder.carSearch(
            query = "volkswagen crafter",
            page = 1,
            locationId = "1234",
            radiusKm = 50,
        )
        assertEquals(
            "https://www.kleinanzeigen.de/s-autos/volkswagen-crafter/k0c216l1234r50",
            url,
        )
    }

    @Test
    fun `car search with price range`() {
        val url = KleinanzeigenUrlBuilder.carSearch(
            query = "volkswagen crafter",
            page = 1,
            minPriceCents = 500000L,
            maxPriceCents = 1500000L,
        )
        assertEquals(
            "https://www.kleinanzeigen.de/s-autos/preis:5000:15000/volkswagen-crafter/k0c216",
            url,
        )
    }

    // ── City slug encoding ──────────────────────────────────────────────────

    @Test
    fun `city slug from name - simple`() {
        assertEquals("berlin", KleinanzeigenUrlBuilder.citySlug("Berlin"))
    }

    @Test
    fun `city slug from name - with parentheses`() {
        assertEquals("frankfurt-%28oder%29", KleinanzeigenUrlBuilder.citySlug("Frankfurt (Oder)"))
    }

    @Test
    fun `city slug from name - with umlaut`() {
        assertEquals("m%C3%BCnchen", KleinanzeigenUrlBuilder.citySlug("München"))
    }

    @Test
    fun `city slug from name - with spaces`() {
        assertEquals("bad-saulgau", KleinanzeigenUrlBuilder.citySlug("Bad Saulgau"))
    }
}
