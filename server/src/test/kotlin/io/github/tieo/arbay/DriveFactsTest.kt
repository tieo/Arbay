package io.github.tieo.arbay

import io.github.tieo.arbay.judging.DriveLife
import io.github.tieo.arbay.judging.DriveOffers
import io.github.tieo.arbay.judging.DriveText
import io.github.tieo.arbay.model.Condition
import io.github.tieo.arbay.model.Currency
import io.github.tieo.arbay.model.DriveFacts
import io.github.tieo.arbay.model.DriveFrontAlert
import io.github.tieo.arbay.model.wants
import io.github.tieo.arbay.model.DriveHealth
import io.github.tieo.arbay.model.DriveHoursFrom
import io.github.tieo.arbay.model.DriveInterface
import io.github.tieo.arbay.model.DriveRecording
import io.github.tieo.arbay.model.DriveVerdict
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.ListingDetail
import io.github.tieo.arbay.model.Money
import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.model.SaleType
import io.github.tieo.arbay.model.Shipping
import io.github.tieo.arbay.model.SmartReading
import io.github.tieo.arbay.model.driveFront
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

class DriveFactsTest {
    private fun offer(
        title: String, euros: Long, shippingCents: Long? = null, platform: PlatformId = PlatformId.KLEINANZEIGEN,
        condition: Condition? = null, saleType: SaleType? = null,
    ) = Listing(
        id = "${platform.name}:1", platformId = platform, externalId = "1", url = "https://example.test/1", title = title,
        price = Money(euros * 100, Currency.EUR), scrapedAt = Clock.System.now(), condition = condition, saleType = saleType,
        shipping = shippingCents?.let { Shipping(cost = Money(it, Currency.EUR), available = true) },
    )

    private fun facts(l: Listing, page: String? = null, health: DriveHealth = DriveHealth()): DriveFacts =
        DriveOffers.read(l, page?.let { ListingDetail(description = it) }, health)

    @Test
    fun a_model_number_states_size_interface_and_recording() {
        val d = facts(offer("Seagate Exos ST18000NM000J", 330, 619))
        assertEquals(18_000, d.capacityGb)
        assertEquals("ST18000NM000J", d.model)
        assertEquals(DriveInterface.SATA, d.driveInterface)
        assertEquals(DriveRecording.CMR, d.recording)
        assertEquals(false, d.external)
    }

    @Test
    fun capacity_decodes_from_every_makers_numbering() {
        assertEquals(14_000, DriveText.capacityOfModel("WD140EDFZ-11A0VA0"))
        assertEquals(8_000, DriveText.capacityOfModel("WD80EFAX"))
        assertEquals(500, DriveText.capacityOfModel("WD5000AZLX"))
        assertEquals(16_000, DriveText.capacityOfModel("MG08ACA16TE"))
        assertEquals(16_000, DriveText.capacityOfModel("WUH721816ALE6L4"))
        assertEquals(8_000, DriveText.capacityOfModel("HUH728080ALE600"))
        assertEquals(14_000, DriveText.capacityOfModel("WDBBGB0140HBK"))
    }

    @Test
    fun several_sizes_in_a_title_are_no_size_and_a_link_speed_is_none() {
        assertNull(DriveText.capacityOfWords("Festplatten 8TB, 10TB, 12TB"))
        assertEquals(18_000, DriveText.capacityOfWords("18 TB HDD SATA 6Gb/s 256 MB"))
    }

    @Test
    fun an_sas_ultrastar_reads_as_sas_and_a_seagate_by_its_ending() {
        assertEquals(DriveInterface.SAS, DriveText.interfaceOfModel("HUH721212AL4204"))
        assertEquals(DriveInterface.SATA, DriveText.interfaceOfModel("WUH721414ALE6L4"))
        assertEquals(DriveInterface.SAS, DriveText.interfaceOfModel("ST18000NM004J"))
        assertEquals(DriveInterface.SATA, DriveText.interfaceOfModel("MG09ACA18TE"))
    }

    @Test
    fun shingled_models_are_told_from_conventional_ones() {
        assertEquals(DriveRecording.SMR, DriveText.recordingOfModel("WD40EFAX"))
        assertEquals(DriveRecording.CMR, DriveText.recordingOfModel("WD80EFAX"))
        assertEquals(DriveRecording.SMR, DriveText.recordingOfModel("ST8000DM004"))
        assertEquals(DriveRecording.SMR, DriveText.recordingOfWords("Festplatte mit SMR"))
    }

