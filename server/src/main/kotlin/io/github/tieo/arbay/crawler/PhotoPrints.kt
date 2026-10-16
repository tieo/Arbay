package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.DataDir
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.PhotoCopy
import io.github.tieo.arbay.repo.readStore
import io.github.tieo.arbay.repo.writeTextAtomically
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.time.Duration.Companion.minutes

/**
 * A fingerprint of every photo the searches have seen, so an ad that shows another ad's photos can
 * be told apart.
 *
 * Live, 2026-10-16: a Kleinanzeigen Crafter in Körne at 12,000 € showed, among its 19 photos, the
 * photos of four other Crafters offered in Gönnheim, Mayen, Marktbreit and Essen in the weeks
 * before: a gallery put together from other people's ads, which is how a van that is not there gets
 * sold. The same photo in a dealer's two ads on two markets is the same van, so only a photo in an
 * ad placed elsewhere or by someone else counts, and a photo in many such ads is a maker's picture.
 *
 * The print is a 64-bit difference hash of the photo at 9 by 8 pixels: unmoved by size and
 * compression, so a photo saved again by a copier prints within a few bits of the original. The
 * first photo of every listing a search returns is printed when it is archived; an ad's whole
 * gallery when its page is read.
 */
object PhotoPrints {
    private val log = LoggerFactory.getLogger(PhotoPrints::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Bits two prints of the same photo may differ by. */
    private const val SAME_PHOTO_BITS = 6
    /** Places a photo may show up in before it counts as a maker's or a stock picture. */
    private const val STOCK_PLACES = 5
    /** Listings kept, the oldest forgotten first. */
    private const val KEPT = 150_000
    /** Two ads closer than this are taken to be in the same place. */
    private const val SAME_PLACE_KM = 30.0

    @Serializable
    data class Entry(
        val id: String,
        val url: String,
        val place: String? = null,
        val lat: Double? = null,
        val lon: Double? = null,
        val seller: String? = null,
        val prints: List<Long> = emptyList(),
    )

    private val file = DataDir.file("photo_prints.json")
    private val entries = LinkedHashMap<String, Entry>()
    /** Byte position and value of a print to the listings holding such a print. */
    private val byByte = HashMap<Int, MutableSet<String>>()
    @Volatile private var dirty = false
    private val downloads = Semaphore(6)

    init {
        file.readStore(log) { json.decodeFromString<List<Entry>>(it) }?.forEach(::put)
        // A deploy restarts the server between two saves; what was printed since the last is kept.
        Runtime.getRuntime().addShutdownHook(Thread { if (dirty) save() })
        scope.launch {
            while (true) {
                delay(5.minutes)
                if (dirty) save()
            }
        }
    }

    // ── printing ─────────────────────────────────────────────────────────────────────────────

    /** The photo's print, or null where it cannot be decoded (only JPEG and PNG can). */
    fun print(bytes: ByteArray): Long? {
        val image = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull() ?: return null
        val small = BufferedImage(9, 8, BufferedImage.TYPE_BYTE_GRAY)
        small.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            drawImage(image.getScaledInstance(9, 8, java.awt.Image.SCALE_AREA_AVERAGING), 0, 0, null)
            dispose()
        }
        var bits = 0L
        for (y in 0 until 8) for (x in 0 until 8) {
            bits = (bits shl 1) or (if (small.raster.getSample(x, y, 0) > small.raster.getSample(x + 1, y, 0)) 1L else 0L)
        }
        return bits
    }

    /**
     * The same photo as a JPEG, from hosts that serve their photos as WebP or AVIF and JPEG alike:
     * the size or format is part of the address. Any other address is returned as it is.
     */
    internal fun jpegAddress(url: String): String = when {
        "img.kleinanzeigen.de" in url -> url.substringBefore('?') + "?rule=\$_59.JPG"
        "img.classistatic.de" in url -> url.substringBefore('?') + "?rule=mo-640.jpg"
        "pictures.autoscout24.net" in url -> url.replace(Regex("""\.webp$"""), ".jpg")
        "i.ebayimg.com" in url -> url.replace(Regex("""\.webp$"""), ".jpg")
        else -> url
    }

