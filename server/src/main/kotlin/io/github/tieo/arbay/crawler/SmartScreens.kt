package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.DataDir
import io.github.tieo.arbay.model.DriveHealth
import io.github.tieo.arbay.model.DriveVerdict
import io.github.tieo.arbay.model.SmartReading
import io.github.tieo.arbay.repo.readStore
import io.github.tieo.arbay.repo.writeTextAtomically
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

    /** The largest photo read, in bytes and in pixels a side, so a hostile file cannot exhaust the server. */
    private const val MOST_BYTES = 8_000_000
    private const val MOST_SIDE = 5_000

    /** A screenshot this narrow is upscaled before reading: Tesseract wants letters some 20 pixels high. */
    private const val READ_WIDTH = 1_400

    /** A row of a SMART table: three normalised values and the raw one in hex, as CrystalDiskInfo and
     *  smartctl print them ("Wiederzugewiesene Sektoren 88 88 10 00000000B9A0"). */
    private val tableRow = Regex("""(\d{1,3})\s*[|\]]?\s+(\d{1,3})\s*[|\]]?\s+(\d{1,3})\s*[|\]]?\s+([0-9A-Fa-f]{6,16})\s*$""")

    /** A row with only a value and its threshold, as Scrutiny shows some ("Current Helium Level 19 25"). */
    private val pairRow = Regex("""(\d{1,6})\s+(\d{1,3})\b""")

    private enum class Attribute(val names: Regex) {
        REALLOCATED(Regex("""(?i)wiederzugewiesene\s+sektoren|reallocated\s+sectors?(\s+count)?\b""")),
        PENDING(Regex("""(?i)ausstehende\s+sektoren|pending\s+sectors?""")),
        UNCORRECTABLE(Regex("""(?i)nicht\s?korrigierbare\s+sektoren|offline\s+uncorrectable|uncorrectable\s+sectors?""")),
        REPORTED_UNCORRECTABLE(Regex("""(?i)gemeldete\s+unkorrigierbare|reported\s+uncorrect""")),
        HOURS(Regex("""(?i)betriebsstunden|power[\s-]?on[\s-]?hours""")),
        HELIUM(Regex("""(?i)helium""")),
    }

    private val status = Regex("""(?i)\b(gut|vorsicht|schlecht|good|caution|bad)\s+(schnittstelle|interface)\b""")
    private val headerHours = Regex("""(?i)(betriebsstunden|power\s+on\s+hours)\s+(\d{1,3}(?:[.,]?\d{3})*)\s*(std|stunden|hours)""")
    private val failingStatus = setOf("vorsicht", "schlecht", "caution", "bad")

    /** The most bad sectors a reading believes; a drive's spare area holds some thousands to tens of thousands. */
    private const val MOST_SECTORS = 1_000_000L

    /** Reallocated sectors beyond which a drive is failing rather than wearing. */
    private const val MANY_REALLOCATED = 100

    /**
     * What one photo's text says, or null where it is no SMART screen: at least three attributes
     * have to be found by name before it counts as one.
     */
    fun parse(text: String, photo: Int): SmartReading? {
        val lines = text.lines()
        var found = 0
        val counts = HashMap<Attribute, Long>()
        val failing = mutableListOf<String>()
        var tableHours: Long? = null
        for (line in lines) {
            val attribute = Attribute.entries.firstOrNull { it.names.containsMatchIn(line) } ?: continue
            found++
            val after = line.substring(attribute.names.find(line)!!.range.last + 1)
            val row = tableRow.find(after)
            if (row != null) {
                val (current, _, threshold, rawHex) = row.destructured
                val raw = rawHex.toLongOrNull(16)
                // A value at or under a threshold above zero is the drive's own failure mark.
                val cur = current.toInt()
                val thr = threshold.toInt()
                if (thr in 1..254 && cur <= thr) failing += "${attribute.label()} $cur at threshold $thr"
                when (attribute) {
                    Attribute.HOURS -> tableHours = raw
                    Attribute.HELIUM -> {}
                    // No drive has this many bad sectors: a count past it is the OCR misreading a
                    // digit of the hex ("…0000" read as "…9000"), and stays unread.
                    else -> if (raw != null && raw <= MOST_SECTORS) counts[attribute] = raw
                }
                continue
            }
            // Only a row of exactly two numbers is Scrutiny's: a CrystalDiskInfo row whose hex was
            // misread still has its three normalised values, and "200 200" is no sector count.
            if (Regex("""\b\d+\b""").findAll(after).count() != 2) continue
            val pair = pairRow.find(after) ?: continue
            val (value, threshold) = pair.destructured
            when (attribute) {
                // Scrutiny's helium row is a value against its threshold, nothing else.
                Attribute.HELIUM -> if (threshold.toInt() > 0 && value.toInt() <= threshold.toInt())
                    failing += "Helium ${value.toInt()} at threshold ${threshold.toInt()}"
                // Scrutiny shows the raw count of a sector attribute as its value.
                Attribute.REALLOCATED, Attribute.PENDING, Attribute.UNCORRECTABLE, Attribute.REPORTED_UNCORRECTABLE ->
                    counts[attribute] = value.toLong()
                Attribute.HOURS -> {}
            }
        }
        if (found < 3) return null
        val word = status.find(text)?.groupValues?.get(1)
        val hours = headerHours.find(text)?.groupValues?.get(2)?.replace(Regex("[.,]"), "")?.toLongOrNull() ?: tableHours
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
        val verdict = when {
            (word != null && word.lowercase() in failingStatus) || failing.isNotEmpty() ||
                (pending ?: 0) > 0 || (uncorrectable ?: 0) > 0 || (reallocated ?: 0) > MANY_REALLOCATED -> DriveVerdict.FAILING
            (reallocated ?: 0) > 0 -> DriveVerdict.WORN
            reallocated == 0L && pending == 0L && uncorrectable == 0L -> DriveVerdict.CLEAN
            else -> DriveVerdict.UNREADABLE
        }
        return SmartReading(
            photo = photo, verdict = verdict, powerOnHours = hours, reallocated = reallocated,
            pending = pending, uncorrectable = uncorrectable, status = word, findings = findings,
        )
    }

    private fun Attribute.label() = when (this) {
        Attribute.REALLOCATED -> "Reallocated sectors"
        Attribute.PENDING -> "Pending sectors"
        Attribute.UNCORRECTABLE -> "Uncorrectable sectors"
        Attribute.REPORTED_UNCORRECTABLE -> "Reported uncorrectable errors"
        Attribute.HOURS -> "Power-on hours"
        Attribute.HELIUM -> "Helium"
    }

    // ── Reading photos ──

    /** Each photo's reading by its address, so an ad opened again is not read again. */
    @Serializable
    private data class Read(val url: String, val reading: SmartReading?)

    private val cacheFile = DataDir.file("smart_screens.json")
    private val cache: MutableMap<String, SmartReading?> = HashMap<String, SmartReading?>().apply {
        cacheFile.readStore(log) { json.decodeFromString<List<Read>>(it) }?.forEach { put(it.url, it.reading) }
    }

    /** Every reading with the ad it came from, one JSON object a line, to be checked against the drives. */
    private val shadowLog = DataDir.file("smart_screens_log.jsonl")

    @Serializable
    private data class Logged(val at: String, val listingId: String, val title: String, val url: String, val reading: SmartReading)

    /** The SMART screenshots among [photos], read; empty where there are none or the reader is missing. */
    suspend fun read(listingId: String, title: String, photos: List<String>, fetch: suspend (String) -> ByteArray?): DriveHealth {
        if (!tesseractPresent) return DriveHealth()
        val urls = photos.take(MOST_PHOTOS)
        val readings = urls.mapIndexedNotNull { i, url ->
            val known = synchronized(cache) { if (url in cache) Result.success(cache[url]) else null }
            val reading = known?.getOrNull() ?: if (known != null) null else {
                val bytes = runCatching { fetch(fullSize(url)) }.getOrNull()
                val text = bytes?.let { ocr(it) }
                val read = text?.let { parse(it, i + 1) }
                // Only a photo that was read is remembered: one that failed to arrive or to read is
                // tried again the next time, rather than counted as no screenshot for good.
                if (text != null) synchronized(cache) { cache[url] = read }
                read?.let { r ->
                    runCatching {
                        shadowLog.appendText(json.encodeToString(Logged.serializer(), Logged(Clock.System.now().toString(), listingId, title, url, r)) + "\n")
                    }
                }
                read
            }
            reading?.copy(photo = i + 1)
        }
        persist()
        return DriveHealth(readings = readings, photosRead = urls.size)
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
        val all = synchronized(cache) { cache.map { (url, r) -> Read(url, r) } }.takeLast(5_000)
        runCatching { cacheFile.writeTextAtomically(json.encodeToString(all)) }
            .onFailure { log.warn("SMART readings not kept: {}", it.message) }
    }

    private val tesseractPresent: Boolean by lazy {
        runCatching { ProcessBuilder("tesseract", "--version").start().waitFor() == 0 }.getOrDefault(false)
            .also { if (!it) log.warn("tesseract is not installed: SMART screenshots are not read") }
    }

    /** The photo's text, or null where it is not an image this reads or the reader gave none in time. */
    private suspend fun ocr(bytes: ByteArray): String? {
        if (bytes.size > MOST_BYTES) return null
        val image = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull() ?: return null
        if (image.width > MOST_SIDE || image.height > MOST_SIDE || image.width < 200 || image.height < 150) return null
        val scale = (READ_WIDTH.toDouble() / image.width).coerceIn(1.0, 3.0)
        val gray = BufferedImage((image.width * scale).toInt(), (image.height * scale).toInt(), BufferedImage.TYPE_BYTE_GRAY)
        gray.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            drawImage(image, 0, 0, gray.width, gray.height, null)
            dispose()
        }
        val file: File = Files.createTempFile("smart", ".png").toFile()
        return try {
            ImageIO.write(gray, "png", file)
            // A photo that is not a screenshot can keep Tesseract busy for long; none needs more than this.
            withTimeoutOrNull(30.seconds) {
                runProcess(listOf("tesseract", file.absolutePath, "stdout", "-l", "deu+eng", "--psm", "6"))
            }?.takeIf { it.exitCode == 0 }?.stdout?.decodeToString()
        } finally {
            file.delete()
        }
    }
}
