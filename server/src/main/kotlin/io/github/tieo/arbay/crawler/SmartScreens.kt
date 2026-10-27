package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.DataDir
import io.github.tieo.arbay.model.DriveHealth
import io.github.tieo.arbay.model.DriveVerdict
import io.github.tieo.arbay.model.SmartReading
import io.github.tieo.arbay.repo.readStore
import io.github.tieo.arbay.repo.writeTextAtomically
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * The SMART screenshots sellers put among a drive's photos, read the way a buyer reads them.
 *
 * A used drive's ad often shows CrystalDiskInfo or a similar tool, and that picture is where a
 * dying drive gives itself away: a 45 euro 8TB drive whose helium had fallen under its threshold, a
 * 12TB one with 47,520 reallocated sectors, a 6TB one at 95,000 hours with a pending sector, each
 * under a title and a text that said nothing of it. Each photo is read with Tesseract on this
 * server, so nothing leaves it, and the text is matched by attribute name in German and English,
 * since the ID column is where the OCR slips ("C5" read as "€5").
 *
 * A verdict needs what it rests on to be read: [DriveVerdict.FAILING] a failing value seen, and
 * [DriveVerdict.CLEAN] every sector count seen and zero. Anything less is
 * [DriveVerdict.UNREADABLE]. Nothing acts on a verdict yet; every reading is kept in a log to be
 * checked against the drives themselves before anything does.
 */