    @Test
    fun an_external_drive_is_external_even_with_its_inner_model_named() {
        assertEquals(true, facts(offer("WD Elements 14TB (WD140EDGZ-11B1PA0 inside), 118 h", 329)).external)
        assertEquals(true, facts(offer("WD My Book 14TB WDBBGB0140HBK", 295)).external)
    }

    @Test
    fun hours_come_from_a_photo_before_the_sellers_words() {
        val health = DriveHealth(readings = listOf(
            SmartReading(photo = 1, verdict = DriveVerdict.CLEAN, powerOnHours = 35_243),
            SmartReading(photo = 2, verdict = DriveVerdict.CLEAN, powerOnHours = 39_846),
        ), photosRead = 2)
        val d = facts(offer("Seagate Exos X18 18TB", 330), "Laufzeit ca. 12000 h", health)
        assertEquals(39_846, d.powerOnHours)
        assertEquals(DriveHoursFrom.PHOTO, d.hoursFrom)
        assertEquals(DriveVerdict.CLEAN, d.health)
    }

    @Test
    fun the_sellers_hours_are_read_in_their_forms_and_a_delivery_time_is_not_hours() {
        assertEquals(6367, DriveText.hoursOfWords("Betriebsstunden: 6367 / 5926"))
        assertEquals(43_822, DriveText.hoursOfWords("43.822 Betriebsstunden"))
        assertEquals(20_900, DriveText.hoursOfWords("14400-20900 h je Platte"))
        assertNull(DriveText.hoursOfWords("Versand innerhalb 24 h, 7200 rpm"))
    }

    @Test
    fun a_failing_screen_outranks_a_clean_one_and_takes_the_drive_off_the_life_column() {
        val health = DriveHealth(readings = listOf(
            SmartReading(photo = 1, verdict = DriveVerdict.CLEAN, powerOnHours = 100),
            SmartReading(photo = 2, verdict = DriveVerdict.FAILING, powerOnHours = 200, findings = listOf("12 pending sectors")),
        ), photosRead = 2)
        val d = facts(offer("Toshiba MG08ACA16TE 16TB", 300, 619), null, health)
        assertEquals(DriveVerdict.FAILING, d.health)
        assertEquals("12 pending sectors", d.healthNote)
        assertNull(d.lifeHours)
    }

    @Test
    fun a_price_per_piece_stated_under_a_lot_price_is_what_one_costs() {
        val d = facts(offer("4x Seagate Exos 18TB", 1200, 619), "Preis 300 € pro Stück, 4 Stück vorhanden")
        assertEquals(30_000, d.unitPrice?.amount)
        assertEquals(4, d.stock)
        assertFalse(d.lotPriceUnclear)
        assertTrue(abs(d.centsPerGb!! - 30_619.0 / 18_000) < 0.001)
    }

    @Test
    fun a_listed_price_stays_when_it_already_is_one_drives() {
        val d = facts(offer("Toshiba MG08ACA14TE 14TB", 309, 99), "6 Stück vorhanden, zu je 309 Euro")
        assertNull(d.unitPrice)
        assertEquals(6, d.stock)
        assertTrue(abs(d.centsPerGb!! - 30_999.0 / 14_000) < 0.001)
    }

    @Test
    fun a_lot_without_a_price_per_piece_has_no_price_per_gigabyte() {
        val d = facts(offer("3x Seagate Exos 18TB", 900, 619))
        assertTrue(d.lotPriceUnclear)
        assertNull(d.centsPerGb)
    }

    @Test
    fun an_auctions_bid_is_no_price_per_gigabyte() {
        assertNull(facts(offer("Toshiba MG08ACA16TE", 150, 619, PlatformId.EBAY_DE, saleType = SaleType.AUCTION)).centsPerGb)
    }

    @Test
    fun a_new_box_on_kleinanzeigen_needs_the_words_since_its_form_box_says_nothing() {
        val ticked = facts(offer("Toshiba MG08ACA16TE", 380, condition = Condition.NEW))
        assertNull(ticked.powerOnHours)
        val sealed = facts(offer("Toshiba MG08ACA16TE originalverpackt", 380))
        assertEquals(0, sealed.powerOnHours)
        assertEquals(DriveHoursFrom.NEW, sealed.hoursFrom)
        val ebay = facts(offer("Toshiba MG08ACA16TE", 380, 0, PlatformId.EBAY_DE, Condition.NEW))
        assertEquals(0, ebay.powerOnHours)
    }

