package io.github.tieo.arbay.model

import kotlinx.serialization.Serializable

/** What one SMART screenshot in an ad's photos says about the drive it shows. */
@Serializable
enum class DriveVerdict {
    /** A value the drive itself counts as failing: pending or uncorrectable sectors, many
     *  reallocated ones, an attribute at or under its threshold, or the tool's own warning. */
    FAILING,

    /** A few reallocated sectors and nothing else wrong: still working, already wearing. */
    WORN,

    /** Every sector count read and zero, nothing at its threshold. */
    CLEAN,

    /** A SMART screen was found, but not enough of it read to say either way. */
    UNREADABLE,
}

/**
 * One drive's SMART values as a screenshot in its ad shows them, read off the photo. Null counts
 * were not on the screenshot or not readable; [findings] names what decided the verdict, in the
 * screenshot's own terms ("Helium 19 at threshold 25").
 */
@Serializable
data class SmartReading(
    /** The photo, by its place in the ad's gallery, counted from 1. */
    val photo: Int,
    val verdict: DriveVerdict,
    val powerOnHours: Long? = null,
    val reallocated: Long? = null,
    val pending: Long? = null,
    val uncorrectable: Long? = null,
    /** The tool's own word for the drive's health, as the screenshot shows it ("Gut", "Vorsicht"). */
    val status: String? = null,
    val findings: List<String> = emptyList(),
)

/** Every SMART screenshot found among an ad's photos, read. Empty where its photos show none. */
@Serializable
data class DriveHealth(val readings: List<SmartReading> = emptyList(), val photosRead: Int = 0)

/** The photos of one drive's ad, sent to have its SMART screenshots read. */
@Serializable
data class DriveHealthAsk(val listingId: String, val title: String, val photos: List<String>)

// The case is ignored through the option rather than an inline (?i), which the browser's
// JavaScript regular expressions refuse.
private val driveWords = Regex(
    """\b(hdd|ssd|nvme|festplatten?|hard\s?(disk|drive)s?|harddisk|disque\s+dur|disco\s+duro|""" +
        """exos|ironwolf|ultrastar|skyhawk|barracuda|wd\s?(red|purple|gold|blue|black|green)|caviar|toshiba\s+mg\d+|""" +
        // Titles that name the drive by its line or model number alone: "Western Digital DC HC510
        // 10TB … HUH721010ALE604", "ST8000DM004", "WD80EZAZ".
        """dc\s?hc\d{3}|hu[hs]7\d{5}\w*|st\d{3,5}[a-z]{2}\d{3}|wd\d{2,3}[a-z]{4})\b""",
    RegexOption.IGNORE_CASE,
)

/** Whether a listing's title names a drive, whose photos may hold a SMART screenshot worth reading. */
fun looksLikeADrive(title: String): Boolean = driveWords.containsMatchIn(title)