    private suspend fun download(url: String): ByteArray? = downloads.withPermit {
        try {
            val response = CrawlerRegistry.httpClient.get(jpegAddress(url))
            if (response.status.isSuccess()) response.body<ByteArray>() else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    // ── recording ────────────────────────────────────────────────────────────────────────────

    /**
     * Prints [photos] of [listing] unless as many are printed already; the first may come from
     * [firstFile]. Answers whether any of them is printed, false when not one could be fetched.
     */
    suspend fun record(listing: Listing, photos: List<String>, firstFile: File? = null): Boolean {
        if (photos.isEmpty()) return false
        val known = synchronized(this) { entries[listing.id] }
        if (known != null && known.prints.size >= photos.size) return true
        val prints = coroutineScope {
            photos.take(30).mapIndexed { index, url ->
                async {
                    val local = if (index == 0) firstFile?.takeIf { it.isFile }?.readBytes()?.let(::print) else null
                    local ?: download(url)?.let(::print)
                }
            }.awaitAll().filterNotNull()
        }
        if (prints.isEmpty()) return false
        val location = listing.location
        val entry = Entry(
            id = listing.id, url = listing.url,
            place = location?.city ?: location?.raw, lat = location?.latitude, lon = location?.longitude,
            seller = listing.seller?.name, prints = (known?.prints.orEmpty() + prints).distinct(),
        )
        synchronized(this) { put(entry); dirty = true }
        return true
    }

    /** [record] in the background, for a listing just archived. */
    fun recordAsync(listing: Listing, firstFile: File?) {
        scope.launch { runCatching { record(listing, listing.imageUrls.take(1), firstFile) } }
    }

    private fun put(entry: Entry) {
        entries.remove(entry.id)?.let(::unindex)
        entries[entry.id] = entry
        entry.prints.forEach { p -> for (i in 0 until 8) byByte.getOrPut(key(p, i)) { HashSet() } += entry.id }
        if (entries.size > KEPT) entries.keys.first().let { oldest -> entries.remove(oldest)?.let(::unindex) }
    }

    private fun unindex(entry: Entry) =
        entry.prints.forEach { p -> for (i in 0 until 8) byByte[key(p, i)]?.remove(entry.id) }

    private fun key(print: Long, byte: Int) = (byte shl 8) or ((print ushr (byte * 8)) and 0xFF).toInt()

    private fun save() = synchronized(this) {
        dirty = false
        runCatching { file.writeTextAtomically(json.encodeToString(entries.values.toList())) }
            .onFailure { log.warn("could not write photo prints: {}", it.message) }
    }

    // ── matching ─────────────────────────────────────────────────────────────────────────────

    /** The ads placed elsewhere or by someone else that show one of [listing]'s photos. */
    fun copiesOf(listing: Listing): List<PhotoCopy> = synchronized(this) {
        val own = entries[listing.id] ?: return emptyList()
        own.prints.flatMap { print -> othersWith(print, own) }
            .distinctBy { it.id }
            .map { PhotoCopy(it.id, it.url, it.place) }
    }

    /** The other entries showing [print], unless so many places show it that it is a stock picture. */
    private fun othersWith(print: Long, own: Entry): List<Entry> {
        // Six bits apart touch six of the eight bytes at most, so a print that close shares at
        // least two bytes whole with this one: the index finds every candidate.
        val shared = HashMap<String, Int>()
        for (i in 0 until 8) byByte[key(print, i)]?.forEach { id -> shared.merge(id, 1, Int::plus) }
        val same = shared.filter { (id, n) -> n >= 2 && id != own.id }.keys
            .mapNotNull { entries[it] }
            .filter { e -> e.prints.any { java.lang.Long.bitCount(it xor print) <= SAME_PHOTO_BITS } }
        val elsewhere = same.filter { apart(it, own) }
        return if (elsewhere.distinctBy { placeKey(it) }.size > STOCK_PLACES) emptyList() else elsewhere
    }

    private fun placeKey(e: Entry) = e.place?.lowercase()?.let { Regex("""\p{L}+""").find(it)?.value }

    /** Whether two ads were placed by different sellers or in different places. */
    internal fun apart(a: Entry, b: Entry): Boolean {
        if (a.seller != null && b.seller != null && !a.seller.equals(b.seller, ignoreCase = true)) return true
        if (a.lat != null && a.lon != null && b.lat != null && b.lon != null)
            return Geocoder.haversine(a.lat, a.lon, b.lat, b.lon) > SAME_PLACE_KM
        val pa = placeKey(a) ?: return false
        val pb = placeKey(b) ?: return false
        return pa != pb
    }

    // ── the archive ──────────────────────────────────────────────────────────────────────────

    /**
     * Prints the first photo of every listing already archived, once, on a server that has no
     * prints yet: the photos copied today were most often first seen weeks before.
     */
    fun backfill(archiveListings: File, archiveImages: File, read: (File) -> Listing?) {
        if (entries.isNotEmpty()) return
        scope.launch {
            var printed = 0
            archiveListings.listFiles { f -> f.name.endsWith(".json") }.orEmpty().forEach { f ->
                val listing = runCatching { read(f) }.getOrNull() ?: return@forEach
                val first = File(archiveImages, listing.id).listFiles { img -> img.name.startsWith("0.") }?.firstOrNull() ?: return@forEach
                val print = runCatching { print(first.readBytes()) }.getOrNull() ?: return@forEach
                val location = listing.location
                synchronized(this@PhotoPrints) {
                    if (listing.id !in entries) put(Entry(
                        listing.id, listing.url, location?.city ?: location?.raw, location?.latitude, location?.longitude,
                        listing.seller?.name, listOf(print),
                    ))
                }
                printed++
            }
            dirty = true
            save()
            log.info("photo prints: {} archived listings printed", printed)
        }
    }
}
