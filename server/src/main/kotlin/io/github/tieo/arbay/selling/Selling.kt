package io.github.tieo.arbay.selling

import io.github.tieo.arbay.DataDir
import io.github.tieo.arbay.chat.ChatBrowser
import io.github.tieo.arbay.crawler.CopiesAds
import io.github.tieo.arbay.crawler.CrawlerRegistry
import io.github.tieo.arbay.live.Live
import io.github.tieo.arbay.model.AdDraft
import io.github.tieo.arbay.model.AdPriceType
import io.github.tieo.arbay.model.LiveKind
import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.model.PostAdResult
import io.github.tieo.arbay.model.problem
import io.github.tieo.arbay.repo.readStore
import io.github.tieo.arbay.repo.writeBytesAtomically
import io.github.tieo.arbay.repo.writeTextAtomically
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.slf4j.LoggerFactory
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.random.Random
import kotlin.time.Clock

/**
 * The user's ads for sale: drafts kept here until the user posts them through their own
 * Kleinanzeigen account, in the same signed-in browser that writes to sellers.
 *
 * A draft starts from an ad the user bought from. Its photos are the seller's, so each is cropped a
 * little at the edges, by a different margin on every side, and saved anew: the ad then shows the
 * same thing without carrying the seller's files, which a market or a reader could match to the
 * original ad. The user's own photos join it whole, turned upright and without their metadata,
 * which on a phone photo names the place it was taken. Nothing is posted until the user presses post.
 */