    @Test
    fun a_drive_of_unknown_hours_has_no_expected_life() {
        assertNull(facts(offer("Seagate Exos ST18000NM000J", 330, 619)).lifeHours)
    }

    @Test
    fun an_ssd_has_no_hard_drive_life() {
        val d = facts(offer("Samsung 990 Pro SSD 2TB NVMe 321 Betriebsstunden", 180, 0))
        assertEquals(DriveInterface.NVME, d.driveInterface)
        assertEquals(321, d.powerOnHours)
        assertNull(d.lifeHours)
    }

    @Test
    fun the_expected_life_matches_the_records_calculation() {
        fun close(family: String?, hours: Long, left: Long, p5: Double?) {
            val e = DriveLife.expected(family, hours)!!
            assertTrue(abs(e.hoursLeft - left) <= 1, "$family at $hours: ${e.hoursLeft} for $left")
            if (p5 != null) assertTrue(abs(e.failChance5y!! - p5) < 0.0001, "$family at $hours: ${e.failChance5y} for $p5")
            else assertNull(e.failChance5y)
        }
        close(null, 0, 66_135, 0.0669)
        close(null, 20_000, 47_487, 0.0976)
        close("MG08", 0, 66_040, 0.0720)
        close("MG08", 20_000, 47_351, 0.1033)
        close("MG07", 35_000, 33_748, null)
    }

    @Test
    fun drives_of_one_line_share_their_family_and_records_name_the_same_one() {
        assertEquals("MG08", DriveLife.familyOf("TOSHIBA MG08ACA16TEY"))
        assertEquals("MG08", DriveLife.familyOf("MG08ACA14TE"))
        assertEquals("Ultrastar 7218", DriveLife.familyOf("WDC WUH721816ALE6L4"))
        assertEquals("Ultrastar 7218", DriveLife.familyOf("WUH721818ALE6L4"))
        assertEquals("Seagate NM001G", DriveLife.familyOf("ST14000NM001G-2KJ103"))
        assertEquals("Seagate NM001G", DriveLife.familyOfLine("Seagate Exos X16 16TB"))
        assertEquals("MG09", DriveLife.familyOfLine("Toshiba MG09 18TB"))
    }

    @Test
    fun a_front_alert_holds_a_drive_only_against_what_it_states() {
        val alert = DriveFrontAlert(enabled = true, minGb = 14_000, maxGb = 20_000, driveInterface = DriveInterface.SATA, recording = DriveRecording.CMR, bareOnly = true)
        assertTrue(alert.wants(DriveFacts(capacityGb = 18_000, driveInterface = DriveInterface.SATA, recording = DriveRecording.CMR, external = false)))
        assertTrue(alert.wants(DriveFacts(capacityGb = 18_000)))
        assertFalse(alert.wants(DriveFacts(capacityGb = 12_000)))
        assertFalse(alert.wants(DriveFacts()))
        assertFalse(alert.wants(DriveFacts(capacityGb = 18_000, driveInterface = DriveInterface.SAS)))
        assertFalse(alert.wants(DriveFacts(capacityGb = 18_000, recording = DriveRecording.SMR)))
        assertFalse(alert.wants(DriveFacts(capacityGb = 18_000, external = true)))
    }

    @Test
    fun the_front_holds_only_offers_nothing_beats_on_both() {
        fun drive(id: String, cents: Double?, life: Long?, health: DriveVerdict? = null) =
            offer("x", 1).copy(id = id, drive = DriveFacts(centsPerGb = cents, lifeHours = life, health = health))
        val front = listOf(
            drive("cheap", 1.5, 30_000),
            drive("long", 2.5, 60_000),
            drive("beaten", 2.0, 25_000),
            drive("middle", 2.0, 45_000),
            drive("failing", 0.5, 70_000, DriveVerdict.FAILING),
            drive("unknown", 0.5, null),
        ).driveFront()
        assertEquals(setOf("cheap", "long", "middle"), front)
    }
}