object SmartScreens {
    private val log = LoggerFactory.getLogger(SmartScreens::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Photos read per ad: a gallery longer than this is product shots, not more screenshots. */
    private const val MOST_PHOTOS = 12

    /** A row of a SMART table: three normalised values and the raw one in twelve hex digits, as
     *  CrystalDiskInfo prints them ("Wiederzugewiesene Sektoren 88 88 10 00000000B9A0"). Whatever the
     *  OCR makes of the scroll bar and window edge after it is let be, and so is one stray character
     *  before the raw value ("1000000000000"), since the twelve digits are a fixed width. */
    private val tableRow = Regex("""(\d{1,3})\s*[^\w\s]{0,2}\s+(\d{1,3})\s*[^\w\s]{0,2}\s+(\d{1,3})\s*[^\w\s]{0,2}\s+(?:0x|[0-9A-Fa-f])?([0-9A-Fa-f]{12})(?![0-9A-Fa-f])""")

    /** A CrystalDiskInfo row with its threshold lost: RapidOCR read "Aktuell ausstehende Sektoren
     *  100 100 0 000000000000" as "100 100 000000000000", the lone 0 taken into the raw value's box. */
    private val lostThresholdRow = Regex("""^\s*(\d{1,3})\s+(\d{1,3})\s+([0-9A-Fa-f]{12})(?![0-9A-Fa-f])""")

    /** A row as Hard Disk Sentinel shows it: threshold, value, worst, its own status word, then the
     *  raw value in twelve hex digits ("Reallocated Sectors Count 10 100 100 OK 000000000000"). */
    private val sentinelRow = Regex("""(?i)(\d{1,3})\s+(\d{1,3})\s+(\d{1,3})\s+(?:ok|warning|failed)\b.*?\b([0-9A-Fa-f]{12})\b""")

    /** The same row with the raw value in decimal, as CrystalDiskInfo shows it when set so
     *  ("Wiederzugewiesene Sektoren 100 100 36 0"); believed only on a screen with no hex row. */
    private val decimalRow = Regex("""(\d{1,3})\s*[|\]]?\s+(\d{1,3})\s*[|\]]?\s+(\d{1,3})\s*[|\]]?\s+(\d{1,12})\W*$""")

    /** A row as smartctl prints it: flag, value, worst, threshold, type, when updated, when failed, raw
     *  ("Reallocated_Sector_Ct 0x0033 100 100 010 Pre-fail Always - 0"). The OCR reads the flag's
     *  "0x" as "©x" or "9x", so only its x and four characters are relied on. */
    private val smartctlRow = Regex("""^\W*\S{0,3}x\w{4}\s+(\d{1,3})\s+(\d{1,3})\s+(\d{1,3})\s+\S+\s+\S+\s+(\S+)\s+(\d+)\b""")

    private val smartctlAnywhere = Regex("""\S{0,3}x\w{4}\s+(\d{1,3})\s+(\d{1,3})\s+(\d{1,3})\s+\S+\s+\S+\s+(\S+)\s+(\d+)\b""")

    /** A row as Proxmox's SMART dialog shows it: raw value first, then normalised, threshold, worst
     *  and the flags ("Reallocated_Sector_Ct 0 100 10 100 PO--CK -"). */
    // The flags are letters and dashes, an O among them read as 0 at times ("-0-0-").
    private val proxmoxRow = Regex("""^\W*(\d+)\s+(\d{1,3})\s+(\d{1,3})\s+(\d{1,3})\s+[-A-Z0]{4,}""")

    /** A row with only a value and its threshold, as Scrutiny shows some ("Current Helium Level 19 25"). */
    private val pairRow = Regex("""(\d{1,6})\s+(\d{1,3})\b""")

    // The OCR loses the opening bracket of an ID as often as not ("22 0x16)").
    private val scrutinyMarks = Regex("""(?i)\(?0[x%]\w{2}\)|threshold\s+ideal|fa\w{0,2}ure\s?rate|scrutiny""")

    private enum class Attribute(val names: Regex) {
        // smartctl and Proxmox join the words with underscores ("Reallocated_Sector_Ct"), RapidOCR
        // drops the space between words set close ("WiederzugewieseneSektoren"); the French
        // CrystalDiskInfo names them in French.
        // WD's SSDs call it "Reassigned Block Count".
        REALLOCATED(Regex("""(?i)wiederzugewiesene[nr]?\s*sektor|reallocated[\s_]*sectors?|secteurs\s*r[ée]allou|reassigned\s*block\s*count""")),
        // Older German CrystalDiskInfo calls pending sectors "schwebend".
        PENDING(Regex("""(?i)(ausstehende|schwebende)\s*sektor|pending[\s_]*sectors?|secteurs\s*en\s*attente|secteurs\W+instables""")),
        UNCORRECTABLE(Regex("""(?i)(nicht\s?korrigierbare|unkorrigierbare)\s*sektor|offline[\s_]*uncorrectable|uncorrectable[\s_]*sectors?|erreurs\s*incorrigibles""")),
        REPORTED_UNCORRECTABLE(Regex("""(?i)gemeldete\s*unkorrigierbare|reported[\s_]*uncorrect|erreurs\s*signal|erreurs\s*qui\s*n.ont\s*pas""")),
        // The OCR mangles the middle of the word ("Beitnebsstunden", "Beinebsstunden").
        HOURS(Regex("""(?i)be\w{1,6}stunden|power[\s_-]?[o0]n[\s_-]?hou|heures\s*de\s*fonctionnement""")),
        // The OCR swaps its letters ("Current Heilum Level").
        HELIUM(Regex("""(?i)he(li|il)um""")),
        // An NVMe drive's log page in place of ATA attributes, named in English in every language of
        // CrystalDiskInfo. The OCR reads "Warning" as "Waming"; the threshold comes before the spare
        // itself, whose name it begins with.
        CRITICAL_WARNING(Regex("""(?i)critical\s*wa\w{2,4}g\b""")),
        SPARE_THRESHOLD(Regex("""(?i)available\s*spare\s*threshold""")),
        SPARE(Regex("""(?i)available\s*spare""")),
        USED(Regex("""(?i)percentage\s*used""")),
        MEDIA_ERRORS(Regex("""(?i)media\s*and\s*data\s*integrity""")),
    }

    private val nvmeAttributes = setOf(Attribute.CRITICAL_WARNING, Attribute.SPARE_THRESHOLD, Attribute.SPARE, Attribute.USED, Attribute.MEDIA_ERRORS)

    /** An NVMe row's raw value: CrystalDiskInfo's fourteen hex digits at the end of the line, which the
     *  OCR at times splits once ("000000000001 28") or reads a zero of as O. */
    private val nvmeRaw = Regex("""([0-9A-FOQ]{6,16}(?:\s[0-9A-FOQ]{1,8})?)\W*$""")
    private val nvmePowerCycles = Regex("""(?i)power\s+cycles?\b""")
    private val headerPowerCount = Regex("""(?i)(eingeschaltet|power\s+on\s+count|inschakelingen)[\s|\[\]_:;.lIu!]{1,6}(\d{1,3}(?:[.,]?\d{3})*)\s*(mal|count|keer)""")

    private val status = Regex("""(?i)\b(gut|vorsicht|schlecht|good|caution|bad|goed|voorzichtig|slecht)\s+(schnittstelle|interface)\b""")
    // The field beside the label is framed, and the OCR reads its edge as "[", "|", "_", "l" or "I".
    private val headerHours = Regex("""(?i)(betriebsstunden|power\s+on\s+hours|fonctionnement|bedrijfsuren)[\s|\[\]_:;.lI!]{1,6}(\d{1,3}(?:[.,]?\d{3})*)\s*(std|sta|stunden|hours|heures|uren)""")
    /** smartctl's own verdict on the drive; PASSED says nothing of its sector counts, FAILED says it fails. */
    private val smartctlFailed = Regex("""self-assessment\s+test\s+result:\s*FAILED""", RegexOption.IGNORE_CASE)
    private val daysAndHours = Regex("""(?i)(\d{1,4})\s*(tag\(e\)|tage?|days?)\W{0,3}\s*(\d{1,2})\s*(stunde|hour)""")

    /** Between the texts of a photo's readings, where it was read more than once. */
    private const val PASS_BREAK = "\n\u000C\n"

    /** The most hours a drive's counter believes: some 34 years. */
    private const val MOST_HOURS = 300_000L
    // Dutch CrystalDiskInfo says Goed, Voorzichtig, Slecht and Onbekend. A row named for bad blocks
    // or sectors ("Grown Bad Blocks", read glued as "Bad Blocksperdie") is no warning.
    /** Rows only a flash drive has: its erase cycles, its NAND, its wear. */
    private val solidState = Regex("""(?i)p/?e\s*cycles|nand|erase\s*count|wear\s*level|ssd\s*life|percent\s*lifetime""")
    private val goodTab = Regex("""\b(gut|good|goed)\b""", RegexOption.IGNORE_CASE)
    private val warningWord = Regex("""\b(vorsicht|schlecht|caution|bad(?!\W*(block|sector))|unbekannt|unknown|voorzichtig|slecht|onbekend)\b""", RegexOption.IGNORE_CASE)
    private val failingStatus = setOf("vorsicht", "schlecht", "caution", "bad", "voorzichtig", "slecht")

    /** The most bad sectors a reading believes; a drive's spare area holds some thousands to tens of thousands. */
    private const val MOST_SECTORS = 1_000_000L

    /** Reallocated sectors beyond which a drive is failing rather than wearing. */
    private const val MANY_REALLOCATED = 100

    /** What one OCR text of a photo shows, before a verdict: each value as read, null where unread. */
    private data class Seen(
        val counts: Map<Attribute, Long>,
        val failing: List<String>,
        val word: String?,
        val hours: Long?,
        /** The hours the screen's header shows, and the ones its power-on row holds. */
        val header: Long? = null,
        val table: Long? = null,
        /** A flash drive's screen, which has no pending sectors to count. */
        val solidState: Boolean = false,
    )

    /**
     * What one photo's text says, or null where it is no SMART screen: at least three attributes
     * have to be found by name before it counts as one.
     */
    fun parse(text: String, photo: Int): SmartReading? = parse(listOf(text), photo)

    /**
     * What a photo says, read from more than one OCR of it. A value counts only where every reading
     * that found it agrees: a terminal's slashed zero reads as 0 at one size and 9 at another, and a
     * count read two ways is a count not known.
     */
    fun parse(texts: List<String>, photo: Int): SmartReading? {
        val seen = texts.mapNotNull { see(it) }.ifEmpty { return null }
        fun <T> agreed(values: List<T?>): T? = values.filterNotNull().distinct().singleOrNull()
        // A count above zero says the drive fails, so it has to be read the same by every reading;
        // a zero is taken from any reading no other contradicts.
        val counts = Attribute.entries.mapNotNull { a ->
            val all = seen.map { it.counts[a] }
            // How much spare and wear an NVMe drive shows is no count of faults: any one reading of it
            // no other contradicts is taken.
            val levels = a in setOf(Attribute.SPARE, Attribute.SPARE_THRESHOLD, Attribute.USED)
            agreed(all)?.takeIf { levels || it == 0L || all.all { v -> v == it } }?.let { a to it }
        }.toMap()
        // A value at its threshold is read off a whole row that passed its own check (current at or
        // above worst), and a reading at the larger size can lose a row the first one had, so a
        // mark either reading makes counts.
        val failing = seen.flatMap { it.failing }.distinct()
        return judge(
            Seen(counts, failing, seen.firstNotNullOfOrNull { it.word }, agreedHours(seen), solidState = seen.any { it.solidState }),
            photo,
        )
    }

    /** The hours as a power-on row whose hex Tesseract reads with every B as 8 and every A as 4
     *  shows them (41779 hours, 0xA333, as 0x4333): the same count. */
    private fun asTableReadsIt(hours: Long) = hours.toString(16).uppercase().replace('B', '8').replace('A', '4').toLong(16)

    /**
     * The hours the readings agree on; where they differ, the count most of the witnesses read: each
     * reading's header, and the power-on row once.
     * A CrystalDiskInfo header read once as 35337 and once as 45337 is told apart by its own power-on
     * row, 0x8A09, and the "Std" the first reading saw as "sta".
     */
    private fun agreedHours(seen: List<Seen>): Long? {
        // Each reading's header count, or its table's where the header was lost, where all agree:
        // the table's hex is the weaker witness, read with B as 8.
        seen.mapNotNull { it.hours }.distinct().singleOrNull()?.let { return it }
        val headers = seen.mapNotNull { it.header }
        // The table is one witness however many readings show it: both read the same font, and
        // read its B as 8 alike (0xB0 as 0x80, 176 hours as 128).
        val tables = seen.mapNotNull { it.table }.distinct()
        val votes = (headers + tables.map { t -> headers.firstOrNull { asTableReadsIt(it) == t } ?: t })
            .groupingBy { it }.eachCount()
        val most = votes.values.maxOrNull()?.takeIf { it >= 2 } ?: return null
        return votes.filterValues { it == most }.keys.singleOrNull()
    }

    /** The power-on row's raw value in hex where the OCR left O's in it, which no hex digit is:
     *  "COOOOO008A09" is 0x8A09 hours. Only its low four bytes count, as with Seagate's. */
    private fun hoursInRawHex(line: String): Long? {
        val raw = Regex("""([0-9A-FOoQ]{12})\W*$""").find(line.trim())?.groupValues?.get(1) ?: return null
        return raw.replace(Regex("[OoQ]"), "0").toLongOrNull(16)?.and(0xFFFFFFFFL)?.takeIf { it in 1..MOST_HOURS }
    }

    private fun see(text: String): Seen? {
        val lines = text.lines()
        var found = 0
        val counts = HashMap<Attribute, Long>()
        val failing = mutableListOf<String>()
        var tableHours: Long? = null
        // A screen shows raw values in hex when one of them has a letter or a leading zero, which no
        // decimal number has; twelve decimal digits alone also fit the hex row.
        val hexScreen = lines.any { line -> tableRow.findAll(line).any { it.groupValues[4].let { raw -> raw.startsWith('0') || raw.any(Char::isLetter) } } }
        // Only Scrutiny shows a row as a bare value and threshold, and it is known by its columns and
        // its IDs ("5(0x05)"); on any other screen two numbers are a row the OCR lost half of, and
        // CrystalDiskInfo's "100 100" is no sector count.
        val scrutiny = !hexScreen && scrutinyMarks.containsMatchIn(text)
        // A terminal font with a slashed zero reads it as 8 or 9. No drive counts the same 8 or 9
        // on three attributes, so where three smartctl rows end in one such lone digit, every lone
        // 8 or 9 on the screen is a zero misread and is left unread.
        // An NVMe screen's table is a name and one raw value a row, and the OCR can pair each name with
        // the next row's value, so the table counts only where it agrees with the screen's header.
        val nvmeScreen = lines.count { line -> nvmeAttributes.any { it.names.containsMatchIn(line) } } >= 2
        val nvmeRows = HashMap<Attribute, Long>()
        var nvmeCycles: Long? = null
        val zeroAsDigit = lines.mapNotNull { smartctlAnywhere.find(it)?.groupValues?.last() }
            .filter { it == "8" || it == "9" }.groupingBy { it }.eachCount().values.any { it >= 3 }
        for (line in lines) {
            if (nvmeScreen && nvmePowerCycles.containsMatchIn(line)) nvmeCycles = nvmeValue(line)
            val attribute = Attribute.entries.firstOrNull { it.names.containsMatchIn(line) } ?: continue
            found++
            if (nvmeScreen && (attribute in nvmeAttributes || attribute == Attribute.HOURS)) {
                if (!headerHours.containsMatchIn(line)) nvmeValue(line)?.let { nvmeRows[attribute] = it }
                continue
            }
            if (attribute in nvmeAttributes) continue
            // The rest of the name ("_Ct", " Count") is left to the row patterns, which skip it.
            val after = line.substring(attribute.names.find(line)!!.range.last + 1).replace(Regex("""^[\w-]*"""), "")
            // Each format names its columns in its own order: current, worst, threshold, raw.
            val row: List<String>? = sentinelRow.find(after)?.groupValues?.let { g -> listOf(g[2], g[3], g[1], g[4]) }
                ?: tableRow.find(after)?.groupValues?.drop(1)
                // The threshold is no failure mark where it is lost, and the row's raw value stands.
                ?: lostThresholdRow.find(after)?.groupValues?.let { g -> listOf(g[1], g[2], "0", g[3]) }
                ?: smartctlRow.find(after)?.groupValues?.takeUnless { zeroAsDigit && it[5] in setOf("8", "9") }?.let { g ->
                    if (g[4].startsWith("FAILING", ignoreCase = true)) failing += "${attribute.label()} failing now"
                    listOf(g[1], g[2], g[3], g[5])
                }
                ?: proxmoxRow.find(after)?.groupValues?.let { g -> listOf(g[2], g[4], g[3], g[1]) }
                ?: if (hexScreen) null else decimalRow.find(after)?.groupValues?.drop(1)
            if (attribute == Attribute.HOURS && row == null && hexScreen) hoursInRawHex(line)?.let { tableHours = it }
            if (row != null) {
                val (current, worst, threshold, rawDigits) = row
                val hex = hexScreen && rawDigits.length == 12
                val raw = rawDigits.toLongOrNull(if (hex) 16 else 10)
                // A value at or under a threshold above zero is the drive's own failure mark. The
                // worst value is the lowest the current one has been, so a current one under it is
                // the OCR misreading the row ("100" read as "0"), and the row's marks are not believed.
                val cur = current.toInt()
                val thr = threshold.toInt()
                if (thr in 1..254 && cur <= thr && cur >= worst.toInt() && cur <= 253) failing += "${attribute.label()} $cur at threshold $thr"
                when (attribute) {
                    // Seagate keeps more than hours in this raw value; the hours are its low four bytes.
                    Attribute.HOURS -> tableHours = (if (hex) raw?.and(0xFFFFFFFFL) else raw)?.takeIf { it <= MOST_HOURS }
                    Attribute.HELIUM -> {}
                    // No drive has this many bad sectors: a count past it is the OCR misreading a
                    // digit of the hex ("…0000" read as "…9000"), and stays unread.
                    else -> if (raw != null && raw <= MOST_SECTORS) counts[attribute] = raw
                }
                continue
            }
            // Only a row of exactly two numbers is Scrutiny's: a CrystalDiskInfo row whose hex was
            // misread still has its three normalised values, and "200 200" is no sector count.
            if (!scrutiny || Regex("""\b\d+\b""").findAll(after).count() != 2) continue
            val pair = pairRow.find(after) ?: continue
            val (value, threshold) = pair.destructured
            when (attribute) {
                // Scrutiny's helium row is a value against its threshold, nothing else.
                Attribute.HELIUM -> if (threshold.toInt() > 0 && value.toInt() <= threshold.toInt())
                    failing += "Helium ${value.toInt()} at threshold ${threshold.toInt()}"
                // Scrutiny shows the raw count of a sector attribute as its value.
                Attribute.REALLOCATED, Attribute.PENDING, Attribute.UNCORRECTABLE, Attribute.REPORTED_UNCORRECTABLE ->
                    counts[attribute] = value.toLong()
                else -> {}
            }
        }
        // CrystalDiskInfo's header ("Bedrijfsuren 1920 uren", "Inschakelingen 832 keer") names the
        // screen as surely as a table row, and survives a photo of a monitor the table does not.
        if (headerHours.containsMatchIn(text)) found++
        if (headerPowerCount.containsMatchIn(text)) found++
        if (found < 3) return null
        val headerCount = headerHours.find(text)?.groupValues?.get(2)?.replace(Regex("[.,]"), "")?.toLongOrNull()
        val powerCount = headerPowerCount.find(text)?.groupValues?.get(2)?.replace(Regex("[.,]"), "")?.toLongOrNull()
        // The Fanxiang S880 at 321 hours and 676 power-ons read its hours row as 676 and its media
        // errors as 1: every value one row down. A row matching its own header count shows no shift.
        val nvmeBelieved = nvmeRows[Attribute.HOURS]?.let { it == headerCount } == true ||
            nvmeCycles?.let { it == powerCount } == true
        if (nvmeBelieved) {
            nvmeRows.forEach { (a, v) -> if (a == Attribute.HOURS) tableHours = v.takeIf { it <= MOST_HOURS } else counts[a] = v }
        }
        if (smartctlFailed.containsMatchIn(text)) failing += "smartctl says FAILED"
        // The big status box is white on colour and often lost to the OCR; the row of drive tabs
        // above it names each drive's status too. Where every tab is good and no word anywhere on
        // the screen is a warning, the drive shown is good as well.
        val word = status.find(text)?.groupValues?.get(1)
            ?: goodTab.find(text)?.value?.takeIf { !warningWord.containsMatchIn(text) }
        // The header's own count comes first: in the table's hex the OCR reads B as 8.
        val header = headerHours.find(text)?.groupValues?.get(2)?.replace(Regex("[.,]"), "")?.toLongOrNull()?.takeIf { it <= MOST_HOURS }
            ?: daysAndHours.find(text)?.let { it.groupValues[1].toLong() * 24 + it.groupValues[3].toLong() }
        // A table whose power-on row holds a value no unit of the header's hours explains is read one
        // row off: RapidOCR paired a Seagate's names with the next row's values, its power-on row held
        // 0 under a header of 1063 hours and its "reallocated" row the read error rate. Its counts are
        // no drive's, so none is believed. The row counts in its own unit, minutes on some drives
        // (0x92FF beside 627 hours), and a screenshot's header can be hours ahead of its table.
        val shifted = !nvmeScreen && header != null && header > 0 && tableHours != null &&
            asTableReadsIt(header) != tableHours && (tableHours * 2 < header || tableHours > header * 70)
        val believed = if (shifted) emptyMap() else counts
        return Seen(believed, failing, word, header ?: tableHours, header, tableHours, solidState.containsMatchIn(text))
    }

    /** The raw value ending an NVMe row, or null where the OCR left none to read. */
    private fun nvmeValue(line: String): Long? {
        val digits = nvmeRaw.find(line.trim())?.groupValues?.get(1)?.replace(Regex("""\s"""), "") ?: return null
        if (digits.length !in 12..16) return null
        return digits.replace(Regex("[OQ]"), "0").toLongOrNull(16)
    }

    private fun judge(seen: Seen, photo: Int): SmartReading {
        val (counts, failing, word, hours) = seen
        if (listOf(Attribute.CRITICAL_WARNING, Attribute.SPARE, Attribute.MEDIA_ERRORS).any { it in counts }) return judgeNvme(seen, photo)
        val reallocated = counts[Attribute.REALLOCATED]
        val pending = counts[Attribute.PENDING]
        val uncorrectable = listOfNotNull(counts[Attribute.UNCORRECTABLE], counts[Attribute.REPORTED_UNCORRECTABLE]).takeIf { it.isNotEmpty() }?.max()
        val findings = buildList {
            if (word != null && word.lowercase() in failingStatus) add("the tool says $word")
            pending?.takeIf { it > 0 }?.let { add("$it pending sectors") }
            uncorrectable?.takeIf { it > 0 }?.let { add("$it uncorrectable errors") }
            reallocated?.takeIf { it > 0 }?.let { add("$it reallocated sectors") }
            addAll(failing)
        }
        // CrystalDiskInfo itself turns to Vorsicht as soon as a reallocated, pending or uncorrectable
        // sector is counted, so under its Gut a count above zero is the OCR reading a 0 as 9 or 8.
        val misread = word?.lowercase() in setOf("gut", "good", "goed") &&
            listOf(Attribute.REALLOCATED, Attribute.PENDING, Attribute.UNCORRECTABLE).any { (counts[it] ?: 0) > 0 }
        val verdict = when {
            misread -> DriveVerdict.UNREADABLE
            (word != null && word.lowercase() in failingStatus) || failing.isNotEmpty() ||
                (pending ?: 0) > 0 || (uncorrectable ?: 0) > 0 || (reallocated ?: 0) > MANY_REALLOCATED -> DriveVerdict.FAILING
            (reallocated ?: 0) > 0 -> DriveVerdict.WORN
            reallocated == 0L && (pending == 0L || pending == null && seen.solidState) && uncorrectable == 0L -> DriveVerdict.CLEAN
            else -> DriveVerdict.UNREADABLE
        }
        return SmartReading(
            photo = photo, verdict = verdict, powerOnHours = hours, reallocated = reallocated,
            pending = pending, uncorrectable = uncorrectable, status = word, findings = findings,
        )
    }

    /**
     * An NVMe drive's reading: failing where it raises its critical warning, counts a media error,
     * has less spare left than its own threshold or has used up its rated wear; clean where the
     * warning, the errors and the spare are all read and fine.
     */
    private fun judgeNvme(seen: Seen, photo: Int): SmartReading {
        val (counts, failing, word, hours) = seen
        val warning = counts[Attribute.CRITICAL_WARNING]
        val spare = counts[Attribute.SPARE]
        val threshold = counts[Attribute.SPARE_THRESHOLD]
        val used = counts[Attribute.USED]
        val errors = counts[Attribute.MEDIA_ERRORS]
        val findings = buildList {
            if (word != null && word.lowercase() in failingStatus) add("the tool says $word")
            warning?.takeIf { it > 0 }?.let { add("critical warning raised") }
            errors?.takeIf { it > 0 }?.let { add("$it uncorrectable errors") }
            if (spare != null && threshold != null && threshold > 0 && spare < threshold) add("Spare $spare % under its threshold of $threshold %")
            used?.takeIf { it >= 100 }?.let { add("Rated wear used up: $it %") }
            addAll(failing)
        }
        val verdict = when {
            findings.isNotEmpty() -> DriveVerdict.FAILING
            warning == 0L && errors == 0L && spare != null -> DriveVerdict.CLEAN
            else -> DriveVerdict.UNREADABLE
        }
        return SmartReading(
            photo = photo, verdict = verdict, powerOnHours = hours, uncorrectable = errors, status = word,
            findings = findings, wearUsed = used?.toInt(), spareLeft = spare?.toInt(),
        )
    }

    private fun Attribute.label() = when (this) {
        Attribute.REALLOCATED -> "Reallocated sectors"
        Attribute.PENDING -> "Pending sectors"
        Attribute.UNCORRECTABLE -> "Uncorrectable sectors"
        Attribute.REPORTED_UNCORRECTABLE -> "Reported uncorrectable errors"
        Attribute.HOURS -> "Power-on hours"
        Attribute.HELIUM -> "Helium"
        Attribute.CRITICAL_WARNING -> "Critical warning"
        Attribute.SPARE_THRESHOLD -> "Spare threshold"
        Attribute.SPARE -> "Available spare"
        Attribute.USED -> "Percentage used"
        Attribute.MEDIA_ERRORS -> "Media errors"
    }

    // ── Reading photos ──

    /**
     * Each photo's text by its address, so an ad opened again is not read again. The text is kept
     * rather than the reading, so a better parser reads every photo seen before anew; a photo whose
     * text names no SMART attribute is kept as null, being no screen.
     */
    @Serializable
    private data class Read(val url: String, val text: String?)

    // Each reader keeps its own texts: what Tesseract read as no screen at all, RapidOCR reads again.
    private val cacheFile = DataDir.file(if (PhotoText.screenReaderPresent) "smart_screens_rapid.json" else "smart_screens_text.json")
    private val cache: MutableMap<String, String?> = LinkedHashMap<String, String?>().apply {
        cacheFile.readStore(log) { json.decodeFromString<List<Read>>(it) }?.forEach { put(it.url, it.text) }
    }

    /** Every reading with the ad and the text it came from, one JSON object a line, to be checked
     *  against the photos. */
    private val shadowLog = DataDir.file("smart_screens_log.jsonl")

    @Serializable
    private data class Logged(val at: String, val listingId: String, val title: String, val url: String, val reading: SmartReading, val text: String)

    /** One photo read at a time: RapidOCR takes every core, and three ads read at once took 90 s
     *  each where one alone takes a few seconds a photo. */
    private val reading = kotlinx.coroutines.sync.Mutex()

    /** Reads the photos an answer had no time for, after it went out. */
    private val background = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    /** Photos queued for the background, so asking twice queues each once. */
    private val queued: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** How long an answer waits on photos not read before: an offer's page and the MCP call that
     *  asks for it give up after a minute, and the rest is read in the background meanwhile. */
    private const val ANSWER_WITHIN_MS = 20_000L

    /**
     * The SMART screenshots among [photos], read; empty where there are none or the reader is
     * missing. Photos read before answer at once; new ones are read until [ANSWER_WITHIN_MS] has
     * passed, and the rest are read in the background and counted as [DriveHealth.photosPending].
     */
    suspend fun read(listingId: String, title: String, photos: List<String>, fetch: suspend (String) -> ByteArray?): DriveHealth {
        if (!PhotoText.present && !PhotoText.screenReaderPresent) return DriveHealth()
        val urls = photos.take(MOST_PHOTOS)
        val until = System.currentTimeMillis() + ANSWER_WITHIN_MS
        var pending = 0
        val readings = urls.mapIndexedNotNull { i, url ->
            val text = known(url) ?: if (System.currentTimeMillis() < until) {
                reading.withLock { known(url) ?: readPhoto(listingId, title, url, fetch) }
            } else {
                pending++
                if (queued.add(url)) background.launch {
                    try { reading.withLock { if (known(url) == null) readPhoto(listingId, title, url, fetch) } } finally { queued.remove(url) }
                    persist()
                }
                null
            }
            text?.getOrNull()?.let { parse(it.split(PASS_BREAK), i + 1) }
        }
        persist()
        return DriveHealth(readings = readings, photosRead = urls.size - pending, photosPending = pending)
    }

    /** What the cache holds for the photo, null where it has to be read (again). A success of null
     *  is a photo read that shows no screen. */
    private fun known(url: String): Result<String?>? {
        val known = synchronized(cache) { if (url in cache) Result.success(cache[url]) else null } ?: return null
        // A text read once, as every photo is that shows no screen, is read again at twice the size
        // where a better parser now finds a screen in it. RapidOCR reads a photo once, so this is
        // Tesseract's alone.
        val readOnce = !PhotoText.screenReaderPresent && known.getOrNull()?.let { PASS_BREAK !in it && see(it) != null } == true
        return known.takeUnless { readOnce }
    }

    /** The photo at [url] read and kept; null where it did not arrive or could not be read. */
    private suspend fun readPhoto(listingId: String, title: String, url: String, fetch: suspend (String) -> ByteArray?): Result<String?>? {
        val text = runCatching { fetch(fullSize(url)) }.getOrNull()?.let { bytes ->
            // A photo that shows a SMART screen is read a second time at twice the size, and a
            // count is believed only where both readings agree: Tesseract reads a slashed zero as
            // 0 at one size and 9 at another. RapidOCR takes some five seconds a photo, which a
            // second reading would double, so its one reading stands.
            ocr(bytes, 1.0)?.let { first ->
                if (PhotoText.screenReaderPresent || see(first) == null) first
                else listOfNotNull(first, ocr(bytes, 2.0)).joinToString(PASS_BREAK)
            }
        } ?: return null
        // Only a photo that was read is remembered: one that failed to arrive or to read is tried
        // again the next time, rather than counted as no screenshot for good.
        val kept = text.takeIf { t -> Attribute.entries.any { it.names.containsMatchIn(t) } }
        synchronized(cache) { cache[url] = kept }
        parse(text.split(PASS_BREAK), 1)?.let { r ->
            runCatching {
                shadowLog.appendText(json.encodeToString(Logged.serializer(), Logged(Clock.System.now().toString(), listingId, title, url, r, text)) + "\n")
            }
        }
        return Result.success(kept)
    }

    /**
     * The largest copy a market serves of the photo at [url]. Galleries and cards ask for smaller
     * ones, and a SMART table's digits do not survive them: Kleinanzeigen's usual rule is 960
     * pixels where 1600 are there, Ricardo's cards 265 by 200 where 1013 by 1350 are there.
     */
    internal fun fullSize(url: String): String = when {
        "img.kleinanzeigen.de" in url -> url.substringBefore('?') + "?rule=\$_57.JPG"
        "img.ricardostatic.ch" in url -> url.replace(Regex("""/t_\d+x\d+/"""), "/t_1800x1350/")
        "cache.willhaben.at" in url -> url.replace(Regex("""_(hoved|thumb)\.(jpg|webp)$"""), ".jpg")
        "i.ebayimg.com" in url -> url.replace(Regex("""s-l\d+\.(jpg|jpeg|webp|png)"""), "s-l1600.jpg")
        else -> PhotoPrints.jpegAddress(url)
    }

    private fun persist() {
        val all = synchronized(cache) { cache.map { (url, t) -> Read(url, t) } }.takeLast(5_000)
        runCatching { cacheFile.writeTextAtomically(json.encodeToString(all)) }
            .onFailure { log.warn("SMART readings not kept: {}", it.message) }
    }

    private suspend fun ocr(bytes: ByteArray, enlarge: Double): String? = PhotoText.readScreen(bytes, enlarge)
}