object Selling {
    private val log = LoggerFactory.getLogger(Selling::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val draftsFile = DataDir.file("selling/drafts.json")
    private val drafts = mutableListOf<AdDraft>()

    /** What a post or a draft failed on, worded for the user. */
    class SellingFailure(message: String) : RuntimeException(message)

    init {
        draftsFile.readStore(log) { json.decodeFromString<List<AdDraft>>(it) }?.let { drafts += it }
    }

    fun drafts(): List<AdDraft> = synchronized(drafts) { drafts.sortedByDescending { it.createdAt } }

    fun draft(id: String): AdDraft? = synchronized(drafts) { drafts.firstOrNull { it.id == id } }

    fun photoFile(draftId: String, photo: String): File? =
        DataDir.file("selling/$draftId/$photo.jpg").takeIf { draftId.isSafeName() && photo.isSafeName() && it.isFile }

    /** A draft of an ad like the one behind [listingId], which must be a Kleinanzeigen ad. */
    suspend fun fromListing(listingId: String): AdDraft {
        val adId = listingId.substringAfter("${PlatformId.KLEINANZEIGEN.name}:", "").takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
            ?: throw SellingFailure("Only a Kleinanzeigen ad can be copied")
        val market = CrawlerRegistry.crawlerFor(PlatformId.KLEINANZEIGEN) as? CopiesAds
            ?: throw SellingFailure("Kleinanzeigen is switched off")
        val copy = market.adCopy("https://www.kleinanzeigen.de/s-anzeige/$adId")
            ?: throw SellingFailure("The ad's page could not be read")
        val now = Clock.System.now()
        val id = "d${now.toEpochMilliseconds()}"
        val photos = copy.photos.mapIndexed { i, url ->
            val name = "p${i + 1}"
            DataDir.file("selling/$id/$name.jpg").writeBytesAtomically(cropALittle(market.photo(url), Random(id.hashCode() + i)))
            name
        }
        val draft = AdDraft(
            id = id,
            fromListingId = listingId,
            categoryId = copy.categoryId,
            title = copy.title,
            description = copy.description,
            attributes = copy.attributes,
            photos = photos,
            createdAt = now,
        )
        synchronized(drafts) { drafts += draft }
        persist()
        return draft
    }

    /** [image] as the draft's last photo, upright, at most [MAX_PHOTO_SIDE] on its longer side. */
    fun addPhoto(id: String, image: ByteArray): AdDraft {
        val name = synchronized(drafts) {
            val old = drafts.firstOrNull { it.id == id } ?: throw SellingFailure("No draft $id")
            if (old.postedAdId != null) throw SellingFailure("This ad is posted already")
            val taken = old.photos.toSet() + DataDir.file("selling/$id").list().orEmpty().map { it.substringBefore('.') }
            generateSequence(1) { it + 1 }.map { "p$it" }.first { it !in taken }
        }
        DataDir.file("selling/$id/$name.jpg").writeBytesAtomically(ownPhoto(image))
        val now = synchronized(drafts) {
            val current = drafts.firstOrNull { it.id == id } ?: throw SellingFailure("No draft $id")
            current.copy(photos = current.photos + name).also { drafts[drafts.indexOf(current)] = it }
        }
        persist()
        return now
    }

    /**
     * The draft as the user changed it. Photos can be left out or put in another order, never added
     * from elsewhere; a posted draft stays as it was posted.
     */
    fun change(changed: AdDraft): AdDraft {
        val now = synchronized(drafts) {
            val old = drafts.firstOrNull { it.id == changed.id } ?: throw SellingFailure("No draft ${changed.id}")
            if (old.postedAdId != null) throw SellingFailure("This ad is posted already")
            val next = old.copy(
                title = changed.title,
                description = changed.description,
                attributes = changed.attributes.filter { (k, v) -> k.isNotBlank() && v.isNotBlank() },
                priceEur = changed.priceEur?.takeIf { it >= 0 },
                priceType = changed.priceType,
                shipping = changed.shipping,
                buyNow = changed.buyNow,
                photos = changed.photos.filter { it in old.photos }.distinct(),
            )
            drafts[drafts.indexOf(old)] = next
            next
        }
        persist()
        return now
    }

    /**
     * A posted ad changed on the market through its own edit form, then kept here as changed: title,
     * text, price, price type, shipping and Direkt kaufen. Only what differs from the ad as posted
     * is sent, so a new price leaves the rest of the ad untouched. Photos and details stay as posted.
     * With [send] false the form is filled and left without saving, and the answer says what it took.
     */
    suspend fun update(changed: AdDraft, send: Boolean = true): Pair<AdDraft, JsonObject?> {
        val old = draft(changed.id) ?: throw SellingFailure("No draft ${changed.id}")
        val adId = old.postedAdId ?: throw SellingFailure("This ad is not posted yet")
        if (changed.photos != old.photos || changed.attributes != old.attributes)
            throw SellingFailure("A posted ad's photos and details stay as posted")
        val next = old.copy(
            title = changed.title, description = changed.description, priceEur = changed.priceEur?.takeIf { it >= 0 },
            priceType = changed.priceType, shipping = changed.shipping, buyNow = changed.shipping && changed.buyNow,
        )
        next.problem()?.let { throw SellingFailure(it) }
        if (next == old) return old to null
        val answer = try {
            ChatBrowser.call(
                "edit_ad", 180_000,
                "adId" to adId,
                "title" to next.title.takeIf { it != old.title },
                "description" to next.description.takeIf { it != old.description },
                "priceType" to next.priceType.label,
                "price" to next.priceEur,
                "shipping" to next.shipping.takeIf { it != old.shipping },
                "buyNow" to next.buyNow.takeIf { it != old.buyNow },
                "send" to send,
            )
        } catch (e: ChatBrowser.ChatFailure) {
            e.detail?.let { keepFailedPage(old, it, e) }
            throw SellingFailure(e.message ?: "The ad could not be changed")
        }
        if (!send) return old to answer
        synchronized(drafts) {
            val current = drafts.first { it.id == old.id }
            drafts[drafts.indexOf(current)] = next.copy(postedAt = current.postedAt)
        }
        persist()
        return next to answer
    }

    fun delete(id: String) {
        synchronized(drafts) { drafts.removeAll { it.id == id } }
        if (id.isSafeName()) DataDir.file("selling/$id").deleteRecursively()
        persist()
    }

    /**
     * Posts the draft through the user's account: the market's own post form, filled in as a person
     * does, photos uploaded, then its own "Anzeige aufgeben" button. With [send] false the form is
     * filled and left without posting, and the answer says what it took, for checking the steps.
     */
    suspend fun post(id: String, send: Boolean = true): Pair<PostAdResult, JsonObject?> {
        val draft = draft(id) ?: throw SellingFailure("No draft $id")
        if (draft.postedAdId != null) return PostAdResult(draft) to null
        draft.problem()?.let { return PostAdResult(draft, it) to null }
        val answer = try {
            ChatBrowser.call(
                "post_ad", 300_000,
                "categoryId" to draft.categoryId,
                "title" to draft.title,
                "description" to draft.description,
                "attributes" to JsonObject(draft.attributes.mapValues { JsonPrimitive(it.value) }),
                "priceType" to draft.priceType.label,
                "price" to draft.priceEur,
                "shipping" to draft.shipping,
                "buyNow" to draft.buyNow,
                "photos" to JsonArray(draft.photos.map { JsonPrimitive(DataDir.file("selling/$id/$it.jpg").absolutePath) }),
                "send" to send,
            )
        } catch (e: ChatBrowser.ChatFailure) {
            e.detail?.let { keepFailedPage(draft, it, e) }
            return PostAdResult(draft, e.message) to e.detail
        }
        if (!send) return PostAdResult(draft) to answer
        val adId = answer["adId"]?.jsonPrimitive?.contentOrNull ?: return PostAdResult(draft, "The market took the ad but named no ad id") to answer
        val posted = synchronized(drafts) {
            val current = drafts.first { it.id == id }
            current.copy(postedAdId = adId, postedAt = Clock.System.now()).also { drafts[drafts.indexOf(current)] = it }
        }
        persist()
        return PostAdResult(posted) to answer
    }

    /** The post form a draft failed on, as an error snapshot to read afterwards. */
    private fun keepFailedPage(draft: AdDraft, detail: JsonObject, error: Exception) {
        io.github.tieo.arbay.crawler.ErrorSnapshotStore.capture(
            platform = "KLEINANZEIGEN_SELLING",
            query = draft.id,
            error = RuntimeException("${error.message}; the page sent: ${detail["requests"] ?: ""}"),
            errorType = io.github.tieo.arbay.crawler.ErrorType.PARSE_ERROR,
            url = detail["url"]?.jsonPrimitive?.contentOrNull,
            fetchStage = "post_ad",
            html = detail["html"]?.jsonPrimitive?.contentOrNull,
        )
    }

    private fun persist() {
        val all = synchronized(drafts) { drafts.toList() }
        runCatching { draftsFile.writeTextAtomically(json.encodeToString(all)) }
            .onFailure { log.warn("Drafts not saved: {}", it.message) }
        Live.changed(LiveKind.SELLING)
    }

    private fun String.isSafeName() = isNotEmpty() && all { it.isLetterOrDigit() }

    /**
     * [jpeg] with 5 to 9 percent cut off each side, a different amount on each, saved anew as a JPEG
     * without the original's metadata. The subject of a market photo sits well inside its frame, so
     * that much leaves it whole.
     */
    internal fun cropALittle(jpeg: ByteArray, random: Random): ByteArray {
        val image = ImageIO.read(jpeg.inputStream()) ?: throw SellingFailure("A photo could not be read")
        fun cut(size: Int) = (size * random.nextDouble(0.05, 0.09)).toInt()
        val left = cut(image.width); val right = cut(image.width)
        val top = cut(image.height); val bottom = cut(image.height)
        return jpeg(image.getSubimage(left, top, image.width - left - right, image.height - top - bottom))
    }

    /** The longest side a photo of the user's is kept at; Kleinanzeigen shows far less. */
    const val MAX_PHOTO_SIDE = 2400

    /**
     * A photo of the user's, turned as its EXIF orientation says (a phone stores it as the sensor
     * saw it and leaves the turning to the viewer), scaled to [MAX_PHOTO_SIDE], and saved anew as a
     * JPEG, which leaves its metadata and the position in it behind.
     */
    internal fun ownPhoto(bytes: ByteArray): ByteArray {
        val image = ImageIO.read(bytes.inputStream()) ?: throw SellingFailure("A photo could not be read")
        val upright = turned(image, exifOrientation(bytes))
        val scale = minOf(1.0, MAX_PHOTO_SIDE.toDouble() / maxOf(upright.width, upright.height))
        if (scale == 1.0) return jpeg(upright)
        val w = (upright.width * scale).toInt(); val h = (upright.height * scale).toInt()
        val scaled = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        scaled.createGraphics().apply {
            setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_QUALITY)
            drawImage(upright, 0, 0, w, h, null); dispose()
        }
        return jpeg(scaled)
    }

