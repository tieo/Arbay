package io.github.tieo.arbay.model

import kotlinx.serialization.Serializable

@Serializable
enum class DriveInterface(val label: String) { SATA("SATA"), SAS("SAS"), NVME("NVMe") }

@Serializable
enum class DriveRecording { CMR, SMR }

/** Where a drive's power-on hours come from, the photo being the only one the seller cannot simply type. */
@Serializable
enum class DriveHoursFrom {
    /** A SMART screenshot among the ad's photos, read on the server. */
    PHOTO,

    /** The seller's own words. */
    AD_TEXT,

    /** No hours at all: the market or the seller says it is new. */
    NEW,
}

/**
 * What an offer for a drive states, read from its card, its page and its photos, so drives can be
 * compared without opening each ad. Every field is null where the offer does not say.
 */
@Serializable
data class DriveFacts(
    val capacityGb: Int? = null,
    val model: String? = null,
    val driveInterface: DriveInterface? = null,
    val recording: DriveRecording? = null,
    /** In a USB case as sold; false where the offer names a bare drive. */
    val external: Boolean? = null,
    val powerOnHours: Long? = null,
    val hoursFrom: DriveHoursFrom? = null,
    /** The worst of what the ad's SMART screenshots say. */
    val health: DriveVerdict? = null,
    val healthNote: String? = null,
    /** What one drive costs where the ad sells several ("pro Stück", "zu je"). */
    val unitPrice: Money? = null,
    /** How many the seller has ("6 Stück vorhanden"). */
    val stock: Int? = null,
    /** The ad sells several at a price that may be for all of them, so no price per drive is known. */
    val lotPriceUnclear: Boolean = false,
    /** Whether the ad's page has been read yet, which is where most of these facts are. */
    val pageRead: Boolean = false,
    /** Photos still to be read for SMART screenshots. */
    val photosPending: Int = 0,
    /** One drive with its shipping, in euro cents per gigabyte. */
    val centsPerGb: Double? = null,
    /** Expected working hours left, up to the end of what the failure records cover. */
    val lifeHours: Long? = null,
    /** The chance of failing within five years of running around the clock. */
    val failChance5y: Double? = null,
    /** Whose failure records [lifeHours] rests on: a model family's, or the fleet's of 12 TB and up. */
    val lifeBasis: String? = null,
)

/**
 * The offers no other offer beats on both price per gigabyte and expected life left: the ones worth
 * weighing against each other. A drive whose SMART says it is failing is on no front.
 */
fun List<Listing>.driveFront(): Set<String> {
    val candidates = mapNotNull { l ->
        val d = l.drive ?: return@mapNotNull null
        val cents = d.centsPerGb ?: return@mapNotNull null
        val life = d.lifeHours ?: return@mapNotNull null
        Triple(l.id, cents, life).takeUnless { d.health == DriveVerdict.FAILING }
    }
    return candidates.filter { (_, cents, life) ->
        candidates.none { (_, c, l) -> c <= cents && l >= life && (c < cents || l > life) }
    }.mapTo(HashSet()) { it.first }
}

private val driveSizeWords = Regex("""\b\d{1,3}(?:[.,]\d)?\s?tb\b""", RegexOption.IGNORE_CASE)

/** Whether a search is after a drive, so its offers are worth reading for a drive's facts. */
fun looksLikeADriveSearch(text: String): Boolean = looksLikeADrive(text) || driveSizeWords.containsMatchIn(text)

/** How a drive's facts read on one line of an offer: "18 TB · SATA · CMR · 2.1 ct/GB · 38k h left". */
fun DriveFacts.summary(): List<String> = listOfNotNull(
    capacityGb?.let { if (it >= 1000) "${trimmedTb(it)} TB" else "$it GB" },
    driveInterface?.label,
    recording?.name,
    external?.takeIf { it }?.let { "external" },
    centsPerGb?.let { "${oneDecimal(it)} ct/GB" },
    lifeHours?.let { "${thousands(it)} h left" },
    powerOnHours?.let { "${thousands(it)} h run" + if (hoursFrom == DriveHoursFrom.AD_TEXT) " (ad says)" else "" },
    health?.let { it.name.lowercase() },
    unitPrice?.let { "${it.amount / 100} € each" },
    stock?.let { "$it in stock" },
    "price for how many unclear".takeIf { lotPriceUnclear },
)

private fun trimmedTb(gb: Int): String = if (gb % 1000 == 0) "${gb / 1000}" else oneDecimal(gb / 1000.0)

private fun oneDecimal(v: Double): String {
    val tenths = kotlin.math.round(v * 10).toLong()
    return "${tenths / 10}.${tenths % 10}"
}

private fun thousands(h: Long): String = if (h >= 1000) "${h / 1000}k" else "$h"