    /**
     * The EXIF orientation of a JPEG, 1 (as stored) to 8, read from its APP1 segment; 1 where it
     * has none. ImageIO reads the pixels and ignores the tag.
     */
    internal fun exifOrientation(jpeg: ByteArray): Int {
        fun u16(at: Int, little: Boolean) = if (little) (jpeg[at].toInt() and 0xff) or ((jpeg[at + 1].toInt() and 0xff) shl 8)
            else ((jpeg[at].toInt() and 0xff) shl 8) or (jpeg[at + 1].toInt() and 0xff)
        fun u32(at: Int, little: Boolean) = if (little) u16(at, true) or (u16(at + 2, true) shl 16) else (u16(at, false) shl 16) or u16(at + 2, false)
        if (jpeg.size < 4 || u16(0, false) != 0xFFD8) return 1
        var at = 2
        while (at + 4 <= jpeg.size && (jpeg[at].toInt() and 0xff) == 0xFF) {
            val marker = jpeg[at + 1].toInt() and 0xff
            val length = u16(at + 2, false)
            if (marker == 0xDA) break
            val body = at + 4
            if (marker == 0xE1 && body + 14 <= jpeg.size && String(jpeg, body, 4, Charsets.ISO_8859_1) == "Exif") {
                val tiff = body + 6
                val little = jpeg[tiff].toInt() == 'I'.code
                val ifd = tiff + u32(tiff + 4, little)
                if (ifd + 2 > jpeg.size) return 1
                for (i in 0 until u16(ifd, little)) {
                    val entry = ifd + 2 + i * 12
                    if (entry + 12 > jpeg.size) return 1
                    if (u16(entry, little) == 0x0112) return u16(entry + 8, little).takeIf { it in 1..8 } ?: 1
                }
                return 1
            }
            at = body + length - 2
        }
        return 1
    }

    /** [image] drawn the way EXIF [orientation] says it is to be seen. */
    internal fun turned(image: BufferedImage, orientation: Int): BufferedImage {
        if (orientation !in 2..8) return image
        val w = image.width; val h = image.height
        val sideways = orientation >= 5
        val outW = if (sideways) h else w; val outH = if (sideways) w else h
        val source = image.getRGB(0, 0, w, h, null, 0, w)
        val pixels = IntArray(outW * outH)
        for (y in 0 until outH) for (x in 0 until outW) {
            // The stored pixel shown at (x, y).
            val (sx, sy) = when (orientation) {
                2 -> w - 1 - x to y
                3 -> w - 1 - x to h - 1 - y
                4 -> x to h - 1 - y
                5 -> y to x
                6 -> y to h - 1 - x
                7 -> w - 1 - y to h - 1 - x
                else -> w - 1 - y to x
            }
            pixels[y * outW + x] = source[sy * w + sx]
        }
        return BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB).also { it.setRGB(0, 0, outW, outH, pixels, 0, outW) }
    }

    /** [image] as a JPEG with no metadata. */
    private fun jpeg(image: BufferedImage): ByteArray {
        // A plain RGB copy: a subimage shares the original's raster, and the writer wants no alpha.
        val rgb = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB)
        rgb.createGraphics().apply { drawImage(image, 0, 0, null); dispose() }
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val out = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(out).use { stream ->
            writer.output = stream
            val params = writer.defaultWriteParam.apply { compressionMode = ImageWriteParam.MODE_EXPLICIT; compressionQuality = 0.92f }
            writer.write(null, IIOImage(rgb, null, null), params)
        }
        writer.dispose()
        return out.toByteArray()
    }
}
